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
package org.sbadudfs.udfexceptions.evaluating.StringFunctions

import org.apache.daffodil.udf.UserDefinedFunction
import org.apache.daffodil.udf.UserDefinedFunctionIdentification
import org.apache.daffodil.udf.UserDefinedFunctionProvider
import org.apache.daffodil.udf.exceptions.UserDefinedFunctionProcessingError

/**
 * UDF Provider for Negative Unit test
 *
 * Contains classes that throw exceptions
 */
class StringFunctionsProvider extends UserDefinedFunctionProvider {

  override def getUserDefinedFunctionClasses: Array[Class[?]] = {
    Array(classOf[ReverseWords], classOf[Reverse])
  }
}

/**
 * UDF for exceptions negative tests in Scala
 *
 * Throws exception on evaluate
 */
@UserDefinedFunctionIdentification(
  name = "reverse",
  namespaceURI = "http://example.com/scala/udf"
)
class Reverse extends UserDefinedFunction {
  case class CustomException(
    private val message: String = "",
    private val cause: Throwable = None.orNull
  ) extends Exception(message, cause)

  /**
   * Reverses the order of chars in a string
   *
   * @param str string whose order you wish to reverse
   * @return reversed str
   */
  def evaluate(str: String) = {
    val ret = str.reverse
    throw new CustomException("UDF Error!")
    ret
  }
}

/**
 * UDF for exceptions negative tests in Scala
 */
@UserDefinedFunctionIdentification(
  name = "rev-words",
  namespaceURI = "http://example.com/scala/udf"
)
class ReverseWords extends UserDefinedFunction {

  /**
   * Reverses the order of words in a sentence
   *
   * @param strToRev string whose word order you wish to reverse
   * @param sep Boundary to split your sentence on
   * @return reversed sentence based on $sep boundary
   */
  def evaluate(strToRev: String, sep: String = " ") = {
    throw new UserDefinedFunctionProcessingError("UDF PE!")
    val ret = strToRev.split(sep).reverse.mkString(sep)
    ret
  }
}
