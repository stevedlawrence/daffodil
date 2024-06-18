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


package org.apache.daffodil.charsets

import org.apache.daffodil.io.processors.charset.BitsCharsetDefinition
import org.apache.daffodil.io.processors.charset.BitsCharsetJava

object BitsCharsetTest_ISO_8859_13 extends BitsCharsetJava {
  override val name = "ISO-8859-13"
}

final class BitsCharsetTest_ISO_8859_13_Definition
  extends BitsCharsetDefinition(BitsCharsetTest_ISO_8859_13)
