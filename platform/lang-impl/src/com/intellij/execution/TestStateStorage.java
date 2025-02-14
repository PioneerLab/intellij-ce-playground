/*
 * Copyright 2000-2015 JetBrains s.r.o.
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
package com.intellij.execution;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.ThrowableComputable;
import com.intellij.openapi.util.io.FileUtilRt;
import com.intellij.openapi.vfs.newvfs.persistent.FlushingDaemon;
import com.intellij.util.io.DataExternalizer;
import com.intellij.util.io.EnumeratorStringDescriptor;
import com.intellij.util.io.IOUtil;
import com.intellij.util.io.PersistentHashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.concurrent.ScheduledFuture;

/**
 * @author Dmitry Avdeev
 */
public class TestStateStorage implements Disposable {

  private static final File TEST_HISTORY_PATH = new File(PathManager.getSystemPath(), "testHistory");
  private final File myFile;

  public static File getTestHistoryRoot(Project project) {
    return new File(TEST_HISTORY_PATH, project.getLocationHash());
  }

  public static class Record {
    public final int magnitude;
    public final Date date;

    public Record(int magnitude, Date date) {
      this.magnitude = magnitude;
      this.date = date;
    }
  }

  private static final Logger LOG = Logger.getInstance(TestStateStorage.class);
  @Nullable
  private PersistentHashMap<String, Record> myMap;
  private volatile ScheduledFuture<?> myMapFlusher;

  public static TestStateStorage getInstance(@NotNull Project project) {
    return ServiceManager.getService(project, TestStateStorage.class);
  }

  public TestStateStorage(Project project) {

    myFile = new File(getTestHistoryRoot(project).getPath() + "/testStateMap");
    FileUtilRt.createParentDirs(myFile);
    try {
      myMap = initializeMap();
    } catch (IOException e) {
      LOG.error(e);
    }
    myMapFlusher = FlushingDaemon.everyFiveSeconds(new Runnable() {
      @Override
      public void run() {
        flushMap();
      }
    });

    Disposer.register(project, this);
  }

  protected PersistentHashMap<String, Record> initializeMap() throws IOException {
    return IOUtil.openCleanOrResetBroken(getComputable(myFile), myFile);
  }

  private synchronized void flushMap() {
    if (myMapFlusher == null) return; // disposed
    if (myMap != null && myMap.isDirty()) myMap.force();
  }

  @NotNull
  private static ThrowableComputable<PersistentHashMap<String, Record>, IOException> getComputable(final File file) {
    return new ThrowableComputable<PersistentHashMap<String, Record>, IOException>() {
      @Override
      public PersistentHashMap<String, Record> compute() throws IOException {
        return new PersistentHashMap<String, Record>(file, new EnumeratorStringDescriptor(), new DataExternalizer<Record>() {
          @Override
          public void save(@NotNull DataOutput out, Record value) throws IOException {
            out.writeInt(value.magnitude);
            out.writeLong(value.date.getTime());
          }

          @Override
          public Record read(@NotNull DataInput in) throws IOException {
            return new Record(in.readInt(), new Date(in.readLong()));
          }
        });
      }
    };
  }

  @Nullable
  public synchronized Record getState(String testUrl) {
    try {
      return myMap == null ? null : myMap.get(testUrl);
    }
    catch (IOException e) {
      thingsWentWrongLetsReinitialize(e);
      return null;
    }
  }

  public synchronized void writeState(@NotNull String testUrl, Record record) {
    if (myMap == null) return;
    try {
      myMap.put(testUrl, record);
    }
    catch (IOException e) {
      thingsWentWrongLetsReinitialize(e);
    }
  }

  @Override
  public synchronized void dispose() {
    myMapFlusher.cancel(false);
    myMapFlusher = null;
    if (myMap == null) return;
    try {
      myMap.close();
    }
    catch (IOException e) {
      LOG.error(e);
    }
    finally {
      myMap = null;
    }
  }

  private void thingsWentWrongLetsReinitialize(IOException e) {
    try {
      if (myMap != null) {
        try {
          myMap.close();
        }
        catch (IOException ignore) {
        }
        IOUtil.deleteAllFilesStartingWith(myFile);
      }
      myMap = initializeMap();
      LOG.error("Repaired after crash", e);
    }
    catch (IOException e1) {
      LOG.error("Cannot repair", e1);
      myMap = null;
    }
  }
}
