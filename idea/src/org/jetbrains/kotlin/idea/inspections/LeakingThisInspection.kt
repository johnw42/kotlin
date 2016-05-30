/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.inspections

import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType.*
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.kotlin.cfg.LeakingThisDescriptor.*
import org.jetbrains.kotlin.descriptors.MemberDescriptor
import org.jetbrains.kotlin.idea.caches.resolve.analyzeFully
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext.LEAKING_THIS
import org.jetbrains.kotlin.resolve.DescriptorToSourceUtils
import org.jetbrains.kotlin.psi.addRemoveModifier.addModifier

class LeakingThisInspection : AbstractKotlinInspection() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
        return object : KtVisitorVoid() {
            override fun visitExpression(expression: KtExpression) {
                val context = expression.analyzeFully()
                val leakingThisDescriptor = context.get(LEAKING_THIS, expression)
                val description = when (leakingThisDescriptor) {
                    is PropertyIsNull -> null // Not supported yet
                    is NonFinalClass ->
                        if (expression is KtThisExpression)
                            "Leaking this in constructor of non-final class ${leakingThisDescriptor.klass.name}"
                        else
                            null // Not supported yet
                    is NonFinalProperty ->
                        "Accessing non-final property ${leakingThisDescriptor.property.name} in constructor"
                    is NonFinalFunction ->
                        "Calling non-final function ${leakingThisDescriptor.function.name} in constructor"
                    null -> null
                }
                if (description != null && leakingThisDescriptor != null) {
                    val memberDescriptorToFix = when (leakingThisDescriptor) {
                        is NonFinalProperty -> leakingThisDescriptor.property
                        is NonFinalFunction -> leakingThisDescriptor.function
                        else -> null
                    }
                    val memberFix = memberDescriptorToFix?.let { MakeFinalFix(it) }
                    val classFix = MakeFinalFix(leakingThisDescriptor.classOrObject)
                    holder.registerProblem(
                            expression, description,
                            when (leakingThisDescriptor) {
                                is NonFinalProperty, is NonFinalFunction -> GENERIC_ERROR_OR_WARNING
                                else -> WEAK_WARNING
                            },
                            *(if (memberFix?.isApplicable() ?: false) arrayOf(memberFix, classFix) else arrayOf(classFix))
                    )
                }
            }
        }
    }

    class MakeFinalFix private constructor(val modifierListOwner: KtModifierListOwner?, val name: Name): LocalQuickFix {

        constructor(member: MemberDescriptor):
                this(DescriptorToSourceUtils.descriptorToDeclaration(member) as? KtModifierListOwner, member.name)

        constructor(classOrObject: KtClassOrObject):
                this(classOrObject, classOrObject.nameAsSafeName)

        internal fun isApplicable() = modifierListOwner != null

        override fun getName() = familyName

        override fun getFamilyName() = "Make '$name' final"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            modifierListOwner?.let { addModifier(it, KtTokens.FINAL_KEYWORD) }
        }
    }
}