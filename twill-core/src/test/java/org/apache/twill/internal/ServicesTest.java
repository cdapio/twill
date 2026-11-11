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

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.AbstractIdleService;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.Service;
import org.apache.twill.api.Command;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Unit test for {@link Services} methods.
 */
public class ServicesTest {

  private static final Logger LOG = LoggerFactory.getLogger(ServicesTest.class);

  @Test
  public void testChain() throws ExecutionException, InterruptedException {
    AtomicBoolean transiting = new AtomicBoolean(false);
    Service s1 = new DummyService("s1", transiting);
    Service s2 = new DummyService("s2", transiting);
    Service s3 = new DummyService("s3", transiting);

    Services.chainStart(s1, s2, s3).get();
    Services.chainStop(s3, s2, s1).get();
    // Stopping already terminated services should complete immediately without hanging
    Services.chainStop(s3, s2, s1).get();
    // Starting an already terminated service should complete the returned future exceptionally
    try {
      Services.chainStart(s1, s2).get();
      Assert.fail("Expected ExecutionException when starting an already terminated service");
    } catch (ExecutionException e) {
      Assert.assertTrue(e.getCause() instanceof IllegalStateException);
    }
  }

  @Test
  public void testChainStopContinuesOnFailure() throws Exception {
    AtomicBoolean transiting = new AtomicBoolean(false);
    Service s1 = new DummyService("s1", transiting);
    Service s2 = new DummyService("s2", transiting);
    Service s3 = new DummyService("s3", transiting);

    Services.chainStart(s1, s2, s3).get(5, TimeUnit.SECONDS);

    // Cause s3 to fail during shutDown by setting transiting=true before stopping s3 alone,
    // and also test a service that fails during chainStop itself.
    Service failingStopService = new AbstractIdleService() {
      @Override
      protected void startUp() {
      }

      @Override
      protected void shutDown() {
        throw new RuntimeException("Simulated shutdown failure");
      }
    };
    failingStopService.startAsync().awaitRunning(5, TimeUnit.SECONDS);

    // Force s3 into FAILED state first
    transiting.set(true);
    try {
      s3.stopAsync().awaitTerminated(5, TimeUnit.SECONDS);
      Assert.fail("Expected s3 to fail");
    } catch (IllegalStateException expected) {
      // Expected
    }
    transiting.set(false);
    Assert.assertEquals(Service.State.FAILED, s3.state());

    // chainStop should continue past already-FAILED s3 and failing-on-stop failingStopService,
    // stopping s2 and s1 and completing normally.
    Service.State finalState = Services.chainStop(s3, failingStopService, s2, s1).get(5, TimeUnit.SECONDS);
    Assert.assertEquals(Service.State.TERMINATED, finalState);
    Assert.assertEquals(Service.State.TERMINATED, s2.state());
    Assert.assertEquals(Service.State.TERMINATED, s1.state());
  }

