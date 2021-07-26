/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.activemq.artemis.tests.integration.amqp.connect;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.vertx.core.Vertx;
import io.vertx.proton.ProtonConnection;
import io.vertx.proton.ProtonServerOptions;
import org.apache.activemq.artemis.core.config.amqpBrokerConnectivity.AMQPBrokerConnectConfiguration;
import org.apache.activemq.artemis.core.config.amqpBrokerConnectivity.AMQPBrokerConnectionAddressType;
import org.apache.activemq.artemis.core.config.amqpBrokerConnectivity.AMQPBrokerConnectionElement;
import org.apache.activemq.artemis.core.config.amqpBrokerConnectivity.AMQPMirrorBrokerConnectionElement;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.logs.AssertionLoggerHandler;
import org.apache.activemq.artemis.protocol.amqp.broker.ActiveMQProtonRemotingConnection;
import org.apache.activemq.artemis.protocol.amqp.broker.ProtonProtocolManager;
import org.apache.activemq.artemis.protocol.amqp.connect.AMQPBrokerConnection;
import org.apache.activemq.artemis.protocol.amqp.connect.mirror.AMQPMirrorControllerSource;
import org.apache.activemq.artemis.tests.integration.amqp.AmqpClientTestSupport;
import org.apache.activemq.artemis.tests.util.CFUtil;
import org.apache.activemq.artemis.tests.util.Wait;
import org.apache.activemq.artemis.utils.collections.ConcurrentHashSet;
import org.apache.qpid.proton.amqp.Symbol;
import org.apache.qpid.proton.amqp.transport.AmqpError;
import org.apache.qpid.proton.amqp.transport.ErrorCondition;
import org.apache.qpid.proton.amqp.transport.Target;
import org.apache.qpid.proton.engine.Link;
import org.apache.qpid.proton.engine.Receiver;
import org.apache.qpid.proton.engine.impl.ConnectionImpl;
import org.junit.Assert;
import org.junit.Test;

import static java.util.EnumSet.of;
import static org.apache.qpid.proton.engine.EndpointState.ACTIVE;

/**
 * This test will make sure the Broker connection will react accordingly to a few misconfigs and possible errors on the network of brokers and eventually qipd-dispatch.
 */
public class ValidateAMQPErrorsTest extends AmqpClientTestSupport {

   protected static final int AMQP_PORT_2 = 5673;

   @Override
   protected ActiveMQServer createServer() throws Exception {
      return createServer(AMQP_PORT, false);
   }

   /**
    * Connecting to itself should issue an error.
    * and the max retry should still be counted, not just keep connecting forever.
    */
   @Test
   public void testConnectItself() throws Exception {
      try {
         AssertionLoggerHandler.startCapture();

         AMQPBrokerConnectConfiguration amqpConnection = new AMQPBrokerConnectConfiguration("test", "tcp://localhost:" + AMQP_PORT).setReconnectAttempts(10).setRetryInterval(1);
         amqpConnection.addElement(new AMQPMirrorBrokerConnectionElement());
         server.getConfiguration().addAMQPConnection(amqpConnection);

         server.start();

         Assert.assertEquals(1, server.getBrokerConnections().size());
         server.getBrokerConnections().forEach((t) -> Wait.assertFalse(t::isStarted));
         Wait.assertTrue(() -> AssertionLoggerHandler.findText("AMQ111001")); // max retry
         AssertionLoggerHandler.clear();
         Thread.sleep(100);
         Assert.assertFalse(AssertionLoggerHandler.findText("AMQ111002")); // there shouldn't be a retry after the last failure
         Assert.assertFalse(AssertionLoggerHandler.findText("AMQ111003")); // there shouldn't be a retry after the last failure
      } finally {
         AssertionLoggerHandler.stopCapture();
      }
   }

