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

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CoderResult
import java.nio.charset.CodingErrorAction
import java.nio.charset.{ Charset => JavaCharset }
import java.nio.charset.{ CharsetEncoder => JavaCharsetEncoder }
import java.nio.charset.{ CharsetDecoder => JavaCharsetDecoder }
import scala.collection.convert.ImplicitConversions.`collection AsScalaIterable`

import org.apache.daffodil.io.FormatInfo
import org.apache.daffodil.io.InputSourceDataInputStream
import org.apache.daffodil.lib.exceptions.Assert
import org.apache.daffodil.lib.schema.annotation.props.gen.BitOrder
import org.apache.daffodil.lib.util.MaybeInt

/**
 * Charset enhanced with features allowing it to work with Daffodil's Bit-wise
 * DataInputStream and DataOutputStream.
 *
 * Daffodil uses BitsCharset as its primary abstraction for dealing with
 * character sets, which enables it to support character sets where the code
 * units are smaller than 1 byte.
 *
 * Note that BitsCharset is NOT derived from java.nio.charset.Charset, nor are
 * BitsCharsetDecoder or BitsCharsetEncoder derived from
 * java.nio.charset.CharsetDecoder or CharsetEncoder respectively. This is
 * partly because these Java classes have many final methods that make it
 * impossible for us to implement what we need by extending them. But more
 * importantly, we need much more low level control about how characters are
 * decoded what what kind of information is returned during decode operations.
 * Getting that information with the limitations of the java Charset API become
 * an encumbrance. Replacing with our own Charset decoders grealy simplifies
 * the code and allows for future enhancements as needed.
 */
trait BitsCharset extends Serializable {
  final override def hashCode = name.hashCode
  final override def equals(other: Any) = other match {
    case bcs: BitsCharset => this.name == bcs.name
    case _ => false
  }
  def name: String
  def bitWidthOfACodeUnit: Int // in units of bits
  def requiredBitOrder: BitOrder
  def mandatoryBitAlignment: Int // ignored when dfdlx:alignmentKind is 'manual'
  def newDecoder(): BitsCharsetDecoder
  def newEncoder(): BitsCharsetEncoder

  /**
   * Used to determine if zoned numbers use ascii or ebcdic conventions
   * for overpunched signs. This determines whether the textZonedSignStyle property is needed or not.
   *
   * Override in any EBCDIC family charset definition.
   * @return true if the charset is an EBCDIC family charset.
   */
  def isEbcdicFamily(): Boolean = false

  /**
   * @return the width, in bits of a character, or Nope if the character set is variable width.
   */
  def maybeFixedWidth: MaybeInt

  final def padCharWidthInBits = {
    if (maybeFixedWidth.isDefined)
      maybeFixedWidth.get
    else {
      this match {
        case StandardBitsCharsets.UTF_8 => 8
        case _ =>
          Assert.invariantFailed(
            "Getting pad char width for unsupported variable-width charset: " + name
          )
      }
    }
  }
}

trait IsResetMixin {
  private final var isReset_ : Boolean = true

  /**
   * True if the decoder has not decoded anything since the last reset call.
   * False if decodeLoop has been called.
   *
   * Use to control things that we only want to check once per reset of the
   * decoder.
   */
  final def isReset = isReset_

  /**
   * Allow assignment to isReset only in derived classes
   */
  protected final def isReset_=(v: Boolean): Unit = isReset_ = v
}

/**
 * Implements BitsCharset based on encapsulation of a regular JavaCharset.
 */
trait BitsCharsetJava extends BitsCharset {

  @transient lazy val javaCharset = JavaCharset.forName(name)
  @transient final override lazy val maybeFixedWidth = {
    val enc = javaCharset.newEncoder()
    val avg = enc.averageBytesPerChar()
    val max = enc.maxBytesPerChar()
    if (avg == max) MaybeInt(avg.toInt * 8)
    else MaybeInt.Nope
  }

  private lazy val hasNameOrAliasContainingEBCDIC = {
    val allCharsetNames = (javaCharset.aliases().toSeq :+ name :+ javaCharset.name()).map {
      _.toUpperCase
    }
    val res = allCharsetNames.exists(_.contains("EBCDIC"))
    res
  }

  override def isEbcdicFamily(): Boolean = hasNameOrAliasContainingEBCDIC

  final override def newEncoder() =
    new BitsCharsetWrappingJavaCharsetEncoder(this, javaCharset.newEncoder())

