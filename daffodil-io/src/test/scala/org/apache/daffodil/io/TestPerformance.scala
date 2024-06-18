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

package org.apache.daffodil.io

import org.junit.Test

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CoderResult

import org.apache.daffodil.io.processors.charset.CharsetUtils
import org.apache.daffodil.lib.util.Timer

class TestPerformance {
  
  /**
   * InputStream that always returns the same value for a specified number of bytes, will never
   * return more than decodeSize bytes in a single call to read()
   */
  class RepeatingInputStream(numBytes: Long, decodeSize: Int, byte: Byte) extends InputStream {
    private var numRemaining = numBytes
    private val src = Array.fill[Byte](decodeSize)(byte)

    def restart(): Unit = numRemaining = numBytes

    override def read(): Int = {
      if (numRemaining == 0) -1
      else {
        numRemaining -= 1
        byte
      }
    }

    override def read(b: Array[Byte]): Int = read(b, 0, b.length)

    override def read(b: Array[Byte], off: Int, len: Int): Int = {
      if (len == 0) 0
      else if (numRemaining == 0) -1
      else {
        val num = Math.min(Math.min(numRemaining, len), decodeSize).toInt
        System.arraycopy(src, 0, b, off, num)
        numRemaining -= num
        num
      }
    }
  }

  private def decodeStreamDaffodil(is: InputStream, charset: String, decodeSize: Int): Long = {
    val input = InputSourceDataInputStream(is)
    val output = CharBuffer.allocate(decodeSize)
    val fmtinfo = FakeFormatInfo_MSBF_BE
    val decoder = CharsetUtils.getCharset(charset).newDecoder()

    val (nanos, _) = Timer.getTimeResult(
      while ({
        val num = decoder.decode(input, fmtinfo, output)
        num > 0
      }) {
        output.clear() 
      }
    )
    nanos
  }

  private def decodeStreamJava(is: InputStream, charset: String, decodeSize: Int): Long = {
    // java decoders cannot decode from a stream, so we read bytes from the stream into an array
    // that is wrapped by a ByteBuffer and then decode that ByteBuffer. This is somewhat
    // comparable to how daffodil reads from a stream to a bucketing input source and decodes
    // from that.
    val inputArray = new Array[Byte](decodeSize)
    val input = ByteBuffer.wrap(inputArray)
    val output = CharBuffer.allocate(decodeSize)
    val decoder = Charset.forName(charset).newDecoder()

    val (nanos, _) = Timer.getTimeResult(
      while ({
        val num = is.read(inputArray)
        if (num > -1) {
          decoder.decode(input, output, true)
          true // assume no decoder issues, we use result of the input stream to know we are done
        } else {
          false
        }
      }) {
        input.clear()
        output.clear()
      }
    )
    nanos
  }

  private def decodeArrayDaffodil(arr: Array[Byte], charset: String, decodeSize: Int): Long = {
    val input = InputSourceDataInputStream(arr)
    val output = CharBuffer.allocate(decodeSize)
    val fmtinfo = FakeFormatInfo_MSBF_BE
    val decoder = CharsetUtils.getCharset(charset).newDecoder()

    val (nanos, _) = Timer.getTimeResult(
      while ({
        val num = decoder.decode(input, fmtinfo, output)
        num > 0
      }) {
        output.clear() 
      }
    )

    nanos
  }

  private def decodeArrayJava(arr: Array[Byte], charset: String, decodeSize: Int): Long = {
    val input = ByteBuffer.wrap(arr)
    val output = CharBuffer.allocate(decodeSize)
    val decoder = Charset.forName(charset).newDecoder()

    val (nanos, _) = Timer.getTimeResult(
      while ({
        val res = decoder.decode(input, output, true)
        res != CoderResult.UNDERFLOW
      }) {
        output.clear()
      }
    )
    nanos
  }

  private def toMBs(bytes: Long, seconds: Double): Double = {
    val B_IN_MB = 1000000.0
    bytes / B_IN_MB / seconds
  }

  @Test
  def testPerformance(): Unit = {
    val dataSize = 2000000000
    val dataByte = 'A'.toByte
    val charset = "US-ASCII"
    val daffodil = true
    val stream = true

    val NS_IN_S = 1000000000.0

    val fieldSizes = Seq(1000, 1, 2, 5, 10, 50, 100)

    val pad = " " * 80
    val name = {if (daffodil) "Daffodil/" else "Java/"} + {if (stream) "Stream/" else "Array/"} + charset

    val sizeNanos = fieldSizes.map { fieldSize =>
      System.err.println(name + "..." + fieldSize + pad)
      val nanos =
        if (stream) {
          val is = new RepeatingInputStream(dataSize, 8192, dataByte)
          if (daffodil)
            decodeStreamDaffodil(is, charset, fieldSize)
          else
            decodeStreamJava(is, charset, fieldSize)
        } else {
          val arr = Array.fill[Byte](dataSize)(dataByte)
          if (daffodil)
            decodeArrayDaffodil(arr, charset, fieldSize)
          else
            decodeArrayJava(arr, charset, fieldSize)
        }
      nanos / NS_IN_S
    }
    System.err.println("DONE..." + pad + "\n" + pad)
    val str = sizeNanos.mkString(name + pad + "\n", pad + "\n", pad)
    System.err.println(str)
  }

}
