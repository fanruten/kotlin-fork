/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.inspections

import com.intellij.codeInspection.IntentionWrapper
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemHighlightType.GENERIC_ERROR_OR_WARNING
import com.intellij.codeInspection.ProblemHighlightType.WEAK_WARNING
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.util.elementType
import org.bouncycastle.asn1.x500.style.RFC4519Style.c
import org.jetbrains.kotlin.cfg.LeakingThisDescriptor.*
import org.jetbrains.kotlin.cfg.pseudocode.Pseudocode
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.idea.KotlinBundle
import org.jetbrains.kotlin.idea.caches.resolve.analyzeWithContent
import org.jetbrains.kotlin.idea.quickfix.AddModifierFix
import org.jetbrains.kotlin.idea.quickfix.ChangeVariableMutabilityFix
import org.jetbrains.kotlin.idea.references.KtInvokeFunctionReference
import org.jetbrains.kotlin.idea.references.KtSimpleNameReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import org.jetbrains.kotlin.psi.psiUtil.getChildrenOfType
import org.jetbrains.kotlin.psi.psiUtil.getPrevSiblingIgnoringWhitespace
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import org.jetbrains.kotlin.psi.stubs.elements.KtDotQualifiedExpressionElementType
import org.jetbrains.kotlin.resolve.BindingContext.LEAKING_THIS
import org.jetbrains.kotlin.resolve.DescriptorToSourceUtils
import java.util.HashMap

class MemoryMutabilityInspection: AbstractKotlinInspection() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : KtVisitorVoid() {
            private val pseudocodeCache = HashMap<KtDeclaration, Pseudocode>()

            override fun visitBinaryExpression(expression: KtBinaryExpression) {
                super.visitBinaryExpression(expression)
                println("visitBinaryExpression | ${expression.text} |")

                expression.checkMutationProblems(holder)
            }

            override fun visitUnaryExpression(expression: KtUnaryExpression) {
                super.visitUnaryExpression(expression)
                println("visitUnaryExpression | ${expression.text} |")

                expression.checkMutationProblems(holder)
            }
        }
    }
}

private fun KtOperationExpression.checkMutationProblems(holder: ProblemsHolder) {
    var mutatedReferences = mutatedReferences()

    print("Mutated refs:")
    for (ref in mutatedReferences) {
        print(" ${ref.canonicalText}")
    }
    println()

    if (mutatedReferences.firstOrNull()?.isSingletonPropertyRef() == true) {
        holder.registerProblem(this, "Trying mutate frozen Object property", GENERIC_ERROR_OR_WARNING)
        println("Trying mutate frozen Object property")
        return
    }

    var mutatedProps = mutatedReferences.mapNotNull { it.resolve() }
    if (mutatedProps.isEmpty()) {
        return
    }

    var item = getPrevSiblingIgnoringWhitespace()
    while (item != null) {
        when (item) {
            is KtOperationExpression -> {
                var newMutatedReferences = item.mutatedReferences()
                var newMutatedProps = newMutatedReferences.mapNotNull { it.resolve() }
                if (newMutatedProps.isNotEmpty()) {
                    var count = newMutatedProps.size
                    for (prop in newMutatedProps) {
                        if (mutatedProps.contains(prop)) {
                            count -= 1
                        } else {
                            break
                        }
                    }

                    if (count == 0) {
                        mutatedProps = newMutatedProps
                    }
                }
            }
            is KtDotQualifiedExpression -> {
                val frozenProps = item.freezeCallSubject().mapNotNull { it.resolve() }
                if (frozenProps.isNotEmpty()) {
                    var count = frozenProps.size
                    for (prop in frozenProps) {
                        if (mutatedProps.contains(prop)) {
                            count -= 1
                        } else {
                            break
                        }
                    }

                    if (count == 0 && frozenProps.size < mutatedProps.size) {
                        holder.registerProblem(this, "Trying mutate frozen object", GENERIC_ERROR_OR_WARNING)
                        println("Trying mutate frozen object")
                        return
                    }
                }
            }
        }

        item = item.getPrevSiblingIgnoringWhitespace()
    }
}

private fun KtOperationExpression.mutatedReferences(): List<KtSimpleNameReference> {
    return allReferenceExpressions()
}

private fun KtDotQualifiedExpression.freezeCallSubject(): List<KtSimpleNameReference> {
    val call = children.find { it is KtCallExpression } ?: return listOf()
    val invokeFunction = call.children.find { it is KtReferenceExpression } ?: return listOf()
    val nameReference = invokeFunction.references.find { it is KtSimpleNameReference } ?: return listOf()

    if (nameReference.canonicalText == "freeze") {
        return allReferenceExpressions()
    }

    return listOf()
}

private fun PsiElement.allReferenceExpressions(): List<KtSimpleNameReference> {
    val refs = ArrayList<KtSimpleNameReference>(0)

    val stack = ArrayList<PsiElement>(0)
    stack.add(firstChild)

    while (stack.isNotEmpty()) {
        val item = stack.first()
        stack.removeAt(0)

        when (item) {
            is KtDotQualifiedExpression ->
                stack.addAll(item.children.reversed())

            is KtReferenceExpression -> {
                val nameRef = item.references.find { it is KtSimpleNameReference } as? KtSimpleNameReference
                if (nameRef != null) {
                    refs.add(nameRef)
                }
            }
        }
    }

    return refs.reversed()
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
