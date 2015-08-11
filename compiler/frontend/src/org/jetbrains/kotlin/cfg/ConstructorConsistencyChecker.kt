/*
 * Copyright 2010-2015 JetBrains s.r.o.
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

package org.jetbrains.kotlin.cfg

import org.jetbrains.kotlin.cfg.pseudocode.PseudocodeUtil
import org.jetbrains.kotlin.cfg.pseudocode.instructions.KtElementInstruction
import org.jetbrains.kotlin.cfg.pseudocode.instructions.eval.MagicInstruction
import org.jetbrains.kotlin.cfg.pseudocode.instructions.eval.MagicKind
import org.jetbrains.kotlin.cfg.pseudocode.instructions.eval.ReadValueInstruction
import org.jetbrains.kotlin.cfg.pseudocodeTraverser.TraversalOrder
import org.jetbrains.kotlin.cfg.pseudocodeTraverser.traverse
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.types.expressions.OperatorConventions

sealed class LeakingThisDescriptor {
    class PropertyIsNull(val property: PropertyDescriptor) : LeakingThisDescriptor()

    class NonFinalClass(val klass: ClassDescriptor): LeakingThisDescriptor()
}

class ConstructorConsistencyChecker private constructor(declaration: KtDeclaration, private val trace: BindingTrace) {

    private val classOrObject = declaration as? KtClassOrObject ?: (declaration as KtConstructor<*>).getContainingClassOrObject()

    private val classDescriptor = trace.get(BindingContext.CLASS, classOrObject)

    private val finalClass = classDescriptor?.isFinalClass ?: true

    private val pseudocode = PseudocodeUtil.generatePseudocode(declaration, trace.bindingContext)

    private val variablesData = PseudocodeVariablesData(pseudocode, trace.bindingContext)

    private fun safeThisUsage(expression: KtThisExpression): Boolean {
        val referenceDescriptor = trace.get(BindingContext.REFERENCE_TARGET, expression.instanceReference)
        if (referenceDescriptor != classDescriptor) return true
        val parent = expression.parent
        return when (parent) {
            is KtQualifiedExpression -> parent.selectorExpression is KtSimpleNameExpression
            is KtBinaryExpression -> OperatorConventions.EQUALS_OPERATIONS.contains(parent.operationToken) ||
                                     OperatorConventions.IDENTITY_EQUALS_OPERATIONS.contains(parent.operationToken)
            else -> false
        }
    }

    private fun safeCallUsage(expression: KtCallExpression): Boolean {
        val callee = expression.calleeExpression
        if (callee is KtReferenceExpression) {
            val descriptor = trace.get(BindingContext.REFERENCE_TARGET, callee)
            if (descriptor is FunctionDescriptor) {
                val containingDescriptor = descriptor.containingDeclaration
                if (containingDescriptor != classDescriptor) return true
            }
        }
        return false
    }

    fun check() {
        // List of properties to initialize
        val propertyDescriptors = variablesData.getDeclaredVariables(pseudocode, false)
                .filterIsInstance<PropertyDescriptor>()
                .filter { trace.get(BindingContext.BACKING_FIELD_REQUIRED, it) == true }
        pseudocode.traverse(
                TraversalOrder.FORWARD, variablesData.variableInitializers, { instruction, enterData, exitData ->

            fun firstUninitializedNotNullProperty() = propertyDescriptors.firstOrNull {
                !it.type.isMarkedNullable && !it.isLateInit && !(enterData[it]?.definitelyInitialized() ?: false)
            }

            fun target(expression: KtExpression): KtExpression = when (expression) {
                is KtThisExpression -> {
                    val selectorOrThis = (expression.parent as? KtQualifiedExpression)?.let {
                        if (it.receiverExpression === expression) it.selectorExpression else null
                    } ?: expression
                    if (selectorOrThis === expression) selectorOrThis else target(selectorOrThis)
                }
                is KtCallExpression -> expression.let { it.calleeExpression ?: it }
                else -> expression
            }

            fun handleLeakingThis(expression: KtExpression) {
                if (!finalClass && classDescriptor != null) {
                    trace.record(BindingContext.LEAKING_THIS, target(expression),
                                 LeakingThisDescriptor.NonFinalClass(classDescriptor))
                }
                else {
                    val uninitializedProperty = firstUninitializedNotNullProperty()
                    if (uninitializedProperty != null) {
                        trace.record(BindingContext.LEAKING_THIS, target(expression),
                                     LeakingThisDescriptor.PropertyIsNull(uninitializedProperty))
                    }
                }
            }

            if (instruction.owner != pseudocode) {
                // We should miss *some* of this local declarations, but not all
                return@traverse
            }

            if (instruction is KtElementInstruction) {
                val element = instruction.element
                when (instruction) {
                    is ReadValueInstruction ->
                        if (element is KtThisExpression) {
                            if (!safeThisUsage(element)) {
                                handleLeakingThis(element)
                            }
                        }
                    is MagicInstruction ->
                        if (instruction.kind == MagicKind.IMPLICIT_RECEIVER && element is KtCallExpression) {
                            if (!safeCallUsage(element)) {
                                handleLeakingThis(element)
                            }
                        }
                }
            }
        })
    }

    companion object {
        fun check(constructor: KtConstructor<*>, trace: BindingTrace) {
            ConstructorConsistencyChecker(constructor, trace).check()
        }

        fun check(classOrObject: KtClassOrObject, trace: BindingTrace) {
            ConstructorConsistencyChecker(classOrObject, trace).check()
        }
    }
}