  final override def newDecoder() =
    new BitsCharsetWrappingJavaCharsetDecoder(this, javaCharset.newDecoder())

  override def bitWidthOfACodeUnit: Int = 8
  override def requiredBitOrder =
    BitOrder.MostSignificantBitFirst // really none, as these are mandatory aligned to byte boundary.
  override def mandatoryBitAlignment = 8
}

abstract class BitsCharsetEncoder extends IsResetMixin {
  def bitsCharset: BitsCharset
  def averageBytesPerChar(): Float
  def maxBytesPerChar(): Float
  def averageBitsPerChar(): Float
  def maxBitsPerChar(): Float
  def replacement(): Array[Byte]
  def replaceWith(newReplacement: Array[Byte]): BitsCharsetEncoder
  def flush(out: ByteBuffer): CoderResult
  def reset(): BitsCharsetEncoder

  /**
   * Used to determine if the data input stream must be aligned (if not already)
   * for this encoding. Based on whether the coder has been reset. If the
   * coder has not been reset, it is assumed we are in the middle of encoding
   * many characters, and so no mandatory alignment is needed. However, if the
   * coder was reset, then it is assumed that we may be unaligned at the start
   * of encoding characters, and so we must check if we are mandatory aligned.
   */
  def isMandatoryAlignmentNeeded(): Boolean
  def malformedInputAction(): CodingErrorAction
  def onMalformedInput(action: CodingErrorAction): BitsCharsetEncoder
  def unmappableCharacterAction(): CodingErrorAction
  def onUnmappableCharacter(action: CodingErrorAction): BitsCharsetEncoder
  def encode(in: CharBuffer, out: ByteBuffer, endOfInput: Boolean): CoderResult
  protected def encodeLoop(in: CharBuffer, out: ByteBuffer): CoderResult
}

/**
 * Implements BitsCharsetEncoder by encapsulating a standard JavaCharsetEncoder
 */
final class BitsCharsetWrappingJavaCharsetEncoder(
  override val bitsCharset: BitsCharsetJava,
  enc: JavaCharsetEncoder
) extends BitsCharsetEncoder {

  def setInitialBitOffset(offset: Int): Unit = Assert.usageError("Not to be called.")
  def averageBytesPerChar() = enc.averageBytesPerChar()
  def averageBitsPerChar() = averageBytesPerChar() / 8.0f
  def maxBytesPerChar() = enc.maxBytesPerChar()
  def maxBitsPerChar() = maxBytesPerChar() / 8.0f
  def replacement() = enc.replacement()
  def replaceWith(newReplacement: Array[Byte]) = {
    Assert.usage(isReset)
    enc.replaceWith(newReplacement);
    this
  }
  def flush(out: ByteBuffer) = {
    Assert.usage(!isReset)
    enc.flush(out)
  }
  def reset() = {
    enc.reset();
    isReset = true
    this
  }
  def isMandatoryAlignmentNeeded = isReset
  def malformedInputAction() = enc.malformedInputAction()
  def onMalformedInput(action: CodingErrorAction) = {
    Assert.usage(isReset)
    enc.onMalformedInput(action);
    this
  }
  def unmappableCharacterAction() = enc.malformedInputAction()
  def onUnmappableCharacter(action: CodingErrorAction) = {
    Assert.usage(isReset)
    enc.onUnmappableCharacter(action);
    this
  }
  def encode(in: CharBuffer, out: ByteBuffer, endOfInput: Boolean) = {
    isReset = false
    enc.encode(in, out, endOfInput)
  }
  protected def encodeLoop(in: CharBuffer, out: ByteBuffer) =
    Assert.usageError("Not to be called")
}

/**
 * Implements BitsCharsetDecoder by encapsulating a standard JavaCharsetDecoder
 */