   @Test
   public void testCloseLinkOnMirror() throws Exception {
      try {
         AssertionLoggerHandler.startCapture();

         ActiveMQServer server2 = createServer(AMQP_PORT_2, false);

         AMQPBrokerConnectConfiguration amqpConnection = new AMQPBrokerConnectConfiguration("test", "tcp://localhost:" + AMQP_PORT_2).setReconnectAttempts(-1).setRetryInterval(10);
         amqpConnection.addElement(new AMQPMirrorBrokerConnectionElement());
         server.getConfiguration().addAMQPConnection(amqpConnection);

         server.start();
         Assert.assertEquals(1, server.getBrokerConnections().size());
         Wait.assertTrue(() -> AssertionLoggerHandler.findText("AMQ111002"));
         server.getBrokerConnections().forEach((t) -> Wait.assertTrue(() -> ((AMQPBrokerConnection) t).isConnecting()));

         server2.start();

         server.getBrokerConnections().forEach((t) -> Wait.assertFalse(() -> ((AMQPBrokerConnection) t).isConnecting()));

         createAddressAndQueues(server);

         Wait.assertTrue(() -> server2.locateQueue(getQueueName()) != null);

         Wait.assertEquals(1, server2.getRemotingService()::getConnectionCount);
         server2.getRemotingService().getConnections().forEach((t) -> {
            try {
               ActiveMQProtonRemotingConnection connection = (ActiveMQProtonRemotingConnection) t;
               ConnectionImpl protonConnection = (ConnectionImpl) connection.getAmqpConnection().getHandler().getConnection();
               Wait.waitFor(() -> protonConnection.linkHead(of(ACTIVE), of(ACTIVE)) != null);
               connection.getAmqpConnection().runNow(() -> {
                  Receiver receiver = (Receiver) protonConnection.linkHead(of(ACTIVE), of(ACTIVE));
                  receiver.close();
                  connection.flush();
               });
            } catch (Exception e) {
               e.printStackTrace();
            }
         });

         ConnectionFactory cf1 = CFUtil.createConnectionFactory("AMQP", "tcp://localhost:" + AMQP_PORT);
         ConnectionFactory cf2 = CFUtil.createConnectionFactory("AMQP", "tcp://localhost:" + AMQP_PORT_2);

         try (Connection connection = cf1.createConnection()) {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageProducer producer = session.createProducer(session.createQueue(getQueueName()));
            for (int i = 0; i < 10; i++) {
               producer.send(session.createTextMessage("message " + i));
            }
         }

         // messages should still flow after a disconnect on the link
         // the server should reconnect as if it was a failure
         try (Connection connection = cf2.createConnection()) {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageConsumer consumer = session.createConsumer(session.createQueue(getQueueName()));
            connection.start();
            for (int i = 0; i < 10; i++) {
               Assert.assertEquals("message " + i, ((TextMessage) consumer.receive(5000)).getText());
            }
         }

      } finally {
         AssertionLoggerHandler.stopCapture();
      }
   }

   @Test
   public void testCloseLinkOnSender() throws Exception {
      testCloseLink(true);
   }

   @Test
   public void testCloseLinkOnReceiver() throws Exception {
      testCloseLink(false);
   }

