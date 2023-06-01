<!--
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->

## Daffodil Release Candidate Container

To improve reproducibility and to minimize the effects and variability of a
users environment, the Daffodil release container should be used to create
release candidates. This is triggered by dispatching a GitHub action.

To locally test the release candidate container you can run the following
commands to build and run the container:

    podman build -t daffodil-release-candidate containers/release-candidate/
    
    podman run -it --privileged --group-add keep-groups --rm \
      --volume ./:/root/build/ \
      --workdir /root/build/ \
      --hostname daffodil.build \
      daffodil-release-candidate \
        --project=daffodil \
        --release-label=rc1 \
        --signing-key="$(gpg --armor --export-secret-key example@example.com)"

This should be run from the root of the Daffodil repository. The email address
should be change to reflect the correct signing key.

Note that this will perform a dry run and will not actually publish any files.
Publishing should only happen when the GitHub action is run.