final class BitsCharsetWrappingJavaCharsetDecoder(
  val bitsCharset: BitsCharsetJava,
  dec: JavaCharsetDecoder,
) extends BitsCharsetDecoder {

  // TODO: We only support encodingErrorPolicy="replace"
  dec.onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
  dec.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)

  /**
   * Decode multiple characters into a CharBuffer using a Java CharsetDecoder
   */
  final override def decode(
    dis: InputSourceDataInputStream,
    finfo: FormatInfo,
    chars: CharBuffer,
  ): Int = {
    // Daffodil inserts parsers to ensure we are aligned with the mandatory charset alignment
    // unless alignmentKind="manual". If manual alignment isn't correct, we can't correctly
    // decode any data so throw an exception
    if (!dis.isAligned(bitsCharset.mandatoryBitAlignment)) {
      throw new BitsCharsetDecoderUnalignedCharDecodeException(dis.bitPos1b)
    }

    val startCharPos = chars.position()

    // used to figure out the final bitPos0b once we have finished decoding
    val startBitPos0b = dis.bitPos0b
    var numBytesDecoded: Long = 0

    // When Java decodes a ByteBuffer, it can sometimes leave unread bytes at the end. For
    // example, this happens when decoding UTF8 characters and the ByteBuffer ends with partial
    // bytes of a multi-byte character. This keeps track of the previous ByteBuffer so that we
    // can include those remaining bytes on the next decode loop.
    var prevBB: ByteBuffer = null

    var keepDecoding = true
    while (keepDecoding) {
      // Ask for a ByteBuffer of new bytes and whethere or not there could be more data if we
      // called getByteBuffer again
      val (curBB, endOfInput) = dis.getByteBuffer()
      val curBBLength = curBB.remaining()
 
      val decodeBB =
        if (prevBB != null && prevBB.remaining() > 0) {
          // Java left some remaining undecoded bytes on the previous ByteBuffer (e.g.
          // multi-byte UTF-8 character spanning two ByteBuffers). Java expects us to grow the
          // ByteBuffer, add more bytes, and call decode again. But the purpose of
          // dis.getByteBuffer is to wrap underlying byte Arrays managed by the InputSource, so
          // we can't just simply grow them. Instead, we allocate a new ByteBuffer big enough to
          // store the prev and cur ByteBuffers and concatenate them together and decode that.
          // There should only be a couple of bytes remaining in the prevBB so there shouldn't
          // be any issues with overflow. And this should also be pretty rare as long as the
          // ByteBuffers are a decent size, so likely not a performance concern.
          val newBB = ByteBuffer.allocate(prevBB.remaining() + curBB.remaining())
          newBB.put(prevBB)
          newBB.put(curBB)
          newBB.flip()
          newBB
        } else {
          curBB
        }
      val decodeBBStartPos = decodeBB.position()

      val res = dec.decode(decodeBB, chars, endOfInput)
      if (res == CoderResult.OVERFLOW) {
        // ran out of space in the CharBuffer, we are done
        keepDecoding = false
      } else if (res == CoderResult.UNDERFLOW) {
        if (endOfInput) {
          // ran out of data in the ByteBuffer, but there are no more bytes available, we are done
          keepDecoding = false
        } else {
          // ran out of data in the ByteBuffer, but it's possible there is more data. Loop
          // around, request more bytes, and continue decoding
          keepDecoding = true

          // there might be a small number of remaining bytes in decodeBB that weren't decoded
          // because more data is needed to know how to deal with those bytes (e.g. a multi-bye
          // UTF-8 character spanning ByteBuffers). Save the decode ByteBuffer so any remaining
          // bytes can be included in the next decode
          prevBB = decodeBB

          // We are going to loop around and request a new ByteBuffer that needs to start where
          // the last call to dis.getByteBuffer ended. This new location is just the bitPos0b
          // when dis.getByteBuffer was called (i.e. the current bitPos0b since we haven't changed
          // it) plus the number of bytes dis.getByteBuffer returned (i.e. curBBLength)
          dis.setBitPos0b(dis.bitPos0b + (curBBLength.toLong * 8))
        }
      } else {
        Assert.invariantFailed("Should never happen because we do not support encodingErrorPolicy='error'")
      }

      // keep track of the total number of bytes actually decoded
      numBytesDecoded += decodeBB.position() - decodeBBStartPos
    }

    // now that we are done decoding, update the bitPos0b to be where we actually finished
    // decoding based on the number of decoded bytes.
    dis.setBitPos0b(startBitPos0b + (numBytesDecoded * 8))

    val numCharsDecoded = chars.position() - startCharPos
    numCharsDecoded
  }

  override def reset() = dec.reset()
}


/**
 * Provides BitsCharset objects corresponding to the usual java charsets found
 * in StandardCharsets.
 */
object StandardBitsCharsets {
  lazy val UTF_8 = BitsCharsetUTF8
  lazy val UTF_16BE = BitsCharsetUTF16BE
  lazy val UTF_16LE = BitsCharsetUTF16LE
  lazy val ISO_8859_1 = BitsCharsetISO88591
}
