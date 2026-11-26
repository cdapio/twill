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
package org.apache.twill.internal;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.util.DefaultJoranConfigurator;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Verifies Logback 1.6 {@link DefaultJoranConfigurator} configuration loading in {@link ServiceMain}.
 */
public class ServiceMainLogbackTest {

  @Rule
  public final TemporaryFolder tmpFolder = new TemporaryFolder();

  @Test
  public void testConfigureTemplateWhenNoDefaultConfig() throws Exception {
    URL templateUrl = getClass().getClassLoader().getResource(Constants.Files.LOGBACK_TEMPLATE);
    Assert.assertNotNull(templateUrl);

    LoggerContext context = new LoggerContext();
    try {
      DefaultJoranConfigurator noDefaultConfig = new DefaultJoranConfigurator() {
        @Override
        public URL findURLOfDefaultConfigurationFile(boolean updateStatus) {
          return null;
        }
      };
      ServiceMain.configureLoggerContext(context, noDefaultConfig, new File(templateUrl.toURI()));

      Assert.assertEquals(Level.WARN, context.getLogger("org.apache.hadoop").getLevel());
      Assert.assertEquals(Level.WARN, context.getLogger("org.apache.zookeeper").getLevel());
      Assert.assertEquals(Level.INFO, context.getLogger(Logger.ROOT_LOGGER_NAME).getLevel());
    } finally {
      context.stop();
    }
  }

  @Test
  public void testSkipsTemplateWhenDefaultConfigPresent() throws Exception {
    URL templateUrl = getClass().getClassLoader().getResource(Constants.Files.LOGBACK_TEMPLATE);
    Assert.assertNotNull(templateUrl);
    File customXml = tmpFolder.newFile("custom-logback.xml");
    Files.write(customXml.toPath(),
                "<configuration><root level=\"ERROR\"/></configuration>".getBytes(StandardCharsets.UTF_8));

    String prev = System.getProperty("logback.configurationFile");
    LoggerContext context = new LoggerContext();
    try {
      System.setProperty("logback.configurationFile", customXml.getAbsolutePath());
      ServiceMain.configureLoggerContext(context, new DefaultJoranConfigurator(), new File(templateUrl.toURI()));
      Assert.assertNull(context.getLogger("org.apache.hadoop").getLevel());
    } finally {
      if (prev == null) {
        System.clearProperty("logback.configurationFile");
      } else {
        System.setProperty("logback.configurationFile", prev);
      }
      context.stop();
    }
  }
}
