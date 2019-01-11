/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.daffodil.dpath

import org.apache.daffodil.exceptions.Assert
import org.apache.daffodil.processors.unparsers.UState
import org.apache.daffodil.dsom.CompiledExpression
import org.apache.daffodil.util.Maybe
import org.apache.daffodil.util.Maybe._
import org.apache.daffodil.processors.Suspension
import org.apache.daffodil.util.LogLevel

/**
 * Base for unparse-time expression evaluation that can have forward reference.
 * There are only two such cases, which is dfdl:outputValueCalc, and
 * dfdl:setVariable expressions (which variables are in-turn used by
 * dfdl:outputValueCalc.
 */
trait SuspendableExpression
  extends Suspension {

  override val isReadOnly = true

  protected def expr: CompiledExpression[AnyRef]

  override def toString = "SuspendableExpression(" + rd.diagnosticDebugName + ", expr=" + expr.prettyExpr + ")"

  protected def processExpressionResult(ustate: UState, v: AnyRef): Unit

  override protected final def doTask(ustate: UState) {
    var v: Maybe[AnyRef] = Nope
    if (!isBlocked) {
      log(LogLevel.Debug, "Starting suspendable expression for %s, expr=%s", rd.diagnosticDebugName, expr.prettyExpr)
    } else {
      this.setUnblocked()
      log(LogLevel.Debug, "Retrying suspendable expression for %s, expr=%s", rd.diagnosticDebugName, expr.prettyExpr)
    }
    while (v.isEmpty && !this.isBlocked) {
      v = expr.evaluateForwardReferencing(ustate, this)
      if (v.isEmpty) {
        Assert.invariant(this.isBlocked)
        log(LogLevel.Debug, "UnparserBlocking suspendable expression for %s, expr=%s", rd.diagnosticDebugName, expr.prettyExpr)
      } else {
        Assert.invariant(this.isDone)
        Assert.invariant(ustate.currentInfosetNodeMaybe.isDefined)
        log(LogLevel.Debug, "Completed suspendable expression for %s, expr=%s", rd.diagnosticDebugName, expr.prettyExpr)
        processExpressionResult(ustate, v.get)
      }
    }
  }

}
