/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.twill.filesystem;

import com.google.common.base.Throwables;
import org.apache.hadoop.conf.Configuration;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URI;

/**
 * Utility class.
 */
final class FileContextLocationUtil {

  // To check whether logical URI is needed, hdfs.HAUtil class is used. But the class is meant for internal purposes,
  // and in Hadoop 2.8, the method was renamed from "isLogicalUri" to "useLogicalUri". So resolve to the
  // correct method.
  // In Hadoop 3.0 and above, HAUtil is not available, instead HAUtilClient should be used
  private static final MethodHandle HA_UTIL_USE_LOGICAL_URI_HANDLE;

  private static MethodHandle lookupInHAUtil(String className, String methodName)
    throws NoSuchMethodException, IllegalAccessException, ClassNotFoundException {
    ClassLoader cl = Thread.currentThread().getContextClassLoader();
    if (cl == null) {
      cl = FileContextLocationUtil.class.getClassLoader();
    }
    Class<?> utilClass = cl.loadClass(className);
    return MethodHandles.publicLookup().findStatic(
      utilClass, methodName,
      MethodType.methodType(boolean.class, new Class[]{Configuration.class, URI.class}));
  }

  static {
    MethodHandle handle;
    try {
      try {
        // hadoop version < 2.8
        handle = lookupInHAUtil("org.apache.hadoop.hdfs.HAUtil", "isLogicalUri");
      } catch (ClassNotFoundException | NoSuchMethodException ignored) {
        try {
          // hadoop version = 2.8
          handle = lookupInHAUtil("org.apache.hadoop.hdfs.HAUtil", "useLogicalUri");
        } catch (ClassNotFoundException | NoSuchMethodException ignored1) {
          try {
            // hadoop version >= 3.0
            handle = lookupInHAUtil("org.apache.hadoop.hdfs.HAUtilClient", "isLogicalUri");
          } catch (ClassNotFoundException | NoSuchMethodException ex) {
            handle = null;
          }
        }
      }
    } catch (IllegalAccessException e) {
      handle = null;
    }
    HA_UTIL_USE_LOGICAL_URI_HANDLE = handle;
  }

  static boolean useLogicalUri(final Configuration configuration, final URI uri) {
    try {
      if (HA_UTIL_USE_LOGICAL_URI_HANDLE == null) {
        return false;
      }
      return (Boolean) HA_UTIL_USE_LOGICAL_URI_HANDLE.invoke(configuration, uri);
    } catch (Throwable e) {
      throw Throwables.propagate(e);
    }
  }

  private FileContextLocationUtil() {
  }
}
