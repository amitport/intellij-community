/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.vcs.log;

import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;

/**
 * Tells the log to filter by files and folders.
 */
public interface VcsLogStructureFilter extends VcsLogDetailsFilter {

  /**
   * <p>Returns files from the given VCS root, which are affected by matching commits, and folders containing such files.</p>
   *
   * <p>That is: the commit A (made in the given VCS root) modifying file f.txt matches this filter,
   *    if this method returns a set which includes a folder containing f.txt, or the file f.txt itself.</p>
   */
  @NotNull
  Collection<VirtualFile> getFiles(@NotNull VirtualFile root);

}
