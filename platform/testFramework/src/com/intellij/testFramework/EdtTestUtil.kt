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
package com.intellij.testFramework

import com.intellij.util.ThrowableRunnable
import java.lang.reflect.InvocationTargetException
import javax.swing.SwingUtilities

class EdtTestUtil {
  companion object {
    @JvmStatic fun runInEdtAndWait(runnable: ThrowableRunnable<Throwable>) {
      runInEdtAndWait({ runnable.run() })
    }

    @JvmStatic fun runInEdtAndWait(runnable: Runnable) {
      runInEdtAndWait({ runnable.run() })
    }

    // Test only because in production you must use Application.invokeAndWait(Runnable, ModalityState).
    // The problem is - Application logs errors, but not throws. But in tests must be thrown.
    // In any case name "runInEdtAndWait" is better than "invokeAndWait".
    @JvmStatic fun runInEdtAndWait(runnable: () -> Unit) {
      if (SwingUtilities.isEventDispatchThread()) {
        runnable()
      }
      else {
        try {
          SwingUtilities.invokeAndWait(runnable)
        }
        catch (e: InvocationTargetException) {
          throw e.cause ?: e
        }
      }
    }
  }
}