   public void testCloseLink(boolean isSender) throws Exception {
      try {
         AssertionLoggerHandler.startCapture();

         ActiveMQServer server2 = createServer(AMQP_PORT_2, false);

         if (isSender) {
            AMQPBrokerConnectConfiguration amqpConnection = new AMQPBrokerConnectConfiguration("test", "tcp://localhost:" + AMQP_PORT_2).setReconnectAttempts(-1).setRetryInterval(10);
            amqpConnection.addElement(new AMQPBrokerConnectionElement().setMatchAddress(getQueueName()).setType(AMQPBrokerConnectionAddressType.SENDER));
            server.getConfiguration().addAMQPConnection(amqpConnection);
         } else {
            AMQPBrokerConnectConfiguration amqpConnection = new AMQPBrokerConnectConfiguration("test", "tcp://localhost:" + AMQP_PORT).setReconnectAttempts(-1).setRetryInterval(10);
            amqpConnection.addElement(new AMQPBrokerConnectionElement().setMatchAddress(getQueueName()).setType(AMQPBrokerConnectionAddressType.RECEIVER));
            server2.getConfiguration().addAMQPConnection(amqpConnection);
         }

         if (isSender) {
            server.start();
            Assert.assertEquals(1, server.getBrokerConnections().size());
         } else {
            server2.start();
            Assert.assertEquals(1, server2.getBrokerConnections().size());
         }
         Wait.assertTrue(() -> AssertionLoggerHandler.findText("AMQ111002"));
         server.getBrokerConnections().forEach((t) -> Wait.assertTrue(() -> ((AMQPBrokerConnection) t).isConnecting()));

         if (isSender) {
            server2.start();
         } else {
            server.start();
         }

         server.getBrokerConnections().forEach((t) -> Wait.assertFalse(() -> ((AMQPBrokerConnection) t).isConnecting()));

         createAddressAndQueues(server);
         createAddressAndQueues(server2);

         Wait.assertTrue(() -> server.locateQueue(getQueueName()) != null);
         Wait.assertTrue(() -> server2.locateQueue(getQueueName()) != null);

         Thread.sleep(1000);

         ActiveMQServer serverReceivingConnections = isSender ? server2 : server;
         Wait.assertEquals(1, serverReceivingConnections.getRemotingService()::getConnectionCount);
         serverReceivingConnections.getRemotingService().getConnections().forEach((t) -> {
            try {
               ActiveMQProtonRemotingConnection connection = (ActiveMQProtonRemotingConnection) t;
               ConnectionImpl protonConnection = (ConnectionImpl) connection.getAmqpConnection().getHandler().getConnection();
               Wait.waitFor(() -> protonConnection.linkHead(of(ACTIVE), of(ACTIVE)) != null);
               connection.getAmqpConnection().runNow(() -> {
                  Link theLink = protonConnection.linkHead(of(ACTIVE), of(ACTIVE));
                  theLink.close();
                  connection.flush();
               });
            } catch (Exception e) {
               e.printStackTrace();
            }
         });

         ConnectionFactory cf1 = CFUtil.createConnectionFactory("AMQP", "tcp://localhost:" + AMQP_PORT);
         ConnectionFactory cf2 = CFUtil.createConnectionFactory("AMQP", "tcp://localhost:" + AMQP_PORT_2);

         try (Connection connection = cf1.createConnection()) {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageProducer producer = session.createProducer(session.createQueue(getQueueName()));
            for (int i = 0; i < 10; i++) {
               producer.send(session.createTextMessage("message " + i));
            }
         }

         // messages should still flow after a disconnect on the link
         // the server should reconnect as if it was a failure
         try (Connection connection = cf2.createConnection()) {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageConsumer consumer = session.createConsumer(session.createQueue(getQueueName()));
            connection.start();
            for (int i = 0; i < 10; i++) {
               Assert.assertEquals("message " + i, ((TextMessage) consumer.receive(5000)).getText());
            }
         }

      } finally {
         AssertionLoggerHandler.stopCapture();
      }
   }

   @Test
   public void testTimeoutOnSenderOpen() throws Exception {

      Vertx vertx = Vertx.vertx();

      ProtonServerOptions serverOptions = new ProtonServerOptions();

      MockServer mockServer = new MockServer(vertx, serverOptions, null, serverConnection -> {
         serverConnection.openHandler(serverSender -> {
            serverConnection.closeHandler(x -> serverConnection.close());
            serverConnection.open();
         });
         serverConnection.sessionOpenHandler((s) -> {
            s.open();
         });
         serverConnection.senderOpenHandler((x) -> {
            x.open();
         });
         serverConnection.receiverOpenHandler((x) -> {
            //x.open(); // I'm missing the open, so it won't ever connect
         });
      });

      try {
         AssertionLoggerHandler.startCapture();

         AMQPBrokerConnectConfiguration amqpConnection = new AMQPBrokerConnectConfiguration("test", "tcp://localhost:" + mockServer.actualPort() + "?connect-timeout-millis=20").setReconnectAttempts(5).setRetryInterval(10);
         amqpConnection.addElement(new AMQPBrokerConnectionElement().setMatchAddress(getQueueName()).setType(AMQPBrokerConnectionAddressType.SENDER));
         amqpConnection.addElement(new AMQPMirrorBrokerConnectionElement());
         server.getConfiguration().addAMQPConnection(amqpConnection);
         server.start();

         Wait.assertTrue(() -> AssertionLoggerHandler.findText("AMQ111001"));

      } finally {
         AssertionLoggerHandler.stopCapture();
         mockServer.close();
         vertx.close();
      }
   }

