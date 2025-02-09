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

package org.apache.daffodil.processor.tdml

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import scala.xml.Node

import org.apache.daffodil.runtime1.infoset.InfosetOutputter
import org.apache.daffodil.runtime1.infoset.JDOMInfosetInputter
import org.apache.daffodil.runtime1.infoset.JDOMInfosetOutputter
import org.apache.daffodil.runtime1.infoset.JsonInfosetInputter
import org.apache.daffodil.runtime1.infoset.JsonInfosetOutputter
import org.apache.daffodil.runtime1.infoset.NullInfosetInputter
import org.apache.daffodil.runtime1.infoset.ScalaXMLInfosetInputter
import org.apache.daffodil.runtime1.infoset.ScalaXMLInfosetOutputter
import org.apache.daffodil.runtime1.infoset.TeeInfosetOutputter
import org.apache.daffodil.runtime1.infoset.W3CDOMInfosetInputter
import org.apache.daffodil.runtime1.infoset.W3CDOMInfosetOutputter
import org.apache.daffodil.runtime1.infoset.XMLTextInfosetInputter
import org.apache.daffodil.runtime1.infoset.XMLTextInfosetOutputter

class TDMLInfosetOutputterScala
  extends {
    private val scalaOut = new ScalaXMLInfosetOutputter()
    private val outputters: Seq[InfosetOutputter] = Seq(scalaOut)
  }
  with TeeInfosetOutputter(outputters: _*)
  with TDMLInfosetOutputter {

  override def getResult: Node = scalaOut.getResult

  override lazy val xmlStream: ByteArrayOutputStream = {
    val bos = new ByteArrayOutputStream()
    bos.write(getResult.toString().getBytes(Charset.defaultCharset()))
    bos
  }

  override def toInfosetInputter: TDMLInfosetInputter = {
    val scalaIn = new ScalaXMLInfosetInputter(scalaOut.getResult)
    new TDMLInfosetInputter(scalaIn, Seq())
  }
}

class TDMLInfosetOutputterAll
  extends {
    private val jsonStream = new ByteArrayOutputStream()
    override val xmlStream = new ByteArrayOutputStream()

    private val scalaOut = new ScalaXMLInfosetOutputter()
    private val jdomOut = new JDOMInfosetOutputter()
    private val w3cdomOut = new W3CDOMInfosetOutputter()
    private val jsonOut = new JsonInfosetOutputter(jsonStream, false)
    private val xmlOut = new XMLTextInfosetOutputter(xmlStream, false)

    private val outputters: Seq[InfosetOutputter] =
      Seq(xmlOut, scalaOut, jdomOut, w3cdomOut, jsonOut)
  }
  with TeeInfosetOutputter(outputters: _*)
  with TDMLInfosetOutputter {

  override def getResult: Node = scalaOut.getResult

  override def toInfosetInputter: TDMLInfosetInputter = {
    val scalaIn = new ScalaXMLInfosetInputter(scalaOut.getResult)
    val jdomIn = new JDOMInfosetInputter(jdomOut.getResult)
    val w3cdomIn = new W3CDOMInfosetInputter(w3cdomOut.getResult)
    val jsonIn = new JsonInfosetInputter(new ByteArrayInputStream(jsonStream.toByteArray))
    val xmlIn = new XMLTextInfosetInputter(new ByteArrayInputStream(xmlStream.toByteArray))
    val nullIn = {
      val events = NullInfosetInputter.toEvents(new ByteArrayInputStream(xmlStream.toByteArray))
      new NullInfosetInputter(events)
    }
    new TDMLInfosetInputter(scalaIn, Seq(jdomIn, w3cdomIn, jsonIn, xmlIn, nullIn))
  }
}

trait TDMLInfosetOutputter extends InfosetOutputter {

  def xmlStream: ByteArrayOutputStream

  def getResult: Node

  def toInfosetInputter: TDMLInfosetInputter
}
