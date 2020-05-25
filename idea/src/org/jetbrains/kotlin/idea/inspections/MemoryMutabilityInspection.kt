/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.inspections

import com.intellij.codeInspection.ProblemHighlightType.GENERIC_ERROR_OR_WARNING
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.kotlin.idea.references.KtSimpleNameReference
import org.jetbrains.kotlin.psi.*
import kotlin.collections.ArrayList
import kotlin.collections.List
import kotlin.collections.find
import kotlin.collections.first
import kotlin.collections.isNotEmpty

class MemoryMutabilityInspection : AbstractKotlinInspection() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : KtVisitorVoid() {
            var inspectionImpl = MemoryMutabilityInspectionImpl(holder)

            override fun visitNamedFunction(function: KtNamedFunction) {
                super.visitNamedFunction(function)
                inspectionImpl.checkMutationProblems(function)
            }
        }
    }
}

private class MemoryMutabilityInspectionImpl(private val holder: ProblemsHolder) {
    data class FunctionInfo(
        var isThisFrozen: Boolean = false,
        var frozenPropsStorage: ArrayList<List<PsiElement>> = arrayListOf(),
        var notOverriddenMutatedPropsStorage: ArrayList<List<PsiElement>> = arrayListOf(),
        var allMutatedPropsStorage: ArrayList<List<PsiElement>> = arrayListOf()
    )

    private val functionsInfo: MutableMap<KtNamedFunction, FunctionInfo> = mutableMapOf()

    internal fun checkMutationProblems(function: KtNamedFunction): FunctionInfo {
        functionsInfo[function]?.let {
            return it
        }

        val functionInfo = FunctionInfo()
        functionInfo.isThisFrozen = function.isObjectFunction()

        mainLoop@
        for (item in (function.bodyBlockExpression?.children ?: arrayOf())) {
            when (item) {
                is KtCallExpression -> {
                    if (item.isFreezeCall()) {
                        functionInfo.isThisFrozen = true
                    } else {
                        item.calleeExpression?.let {
                            mergeFunctionInfoFromElementAndCheck(it, functionInfo)
                        }
                    }
                }

                is KtDotQualifiedExpression -> {
                    if (item.isFreezeCall()) {
                        val callItems = item.callReferences().resolve()
                        functionInfo.frozenPropsStorage.add(callItems.dropLast(1))
                    } else {
                        mergeFunctionInfoFromElementAndCheck(item, functionInfo)
                    }
                }

                is KtOperationExpression -> {
                    val rightPart = item.lastChild
                    if (rightPart is KtDotQualifiedExpression) {
                        mergeFunctionInfoFromElementAndCheck(rightPart, functionInfo)
                    }

                    val mutatedProps = item.firstChild.callReferences().resolve()
                    if (mutatedProps.isEmpty()) {
                        continue
                    }

                    if (mutatedProps.first().isThisClassMember()) {
                        val isMutateOnOverridden = functionInfo.allMutatedPropsStorage.any {
                            it.isSubset(mutatedProps)
                        }

                        if (!isMutateOnOverridden) {
                            functionInfo.notOverriddenMutatedPropsStorage.add(mutatedProps)
                        }

                        functionInfo.allMutatedPropsStorage.add(mutatedProps)
                    }

                    functionInfo.frozenPropsStorage = functionInfo.frozenPropsStorage.filterNot {
                        mutatedProps.isSubset(it)
                    }.toCollection(ArrayList())

                    if (mutatedProps.first() is KtObjectDeclaration ||
                        mutatedProps.first().isThisObjectMember() ||
                        (functionInfo.isThisFrozen && mutatedProps.first().isThisClassMember())
                    ) {
                        registerMutationProblemOn(item)
                        continue
                    }

                    for (frozenProp in functionInfo.frozenPropsStorage) {
                        if (frozenProp.isSubset(mutatedProps) && frozenProp.size < mutatedProps.size) {
                            registerMutationProblemOn(item)
                            continue@mainLoop
                        }
                    }
                }
            }
        }

        functionsInfo[function] = functionInfo
        return functionInfo
    }

    private fun mergeFunctionInfoFromElementAndCheck(
        elementForCheck: PsiElement,
        primaryFunctionInfo: FunctionInfo
    ) {
        val props = elementForCheck.callReferences().resolve()
        val funcItem = props.lastOrNull() as? KtNamedFunction
        if (funcItem != null) {
            val additionalFunctionInfo = functionsInfo[funcItem] ?: checkMutationProblems(funcItem)
            mergeFunctionInfoAndCheck(elementForCheck, primaryFunctionInfo, additionalFunctionInfo, props.dropLast(1))
        }
    }

