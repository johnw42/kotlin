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

package org.jetbrains.kotlin.js.translate.utils.jsAstUtils

import com.google.dart.compiler.backend.js.ast.*
import org.jetbrains.kotlin.js.translate.context.Namer

fun JsFunction.addStatement(stmt: JsStatement) {
    body.statements.add(stmt)
}

fun JsFunction.addParameter(identifier: String, index: Int? = null): JsParameter {
    val name = scope.declareFreshName(identifier)
    val parameter = JsParameter(name)

    if (index == null) {
        parameters.add(parameter)
    } else {
        parameters.add(index, parameter)
    }

    return parameter
}

/**
 * Tests, if any node containing in receiver's AST matches, [predicate].
 */
fun JsNode.any(predicate: (JsNode) -> Boolean): Boolean {
    val visitor = object : RecursiveJsVisitor() {
        var matched: Boolean = false

        override fun visitElement(node: JsNode) {
            matched = matched || predicate(node)

            if (!matched) {
                super.visitElement(node)
            }
        }
    }

    visitor.accept(this)
    return visitor.matched
}

fun JsExpression.toInvocationWith(leadingExtraArgs: List<JsExpression>, thisExpr: JsExpression): JsExpression {
    val qualifier: JsExpression
    val arguments: MutableList<JsExpression>

    when (this) {
        is JsNew -> {
            qualifier = Namer.getFunctionCallRef(constructorExpression)
            arguments = getArguments()
            // `new A(a, b, c)` -> `A.call($this, a, b, c)`
            return JsInvocation(qualifier, listOf(thisExpr) + leadingExtraArgs + arguments)
        }
        is JsInvocation -> {
            qualifier = getQualifier()
            arguments = getArguments()
            // `A(a, b, c)` -> `A(a, b, c, $this)`
            return JsInvocation(qualifier, leadingExtraArgs + arguments + thisExpr)
        }
        else -> throw IllegalStateException("Unexpected node type: " + javaClass)
    }
}

var JsWhile.test: JsExpression
    get() = condition
    set(value) { condition = value }

var JsArrayAccess.index: JsExpression
    get() = indexExpression
    set(value) { indexExpression = value }

var JsArrayAccess.array: JsExpression
    get() = arrayExpression
    set(value) { arrayExpression = value }

var JsConditional.test: JsExpression
    get() = testExpression
    set(value) { testExpression = value }

var JsConditional.then: JsExpression
    get() = thenExpression
    set(value) { thenExpression = value }

var JsConditional.otherwise: JsExpression
    get() = elseExpression
    set(value) { elseExpression = value }