   @Test
   public void testReconnectAfterSenderOpenTimeout() throws Exception {

      Vertx vertx = Vertx.vertx();

      ProtonServerOptions serverOptions = new ProtonServerOptions();

      AtomicInteger countOpen = new AtomicInteger(0);
      CyclicBarrier startFlag = new CyclicBarrier(2);
      CountDownLatch blockBeforeOpen = new CountDownLatch(1);
      AtomicInteger disconnects = new AtomicInteger(0);

      ConcurrentHashSet<ProtonConnection> connections = new ConcurrentHashSet<>();

      MockServer mockServer = new MockServer(vertx, serverOptions, null, serverConnection -> {
         serverConnection.disconnectHandler(c -> {
            disconnects.incrementAndGet(); // number of retries
            connections.remove(c);
         });
         serverConnection.openHandler(serverSender -> {
            serverConnection.closeHandler(x -> {
               serverConnection.close();
               connections.remove(serverConnection);
            });
            serverConnection.open();
            connections.add(serverConnection);
         });
         serverConnection.sessionOpenHandler((s) -> {
            s.open();
         });
         serverConnection.senderOpenHandler((x) -> {
            x.open();
         });
         serverConnection.receiverOpenHandler((x) -> {
            if (countOpen.incrementAndGet() > 5) {
               try {
                  startFlag.await(10, TimeUnit.SECONDS);
                  blockBeforeOpen.await(10, TimeUnit.SECONDS);
               } catch (Throwable ignored) {
               }
               HashMap<Symbol, Object> brokerIDProperties = new HashMap<>();
               brokerIDProperties.put(AMQPMirrorControllerSource.BROKER_ID, "fake-id");
               x.setProperties(brokerIDProperties);
               x.setOfferedCapabilities(new Symbol[]{AMQPMirrorControllerSource.MIRROR_CAPABILITY});
               x.open();
            }
         });
      });

      try {
         AssertionLoggerHandler.startCapture();

         AMQPBrokerConnectConfiguration amqpConnection = new AMQPBrokerConnectConfiguration("test", "tcp://localhost:" + mockServer.actualPort() + "?connect-timeout-millis=20").setReconnectAttempts(10).setRetryInterval(10);
         amqpConnection.addElement(new AMQPBrokerConnectionElement().setMatchAddress(getQueueName()).setType(AMQPBrokerConnectionAddressType.SENDER));
         amqpConnection.addElement(new AMQPMirrorBrokerConnectionElement());
         server.getConfiguration().addAMQPConnection(amqpConnection);
         server.start();

         startFlag.await(10, TimeUnit.SECONDS);
         blockBeforeOpen.countDown();

         Wait.assertEquals(5, disconnects::intValue);
         Wait.assertEquals(1, connections::size);

      } finally {
         AssertionLoggerHandler.stopCapture();
         mockServer.close();
         vertx.close();
      }
   }

   @Test
   public void testNoServerMirrorCapability() throws Exception {
      Vertx vertx = Vertx.vertx(); // TODO: ensure this closes, may not if throws before try, move to @Before and @After handling?

      MockServer mockServer = new MockServer(vertx, serverConnection -> {
         serverConnection.openHandler(serverSender -> {
            serverConnection.open();
         });
         serverConnection.sessionOpenHandler((s) -> {
            s.open();
         });
         serverConnection.senderOpenHandler((x) -> {
            x.open();
         });
         serverConnection.receiverOpenHandler((x) -> {
            x.open();
         });
      });

      try {
         AssertionLoggerHandler.startCapture(); //TODO: unused?

         AMQPBrokerConnectConfiguration amqpConnection = new AMQPBrokerConnectConfiguration("test", "tcp://localhost:" + mockServer.actualPort() + "?connect-timeout-millis=2000").setReconnectAttempts(5).setRetryInterval(10);
         amqpConnection.addElement(new AMQPBrokerConnectionElement().setMatchAddress(getQueueName()).setType(AMQPBrokerConnectionAddressType.SENDER)); // Should this be here for a test about mirrors?
         amqpConnection.addElement(new AMQPMirrorBrokerConnectionElement());
         server.getConfiguration().addAMQPConnection(amqpConnection);
         server.start();

         Wait.assertTrue(() -> AssertionLoggerHandler.findText("AMQ111001"));

      } finally {
         AssertionLoggerHandler.stopCapture();
         mockServer.close();
         vertx.close();
      }
   }

