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

object BitsCharsetIBM037 extends BitsCharsetJava {
  override val name = "IBM037"
}

object BitsCharsetISO88591 extends BitsCharsetJava {
  override val name = "ISO-8859-1"
}

object BitsCharsetUSASCII extends BitsCharsetJava {
  override val name = "US-ASCII"
}

object BitsCharsetUTF16BE extends BitsCharsetJava {
  override val name = "UTF-16BE"
}

object BitsCharsetUTF16LE extends BitsCharsetJava {
  override val name = "UTF-16LE"
}

object BitsCharsetUTF32BE extends BitsCharsetJava {
  override val name = "UTF-32BE"
}

object BitsCharsetUTF32LE extends BitsCharsetJava {
  override val name = "UTF-32LE"
}

object BitsCharsetUTF8 extends BitsCharsetJava {
  override val name = "UTF-8"
}


final class BitsCharsetIBM037Definition
  extends BitsCharsetDefinition(BitsCharsetIBM037)

final class BitsCharsetEBCDIC_CP_USDefinition
  extends BitsCharsetDefinition(BitsCharsetIBM037, Some("EBCDIC-CP-US"))

final class BitsCharsetISO88591Definition
  extends BitsCharsetDefinition(BitsCharsetISO88591)

final class BitsCharsetUSASCIIDefinition
  extends BitsCharsetDefinition(BitsCharsetUSASCII)

final class BitsCharsetASCIIDefinition
  extends BitsCharsetDefinition(BitsCharsetUSASCII, Some("ASCII"))

final class BitsCharsetUTF16BEDefinition
  extends BitsCharsetDefinition(BitsCharsetUTF16BE)

final class BitsCharsetUTF16Definition
  extends BitsCharsetDefinition(BitsCharsetUTF16BE, Some("UTF-16"))

final class BitsCharsetUTF16LEDefinition
  extends BitsCharsetDefinition(BitsCharsetUTF16LE)

final class BitsCharsetUTF32BEDefinition
  extends BitsCharsetDefinition(BitsCharsetUTF32BE)

final class BitsCharsetUTF32Definition
  extends BitsCharsetDefinition(BitsCharsetUTF32BE, Some("UTF-32"))

final class BitsCharsetUTF32LEDefinition
  extends BitsCharsetDefinition(BitsCharsetUTF32LE)

final class BitsCharsetUTF8Definition
  extends BitsCharsetDefinition(BitsCharsetUTF8)
