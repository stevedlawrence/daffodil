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

package org.apache.daffodil.io.processors.charset

import java.nio.CharBuffer

import org.apache.daffodil.io.FormatInfo
import org.apache.daffodil.io.InputSourceDataInputStream
import org.apache.daffodil.lib.exceptions.ThinException

class BitsCharsetDecoderMalformedException(val malformedBits: Int) extends ThinException

class BitsCharsetDecoderUnalignedCharDecodeException(val bitPos1b: Long) extends ThinException {
  def bitAlignment1b = bitPos1b % 8
  def bytePos1b = ((bitPos1b - 1) / 8) + 1

  override def getMessage(): String = {
    s"Charset not byte aligned. bitAlignment1b=${bitAlignment1b}, bitPos1b=${bitPos1b}, bytePos1b=${bytePos1b}."
  }
}

trait BitsCharsetDecoderState

abstract class BitsCharsetDecoder {

  /**
   * Decode multiple characters into a CharBuffer, keeping track of the
   * bit positions after each Char decode
   *
   * Decodes at most chars.remaining() characters in the chars CharBuffer.
   *
   * Upon return of the decode operation, the bitPosition0b of the InputSourceDataInputStream
   * will be the end of the last successful character decode operation. Returns the number of
   * successfully decode characters.
   */
  def decode(
    dis: InputSourceDataInputStream,
    finfo: FormatInfo,
    chars: CharBuffer,
  ): Int

  def reset(): Unit
}

/**
 * Decoder be used for fixed-width character sets, supporting character sets that do not have
 * andatory byte alignment and can work with any bitOrder. This requires that the codeToChar
 * mapping is large enough for bit bit width of the charset, and assumes any values not valid in
 * the encoding return the unicode replacement character.
 */
final class BitsCharsetNonByteSizeDecoder(charset: BitsCharsetNonByteSize)
  extends BitsCharsetDecoder {

  final override def decode(
    dis: InputSourceDataInputStream,
    finfo: FormatInfo,
    chars: CharBuffer,
  ): Int = {
    var keepDecoding = true
    val charsToDecode = chars.remaining
    var numDecoded = 0

    val bitWidth = charset.bitWidthOfACodeUnit

    while (keepDecoding && numDecoded < charsToDecode) {
      if (!dis.isDefinedForLength(bitWidth)) {
        keepDecoding = false
      } else {
        // TODO: getUnsignedLong is going to call isDefinedForLength again. We should determine
        // if this overhead is worth removing, or if it's better to just call getUnsignedLong
        // and handle the NotEnoughDataException when if we run out of data
        val code = dis.getUnsignedLong(bitWidth, finfo).toInt
        val char = charset.codeToChar(code)
        chars.put(char)
        numDecoded += 1
      }
    }
    numDecoded
  }

  override def reset(): Unit = {
    // do nothing
  }
}