   /**
    * Refuse the first mirror link, verify broker handles it and reconnects
    * @throws Exception
    */
   @Test
   public void testReconnectAfterMirrorLinkRefusal() throws Exception {
      Vertx vertx = Vertx.vertx(); // TODO: ensure this closes, may not if throws before try, move to @Before and @After handling?

      List<ProtonConnection> connections = Collections.synchronizedList(new ArrayList<ProtonConnection>());
      List<ProtonConnection> disconnected = Collections.synchronizedList(new ArrayList<ProtonConnection>());
      AtomicInteger refusedLinkMessageCount = new AtomicInteger();
      AtomicInteger linkOpens = new AtomicInteger(0);

      MockServer mockServer = new MockServer(vertx, serverConnection -> {
         serverConnection.disconnectHandler(c -> {
            disconnected.add(serverConnection);
         });

         serverConnection.openHandler(c -> {
            connections.add(serverConnection);
            serverConnection.open();
         });

         serverConnection.closeHandler(c -> {
            serverConnection.close();
            connections.remove(serverConnection);
         });

         serverConnection.sessionOpenHandler(session -> {
            session.open();
         });

         serverConnection.receiverOpenHandler(serverReceiver -> {
            Target remoteTarget = serverReceiver.getRemoteTarget();
            String remoteAddress = remoteTarget == null ? null : remoteTarget.getAddress();
            if (remoteAddress == null || !remoteAddress.startsWith(ProtonProtocolManager.MIRROR_ADDRESS)) {
               //TODO: log address and fact it wasnt as expected, or handle any other addresses used
               return;
            }

            //TODO: verify broker set expected link properties and desired capabilities.


            if (linkOpens.incrementAndGet() != 2) {
               //TODO: START delete between here, shouldnt be needed, once broker fixed to handle link refusal.
               HashMap<Symbol, Object> linkProperties = new HashMap<>();
               linkProperties.put(AMQPMirrorControllerSource.BROKER_ID, "fake-id");

               serverReceiver.setProperties(linkProperties);
               serverReceiver.setOfferedCapabilities(new Symbol[]{AMQPMirrorControllerSource.MIRROR_CAPABILITY});
               //TODO: FINISH delete between here

               serverReceiver.setTarget(null);

               //TODO: START could delete between here
               serverReceiver.setAutoAccept(false); // Dont accept.
               serverReceiver.handler((del, msg) -> {
                  refusedLinkMessageCount.incrementAndGet();
                  System.out.println("Should not have got message on refused link: " + msg);
               });

               //      Adding exagerated delay between 'refusing attach' and following detach, and
               //      didnt set prefetch to 0, so credit will be given during explicit open() (credit wouldnt be given if we
               //      removed this and just let the close() cause the needed attach frame before sending the detach frame).
               //      If removing, get rid of refusedLinkMessageCount and related usage since it then cant happen.
               serverReceiver.open();

               vertx.setTimer(5000, x -> {
                  //TODO: reduce time if keeping, small but 'messages could still be sent' period.
                  //TODO: FINISH could delete between here (remove block but keep lines in it below)

                  serverReceiver.setCondition(new ErrorCondition(AmqpError.ILLEGAL_STATE, "Testing refusal of mirror link for $reasons"));
                  serverReceiver.close();
               });
            } else {
               HashMap<Symbol, Object> linkProperties = new HashMap<>();
               linkProperties.put(AMQPMirrorControllerSource.BROKER_ID, "fake-id");

               serverReceiver.setProperties(linkProperties);
               serverReceiver.setOfferedCapabilities(new Symbol[]{AMQPMirrorControllerSource.MIRROR_CAPABILITY});

               serverReceiver.handler((del, msg) -> {
                  //TODO: log/handle (disable receiver auto-accept if wanting to control dispositions)
                  System.out.println("Got message: " + msg);
               });

               serverReceiver.open();
            }
         });
      });

      try {
         AMQPBrokerConnectConfiguration amqpConnection = new AMQPBrokerConnectConfiguration("test", "tcp://localhost:" + mockServer.actualPort()).setReconnectAttempts(3).setRetryInterval(10);
         amqpConnection.addElement(new AMQPMirrorBrokerConnectionElement());
         server.getConfiguration().addAMQPConnection(amqpConnection);
         server.start();

         Wait.assertEquals(1, disconnected::size, 6000);
         Wait.assertEquals(2, connections::size, 6000);

         assertSame(connections.get(0), disconnected.get(0));
         assertFalse(connections.get(1).isDisconnected());

         assertEquals("Should not have got any message on refused link", 0, refusedLinkMessageCount.get());
      } finally {
         mockServer.close();
         vertx.close();
      }
   }
}
