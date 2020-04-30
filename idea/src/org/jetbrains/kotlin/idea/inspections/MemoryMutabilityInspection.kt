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
import org.jetbrains.kotlin.psi.psiUtil.siblings
import kotlin.collections.ArrayList
import kotlin.collections.List
import kotlin.collections.find
import kotlin.collections.first
import kotlin.collections.isNotEmpty
import kotlin.collections.listOf
import kotlin.reflect.KClass

class MemoryMutabilityInspection : AbstractKotlinInspection() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : KtVisitorVoid() {
            override fun visitBinaryExpression(expression: KtBinaryExpression) {
                super.visitBinaryExpression(expression)
                expression.checkMutationProblems(holder)
            }

            override fun visitUnaryExpression(expression: KtUnaryExpression) {
                super.visitUnaryExpression(expression)
                expression.checkMutationProblems(holder)
            }
        }
    }
}

private fun KtOperationExpression.checkMutationProblems(holder: ProblemsHolder) {
    val mutatedReferences = firstChild.allReferences()

    var mutatedProps = mutatedReferences.mapNotNull { it.resolve() }
    if (mutatedProps.isEmpty()) {
        return
    }

    if (mutatedReferences.firstOrNull()?.isSingletonPropertyRef() == true
        && mutatedProps.drop(1).isItemsOf(clazz = KtProperty::class.java)
    ) {
        holder.registerProblem(this, "Trying mutate frozen Object property", GENERIC_ERROR_OR_WARNING)
        return
    }

    if (!mutatedProps.isItemsOf(clazz = KtProperty::class.java)) {
        return
    }

    for (item in this.siblings(forward = false, withItself = false)) {
        when (item) {
            is KtCallExpression -> {
                if (item.isFreezeCall()) {
                    val prop = mutatedProps[0] as? KtProperty
                    if (prop != null) {
                        val classBody = prop.parent as? KtClassBody ?: continue
                        if (classBody.parent is KtClass) {
                            holder.registerProblem(this, "Trying mutate frozen object", GENERIC_ERROR_OR_WARNING)
                            return
                        }
                    }
                }
            }

            is KtOperationExpression -> {
                val newMutatedReferences = item.firstChild.allReferences()
                val newMutatedProps = newMutatedReferences.mapNotNull { it.resolve() }

                if (newMutatedProps.isNotEmpty()) {
                    if (newMutatedProps.isSubset(mutatedProps)) {
                        mutatedProps = newMutatedProps
                    }
                }
            }

            is KtDotQualifiedExpression -> {
                if (!item.isFreezeCall()) {
                    continue
                }
                val frozenProps = item.allReferences().dropLast(1).mapNotNull { it.resolve() }

                if (frozenProps.isNotEmpty()) {
                    if (frozenProps.isSubset(mutatedProps) && frozenProps.size < mutatedProps.size) {
                        holder.registerProblem(this, "Trying mutate frozen object", GENERIC_ERROR_OR_WARNING)
                        return
                    }
                } else {
                    holder.registerProblem(this, "Trying mutate frozen object", GENERIC_ERROR_OR_WARNING)
                    return
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
    var refs = allReferences()
    var props = refs.mapNotNull { it.resolve() }

    if (props.isEmpty()) {
        return false
    }

    if (props.dropLast(1).isItemsOf(clazz = KtProperty::class.java) &&
        props.last() is KtNamedFunction &&
        allReferences().lastOrNull()?.canonicalText == "freeze"
    ) {
        return true
    }

    return false
}

private fun PsiElement.allReferences(): List<KtSimpleNameReference> {
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
                val functionIdentifier = item.children.first()
                if (functionIdentifier != null)
                    stack.add(0, functionIdentifier)
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

private fun KtSimpleNameReference.isSingletonPropertyRef(): Boolean {
    when (val item = resolve()) {
        is KtObjectDeclaration ->
            return true

        is KtProperty -> {
            val classBody = item.parent as? KtClassBody ?: return false
            return classBody.parent is KtObjectDeclaration
        }
    }
    return false
}
