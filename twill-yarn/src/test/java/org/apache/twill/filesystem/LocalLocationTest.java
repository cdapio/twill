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

import org.apache.hadoop.security.UserGroupInformation;
import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 *
 */
public class LocalLocationTest extends LocationTestBase {

  @ClassRule
  public static final TemporaryFolder TEMP_FOLDER = new TemporaryFolder();

  @Override
  protected LocationFactory createLocationFactory(String pathBase) throws Exception {
    File basePath = new File(tmpFolder.newFolder(), pathBase);
    //noinspection ResultOfMethodCallIgnored
    basePath.mkdirs();
    return new LocalLocationFactory(basePath);
  }

  @Override
  protected UserGroupInformation createTestUGI() throws IOException {
    // In local location, UGI is not supported, hence using the current user as the testing ugi.
    return UserGroupInformation.getCurrentUser();
  }

  @Test
  public void testLastModified() throws IOException, InterruptedException {
    LocationFactory lf = new LocalLocationFactory(TEMP_FOLDER.newFolder());
    Location location = lf.create("test1");
    String message = "message";
    try (OutputStream os = location.getOutputStream()) {
      os.write(message.getBytes(StandardCharsets.UTF_8));
    }
    long initialModificationTimestamp = location.lastModified();

    // Modify file, last modified time should get updated.
    // Sleep for a while, in case the filesystem is very fast.
    Thread.sleep(1000);
    try (OutputStream os = location.getOutputStream()) {
      os.write(message.getBytes(StandardCharsets.UTF_8));
    }
    long secondModificationTimestamp = location.lastModified();
    Assert.assertTrue(String.format(
                        "initialModificationTimestamp(%d) is not less than secondModificationTimestamp(%d)",
                        initialModificationTimestamp, secondModificationTimestamp),
                      initialModificationTimestamp < secondModificationTimestamp);
  }
}
