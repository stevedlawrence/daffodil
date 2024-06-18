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

import java.io.InputStream
import java.math.{ BigInteger => JBigInt }
import java.nio.ByteBuffer
import java.nio.CharBuffer

import org.apache.daffodil.lib.exceptions.Assert
import org.apache.daffodil.lib.schema.annotation.props.gen.BitOrder
import org.apache.daffodil.lib.schema.annotation.props.gen.ByteOrder
import org.apache.daffodil.lib.util.Bits
import org.apache.daffodil.lib.util.MStackOf
import org.apache.daffodil.lib.util.Maybe
import org.apache.daffodil.lib.util.MaybeULong
import org.apache.daffodil.lib.util.Pool

import passera.unsigned.ULong

/**
 * Factory for creating this type of DataInputStream
 *
 * Provides only core input sources to avoid making any assumptions about the
 * incoming data (i.e. should a File be mapped to a ByteBuffer or be streamed
 * as an InputStream). The user knows better than us, so have them make the
 * decision.
 */
object InputSourceDataInputStream {

  def apply(byteArray: Array[Byte]): InputSourceDataInputStream = {
    apply(ByteBuffer.wrap(byteArray))
  }

  def apply(byteBuffer: ByteBuffer): InputSourceDataInputStream = {
    new InputSourceDataInputStream(new ByteBufferInputSource(byteBuffer))
  }

  def apply(in: InputStream): InputSourceDataInputStream = {
    new InputSourceDataInputStream(new BucketingInputSource(in))
  }
}

/**
 * The state that must be saved and restored by mark/reset calls
 */
final class MarkState() extends DataStreamCommonState with DataInputStream.Mark {

  override def equals(other: Any) = other match {
    case ar: AnyRef => this eq ar // only if the same object
    case _ => false
  }

  // any members added here must be added to assignFrom below.
  var bitPos0b: Long = 0
  var bitLimit0b: MaybeULong = MaybeULong.Nope
  val charIteratorState = new InputSourceDataInputStreamCharIteratorState

  @inline
  def bytePos0b: Long = bitPos0b >> 3

  def assignFrom(other: MarkState): Unit = {
    super.assignFrom(other)
    this.bitPos0b = other.bitPos0b
    this.bitLimit0b = other.bitLimit0b
    this.charIteratorState.assignFrom(other.charIteratorState)
  }

}

private[io] class MarkPool() extends Pool[MarkState] {
  override def allocate = new MarkState()
}

/**
 * Realization of the DataInputStream API
 *
 * Underlying representation is an InputSource containing all input data.
 */
