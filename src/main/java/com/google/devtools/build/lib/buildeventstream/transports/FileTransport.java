// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.buildeventstream.transports;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.devtools.build.lib.buildeventstream.ArtifactGroupNamer;
import com.google.devtools.build.lib.buildeventstream.BuildEvent;
import com.google.devtools.build.lib.buildeventstream.BuildEventArtifactUploader;
import com.google.devtools.build.lib.buildeventstream.BuildEventContext;
import com.google.devtools.build.lib.buildeventstream.BuildEventProtocolOptions;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos;
import com.google.devtools.build.lib.buildeventstream.BuildEventTransport;
import com.google.devtools.build.lib.buildeventstream.PathConverter;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.ExitCode;
import com.google.devtools.build.lib.util.io.AsynchronousFileOutputStream;
import com.google.devtools.build.lib.vfs.Path;
import com.google.protobuf.Message;
import java.io.IOException;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * Non-blocking file transport.
 *
 * <p>Implementors of this class need to implement {@code #sendBuildEvent(BuildEvent)} which
 * serializes the build event and writes it to file using {@link
 * AsynchronousFileOutputStream#write}.
 */
abstract class FileTransport implements BuildEventTransport {
  private static final Logger logger = Logger.getLogger(FileTransport.class.getName());

  private final BuildEventProtocolOptions options;
  private final BuildEventArtifactUploader uploader;
  private final Consumer<AbruptExitException> exitFunc;
  private boolean errored;

  @VisibleForTesting
  final AsynchronousFileOutputStream out;

  FileTransport(
      String path,
      BuildEventProtocolOptions options,
      BuildEventArtifactUploader uploader,
      Consumer<AbruptExitException> exitFunc)
          throws IOException {
    this.uploader = uploader;
    this.options = options;
    this.exitFunc = exitFunc;
    out = new AsynchronousFileOutputStream(path);
  }

  // Silent wrappers to AsynchronousFileOutputStream methods.

  protected void write(Message m) {
    try {
      out.write(m);
    } catch (Exception e) {
      logger.log(Level.SEVERE, e.getMessage(), e);
    }
  }

  protected void write(String s) {
    try {
      out.write(s);
    } catch (Exception e) {
      logger.log(Level.SEVERE, e.getMessage(), e);
    }
  }

  @Override
  public synchronized ListenableFuture<Void> close() {
    return Futures.catching(
        out.closeAsync(),
        Throwable.class,
        (t) -> {
          logger.log(Level.SEVERE, t.getMessage(), t);
          return null;
        },
        MoreExecutors.directExecutor());
  }

  @Override
  public void closeNow() {
    out.closeNow();
  }

  /**
   * Converts the given event into a proto object; this may trigger uploading of referenced files as
   * a side effect. May return {@code null} if there was an interrupt. This method is not
   * thread-safe.
   */
  @Nullable
  protected BuildEventStreamProtos.BuildEvent asStreamProto(
      BuildEvent event, ArtifactGroupNamer namer) {
    checkNotNull(event);
    PathConverter pathConverter = uploadReferencedFiles(event.referencedLocalFiles());
    if (pathConverter == null) {
      return null;
    }

    BuildEventContext context =
        new BuildEventContext() {
          @Override
          public PathConverter pathConverter() {
            return pathConverter;
          }

          @Override
          public ArtifactGroupNamer artifactGroupNamer() {
            return namer;
          }

          @Override
          public BuildEventProtocolOptions getOptions() {
            return options;
          }
        };
    return event.asStreamProto(context);
  }

  /**
   * Returns a {@link PathConverter} for the uploaded files, or {@code null} when the uploaded
   * failed.
   */
  private @Nullable PathConverter uploadReferencedFiles(Set<Path> artifacts) {
    checkNotNull(artifacts);

    if (errored) {
      return null;
    }
    try {
      return uploader.upload(artifacts);
    } catch (IOException e) {
      errored = true;
      exitFunc.accept(
          new AbruptExitException(
              Throwables.getStackTraceAsString(e), ExitCode.PUBLISH_ERROR, e));
    } catch (InterruptedException e) {
      errored = true;
      exitFunc.accept(new AbruptExitException(ExitCode.INTERRUPTED, e));
      Thread.currentThread().interrupt();
    }
    return null;
  }
}
