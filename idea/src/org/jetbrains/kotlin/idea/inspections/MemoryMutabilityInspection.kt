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
            override fun visitNamedFunction(function: KtNamedFunction) {
                super.visitNamedFunction(function)

                val classBody = (function.parent as? KtClassBody)
                var isThisFrozen = classBody?.parent is KtObjectDeclaration
                var frozenPropsStorage = ArrayList<List<PsiElement>>()

                for (item in function.lastChild.children) {
                    when (item) {
                        is KtCallExpression -> {
                            if (isThisFrozen) {
                                if (item.isMutateThis()) {
                                    holder.registerProblem(item, "Trying mutate frozen object", GENERIC_ERROR_OR_WARNING)
                                }
                            } else {
                                if (item.isFreezeCall()) {
                                    isThisFrozen = true
                                }
                            }
                        }

                        is KtDotQualifiedExpression -> {
                            if (item.isFreezeCall()) {
                                val frozenProps = item.callReferences().mapNotNull { it.resolve() }
                                frozenPropsStorage.add(frozenProps.dropLast(1))
                            }
                        }

                        is KtOperationExpression -> {
                            val mutatedProps = item.firstChild.callReferences().mapNotNull { it.resolve() }

                            if (mutatedProps.firstOrNull() is KtObjectDeclaration ||
                                mutatedProps.firstOrNull()?.isThisObjectMember() == true ||
                                (isThisFrozen && mutatedProps.firstOrNull()?.isThisClassMember() == true)
                            ) {
                                holder.registerProblem(item, "Trying mutate frozen object", GENERIC_ERROR_OR_WARNING)
                            } else {
                                frozenPropsStorage = frozenPropsStorage.filterNot {
                                    (mutatedProps.isNotEmpty() && mutatedProps.isSubset(it))
                                }.toCollection(ArrayList())

                                for (frozenProp in frozenPropsStorage) {
                                    if (frozenProp.isSubset(mutatedProps) && frozenProp.size < mutatedProps.size) {
                                        holder.registerProblem(item, "Trying mutate frozen object", GENERIC_ERROR_OR_WARNING)
                                        break
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
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

private inline fun <T : Any, reified C : Any> Collection<T>.isItemsOf(clazz: Class<C>): Boolean {
    for (item in this) {
        if (!clazz.isInstance(item)) {
            return false
        }
    }
    return true
}

private fun PsiElement.isFreezeCall(): Boolean {
    val refs = callReferences()
    val props = refs.mapNotNull { it.resolve() }

    if (props.isEmpty()) {
        return false
    }

    if (props.last() is KtNamedFunction && refs.lastOrNull()?.canonicalText == "freeze" &&
        props.dropLast(1).isItemsOf(clazz = KtProperty::class.java)
    ) {
        return true
    }

    return false
}

private fun PsiElement.callReferences(): List<KtSimpleNameReference> {
    val refs = ArrayList<KtSimpleNameReference>(0)

    val stack = ArrayList<PsiElement>(0)
    stack.add(this)

    while (stack.isNotEmpty()) {
        val item = stack.first()
        stack.removeAt(0)

        when (item) {
            is KtDotQualifiedExpression ->
                stack.addAll(0, item.children.toList())

            is KtCallExpression -> {
                item.calleeExpression?.let {
                    stack.add(0, it)
                }
            }

            is KtReferenceExpression -> {
                val nameRef = item.references.find { it is KtSimpleNameReference } as? KtSimpleNameReference
                if (nameRef != null) {
                    refs.add(nameRef)
                }
            }
        }
    }

    return refs
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

private fun KtCallExpression.isMutateThis(): Boolean {
    val funcItem = children.firstOrNull()?.references?.find { it is KtSimpleNameReference }?.resolve()
    if (funcItem is KtNamedFunction) {
        val classBody = funcItem.parent as? KtClassBody ?: return false
        val isClassMember = classBody.parent is KtClass

        if (isClassMember) {
            funcItem.bodyBlockExpression?.let {
                for (item in it.children) {
                    when (item) {
                        is KtOperationExpression -> {
                            val mutatedReferences = item.firstChild.callReferences()
                            if (mutatedReferences.firstOrNull()?.resolve()?.isThisClassMember() == true) {
                                return true
                            }
                        }
                    }
                }
            }
        }
    }

    return false
}