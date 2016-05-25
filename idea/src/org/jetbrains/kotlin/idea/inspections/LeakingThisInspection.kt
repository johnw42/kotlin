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
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.kotlin.cfg.LeakingThisDescriptor.*
import org.jetbrains.kotlin.idea.caches.resolve.analyzeFully
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtVisitorVoid
import org.jetbrains.kotlin.resolve.BindingContext.LEAKING_THIS

class LeakingThisInspection : AbstractKotlinInspection() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
        return object : KtVisitorVoid() {
            override fun visitKtFile(file: KtFile) {
                val context = file.analyzeFully()
                val expressions = context.getKeys(LEAKING_THIS)
                for (expression in expressions) {
                    val leakingThisDescriptor = context.get(LEAKING_THIS, expression)
                    val description = when (leakingThisDescriptor) {
                        is PropertyIsNull ->
                            "NPE risk: leaking this in constructor while not-null ${leakingThisDescriptor.property.name} is still null"
                        is NonFinalClass ->
                            "NPE risk: leaking this in constructor of non-final class ${leakingThisDescriptor.klass.name}"
                        is NonFinalProperty ->
                            "NPE risk: leaking this in constructor while accessing non-final property ${leakingThisDescriptor.property.name}"
                        null -> null
                    }
                    if (description != null) {
                        holder.registerProblem(expression, description, ProblemHighlightType.WEAK_WARNING)
                    }
                }
            }
        }
    }
}