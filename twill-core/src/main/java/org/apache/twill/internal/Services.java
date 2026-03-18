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

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.Service;
import com.google.common.util.concurrent.SettableFuture;
import org.apache.twill.common.Threads;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Utility methods for help dealing with {@link Service}.
 */
public final class Services {

  /**
   * Starts a list of {@link Service} one by one. Starting of next Service is triggered from the callback listener
   * thread of the previous Service.
   *
   * @param firstService First service to start.
   * @param moreServices The rest services to start.
   * @return A {@link ListenableFuture} that will be completed when all services are started.
   */
  public static ListenableFuture<Service.State> chainStart(Service firstService,
                                                            Service...moreServices) {
    return doChain(true, firstService, moreServices);
  }

  /**
   * Stops a list of {@link Service} one by one. It behaves the same as
   * {@link #chainStart(com.google.common.util.concurrent.Service, com.google.common.util.concurrent.Service...)}
   * except {@link com.google.common.util.concurrent.Service#stopAsync()} is called instead of startAsync.
   *
   * @param firstService First service to stop.
   * @param moreServices The rest services to stop.
   * @return A {@link ListenableFuture} that will be completed when all services are stopped.
   * @see #chainStart(com.google.common.util.concurrent.Service, com.google.common.util.concurrent.Service...)
   */
  public static ListenableFuture<Service.State> chainStop(Service firstService,
                                                           Service...moreServices) {
    return doChain(false, firstService, moreServices);
  }

  /**
   * Returns a {@link ListenableFuture} that will be completed when the given service is stopped. If the service
   * stopped due to error, the failure cause would be reflected in the future.
   *
   * @param service The {@link Service} to block on.
   * @return A {@link ListenableFuture} that will be completed when the service is stopped.
   */
  public static ListenableFuture<Service.State> getCompletionFuture(Service service) {
    final SettableFuture<Service.State> resultFuture = SettableFuture.create();

    service.addListener(new ServiceListenerAdapter() {
      @Override
      public void terminated(Service.State from) {
        resultFuture.set(Service.State.TERMINATED);
      }

      @Override
      public void failed(Service.State from, Throwable failure) {
        resultFuture.setException(failure);
      }
    }, Threads.SAME_THREAD_EXECUTOR);

    Service.State state = service.state();
    if (state == Service.State.TERMINATED) {
      return Futures.immediateFuture(state);
    } else if (state == Service.State.FAILED) {
      return Futures.immediateFailedFuture(new IllegalStateException("Service failed with unknown exception."));
    }

    return resultFuture;
  }

  /**
   * Performs the actual logic of chain Service start/stop.
   */
  private static ListenableFuture<Service.State> doChain(boolean doStart,
                                                          Service firstService,
                                                          Service...moreServices) {
    final SettableFuture<Service.State> resultFuture = SettableFuture.create();
    final AtomicInteger idx = new AtomicInteger(0);
    startOrStop(doStart, firstService, moreServices, idx, resultFuture);
    return resultFuture;
  }

  /**
   * Starts or stops a service and adds a listener to chain the next service when the operation completes.
   */
  private static void startOrStop(final boolean doStart, Service service, final Service[] moreServices,
                                  final AtomicInteger idx,
                                  final SettableFuture<Service.State> resultFuture) {
    service.addListener(new ServiceListenerAdapter() {
      @Override
      public void running() {
        if (doStart) {
          onServiceOperationComplete(Service.State.RUNNING);
        }
      }

      @Override
      public void terminated(Service.State from) {
        if (!doStart) {
          onServiceOperationComplete(Service.State.TERMINATED);
        }
      }

      @Override
      public void failed(Service.State from, Throwable failure) {
        resultFuture.setException(failure);
      }

      private void onServiceOperationComplete(Service.State state) {
        int nextIdx = idx.getAndIncrement();
        if (nextIdx >= moreServices.length) {
          resultFuture.set(state);
          return;
        }
        startOrStop(doStart, moreServices[nextIdx], moreServices, idx, resultFuture);
      }
    }, Threads.SAME_THREAD_EXECUTOR);

    if (doStart) {
      service.startAsync();
    } else {
      service.stopAsync();
    }
  }

  private Services() {
  }
}
