// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.vfs;

import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodec;
import com.google.devtools.build.lib.skyframe.serialization.SerializationException;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;

/** Custom serialization for {@link Path}s. */
public class PathCodec implements ObjectCodec<Path> {

  private final FileSystem fileSystem;

  /** Create an instance for serializing and deserializing {@link Path}s on {@code fileSystem}. */
  public PathCodec(FileSystem fileSystem) {
    this.fileSystem = fileSystem;
  }

  @Override
  public Class<Path> getEncodedClass() {
    return Path.class;
  }

  @Override
  public void serialize(Path path, CodedOutputStream codedOut)
      throws IOException, SerializationException {
    Preconditions.checkState(
        path.getFileSystem() == fileSystem,
        "Path's FileSystem (%s) did not match the configured FileSystem (%s)",
        path.getFileSystem(),
        fileSystem);
    PathFragment.CODEC.serialize(path.asFragment(), codedOut);
  }

  @Override
  public Path deserialize(CodedInputStream codedIn) throws IOException, SerializationException {
    PathFragment pathFragment = PathFragment.CODEC.deserialize(codedIn);
    return fileSystem.getPath(pathFragment);
  }
}