final class InputSourceDataInputStream(val inputSource: InputSource)
  extends DataInputStreamImplMixin
  with java.io.Closeable {

  import DataInputStream._

  override def toString = {
    val bp0b = bitPos0b
    val bl0b = bitLimit0b
    val bl0b1 = if (bl0b.isDefined) bl0b.get.toString else "none"
    val str = "DataInputStream(bitPos=" + bp0b +
      ", bitLimit=" + bl0b1 + ")"
    str
  }

  override final val cst = new MarkState
  val markStack = new MStackOf[MarkState]
  val markPool = new MarkPool()

  /**
   * Creators of this class are responsible to call close when done.
   */
  override def close(): Unit = inputSource.close()

  @inline
  override final def bitPos0b: Long = cst.bitPos0b

  @inline
  override final def bitLimit0b: MaybeULong = cst.bitLimit0b

  /**
   * Tells us if the underlying input source has detected end-of-data
   * (the read(...) call returned -1.
   *
   * But this does NOT tell us we are positioned at the end, only whether
   * in the course of reading, we encountered the end of data. If we
   * backtracked we could have seen the end of data, but backed up in
   * the data to an earlier position.
   */
  def hasReachedEndOfData: Boolean = inputSource.hasReachedEndOfData

  /**
   * Return the number of currently available bytes.
   *
   * This should not be used to determine the length of the data, as more bytes
   * may become available in the future. This should really only be used for
   * debug or diagnostic purposes.
   */
  def knownBytesAvailable: Long = inputSource.knownBytesAvailable

  def setBitPos0b(newBitPos0b: Long): Unit = {
    // threadCheck()
    Assert.invariant(newBitPos0b >= 0)
    Assert.invariant(bitLimit0b.isEmpty || newBitPos0b <= bitLimit0b.get)

    inputSource.position(newBitPos0b >> 3)
    cst.bitPos0b = newBitPos0b
  }

  override def setBitLimit0b(newBitLimit0b: MaybeULong): Boolean = {
    // threadCheck()
    Assert.invariant(newBitLimit0b.isEmpty || newBitLimit0b.get >= 0)
    if (bitLimit0b.isEmpty || newBitLimit0b.isEmpty || newBitLimit0b.get <= bitLimit0b.get) {
      cst.bitLimit0b = newBitLimit0b
      true
    } else {
      false
    }
  }

  def resetBitLimit0b(savedBitLimit0b: MaybeULong): Unit = {
    cst.bitLimit0b = savedBitLimit0b
  }

  def getByteArray(bitLengthFrom1: Int, finfo: FormatInfo): Array[Byte] = {
    // threadCheck()
    if (!isDefinedForLength(bitLengthFrom1))
      throw DataInputStream.NotEnoughDataException(bitLengthFrom1)

    val arraySize = (bitLengthFrom1 + 7) / 8
    val array = new Array[Byte](arraySize)
    fillByteArray(array, bitLengthFrom1, finfo)

    setBitPos0b(bitPos0b + bitLengthFrom1)

    array
  }

  def getByteArray(bitLengthFrom1: Int, finfo: FormatInfo, array: Array[Byte]): Unit = {
    // threadCheck()
    if (!isDefinedForLength(bitLengthFrom1))
      throw DataInputStream.NotEnoughDataException(bitLengthFrom1)

    val bytesNeeded = (bitLengthFrom1 + 7) / 8
    Assert.usage(array.size >= bytesNeeded)

    fillByteArray(array, bitLengthFrom1, finfo)

    setBitPos0b(bitPos0b + bitLengthFrom1)
  }

  /**
   * Accepts a preallocated array and a bitLength. Reads the specified number
   * of bits and stores them in the array consistent with bitOrder and
   * byteOrder. Note that function is not used just for hexBinary data, but is
   * also used for calculating binary integer data as well. For this reason,
   * this function must support littleEndian byte order. To do so, we first
   * read the data as if were hexBinary taking into account only bitOrder. If
   * there are fragment bytes the last bits are placed appropriately based on
   * bitOrder. Once complete, if the byteOrder is little endian, then the order
   * of the bytes in the array is swapped. Note that when the primitive type is
   * hexBinary, byteOrder is always big-endian, and so this byte reversal at
   * the end will not happen.
   *
   * For example, assume we have 3 bytes of data like so:
   *
   *   Byte0    Byte1    Byte2
   *   ABCDEFGH IJKLMNOP QRSTUVWX
   *
   * And bitLengthFrom1 is 19 (i.e. we want to read 19 bits). The resulting
   * byte array would look like the following for the different combinations of
   * byteOrder and bitOrder:
   *
   *   BE MSBF: ABCDEFGH IJKLMNOP QRSxxxxx
   *   BE LSBF: ABCDEFGH IJKLMNOP xxxxxVWX
   *
   * Little endian is the exact same, but with the bytes reversed, like so:
   *
   *   LE MSBF: QRSxxxxx IJKLMNOP ABCDEFGH
   *   LE LSBF: xxxxxVWX IJKLMNOP ABCDEFGH
   *
   * In both cases, the x's are always zeros. It is the responsiblity of the
   * caller to be aware of the length of the data and to know which bits are
   * data vs padding based on bitOrder/byteOrder
   */
  private def fillByteArray(
    array: Array[Byte],
    bitLengthFrom1: Int,
    finfo: FormatInfo
  ): Unit = {
    val isUnaligned = !isAligned(8)
    val fragmentBits = bitLengthFrom1 % 8
    val bytesToFill = (bitLengthFrom1 + 7) / 8

    // We may only need N bytes to fill in the array, but if we are unaligned
    // then that means we could potentially need to get an extra byte. For
    // example, say bitLength is 8, so we only need 1 byte total and the array
    // size is 1. If we are unaligned and our bit offset is 1, we need to read
    // two bytes total, extracting 7 bits from the first byte and 1 bit from
    // the second byte. To be efficient, let's fill the array with data we know
    // we'll need. Later, if we determine that we are unaligned and an extra
    // byte is needed, we will read one more byte into an overflow variable and
    // eventually combine that into the array.
    inputSource.get(array, 0, bytesToFill)

    if (isUnaligned) {
      // If we are not aligned, then we need to shift all the bits we just got
      // to the left, based on the bitOffset and the bitOrder. Do that shift
      // here.

      val isMSBF = finfo.bitOrder == BitOrder.MostSignificantBitFirst
      val bitOffset0b = (bitPos0b % 8).toInt

      // Determine if we need an overflow byte and read it if so
      val bytesToRead = (bitLengthFrom1 + bitOffset0b + 7) / 8
      val arrayOverflow =
        if (bytesToRead > bytesToFill) Bits.asUnsignedByte(inputSource.get()) else 0

      // Determine the masks and shifts needed to create a new byte based on
      // the bitOrder
      val curBitMask =
        if (isMSBF)
          Bits.maskR(8 - bitOffset0b)
        else
          Bits.maskL(8 - bitOffset0b)

      val nxtBitMask = ~curBitMask & 0xff
      val curShift = bitOffset0b
      val nxtShift = 8 - bitOffset0b

      def calcNewByte(curByte: Long, nxtByte: Long): Byte = {
        // Takes two bytes and masks + shifts appropriately based on bitOrder
        // to create a new byte.
        val curBits = curByte & curBitMask
        val nxtBits = nxtByte & nxtBitMask
        val newByte =
          if (isMSBF)
            (curBits << curShift) | (nxtBits >> nxtShift)
          else
            (curBits >> curShift) | (nxtBits << nxtShift)
        Bits.asSignedByte(newByte)
      }

      // Shift bytes into position according to the bit order. We need to stop
      // before the last index since each iteration in the array accesses the
      // next array index. So the last index needs data from the overflow byte
      // since create a full 8-bit byte here. If the last byte was a
      // fragmentbyte (due to bitLength not being a multiple of 8) then that
      // means we took too much data for the last byte--we'll mask that out
      // later.
      var index = 0
      var curByte = Bits.asUnsignedByte(array(index))
      val stopIndex = bytesToFill - 1
      while (index < stopIndex) {
        val nxtByte = Bits.asUnsignedByte(array(index + 1))
        array(index) = calcNewByte(curByte, nxtByte)
        curByte = nxtByte
        index += 1
      }
      array(index) = calcNewByte(curByte, arrayOverflow)
    }

    // We have now filled the byte array and, if necessary, shifted all the
    // bytes to account for alignment according to the bitOrder. However, if
    // there was a fragment byte, the last index may contain too much data
    // since we always just took 8 bits at a time--in this case, we need to
    // mask out the unwanted bits.
    if (fragmentBits > 0) {
      val mask =
        if (finfo.bitOrder == BitOrder.MostSignificantBitFirst)
          Bits.maskL(fragmentBits)
        else
          Bits.maskR(fragmentBits)

      val lastIndex = bytesToFill - 1
      val lastByte = Bits.asUnsignedByte(array(lastIndex))
      array(lastIndex) = Bits.asSignedByte(lastByte & mask)
    }

    // We've read the data and potentially shifted the data as if it were
    // bigEndian and dealt with fragments bytes. But that was all done as if
    // this were bigEndian. If it was little endian, we now need to reverse the
    // order of the the bytes. We only ever need to reverse if there is more
    // than one byte, so don't even bother checking byteOrder and reversing if
    // that isn't the case. Single- and sub-byte lengths are fairly common so
    // optimize for those.
    if (bitLengthFrom1 > 8 && finfo.byteOrder == ByteOrder.LittleEndian) {
      Bits.reverseBytes(array, bytesToFill)
    }
  }

  def getBinaryDouble(finfo: FormatInfo): Double = {
    val l = getSignedLong(64, finfo)
    val d = java.lang.Double.longBitsToDouble(l)
    d
  }

  def getBinaryFloat(finfo: FormatInfo): Float = {
    val i = getSignedLong(32, finfo).toInt
    val f = java.lang.Float.intBitsToFloat(i)
    f
  }

  def getSignedLong(bitLengthFrom1To64: Int, finfo: FormatInfo): Long = {
    // threadCheck()
    Assert.usage(bitLengthFrom1To64 >= 1)
    Assert.usage(bitLengthFrom1To64 <= 64)

    val res = getUnsignedLong(bitLengthFrom1To64, finfo)
    Bits.signExtend(res.longValue, bitLengthFrom1To64)
  }

  // used by the below function to get up to 8 bytes of data
  private val longArray = new Array[Byte](8)

  /**
   * This method returns an unsigned long created from bitLengthFrom1To64 bits
   * of data.
   *
   * To accomplish this, it uses fillByteArray to take care of the logic to
   * deal with byteOrder and bitOrder. However, that function returns a
   * byteArray intended for output as hexBinary data. For lengths that are a
   * multiple of 8 (the most common case), this isn't a problem and we can
   * quickly convert that data directly to a long. However, if there are
   * fragment bytes, some adjustments are needed to convert to a long. This is
   * because, depending on the bit/byteOrder, there are empty gaps of data that
   * need to be removed. To visualize the problem, assume we have the following
   * three bytes of data:
   *
   *   Byte0    Byte1    Byte2
   *   ABCDEFGH IJKLMNOP QRSTUVWX
   *
   * And bitLengthFrom1To64 is 19 (i.e. we want to read 19 bits). The resulting
   * byte array from fillByteArray would look like the following for the
   * different combinations of byteOrder and bitOrder:
   *
   *   BE MSBF: ABCDEFGH IJKLMNOP QRSxxxxx
   *   BE LSBF: ABCDEFGH IJKLMNOP xxxxxVWX
   *   LE MSBF: QRSxxxxx IJKLMNOP ABCDEFGH
   *   LE LSBF: xxxxxVWX IJKLMNOP ABCDEFGH
   *
   * The gaps indicated by x's must be removed to calculate the long. To remove
   * those gaps the following logic is used;
   *
   * BE MSBF: Use the normal logic to create a long, then shift the resulting
   * long to the right to remove the right gap.
   *
   * BE LSBF: First shift the last byte to the left to remove the gap (adding a
   * right gap), then use the normal logic to create a long, then shift the
   * resulting long to the right to remove the right the gap.
   *
   * LE MSBF: Shift the first byte to the right to remove the right gap, then
   * use the normal logic to create a long.
   *
   * LE LSBF: There are no gaps, so just use the normal logic to create a long.
   *
   * Also see adjustBigIntArrayWithFragmentByte, which uses similar logic for
   * handling BigInt's that have fragment bytes.
   */
  def getUnsignedLong(bitLengthFrom1To64: Int, finfo: FormatInfo): ULong = {
    Assert.usage(bitLengthFrom1To64 >= 1)
    Assert.usage(bitLengthFrom1To64 <= 64)

    if (!isDefinedForLength(bitLengthFrom1To64))
      throw DataInputStream.NotEnoughDataException(bitLengthFrom1To64)

    val numBytes = (bitLengthFrom1To64 + 7) / 8

    def buildLongFromArray(): Long = {
      // Function for converting the array to a long, ignoring anything about a
      // fragment byte. If there is a fragment bytes, it is assumed to be
      // handled outside this function by modifying the array or the resulting
      // long.
      var long: Long = Bits.asUnsignedByte(longArray(0))
      var i = 1
      while (i < numBytes) {
        long = (long << 8) | Bits.asUnsignedByte(longArray(i))
        i += 1
      }
      long
    }

    fillByteArray(longArray, bitLengthFrom1To64, finfo)

    val fragmentLength = bitLengthFrom1To64 % 8

    val res =
      if (fragmentLength == 0) {
        // no fragments, thus no gaps, just convert to a long
        val long = buildLongFromArray()
        long
      } else {
        if (finfo.byteOrder == ByteOrder.LittleEndian) {
          if (finfo.bitOrder == BitOrder.MostSignificantBitFirst) {
            //  LE MSBF: 012xxxxx 01234567 01234567 -- shift first byte right
            val firstByte = Bits.asUnsignedByte(longArray(0))
            val shifted = firstByte >>> (8 - fragmentLength)
            longArray(0) = Bits.asSignedByte(shifted)
          } else {
            //  LE LSBF: xxxxx210 76543210 76543210 -- no inner bit gaps, nothing to do
          }
          val long = buildLongFromArray()
          long
        } else { // ByteOrder.BigEndian
          if (finfo.bitOrder == BitOrder.LeastSignificantBitFirst) {
            //  BE LSBF: 76543210 76543210 xxxxx210 -- shift last byte left
            val lastIndex = numBytes - 1
            val lastByte = longArray(lastIndex)
            val shifted = lastByte << (8 - fragmentLength)
            longArray(lastIndex) = Bits.asSignedByte(shifted)
          } else {
            //  BE MSBF: 01234567 01234567 012xxxxx -- no inner bit gaps, nothing to do
          }
          val unShiftedLong = buildLongFromArray()
          // Shift the resulting long right to remove right gap
          val long = unShiftedLong >>> (8 - fragmentLength)
          long
        }
      }

    setBitPos0b(bitPos0b + bitLengthFrom1To64)

    ULong(res)
  }

  /**
   * This function modifies an array representing a BigInt that has a fragment
   * byte, using similar logic as getUnsignedLog. For a description of whcy we
   * are adjusting this array is this manner, see that function. This function
   * modifies the byte array in place.
   */
  private def adjustBigIntArrayWithFragmentByte(
    array: Array[Byte],
    fragmentLength: Int,
    finfo: FormatInfo
  ): Unit = {

    if (finfo.byteOrder == ByteOrder.LittleEndian) {
      if (finfo.bitOrder == BitOrder.MostSignificantBitFirst) {
        val firstByte = Bits.asUnsignedByte(array(0))
        val shifted = firstByte >>> (8 - fragmentLength)
        array(0) = Bits.asSignedByte(shifted)
      } else {
        // noop
      }
    } else { // ByteOrder.BigEndian
      if (finfo.bitOrder == BitOrder.LeastSignificantBitFirst) {
        val lastIndex = array.length - 1
        val lastByte = array(lastIndex)
        val shifted = lastByte << (8 - fragmentLength)
        array(lastIndex) = Bits.asSignedByte(shifted)
      } else {
        // noop
      }

      // Shift the resulting long right to remove right gap, do not sign
      // extend, since this may be used unsigned BitInts. The caller should
      // sign extend it if necessary.
      val bb = ByteBuffer.wrap(array)
      Bits.shiftRight(bb, 8 - fragmentLength)
    }
  }

  def getSignedBigInt(bitLengthFrom1: Int, finfo: FormatInfo): JBigInt = {
    // threadCheck()
    Assert.usage(bitLengthFrom1 >= 1)

    if (bitLengthFrom1 <= 64) {
      JBigInt.valueOf(getSignedLong(bitLengthFrom1, finfo))
    } else {
      val bytes = getByteArray(bitLengthFrom1, finfo)
      val fragmentLength = bitLengthFrom1 % 8
      if (fragmentLength > 0) {
        adjustBigIntArrayWithFragmentByte(bytes, fragmentLength, finfo)

        // Since there is a fragment byte, we need to sign extend the most
        // significant byte so that the BigInt constructor knows the sign of
        // the BigInt. This uses >> to extend the sign.
        val shift = 8 - fragmentLength
        bytes(0) = ((bytes(0) << shift).toByte >> shift).toByte
      }
      new JBigInt(bytes)
    }
  }

  def getUnsignedBigInt(bitLengthFrom1: Int, finfo: FormatInfo): JBigInt = {
    Assert.usage(bitLengthFrom1 >= 1)
    val bytes = getByteArray(bitLengthFrom1, finfo)
    val fragmentLength = bitLengthFrom1 % 8
    if (fragmentLength > 0) {
      adjustBigIntArrayWithFragmentByte(bytes, fragmentLength, finfo)
    }

    new JBigInt(1, bytes)
  }

  /**
   * Determines whether the input stream has this much more data.
   *
   * Does not advance the position.
   *
   * This operation will block until either n bytes are read or end-of-data
   * is hit.
   */
  final def isDefinedForLength(nBits: Long): Boolean = {
    val newBitPos0b = bitPos0b + nBits
    if (bitLimit0b.isDefined && newBitPos0b > bitLimit0b.get) false
    else {
      val newEndingBytePos0b = Bits.roundUpBitToBytePosition(newBitPos0b)
      val moreBytesNeeded = newEndingBytePos0b - inputSource.position()
      inputSource.areBytesAvailable(moreBytesNeeded)
    }
  }

  final def hasData() = isDefinedForLength(1)

  def skip(nBits: Long, finfo: FormatInfo): Boolean = {
    // threadCheck()
    if (!this.isDefinedForLength(nBits)) return false
    setBitPos0b(bitPos0b + nBits)
    true
  }

  def mark(requestorID: String): DataInputStream.Mark = {
    val m = markPool.getFromPool(requestorID)
    m.assignFrom(cst)
    markStack.push(m)
    inputSource.lockPosition(m.bytePos0b)
    m
  }

  private def releaseUntilMark(mark: DataInputStream.Mark) = {
    // threadCheck()
    Assert.usage(!markStack.isEmpty)
    Assert.usage(mark != null)
    var current = markStack.pop
    while (!(markStack.isEmpty) && (current ne mark)) {
      inputSource.releasePosition(current.bytePos0b)
      markPool.returnToPool(current)
      current = markStack.pop
    }
    Assert.invariant(current eq mark) // holds unless markStack was empty
    current
  }

  def reset(mark: DataInputStream.Mark): Unit = {
    val current = releaseUntilMark(mark)
    Assert.invariant(current eq mark)
    cst.assignFrom(current)
    setBitPos0b(cst.bitPos0b)
    inputSource.releasePosition(current.bytePos0b)
    markPool.returnToPool(current)
  }

  def discard(mark: DataInputStream.Mark): Unit = {
    val current = releaseUntilMark(mark)
    Assert.invariant(current eq mark)
    inputSource.releasePosition(current.bytePos0b)
    markPool.returnToPool(current)
  }

  override def markPos: MarkPos = bitPos0b

  override def resetPos(m: MarkPos): Unit = {
    setBitPos0b(m)
  }

  def validateFinalStreamState(): Unit = {
    // threadCheck()
    markPool.finalCheck
  }

  /**
   * Returns some characters (up to nChars) if they are available.
   *
   * @param nChars From 1 up to this many characters are returned as the string, or Nope for 0.
   * @param finfo
   * @return
   */
  final def getSomeString(nChars: Long, finfo: FormatInfo): Maybe[String] = {
    val startingBitPos = bitPos0b
    withLocalCharBuffer { lcb =>
      val cb = lcb.getBuf(nChars)
      finfo.decoder.reset()
      val numDecoded = finfo.decoder.decode(this, finfo, cb)
      if (numDecoded > 0) {
        Maybe(cb.flip.toString)
      } else {
        setBitPos0b(startingBitPos)
        Maybe.Nope
      }
    }
  }

  /**
   * Return a read-only ByteBuffer that wraps the underlying data storage of bytes in the
   * InputSource, avoiding copying data. The position of the ByteBuffer is set such that get()
   * returns bytes from the InputSource starting at the current bitPosition rounded down to the
   * nearest byte. The limit of the ByteBuffer is set to as much data can be provided without
   * copying or reading passed the bitLimit rounded down to the nearest byte.
   * 
   * If there is no more data possibly available at the current position up to the limit, then
   * this returns a ByteBuffer with no remaining() data. Otherwise returns a ByteBuffer with at
   * least one byte remaining().
   *
   * Note that users of this must manually take into account alignment, fragment bits,
   * byteOrder, bitOrder, etc. For this reason, it is recommended this only be used in cases
   * where these properties can be ignored, such as when decoding mandatory byte-aligned
   * byte-sized characters.
   *
   * This also returns a boolean that signifies if there could be more data after this
   * ByteBuffer, taking into account the current bitLimit. If we have reach the end of data or
   * the bitLimit, this Boolean is set to true signify the end of available data.
   *
   * To avoid allocations, this can always return the same ByteBuffer but with position() and
   * limit() modified to match the current state. For this reason, it is important that callers
   * do not store the returned ByteBuffer or call other functions that might also call
   * getByteBuffer while the returned ByteBuffer is still in use. In otherwords, there should
   * only be one user of getByteBuffer for this input source at a time.
   */
  final def getByteBuffer(): (ByteBuffer, Boolean) = {
    val (bb, endOfInputSource) = inputSource.getByteBuffer()

    var endOfData = endOfInputSource

    // the byteBuffer is limited by avilable data, but we may need to limit it more by bitLimit
    if (bitLimit0b.isDefined) {
      // get the number of bytes this DataInputStream could has available, rounding position up
      // and limit down to the nearest byte position
      val disPos = bitPos0b / 8
      val disLim = bitLimit0b.get / 8
      val disAvailableBytes = disLim - disPos

      // ByteBuffer's are int based, so the maximum limit it could have is INT_MAX. We only need
      // to think about further restricting the ByteBuffer if the dis has less than INT_MAX
      // amount of bytes.
      if (disAvailableBytes < Int.MaxValue) {
        // get the number of bytes the bb could allow reading
        val bbPos = bb.position()
        val bbLim = bb.limit()
        val bbAvailableBytes = bbLim - bbPos
   
        if (disAvailableBytes.toInt < bbAvailableBytes) {
          // the bitLimit has less bytes available than the actual ByteBuffer provided. Reduce
          // the limit of the ByteBuffer to match, and signify tha there is no more data that we
          // could get.
          bb.limit(bbPos + disAvailableBytes.toInt)
          endOfData = true
        }
      }
    }

    (bb, endOfData)
  }

  lazy val skipCharBuffer = CharBuffer.allocate(32)

  def skipChars(nChars: Long, finfo: FormatInfo): Boolean = {
    // threadCheck()
    val startingBitPos = bitPos0b

    val aligned = align(finfo.encodingMandatoryAlignmentInBits, finfo)
    if (!aligned) {
      false
    } else {
      var remainingCharsToSkip = nChars
      var keepGoing = true

      // starting a new decode, must reset the decoder state. We wont' rest state in the loop so
      // the decoder can keep decoding where it left off
      finfo.decoder.reset()

      while (keepGoing && remainingCharsToSkip > 0) {
        val charsToSkip = Math.min(remainingCharsToSkip, skipCharBuffer.capacity)
        skipCharBuffer.position(0)
        skipCharBuffer.limit(charsToSkip.toInt)

        val numDecoded = finfo.decoder.decode(this, finfo, skipCharBuffer)
        remainingCharsToSkip -= numDecoded

        if (numDecoded == 0) {
          keepGoing = false
        }
      }

      val skippedAllNChars = remainingCharsToSkip == 0

      if (!skippedAllNChars) {
        // failed to skip all the necessary chars, reset the bit position back to
        // where we started
        setBitPos0b(startingBitPos)
      }

      skippedAllNChars
    }
  }

  def lookingAt(matcher: java.util.regex.Matcher, finfo: FormatInfo): Boolean = {
    // Get the current bit position. The following code changes the bit position while we align
    // and decode characters looking for a match. If we don't find a match we need to reset back
    // to this position
    val startingBitPos = bitPos0b
    val aligned = align(finfo.encodingMandatoryAlignmentInBits, finfo)
    if (!aligned) {
      false
    } else {
      var regexMatchBufferLimit = finfo.tunable.initialRegexMatchLimitInCharacters
      val regexMatchBuffer = finfo.regexMatchBuffer

      // the above align() call may have moved our bit position. If a match is found, then we
      // calculate the new ending bit position based on where we actually started decoding
      // characters
      val startingDecodeBitPos = bitPos0b

      regexMatchBuffer.position(0)
      regexMatchBuffer.limit(0)

      // reset the decoder once before we start. We will not need to do this again while looking
      // for a match since we may need to decode characters multiple times and we want the
      // decoder to be able to continue to decoding where it left off
      finfo.decoder.reset()

      var keepMatching = true
      var isMatch = false

      while (keepMatching) {
        // set the position to the last place data stopped decoding and increase
        // the limit so we can fill more data. The bit position is where ever we last stopped
        // decoding so we do not need to change that or reset the decoder state
        regexMatchBuffer.position(regexMatchBuffer.limit())
        regexMatchBuffer.limit(regexMatchBufferLimit)
        val numDecoded = finfo.decoder.decode(this, finfo, regexMatchBuffer)

        val potentiallyMoreData = regexMatchBuffer.position() == regexMatchBuffer.limit()

        regexMatchBuffer.flip

        if (numDecoded > 0) {
          // we decoded at least one extra characer than we had before, so the
          // match results could have changed. Try again.
          matcher.reset(regexMatchBuffer)
          isMatch = matcher.lookingAt()
          val hitEnd = matcher.hitEnd
          val requireEnd = matcher.requireEnd

          if (potentiallyMoreData && (hitEnd || (isMatch && requireEnd))) {
            // We filled the CharBuffer to its limit, so it's possible there is
            // more data available AND either 1) we hit the end of the char
            // buffer and more data might change the match or 2) we got a match
            // but require the end and more data could negate the match. In
            // either case, let's increase the match limit if possible, try to
            // decode more data, and try the match again if we got more data.
            if (regexMatchBufferLimit == regexMatchBuffer.capacity) {
              // consumed too much data, just give up
              keepMatching = false
            } else {
              regexMatchBufferLimit =
                Math.min(regexMatchBufferLimit * 2, regexMatchBuffer.capacity)
            }
          } else {
            // no more data could affect the match result, so exit the loop and
            // figure out the match result
            keepMatching = false
          }
        } else {
          // We failed to decode any data, that means that there is no more
          // data to match against. Either we've already done a match or more
          // data could have changed the outcome, or there was never any data
          // to match in the first place. In either case, the state of isMatch
          // is correct--we are done.
          keepMatching = false
        }
      }

      if (isMatch && matcher.end != 0) {
        // We matched some number of characters. The value of bitPos0b is currntly where we
        // finished decoding characters, but we probably decoded more characters than we
        // matched. So we need to figure out how many bits the matched characters represent and
        // move bitPos0b back to that
        val numMatchedChars = matcher.end()
        if (finfo.maybeCharWidthInBits.isDefined) {
          // This is a fixed-width encoding--we can just multiply the number of matched
          // characters by the fixed width to get the number of bits from where we started
          // decoding
          val numBitsDecoded = numMatchedChars.toLong * finfo.maybeCharWidthInBits.get
          setBitPos0b(startingDecodeBitPos + numBitsDecoded)
        } else {
          // This is a non fixed-width encoding (e.g. UTF-8), so we don't know how many bits
          // were actually matched. To figur it out, we just reset back to where we started
          // decoding and redecode the bytes with a CharBuffer limited to how many characters
          // were matched, essentially redecoding only the matched characters. When decode()
          // returns, bitPos0b will have been set to where the decode of those characters
          // finished, putting bitPos0b at the right spot.
          //
          // Note that we cannot do the opposite and encode the matched characters into bytes
          // and count the number of bytes. This is because if encodingErrorPolicy is "replace"
          // then a single malformed byte could decode to a unicode replacement character, but
          // that same character would encode to three bytes, resulting in the wrong number of
          // bytes matched. We could potentially account for that, but it adds extra complexity.
          setBitPos0b(startingDecodeBitPos)
          regexMatchBuffer.position(0).limit(numMatchedChars)
          finfo.decoder.reset()
          finfo.decoder.decode(this, finfo, regexMatchBuffer)

          // the Matcher passed in will access the CharBuffer if it ever calls functions like
          // matcher.group(). So we need to flip the CharBuffer for that to work since right now
          // position() == limit()
          regexMatchBuffer.flip()
        }
      } else {
        // failed to match, set the bit position back to where we started
        setBitPos0b(startingBitPos)
      }

      isMatch
    }
  }

  private val charIterator = new InputSourceDataInputStreamCharIterator(this)

  def asIteratorChar: CharIterator = {
    val ci = charIterator
    ci.reset()
    ci
  }

  override def setDebugging(setting: Boolean): Unit = {
    super.setDebugging(setting)
    inputSource.setDebugging(setting)
  }

  def pastData(nBytesRequested: Int): ByteBuffer = {
    // threadCheck()
    if (!areDebugging)
      throw new IllegalStateException("Must be debugging.")
    Assert.usage(nBytesRequested >= 0)
    if (nBytesRequested == 0) return ByteBuffer.allocate(0).asReadOnlyBuffer()

    val savedBytePosition = inputSource.position()
    val bytesToRead = Math.min(savedBytePosition, nBytesRequested).toInt
    val newBytePosition = savedBytePosition - bytesToRead
    inputSource.position(newBytePosition)

    val array = new Array[Byte](bytesToRead)
    inputSource.get(array, 0, bytesToRead)

    inputSource.position(savedBytePosition)
    ByteBuffer.wrap(array).asReadOnlyBuffer()
  }

  def futureData(nBytesRequested: Int): ByteBuffer = {
    // threadCheck()
    if (!areDebugging)
      throw new IllegalStateException("Must be debugging.")
    Assert.usage(nBytesRequested >= 0)

    if (nBytesRequested == 0) return ByteBuffer.allocate(0).asReadOnlyBuffer()

    val savedBytePosition = inputSource.position()
    // need to call areBytesAvailable first to ensure at least length bytes are
    // buffered if they exist
    val available = inputSource.areBytesAvailable(nBytesRequested)
    val bytesToRead = if (available) nBytesRequested else inputSource.knownBytesAvailable.toInt
    val array = new Array[Byte](bytesToRead)
    inputSource.get(array, 0, bytesToRead)

    inputSource.position(savedBytePosition)
    ByteBuffer.wrap(array).asReadOnlyBuffer()
  }

}

