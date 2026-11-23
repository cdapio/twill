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
package org.apache.twill.internal.json;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.twill.api.logging.LogEntry;
import org.apache.twill.api.logging.LogThrowable;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests serialization and deserialization of Logback 1.6 {@link ILoggingEvent}.
 */
public class ILoggingEventSerializerTest {

  @Test
  public void testRoundTrip() {
    LoggerContext context = new LoggerContext();
    Logger logger = context.getLogger(ILoggingEventSerializerTest.class);
    RuntimeException cause = new RuntimeException("inner");
    IllegalStateException error = new IllegalStateException("outer", cause);

    LoggingEvent event = new LoggingEvent(
      Logger.FQCN, logger, Level.ERROR, "failed {} times", error, new Object[]{3});
    event.setTimeStamp(1700000000123L);

    Gson gson = new GsonBuilder()
      .registerTypeAdapter(StackTraceElement.class, new StackTraceElementCodec())
      .registerTypeAdapter(LogThrowable.class, new LogThrowableCodec())
      .registerTypeAdapter(ILoggingEvent.class, new ILoggingEventSerializer("host-1", "worker"))
      .registerTypeAdapter(LogEntry.class, new LogEntryDecoder())
      .create();

    String json = gson.toJson(event, ILoggingEvent.class);
    LogEntry decoded = gson.fromJson(json, LogEntry.class);

    Assert.assertEquals(ILoggingEventSerializerTest.class.getName(), decoded.getLoggerName());
    Assert.assertEquals("host-1", decoded.getHost());
    Assert.assertEquals("worker", decoded.getRunnableName());
    Assert.assertEquals(1700000000123L, decoded.getTimestamp());
    Assert.assertEquals(LogEntry.Level.ERROR, decoded.getLogLevel());
    Assert.assertEquals("failed 3 times", decoded.getMessage());
    Assert.assertNotNull(decoded.getThrowable());
    Assert.assertEquals(IllegalStateException.class.getName(), decoded.getThrowable().getClassName());
    Assert.assertEquals("outer", decoded.getThrowable().getMessage());
    Assert.assertNotNull(decoded.getThrowable().getCause());
    Assert.assertEquals("inner", decoded.getThrowable().getCause().getMessage());
  }
}
