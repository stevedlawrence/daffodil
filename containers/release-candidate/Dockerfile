#!/bin/bash
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

FROM registry.fedoraproject.org/fedora-minimal:34

WORKDIR /root

COPY setup-container.sh /root/
RUN /root/setup-container.sh

# Needed to get WiX to run in wine on Linux. See wix_wine.sh for more details
# on why we need to do this and how it works
RUN mv /root/wix311/{candle.exe,real-candle.exe}
RUN mv /root/wix311/{light.exe,real-light.exe}
COPY wix_wine.sh /root/wix311/candle.exe
COPY wix_wine.sh /root/wix311/light.exe

COPY daffodil-release-candidate /root/

ENTRYPOINT ["/root/daffodil-release-candidate"]