class InputSourceDataInputStreamCharIteratorState {

  var decodedChars = CharBuffer.allocate(8)

  var expectedBitPos0b: Long = _
  var curCharStartBitPos0b: Long = _
  var nextFetchStartBitPos0b: Long = _
  var moreDataAvailable: Boolean = _

  clear()

  def clear(): Unit = {
    // Set the Buffers for reading with zero data. The iterator will see there
    // is no cachd decoded chars to read and perform the appropriate actions to
    // fill it in
    decodedChars.position(0).limit(0)
    moreDataAvailable = true
    expectedBitPos0b = 0L
    curCharStartBitPos0b = 0L
    nextFetchStartBitPos0b = 0L
  }

  def assignFrom(other: InputSourceDataInputStreamCharIteratorState): Unit = {
    // We are intentionally not saving/restoring any state here. This is
    // because saving state requires duplicating the long and charbuffers,
    // which is fairly expensive and can use up alot of memory, especially when
    // there are lots of points of uncertainties. Instead, the
    // checkNeedsReset method will determine if something changed the
    // bitPosition (e.g. resetting a mark), and just clear all the internal
    // state, leading to a redecode of data. This does mean that if we reset
    // back to a mark we will repeat work that has already been done. But that
    // should be relatively fast, avoids memory usage, and only takes a penalty
    // when backtracking rather than every time there is a PoU.
  }
}