    private fun mergeFunctionInfoAndCheck(
        elementForCheck: PsiElement,
        primaryFunctionInfo: FunctionInfo,
        additionalFunctionInfo: FunctionInfo,
        scope: List<PsiElement> = arrayListOf()
    ) {
        val otherFrozenPropsStorage = additionalFunctionInfo.frozenPropsStorage.map {
            scope.plus(it)
        }
        val otherAllMutatedPropsStorage = additionalFunctionInfo.allMutatedPropsStorage.map {
            scope.plus(it)
        }
        val otherNotOverriddenMutatedPropsStorage = additionalFunctionInfo.notOverriddenMutatedPropsStorage.map {
            scope.plus(it)
        }

        primaryFunctionInfo.frozenPropsStorage.addAll(otherFrozenPropsStorage)
        primaryFunctionInfo.notOverriddenMutatedPropsStorage.addAll(otherNotOverriddenMutatedPropsStorage)
        primaryFunctionInfo.allMutatedPropsStorage.addAll(otherAllMutatedPropsStorage)

        if (primaryFunctionInfo.isThisFrozen && otherNotOverriddenMutatedPropsStorage.isNotEmpty()) {
            registerMutationProblemOn(elementForCheck)
        } else {
            if (additionalFunctionInfo.isThisFrozen) {
                primaryFunctionInfo.isThisFrozen = true
            }

            mainLoop@
            for (frozenProp in primaryFunctionInfo.frozenPropsStorage) {
                for (mutatedProps in otherNotOverriddenMutatedPropsStorage) {
                    if (frozenProp.isSubset(mutatedProps) && frozenProp.size < mutatedProps.size) {
                        registerMutationProblemOn(elementForCheck)
                        break@mainLoop
                    }
                }
            }
        }

        primaryFunctionInfo.frozenPropsStorage = primaryFunctionInfo.frozenPropsStorage.filter {
            var isIncluded = true
            for (prop in otherAllMutatedPropsStorage) {
                if (prop.isNotEmpty() && prop.isSubset(it)) {
                    isIncluded = false
                    break
                }
            }
            isIncluded
        }.toCollection(ArrayList())
    }

    private fun registerMutationProblemOn(element: PsiElement) {
        holder.registerProblem(element, "Trying mutate frozen object", GENERIC_ERROR_OR_WARNING)
    }
}

private fun PsiElement.callReferences(): List<KtSimpleNameReference> {
    val refs = mutableListOf<KtSimpleNameReference>()

    val stack = mutableListOf<PsiElement>()
    stack.add(this)

    while (stack.isNotEmpty()) {
        val item = stack.first()
        stack.removeAt(0)

        when (item) {
            is KtDotQualifiedExpression -> {
                stack.addAll(0, item.children.toList())
            }
            is KtCallExpression -> {
                item.calleeExpression?.let {
                    stack.add(0, it)
                }
            }
            is KtReferenceExpression -> {
                val nameRef = item.simpleNameReference()
                if (nameRef != null) {
                    refs.add(nameRef)
                } else {
                    break
                }
            }
        }
    }

    return refs
}

private fun List<KtSimpleNameReference>.resolve(): List<PsiElement> {
    val items = ArrayList<PsiElement>()

    for (ref in this) {
        when (val item = ref.resolve()) {
            is KtNamedFunction -> {
                items.add(item)
                break
            }
            is KtClassOrObject ->
                items.add(item)
            is KtProperty ->
                items.add(item)
            else -> {
                break
            }
        }
    }

    return items
}

private fun PsiElement.simpleNameReference(): KtSimpleNameReference? {
    return references.find { it is KtSimpleNameReference } as? KtSimpleNameReference
}

private fun PsiElement.isFreezeCall(): Boolean {
    val props = callReferences().resolve()

    if ((props.lastOrNull() as? KtNamedFunction)?.name == "freeze") {
        return true
    }

    return false
}

private fun KtNamedFunction.isObjectFunction(): Boolean {
    val classBody = (parent as? KtClassBody)
    return classBody?.parent is KtObjectDeclaration
}

private fun PsiElement.isThisObjectMember(): Boolean {
    if (this !is KtProperty) {
        return false
    }
    val classBody = parent as? KtClassBody ?: return false
    return classBody.parent is KtObjectDeclaration
}

private fun PsiElement.isThisClassMember(): Boolean {
    if (this !is KtProperty) {
        return false
    }
    val classBody = parent as? KtClassBody ?: return false
    return classBody.parent is KtClass
}

private fun <T : Any> Collection<T>.isSubset(array: Collection<T>): Boolean {
    val otherSize = array.size
    for ((index, value) in iterator().withIndex()) {
        if (index < otherSize && value == array.elementAt(index)) {
            continue
        } else {
            return false
        }
    }

    return true
}