  @Test
  public void testChainStartEdgeCases() throws Exception {
    // 1. A wrapper service already in RUNNING state whose startAsync() is a no-op should advance the chain
    AtomicBoolean transiting = new AtomicBoolean(false);
    final Service delegate = new DummyService("delegate", transiting);
    delegate.startAsync().awaitRunning(5, TimeUnit.SECONDS);

    Service noopStartRunningService = new Service() {
      @Override
      public Service startAsync() {
        return this;
      }

      @Override
      public boolean isRunning() {
        return delegate.isRunning();
      }

      @Override
      public State state() {
        return delegate.state();
      }

      @Override
      public Service stopAsync() {
        return delegate.stopAsync();
      }

      @Override
      public void awaitRunning() {
        delegate.awaitRunning();
      }

      @Override
      public void awaitRunning(long timeout, TimeUnit unit) throws TimeoutException {
        delegate.awaitRunning(timeout, unit);
      }

      @Override
      public void awaitTerminated() {
        delegate.awaitTerminated();
      }

      @Override
      public void awaitTerminated(long timeout, TimeUnit unit) throws TimeoutException {
        delegate.awaitTerminated(timeout, unit);
      }

      @Override
      public Throwable failureCause() {
        return delegate.failureCause();
      }

      @Override
      public void addListener(Listener listener, Executor executor) {
        delegate.addListener(listener, executor);
      }
    };
    Service nextService = new DummyService("next", transiting);
    Service.State state = Services.chainStart(noopStartRunningService, nextService).get(5, TimeUnit.SECONDS);
    Assert.assertEquals(Service.State.RUNNING, state);
    Assert.assertEquals(Service.State.RUNNING, nextService.state());
    Services.chainStop(nextService, delegate).get(5, TimeUnit.SECONDS);

    // 2. If stopAsync() is called while a service is STARTING, chainStart should fail with IllegalStateException
    final CountDownLatch startingLatch = new CountDownLatch(1);
    final CountDownLatch allowFinishStartLatch = new CountDownLatch(1);
    Service stoppedWhileStarting = new AbstractIdleService() {
      @Override
      protected void startUp() throws Exception {
        startingLatch.countDown();
        allowFinishStartLatch.await(5, TimeUnit.SECONDS);
      }

      @Override
      protected void shutDown() {
      }
    };
    ListenableFuture<Service.State> chainFuture = Services.chainStart(stoppedWhileStarting);
    Assert.assertTrue(startingLatch.await(5, TimeUnit.SECONDS));
    stoppedWhileStarting.stopAsync();
    allowFinishStartLatch.countDown();
    try {
      chainFuture.get(5, TimeUnit.SECONDS);
      Assert.fail("Expected ExecutionException when service terminates during startup");
    } catch (ExecutionException e) {
      Assert.assertTrue(e.getCause() instanceof IllegalStateException);
    }
  }

  @Test
  public void testControllerTerminateBeforeStart() throws Exception {
    AbstractExecutionServiceController controller = new AbstractExecutionServiceController(RunIds.generate()) {
      @Override
      protected void startUp() {
      }

      @Override
      protected void shutDown() {
      }

      @Override
      public Future<Command> sendCommand(Command command) {
        return null;
      }

      @Override
      public Future<Command> sendCommand(String runnableName, Command command) {
        return null;
      }

      @Override
      public void kill() {
      }
    };
    controller.terminate().get(5, TimeUnit.SECONDS);
    controller.awaitTerminated(5, TimeUnit.SECONDS);
    Assert.assertEquals(Service.State.TERMINATED, controller.state());
  }

  @Test
  public void testCompletion() throws ExecutionException, InterruptedException {
    Service service = new DummyService("s1", new AtomicBoolean());
    ListenableFuture<Service.State> completion = Services.getCompletionFuture(service);

    service.startAsync();
    service.stopAsync();

    completion.get();

    AtomicBoolean transiting = new AtomicBoolean();
    service = new DummyService("s2", transiting);
    completion = Services.getCompletionFuture(service);

    service.startAsync().awaitRunning();
    transiting.set(true);
    service.stopAsync();

    try {
      completion.get();
      Assert.assertTrue(false);
    } catch (ExecutionException e) {
      // Expected
    }
  }

  private static final class DummyService extends AbstractIdleService {

    private final String name;
    private final AtomicBoolean transiting;

    private DummyService(String name, AtomicBoolean transiting) {
      this.name = name;
      this.transiting = transiting;
    }

    @Override
    protected void startUp() throws Exception {
      Preconditions.checkState(transiting.compareAndSet(false, true));
      LOG.info("Starting: " + name);
      TimeUnit.MILLISECONDS.sleep(500);
      LOG.info("Started: " + name);
      Preconditions.checkState(transiting.compareAndSet(true, false));
    }

    @Override
    protected void shutDown() throws Exception {
      Preconditions.checkState(transiting.compareAndSet(false, true));
      LOG.info("Stopping: " + name);
      TimeUnit.MILLISECONDS.sleep(500);
      LOG.info("Stopped: " + name);
      Preconditions.checkState(transiting.compareAndSet(true, false));
    }
  }
}