class InputSourceDataInputStreamCharIterator(dis: InputSourceDataInputStream)
  extends DataInputStream.CharIterator {

  private var finfo: FormatInfo = null

  override def reset(): Unit = {
    dis.cst.charIteratorState.clear()
    if (finfo != null) {
      finfo.decoder.reset()
      finfo = null
    }
  }

  override def setFormatInfo(_finfo: FormatInfo): Unit = {
    finfo = _finfo
  }

  @inline
  private def checkNeedsReset(): Unit = {
    val cis = dis.cst.charIteratorState
    if (cis.expectedBitPos0b != dis.bitPos0b) {
      // Something outside of the char iterator moved the bit position since the last time we
      // decoded data (e.g. backtracking to a previous mark, in which we do not save/restore
      // this iterator state data). In this case, all of our cached decoded data is wrong. So
      // clear out the char iterator state, which will force fetch() to be called and redecode
      // the data. Note that we don't call reset() because that sets finfo to null and we don't
      // want to do that. But we do need to reset the internal decoder() state
      cis.clear()
      if (finfo != null) finfo.decoder.reset()

      // we set startDecodeEndBitPos0b to bitPos0b because this is where the decode will start
      // when fetch is called
      cis.nextFetchStartBitPos0b = dis.bitPos0b
      cis.expectedBitPos0b = dis.bitPos0b
    }
  }

  private def fetch(): Unit = {
    val cis = dis.cst.charIteratorState
    if (cis.moreDataAvailable) {

      // Need to decode more data. First reset the bit position to where we last stopped
      // decoding. Alignment may be necessary if this is the first time decoding with this char
      // iterator
      dis.setBitPos0b(cis.nextFetchStartBitPos0b)

      if (!dis.align(finfo.encodingMandatoryAlignmentInBits, finfo)) {
        // failed to align, which means there is not enough data for a single char. Change the
        // state to signify this, which will cause next()/peek() to fail when actually trying to
        // get a character
        cis.moreDataAvailable = false
      } else {
        // char buffer we are going to decode characters into 
        val decodedChars = cis.decodedChars

        // keep track of where this characters we are about to read will start, used to
        // calculate new bit positions when next() is called. Note that this could be different
        // from expecedBitPos0b due to alignment, or different from nextFetchStartBitPos0b if
        // we already have some decoded chars
        if (decodedChars.remaining() == 0) {
          cis.curCharStartBitPos0b = dis.bitPos0b
        }

        // The previous call to decode() only stopped because we ran out of space in our
        // CharBuffer, so there could be more data to get and we need to make space. There might
        // still be some data in the CharBuffer that hasn't been read even though we need more
        // data (e.g. because of peek2), so compact() the buffers. This copies those remaining
        // characters to the front of the CharBuffer and updates the position and limit so
        // decode() appends characters after those
        decodedChars.compact()
        val numDecoded = finfo.decoder.decode(dis, finfo, decodedChars)

        // save where we finished decoding so we know where the next decode should start if
        // fetch is called again
        cis.nextFetchStartBitPos0b = dis.bitPos0b

        // reset back to befor we aligned/decoded so it appears like nothing changed
        dis.setBitPos0b(cis.expectedBitPos0b)

        // flip CharBuffer for reading
        decodedChars.flip()

        if (numDecoded == 0) {
          // ran out of data. calling hasNext() will return fail once next() consumes all the data
          // in the CharBuffer
          cis.moreDataAvailable = false
        }
      }
    } else {
      // do nothing, no more data to get
    }
  }

  def peek(): Char = {
    checkNeedsReset()

    val cis = dis.cst.charIteratorState
    val decodedChars = cis.decodedChars
    if (decodedChars.remaining >= 1) {
      decodedChars.get(decodedChars.position())
    } else if (cis.moreDataAvailable) {
      fetch()
      peek()
    } else {
      -1.toChar
    }
  }

  def peek2(): Char = {
    checkNeedsReset()

    val cis = dis.cst.charIteratorState
    val decodedChars = cis.decodedChars
    if (decodedChars.remaining >= 2) {
      decodedChars.get(decodedChars.position() + 1)
    } else if (cis.moreDataAvailable) {
      fetch()
      peek2()
    } else {
      -1.toChar
    }
  }

  override def hasNext: Boolean = {
    peek() != -1.toChar
  }

  def next(): Char = {
    Assert.invariant(hasNext)

    val cis = dis.cst.charIteratorState
    val char = cis.decodedChars.get()

    if (finfo.maybeCharWidthInBits.isDefined) {
      val newStartPos = cis.curCharStartBitPos0b + finfo.maybeCharWidthInBits.get
      dis.setBitPos0b(newStartPos)
    } else {
      // not a fixed with so we decode just a single char and let that update the bit position
      //
      // TODO: Need a solution for this. We probably can't use the same technique that we use
      // for regex and just recode the data. Surrogate chars are going to a problem since Java's
      // UTF-8 decode won't decode one half of a surrogate pair. We also have issues with the
      // internal state of the decoder. We don't want to reset() the current decoder because we
      // want future decode() operations to use whatever state that decoder has. And we don't
      // have an easy to create a new decoder. And even if we did, this char we are about to
      // return might have relied on previous bytes (e.g. this is a latter half of a surrogate
      // pair) and it will fail to decode. This is a big problem.
      Assert.impossible("Delimiter scanning with UTF-8 is not currently supported with the new character decode logic.")
    }

    val newBitPos = dis.bitPos0b
    cis.curCharStartBitPos0b = newBitPos
    cis.expectedBitPos0b = newBitPos

    char
  }
}
