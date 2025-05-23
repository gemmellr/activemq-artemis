/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.artemis.tests.integration.cluster.reattach;

import java.lang.invoke.MethodHandles;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.activemq.artemis.api.core.ActiveMQException;
import org.apache.activemq.artemis.api.core.ActiveMQExceptionType;
import org.apache.activemq.artemis.api.core.ActiveMQNotConnectedException;
import org.apache.activemq.artemis.api.core.ActiveMQObjectClosedException;
import org.apache.activemq.artemis.api.core.Interceptor;
import org.apache.activemq.artemis.api.core.QueueConfiguration;
import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.api.core.client.ClientConsumer;
import org.apache.activemq.artemis.api.core.client.ClientMessage;
import org.apache.activemq.artemis.api.core.client.ClientProducer;
import org.apache.activemq.artemis.api.core.client.ClientSession;
import org.apache.activemq.artemis.api.core.client.ServerLocator;
import org.apache.activemq.artemis.api.core.client.SessionFailureListener;
import org.apache.activemq.artemis.core.client.impl.ClientSessionFactoryInternal;
import org.apache.activemq.artemis.core.client.impl.ClientSessionInternal;
import org.apache.activemq.artemis.core.protocol.core.impl.wireformat.SessionProducerCreditsMessage;
import org.apache.activemq.artemis.core.remoting.impl.invm.InVMConnector;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.ServerSession;
import org.apache.activemq.artemis.jms.client.ActiveMQTextMessage;
import org.apache.activemq.artemis.spi.core.protocol.RemotingConnection;
import org.apache.activemq.artemis.tests.util.ActiveMQTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class ReattachTest extends ActiveMQTestBase {

   private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

   private static final SimpleString ADDRESS = SimpleString.of("FailoverTestAddress");
   private ActiveMQServer server;
   private ServerLocator locator;

   /**
    * Test failure on connection, but server is still up so should immediately reconnect
    */
   @Test
   public void testImmediateReattach() throws Exception {
      final long retryInterval = 50;

      final double retryMultiplier = 1d;

      final int reconnectAttempts = 10;

      locator.setRetryInterval(retryInterval).setRetryIntervalMultiplier(retryMultiplier).setReconnectAttempts(reconnectAttempts).setConfirmationWindowSize(1024 * 1024);

      ClientSessionFactoryInternal sf = (ClientSessionFactoryInternal) createSessionFactory(locator);

      ClientSession session = sf.createSession(false, true, true);

      session.createQueue(QueueConfiguration.of(ReattachTest.ADDRESS).setDurable(false));

      final int numIterations = 10;

      for (int j = 0; j < numIterations; j++) {
         ClientProducer producer = session.createProducer(ReattachTest.ADDRESS);

         final int numMessages = 100;

         for (int i = 0; i < numMessages; i++) {
            ClientMessage message = session.createMessage(ActiveMQTextMessage.TYPE, false, 0, System.currentTimeMillis(), (byte) 1);
            message.putIntProperty(SimpleString.of("count"), i);
            message.getBodyBuffer().writeString("aardvarks");
            producer.send(message);
         }

         ClientConsumer consumer = session.createConsumer(ReattachTest.ADDRESS);

         RemotingConnection conn = ((ClientSessionInternal) session).getConnection();

         conn.fail(new ActiveMQNotConnectedException());

         session.start();

         for (int i = 0; i < numMessages; i++) {
            ClientMessage message = consumer.receive(500);

            assertNotNull(message);

            assertEquals("aardvarks", message.getBodyBuffer().readString());

            assertEquals(i, message.getObjectProperty(SimpleString.of("count")));

            message.acknowledge();
         }

         ClientMessage message = consumer.receiveImmediate();

         assertNull(message);

         producer.close();

         consumer.close();
      }

      session.close();

      sf.close();
   }

   @Test
   public void testReattachTransferConnectionOnSession() throws Exception {
      final long retryInterval = 50;
      final double retryMultiplier = 1d;
      final int reconnectAttempts = 10;

      locator.setRetryInterval(retryInterval).setRetryIntervalMultiplier(retryMultiplier).setReconnectAttempts(reconnectAttempts).setConfirmationWindowSize(1024 * 1024);
      ClientSessionFactoryInternal sf = (ClientSessionFactoryInternal) createSessionFactory(locator);
      ClientSession session = sf.createSession(false, true, true);

      // there's only one session on the broker
      Object originalConnectionID = ((ServerSession)server.getSessions().toArray()[0]).getConnectionID();

      // trigger re-attach
      ((ClientSessionInternal) session).getConnection().fail(new ActiveMQNotConnectedException());

      session.start();

      assertNotEquals(((ServerSession) server.getSessions().toArray()[0]).getConnectionID(), originalConnectionID);

      session.close();
      sf.close();
   }

   @Test
   public void testReattachTransferConnectionOnSession2() throws Exception {
      final long retryInterval = 50;
      final double retryMultiplier = 1d;
      final int reconnectAttempts = 10;

      locator.setRetryInterval(retryInterval).setRetryIntervalMultiplier(retryMultiplier).setReconnectAttempts(reconnectAttempts).setConfirmationWindowSize(1024 * 1024);
      ClientSessionFactoryInternal sf = (ClientSessionFactoryInternal) createSessionFactory(locator);
      ClientSession session = sf.createSession(false, true, true);
      ClientSession secondSession = sf.createSession(false, true, true);

      // there's only one connection on the broker
      Object originalConnectionID = ((ServerSession) server.getSessions().toArray()[0]).getConnectionID();
      RemotingConnection oldConnection = ((ServerSession) server.getSessions().toArray()[0]).getRemotingConnection();

      // ensure sessions are set as failure listeners on old connection
      Set<ServerSession> originalServerSessions = server.getSessions();
      assertTrue(oldConnection.getFailureListeners().containsAll(originalServerSessions));

      // trigger re-attach
      ((ClientSessionInternal) session).getConnection().fail(new ActiveMQNotConnectedException());

      session.start();
      secondSession.start();

      assertNotEquals(((ServerSession) server.getSessions().toArray()[0]).getConnectionID(), originalConnectionID);

      // ensure sessions were removed as failure listeners of old connection and are now failure listeners of new connection
      assertTrue(originalServerSessions.stream().noneMatch(oldConnection.getFailureListeners()::contains));
      RemotingConnection newConnection = ((ServerSession) server.getSessions().toArray()[0]).getRemotingConnection();
      assertTrue(newConnection.getFailureListeners().containsAll(originalServerSessions));

      session.close();
      secondSession.close();
      sf.close();
   }

   /**
    * Test failure on connection, but server is still up so should immediately reconnect
    */
   @Test
   public void testOverflowCredits() throws Exception {
      final long retryInterval = 500;

      final double retryMultiplier = 1d;

      final int reconnectAttempts = 1;

      locator.setRetryInterval(retryInterval).setRetryIntervalMultiplier(retryMultiplier).setReconnectAttempts(reconnectAttempts).setConfirmationWindowSize(1024 * 1024).setProducerWindowSize(1000);

      final AtomicInteger count = new AtomicInteger(0);

      Interceptor intercept = (packet, connection) -> {
         if (packet instanceof SessionProducerCreditsMessage credit) {

            if (count.incrementAndGet() == 2) {
               connection.fail(new ActiveMQException(ActiveMQExceptionType.UNSUPPORTED_PACKET, "bye"));
               return false;
            }
         }
         return true;
      };

      locator.addIncomingInterceptor(intercept);

      ClientSessionFactoryInternal sf = (ClientSessionFactoryInternal) createSessionFactory(locator);

      ClientSession session = sf.createSession(false, true, true);

      session.createQueue(QueueConfiguration.of(ReattachTest.ADDRESS).setDurable(false));

      ClientProducer producer = session.createProducer(ReattachTest.ADDRESS);

      final int numMessages = 10;

      for (int i = 0; i < numMessages; i++) {
         ClientMessage message = session.createMessage(ActiveMQTextMessage.TYPE, false, 0, System.currentTimeMillis(), (byte) 1);
         message.putIntProperty(SimpleString.of("count"), i);
         message.getBodyBuffer().writeBytes(new byte[5000]);
         producer.send(message);
      }

      session.close();

      sf.close();
   }

   /*
    * Test failure on connection, simulate failure to create connection for a while, then
    * allow connection to be recreated
    */
   @Test
   public void testDelayedReattach() throws Exception {
      final long retryInterval = 50;

      final double retryMultiplier = 1d;

      final int reconnectAttempts = 60;

      locator.setRetryInterval(retryInterval).setRetryIntervalMultiplier(retryMultiplier).setReconnectAttempts(reconnectAttempts).setConfirmationWindowSize(1024 * 1024);

      ClientSessionFactoryInternal sf = (ClientSessionFactoryInternal) createSessionFactory(locator);

      ClientSession session = sf.createSession(false, true, true);

      session.createQueue(QueueConfiguration.of(ReattachTest.ADDRESS).setDurable(false));

      ClientProducer producer = session.createProducer(ReattachTest.ADDRESS);

      final int numMessages = 1000;

      for (int i = 0; i < numMessages; i++) {
         ClientMessage message = session.createMessage(ActiveMQTextMessage.TYPE, false, 0, System.currentTimeMillis(), (byte) 1);
         message.putIntProperty(SimpleString.of("count"), i);
         message.getBodyBuffer().writeString("aardvarks");
         producer.send(message);
      }

      ClientConsumer consumer = session.createConsumer(ReattachTest.ADDRESS);

      InVMConnector.failOnCreateConnection = true;

      RemotingConnection conn = ((ClientSessionInternal) session).getConnection();

      Thread t = new Thread(() -> {
         try {
            Thread.sleep(retryInterval * 3);
         } catch (InterruptedException ignore) {
         }

         InVMConnector.failOnCreateConnection = false;
      });

      t.start();

      conn.fail(new ActiveMQNotConnectedException());

      session.start();

      for (int i = 0; i < numMessages; i++) {
         ClientMessage message = consumer.receive(500);

         assertNotNull(message);

         assertEquals("aardvarks", message.getBodyBuffer().readString());

         assertEquals(i, message.getIntProperty("count").intValue());

         message.acknowledge();
      }

      ClientMessage message = consumer.receiveImmediate();

      assertNull(message);

      session.close();

      sf.close();

      t.join();
   }

   // Test an async (e.g. pinger) failure coming in while a connection manager is already reconnecting
   @Test
   public void testAsyncFailureWhileReattaching() throws Exception {
      final long retryInterval = 50;

      final double retryMultiplier = 1d;

      final int reconnectAttempts = 60;

      final long asyncFailDelay = 200;

      locator.setRetryInterval(retryInterval).setRetryIntervalMultiplier(retryMultiplier).setReconnectAttempts(reconnectAttempts).setConfirmationWindowSize(1024 * 1024);

      ClientSessionFactoryInternal sf = (ClientSessionFactoryInternal) createSessionFactory(locator);

      ClientSession session = sf.createSession(false, true, true);

      ClientSession session2 = sf.createSession(false, true, true);

      class MyFailureListener implements SessionFailureListener {

         volatile boolean failed;

         @Override
         public void connectionFailed(final ActiveMQException me, boolean failedOver) {
            failed = true;
         }

         @Override
         public void connectionFailed(final ActiveMQException me, boolean failedOver, String scaleDownTargetNodeID) {
            connectionFailed(me, failedOver);
         }

         @Override
         public void beforeReconnect(final ActiveMQException exception) {
         }
      }

      MyFailureListener listener = new MyFailureListener();

      session2.addFailureListener(listener);

      session.createQueue(QueueConfiguration.of(ReattachTest.ADDRESS).setDurable(false));

      ClientProducer producer = session.createProducer(ReattachTest.ADDRESS);

      final int numMessages = 1000;

      for (int i = 0; i < numMessages; i++) {
         ClientMessage message = session.createMessage(ActiveMQTextMessage.TYPE, false, 0, System.currentTimeMillis(), (byte) 1);
         message.putIntProperty(SimpleString.of("count"), i);
         message.getBodyBuffer().writeString("aardvarks");
         producer.send(message);
      }

      ClientConsumer consumer = session.createConsumer(ReattachTest.ADDRESS);

      InVMConnector.numberOfFailures = 10;
      InVMConnector.failOnCreateConnection = true;

      final RemotingConnection conn = ((ClientSessionInternal) session).getConnection();

      final RemotingConnection conn2 = ((ClientSessionInternal) session2).getConnection();

      Thread t = new Thread(() -> {
         try {
            Thread.sleep(asyncFailDelay);
         } catch (InterruptedException ignore) {
         }

         conn2.fail(new ActiveMQNotConnectedException("Did not receive pong from server"));
      });

      t.start();

      conn.fail(new ActiveMQNotConnectedException());

      assertTrue(listener.failed);

      session.start();

      for (int i = 0; i < numMessages; i++) {
         ClientMessage message = consumer.receive(500);

         assertNotNull(message);

         assertEquals("aardvarks", message.getBodyBuffer().readString());

         assertEquals(i, message.getIntProperty("count").intValue());

         message.acknowledge();
      }

      ClientMessage message = consumer.receiveImmediate();

      assertNull(message);

      session.close();

      session2.close();

      sf.close();

      t.join();
   }

   @Test
   public void testReattachAttemptsFailsToReconnect() throws Exception {
      final long retryInterval = 50;

      final double retryMultiplier = 1d;

      final int reconnectAttempts = 3;

      locator.setRetryInterval(retryInterval).setRetryIntervalMultiplier(retryMultiplier).setReconnectAttempts(reconnectAttempts).setConfirmationWindowSize(1024 * 1024);

      ClientSessionFactoryInternal sf = (ClientSessionFactoryInternal) createSessionFactory(locator);

      ClientSession session = sf.createSession(false, true, true);

      session.createQueue(QueueConfiguration.of(ReattachTest.ADDRESS).setDurable(false));

      ClientProducer producer = session.createProducer(ReattachTest.ADDRESS);

      final int numMessages = 1000;

      for (int i = 0; i < numMessages; i++) {
         ClientMessage message = session.createMessage(ActiveMQTextMessage.TYPE, false, 0, System.currentTimeMillis(), (byte) 1);
         message.putIntProperty(SimpleString.of("count"), i);
         message.getBodyBuffer().writeString("aardvarks");
         producer.send(message);
      }

      session.createConsumer(ReattachTest.ADDRESS);

      InVMConnector.failOnCreateConnection = true;

      RemotingConnection conn = ((ClientSessionInternal) session).getConnection();

      // Sleep for longer than max retries so should fail to reconnect

      Thread t = new Thread(() -> {
         try {
            Thread.sleep(retryInterval * (reconnectAttempts + 1));
         } catch (InterruptedException ignore) {
         }

         InVMConnector.failOnCreateConnection = false;
      });

      t.start();

      conn.fail(new ActiveMQNotConnectedException());

      // Should throw exception since didn't reconnect

      try {
         session.start();

         fail("Should throw exception");
      } catch (ActiveMQObjectClosedException oce) {
         //ok
      } catch (ActiveMQException e) {
         fail("Invalid Exception type:" + e.getType());
      }

      session.close();

      sf.close();

      t.join();
   }

   @Test
   public void testCreateSessionFailAfterSendSeveralThreads() throws Throwable {

      Timer timer = new Timer();
      ClientSession session = null;

      try {

         final long retryInterval = 100;

         final double retryMultiplier = 1d;

         final int reconnectAttempts = 300;

         locator.setRetryInterval(retryInterval).setRetryIntervalMultiplier(retryMultiplier).setReconnectAttempts(reconnectAttempts).setConfirmationWindowSize(1024 * 1024);

         final ClientSessionFactoryInternal sf = (ClientSessionFactoryInternal) createSessionFactory(locator);

         session = sf.createSession();

         final RemotingConnection connFailure = ((ClientSessionInternal) session).getConnection();

         int numberOfThreads = 100;
         final int numberOfSessionsToCreate = 10;

         final CountDownLatch alignLatch = new CountDownLatch(numberOfThreads);
         final CountDownLatch startFlag = new CountDownLatch(1);

         class CreateSessionThread extends Thread {

            Throwable failure;

            @Override
            public void run() {
               try {
                  alignLatch.countDown();
                  startFlag.await();
                  for (int i = 0; i < numberOfSessionsToCreate; i++) {
                     Thread.yield();
                     ClientSession session = sf.createSession(false, true, true);

                     session.close();
                  }
               } catch (Throwable e) {
                  e.printStackTrace();
                  failure = e;
               }
            }
         }

         CreateSessionThread[] threads = new CreateSessionThread[numberOfThreads];
         for (int i = 0; i < numberOfThreads; i++) {
            threads[i] = new CreateSessionThread();
            threads[i].start();
         }

         waitForLatch(alignLatch);

         timer.schedule(new TimerTask() {
            @Override
            public void run() {
               try {
                  connFailure.fail(new ActiveMQNotConnectedException());
               } catch (Exception e) {
                  logger.warn("Error on the timer {}", e);
               }
            }

         }, 10, 10);

         startFlag.countDown();

         Throwable failure = null;

         for (CreateSessionThread thread : threads) {
            thread.join();
            if (thread.failure != null) {
               System.out.println("Thread " + thread.getName() + " failed - " + thread.failure);
               failure = thread.failure;
            }
         }

         if (failure != null) {
            throw failure;
         }

         sf.close();

      } finally {
         timer.cancel();

         if (session != null) {
            session.close();
         }
      }
   }

   @Test
   public void testCreateSessionFailBeforeSendSeveralThreads() throws Throwable {
      final long retryInterval = 50;

      final double retryMultiplier = 1d;

      final int reconnectAttempts = 60;

      locator.setRetryInterval(retryInterval).setRetryIntervalMultiplier(retryMultiplier).setReconnectAttempts(reconnectAttempts).setConfirmationWindowSize(1024 * 1024);

      final ClientSessionFactoryInternal sf = (ClientSessionFactoryInternal) createSessionFactory(locator);

      InVMConnector.failOnCreateConnection = true;

      int numberOfThreads = 100;

      final CountDownLatch alignLatch = new CountDownLatch(numberOfThreads);
      final CountDownLatch startFlag = new CountDownLatch(1);

      class CreateSessionThread extends Thread {

         Throwable failure;

         @Override
         public void run() {
            try {
               alignLatch.countDown();
               startFlag.await();
               ClientSession session = sf.createSession(false, true, true);

               session.close();
            } catch (Throwable e) {
               e.printStackTrace();
               failure = e;
            }
         }
      }

      CreateSessionThread[] threads = new CreateSessionThread[numberOfThreads];
      for (int i = 0; i < numberOfThreads; i++) {
         threads[i] = new CreateSessionThread();
         threads[i].start();
      }

      // Sleep 3 times retryInterval, so it should at least have 3 retries

      Thread t = new Thread(() -> {
         try {
            Thread.sleep(retryInterval * 3);
         } catch (InterruptedException ignore) {
         }

         InVMConnector.failOnCreateConnection = false;
      });

      waitForLatch(alignLatch);

      t.start();

      startFlag.countDown();

      Throwable failure = null;

      for (CreateSessionThread thread : threads) {
         thread.join();
         if (thread.failure != null) {
            System.out.println("Thread " + thread.getName() + " failed - " + thread.failure);
            failure = thread.failure;
         }
      }

      if (failure != null) {
         throw failure;
      }

      sf.close();

      t.join();
   }

   @Test
   public void testCreateQueue() throws Exception {
      final long retryInterval = 100;

      final double retryMultiplier = 1d;

      final int reconnectAttempts = 300;

      locator.setRetryInterval(retryInterval).setRetryIntervalMultiplier(retryMultiplier).setReconnectAttempts(reconnectAttempts).setConfirmationWindowSize(1024 * 1024);

      ClientSessionFactoryInternal sf = (ClientSessionFactoryInternal) createSessionFactory(locator);

      ClientSession session = sf.createSession(false, true, true);

      // Sleep 3 times retryInterval, so it should at least have 3 retries

      RemotingConnection conn = ((ClientSessionInternal) session).getConnection();

      InVMConnector.failOnCreateConnection = false;

      conn.fail(new ActiveMQNotConnectedException());

      Thread t = new Thread(() -> {
         try {
            Thread.sleep(retryInterval * 3);
         } catch (InterruptedException ignore) {
         }

         InVMConnector.failOnCreateConnection = false;
      });

      t.start();

      for (int i = 0; i < 10; i++) {
         session.createQueue(QueueConfiguration.of("queue" + i).setAddress("address").setRoutingType(RoutingType.ANYCAST));
      }

      //
      // InVMConnector.failOnCreateConnection = true;

      //
      // //Should throw exception since didn't reconnect
      //
      // try
      // {
      // session.start();
      //
      // fail("Should throw exception");
      // }
      // catch (ActiveMQException e)
      // {
      // assertEquals(ActiveMQException.OBJECT_CLOSED, e.getCode());
      // }

      session.close();

      sf.close();

      t.join();
   }

   @Test
   public void testReattachAttemptsSucceedsInReconnecting() throws Exception {
      final long retryInterval = 50;

      final double retryMultiplier = 1d;

      final int reconnectAttempts = 10;

      locator.setRetryInterval(retryInterval).setRetryIntervalMultiplier(retryMultiplier).setReconnectAttempts(reconnectAttempts).setConfirmationWindowSize(1024 * 1024);

      ClientSessionFactoryInternal sf = (ClientSessionFactoryInternal) createSessionFactory(locator);

      ClientSession session = sf.createSession(false, true, true);

      session.createQueue(QueueConfiguration.of(ReattachTest.ADDRESS).setDurable(false));

      ClientProducer producer = session.createProducer(ReattachTest.ADDRESS);

      final int numMessages = 1000;

      for (int i = 0; i < numMessages; i++) {
         ClientMessage message = session.createMessage(ActiveMQTextMessage.TYPE, false, 0, System.currentTimeMillis(), (byte) 1);
         message.putIntProperty(SimpleString.of("count"), i);
         message.getBodyBuffer().writeString("aardvarks");
         producer.send(message);
      }

      ClientConsumer consumer = session.createConsumer(ReattachTest.ADDRESS);

      InVMConnector.failOnCreateConnection = true;
      InVMConnector.numberOfFailures = reconnectAttempts - 1;

      RemotingConnection conn = ((ClientSessionInternal) session).getConnection();

      conn.fail(new ActiveMQNotConnectedException());

      session.start();

      for (int i = 0; i < numMessages; i++) {
         ClientMessage message = consumer.receive(500);

         assertNotNull(message);

         assertEquals("aardvarks", message.getBodyBuffer().readString());

         assertEquals(i, message.getIntProperty("count").intValue());

         message.acknowledge();
      }

      ClientMessage message = consumer.receiveImmediate();

      assertNull(message);

      session.close();

      sf.close();
   }

   @Test
   public void testRetryInterval() throws Exception {
      final long retryInterval = 500;

      final double retryMultiplier = 1d;

      final int reconnectAttempts = 60;

      locator.setRetryInterval(retryInterval).setRetryIntervalMultiplier(retryMultiplier).setReconnectAttempts(reconnectAttempts).setConfirmationWindowSize(1024 * 1024);

      ClientSessionFactoryInternal sf = (ClientSessionFactoryInternal) createSessionFactory(locator);

      ClientSession session = sf.createSession(false, true, true);

      session.createQueue(QueueConfiguration.of(ReattachTest.ADDRESS).setDurable(false));

      ClientProducer producer = session.createProducer(ReattachTest.ADDRESS);

      final int numMessages = 1000;

      for (int i = 0; i < numMessages; i++) {
         ClientMessage message = session.createMessage(ActiveMQTextMessage.TYPE, false, 0, System.currentTimeMillis(), (byte) 1);
         message.putIntProperty(SimpleString.of("count"), i);
         message.getBodyBuffer().writeString("aardvarks");
         producer.send(message);
      }

      ClientConsumer consumer = session.createConsumer(ReattachTest.ADDRESS);

      InVMConnector.failOnCreateConnection = true;

      RemotingConnection conn = ((ClientSessionInternal) session).getConnection();

      long start = System.currentTimeMillis();

      Thread t = new Thread(() -> {
         try {
            Thread.sleep(retryInterval / 2);
         } catch (InterruptedException ignore) {
         }
         InVMConnector.failOnCreateConnection = false;
      });

      t.start();

      conn.fail(new ActiveMQNotConnectedException());

      session.start();

      for (int i = 0; i < numMessages; i++) {
         ClientMessage message = consumer.receive(500);

         assertNotNull(message);

         assertEquals("aardvarks", message.getBodyBuffer().readString());

         assertEquals(i, message.getIntProperty("count").intValue());

         message.acknowledge();
      }

      ClientMessage message = consumer.receiveImmediate();

      assertNull(message);

      long end = System.currentTimeMillis();

      assertTrue(end - start >= retryInterval);

      session.close();

      sf.close();

      t.join();
   }

   @Test
   public void testExponentialBackoff() throws Exception {
      final long retryInterval = 50;

      final double retryMultiplier = 2d;

      final int reconnectAttempts = 60;

      locator.setRetryInterval(retryInterval).setRetryIntervalMultiplier(retryMultiplier).setReconnectAttempts(reconnectAttempts).setConfirmationWindowSize(1024 * 1024);

      ClientSessionFactoryInternal sf = (ClientSessionFactoryInternal) createSessionFactory(locator);

      ClientSession session = sf.createSession(false, true, true);

      session.createQueue(QueueConfiguration.of(ReattachTest.ADDRESS).setDurable(false));

      ClientProducer producer = session.createProducer(ReattachTest.ADDRESS);

      final int numMessages = 1000;

      for (int i = 0; i < numMessages; i++) {
         ClientMessage message = session.createMessage(ActiveMQTextMessage.TYPE, false, 0, System.currentTimeMillis(), (byte) 1);
         message.putIntProperty(SimpleString.of("count"), i);
         message.getBodyBuffer().writeString("aardvarks");
         producer.send(message);
      }

      ClientConsumer consumer = session.createConsumer(ReattachTest.ADDRESS);

      InVMConnector.failOnCreateConnection = true;
      InVMConnector.numberOfFailures = 3;

      RemotingConnection conn = ((ClientSessionInternal) session).getConnection();

      long start = System.currentTimeMillis();

      conn.fail(new ActiveMQNotConnectedException());

      session.start();

      for (int i = 0; i < numMessages; i++) {
         ClientMessage message = consumer.receive(500);

         assertNotNull(message);

         assertEquals("aardvarks", message.getBodyBuffer().readString());

         assertEquals(i, message.getObjectProperty(SimpleString.of("count")));

         message.acknowledge();
      }

      ClientMessage message = consumer.receiveImmediate();

      assertNull(message);

      long end = System.currentTimeMillis();

      double wait = retryInterval + retryMultiplier * retryInterval + retryMultiplier * retryMultiplier * retryInterval;

      assertTrue(end - start >= wait);

      session.close();

      sf.close();
   }

   @Test
   public void testExponentialBackoffMaxRetryInterval() throws Exception {
      final long retryInterval = 50;

      final double retryMultiplier = 2d;

      final int reconnectAttempts = 60;

      final long maxRetryInterval = 100;

      locator.setRetryInterval(retryInterval).setRetryIntervalMultiplier(retryMultiplier).setReconnectAttempts(reconnectAttempts).setMaxRetryInterval(maxRetryInterval).setConfirmationWindowSize(1024 * 1024);

      ClientSessionFactoryInternal sf = (ClientSessionFactoryInternal) createSessionFactory(locator);

      ClientSession session = sf.createSession(false, true, true);

      session.createQueue(QueueConfiguration.of(ReattachTest.ADDRESS).setDurable(false));

      ClientProducer producer = session.createProducer(ReattachTest.ADDRESS);

      final int numMessages = 1000;

      for (int i = 0; i < numMessages; i++) {
         ClientMessage message = session.createMessage(ActiveMQTextMessage.TYPE, false, 0, System.currentTimeMillis(), (byte) 1);
         message.putIntProperty(SimpleString.of("count"), i);
         message.getBodyBuffer().writeString("aardvarks");
         producer.send(message);
      }

      ClientConsumer consumer = session.createConsumer(ReattachTest.ADDRESS);

      InVMConnector.failOnCreateConnection = true;
      InVMConnector.numberOfFailures = 3;

      RemotingConnection conn = ((ClientSessionInternal) session).getConnection();

      long start = System.currentTimeMillis();

      conn.fail(new ActiveMQNotConnectedException());

      session.start();

      for (int i = 0; i < numMessages; i++) {
         ClientMessage message = consumer.receive(500);

         assertNotNull(message);

         assertEquals("aardvarks", message.getBodyBuffer().readString());

         assertEquals(i, message.getIntProperty("count").intValue());

         message.acknowledge();
      }

      ClientMessage message = consumer.receiveImmediate();

      assertNull(message);

      long end = System.currentTimeMillis();

      double wait = retryInterval + retryMultiplier * 2 * retryInterval + retryMultiplier;

      assertTrue(end - start >= wait);

      assertTrue(end - start < wait + 500);

      session.close();

      sf.close();
   }



   @Override
   @BeforeEach
   public void setUp() throws Exception {
      super.setUp();

      server = createServer(false, false);

      server.start();

      locator = createInVMNonHALocator();
   }

   @Override
   @AfterEach
   public void tearDown() throws Exception {
      InVMConnector.resetFailures();

      super.tearDown();
   }


}
