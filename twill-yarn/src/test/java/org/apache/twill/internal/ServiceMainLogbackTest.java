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
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.util.DefaultJoranConfigurator;
import org.junit.Assert;
import org.junit.Test;

import java.net.URL;

/**
 * Verifies Logback 1.6 {@link DefaultJoranConfigurator} configuration loading used by {@link ServiceMain}.
 */
public class ServiceMainLogbackTest {

  @Test
  public void testConfigureTemplate() throws Exception {
    LoggerContext context = new LoggerContext();
    DefaultJoranConfigurator configurator = new DefaultJoranConfigurator();
    configurator.setContext(context);

    URL templateUrl = getClass().getClassLoader().getResource(Constants.Files.LOGBACK_TEMPLATE);
    Assert.assertNotNull(templateUrl);
    configurator.configureByResource(templateUrl);

    Assert.assertEquals(Level.WARN, context.getLogger("org.apache.hadoop").getLevel());
    Assert.assertEquals(Level.WARN, context.getLogger("org.apache.zookeeper").getLevel());
    Assert.assertEquals(Level.INFO, context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME).getLevel());
  }
}
