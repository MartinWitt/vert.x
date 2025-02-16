/*
 * Copyright (c) 2011-2022 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */

package io.vertx.core.eventbus;

import io.vertx.core.*;
import io.vertx.core.shareddata.AsyncMapTest;
import io.vertx.core.spi.cluster.*;
import io.vertx.test.core.TestUtils;
import io.vertx.test.fakecluster.FakeClusterManager;
import org.junit.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;


/**
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public class ClusteredEventBusTestBase extends EventBusTestBase {

  protected static final String ADDRESS1 = "some-address1";

  protected ClusterManager getClusterManager() {
    return new FakeClusterManager();
  }

  @Override
  protected void clusteredVertx(VertxOptions options, Handler<AsyncResult<Vertx>> ar) {
    Promise<Vertx> promise = Promise.promise();
    super.clusteredVertx(options, promise);
    promise.future().onSuccess(vertx -> {
      ImmutableObjectCodec immutableObjectCodec = new ImmutableObjectCodec();
      vertx.eventBus().registerCodec(immutableObjectCodec);
      vertx.eventBus().codecSelector(obj -> obj instanceof ImmutableObject ? immutableObjectCodec.name() : null);
      vertx.eventBus().clusterSerializableChecker(className -> className.startsWith(AsyncMapTest.class.getName()));
      vertx.eventBus().serializableChecker(className -> {
        return EventBus.DEFAULT_SERIALIZABLE_CHECKER.apply(className) || className.startsWith(AsyncMapTest.class.getName());
      });
    }).onComplete(ar);
  }

  @Override
  protected boolean shouldImmutableObjectBeCopied() {
    return true;
  }

  @Override
  protected <T, R> void testSend(T val, R received, Consumer<T> consumer, DeliveryOptions options) {
    if (vertices == null) {
      startNodes(2);
    }

    MessageConsumer<T> reg = vertices[1].eventBus().<T>consumer(ADDRESS1).handler((Message<T> msg) -> {
      if (consumer == null) {
        assertTrue(msg.isSend());
        assertEquals(received, msg.body());
        if (options != null) {
          assertNotNull(msg.headers());
          int numHeaders = options.getHeaders() != null ? options.getHeaders().size() : 0;
          assertEquals(numHeaders, msg.headers().size());
          if (numHeaders != 0) {
            for (Map.Entry<String, String> entry : options.getHeaders().entries()) {
              assertEquals(msg.headers().get(entry.getKey()), entry.getValue());
            }
          }
        }
      } else {
        consumer.accept(msg.body());
      }
      testComplete();
    });
    reg.completionHandler(ar -> {
      assertTrue(ar.succeeded());
      if (options == null) {
        vertices[0].eventBus().send(ADDRESS1, val);
      } else {
        vertices[0].eventBus().send(ADDRESS1, val, options);
      }
    });
    await();
  }

  @Override
  protected <T> void testSend(T val, Consumer <T> consumer) {
    testSend(val, val, consumer, null);
  }

  @Override
  protected <T> void testReply(T val, Consumer<T> consumer) {
    testReply(val, val, consumer, null);
  }

  @Override
  protected <T, R> void testReply(T val, R received, Consumer<R> consumer, DeliveryOptions options) {
    if (vertices == null) {
      startNodes(2);
    }
    String str = TestUtils.randomUnicodeString(1000);
    MessageConsumer<?> reg = vertices[1].eventBus().consumer(ADDRESS1).handler(msg -> {
      assertEquals(str, msg.body());
      if (options == null) {
        msg.reply(val);
      } else {
        msg.reply(val, options);
      }
    });
    reg.completionHandler(ar -> {
      assertTrue(ar.succeeded());
      vertices[0].eventBus().request(ADDRESS1, str, onSuccess((Message<R> reply) -> {
        if (consumer == null) {
          assertTrue(reply.isSend());
          assertEquals(received, reply.body());
          if (options != null && options.getHeaders() != null) {
            assertNotNull(reply.headers());
            assertEquals(options.getHeaders().size(), reply.headers().size());
            for (Map.Entry<String, String> entry: options.getHeaders().entries()) {
              assertEquals(reply.headers().get(entry.getKey()), entry.getValue());
            }
          }
        } else {
          consumer.accept(reply.body());
        }
        testComplete();
      }));
    });

    await();
  }

  @Test
  public void testRegisterRemote1() {
    startNodes(2);
    String str = TestUtils.randomUnicodeString(100);
    vertices[0].eventBus().<String>consumer(ADDRESS1).handler((Message<String> msg) -> {
      assertEquals(str, msg.body());
      testComplete();
    }).completionHandler(ar -> {
      assertTrue(ar.succeeded());
      vertices[1].eventBus().send(ADDRESS1, str);
    });
    await();
  }

  @Test
  public void testRegisterRemote2() {
    startNodes(2);
    String str = TestUtils.randomUnicodeString(100);
    vertices[0].eventBus().consumer(ADDRESS1, (Message<String> msg) -> {
      assertEquals(str, msg.body());
      testComplete();
    }).completionHandler(ar -> {
      assertTrue(ar.succeeded());
      vertices[1].eventBus().send(ADDRESS1, str);
    });
    await();
  }

  @Override
  protected <T> void testPublish(T val, Consumer<T> consumer) {
    int numNodes = 3;
    startNodes(numNodes);
    AtomicInteger count = new AtomicInteger();
    class MyHandler implements Handler<Message<T>> {
      @Override
      public void handle(Message<T> msg) {
        if (consumer == null) {
          assertFalse(msg.isSend());
          assertEquals(val, msg.body());
        } else {
          consumer.accept(msg.body());
        }
        if (count.incrementAndGet() == numNodes - 1) {
          testComplete();
        }
      }
    }
    AtomicInteger registerCount = new AtomicInteger(0);
    class MyRegisterHandler implements Handler<AsyncResult<Void>> {
      @Override
      public void handle(AsyncResult<Void> ar) {
        assertTrue(ar.succeeded());
        if (registerCount.incrementAndGet() == 2) {
          vertices[0].eventBus().publish(ADDRESS1, val);
        }
      }
    }
    MessageConsumer reg = vertices[2].eventBus().<T>consumer(ADDRESS1).handler(new MyHandler());
    reg.completionHandler(new MyRegisterHandler());
    reg = vertices[1].eventBus().<T>consumer(ADDRESS1).handler(new MyHandler());
    reg.completionHandler(new MyRegisterHandler());
    await();
  }

  @Test
  public void testMessageBodyInterceptor() throws Exception {
    String content = TestUtils.randomUnicodeString(13);
    startNodes(2);
    waitFor(2);
    CountDownLatch latch = new CountDownLatch(1);
    vertices[0].eventBus().registerCodec(new StringLengthCodec()).<Integer>consumer("whatever", msg -> {
      assertEquals(content.length(), (int) msg.body());
      complete();
    }).completionHandler(ar -> latch.countDown());
    awaitLatch(latch);
    StringLengthCodec codec = new StringLengthCodec();
    vertices[1].eventBus().registerCodec(codec).addOutboundInterceptor(sc -> {
      if ("whatever".equals(sc.message().address())) {
        assertEquals(content, sc.body());
        complete();
      }
      sc.next();
    }).send("whatever", content, new DeliveryOptions().setCodecName(codec.name()));
    await();
  }

  @Test
  public void testClusteredUnregistration() throws Exception {
    CountDownLatch updateLatch = new CountDownLatch(3);
    Supplier<VertxOptions> options = () -> getOptions().setClusterManager(new WrappedClusterManager(getClusterManager()) {
      @Override
      public void init(Vertx vertx, NodeSelector nodeSelector) {
        super.init(vertx, new WrappedNodeSelector(nodeSelector) {
          @Override
          public void registrationsUpdated(RegistrationUpdateEvent event) {
            super.registrationsUpdated(event);
            if (event.address().equals("foo") && event.registrations().isEmpty()) {
              updateLatch.countDown();
            }
          }
        });
      }
    });
    startNodes(options.get(), options.get());
    MessageConsumer<Object> consumer = vertices[0].eventBus().consumer("foo", msg -> msg.reply(msg.body()));
    consumer.completionHandler(onSuccess(reg -> {
      vertices[0].eventBus().request("foo", "echo", onSuccess(reply1 -> {
        assertEquals("echo", reply1.body());
        vertices[1].eventBus().request("foo", "echo", onSuccess(reply2 -> {
          assertEquals("echo", reply1.body());
          consumer.unregister(onSuccess(unreg -> {
            updateLatch.countDown();
          }));
        }));
      }));
    }));
    awaitLatch(updateLatch);
    vertices[1].eventBus().request("foo", "echo", onFailure(fail1 -> {
      assertThat(fail1, is(instanceOf(ReplyException.class)));
      assertEquals(ReplyFailure.NO_HANDLERS, ((ReplyException) fail1).failureType());
      vertices[0].eventBus().request("foo", "echo", onFailure(fail2 -> {
        assertThat(fail2, is(instanceOf(ReplyException.class)));
        assertEquals(ReplyFailure.NO_HANDLERS, ((ReplyException) fail2).failureType());
        testComplete();
      }));
    }));
    await();
  }
}
