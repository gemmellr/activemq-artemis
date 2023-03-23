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

package org.apache.activemq.artemis.tests.soak.interrupt;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.MessageProducer;
import javax.jms.Session;
import java.lang.invoke.MethodHandles;
import java.util.concurrent.TimeUnit;

import org.apache.activemq.artemis.api.config.ActiveMQDefaultConfiguration;
import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.api.core.management.ObjectNameBuilder;
import org.apache.activemq.artemis.api.core.management.QueueControl;
import org.apache.activemq.artemis.tests.soak.SoakTestBase;
import org.apache.activemq.artemis.tests.util.CFUtil;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JournalFlushInterruptTest extends SoakTestBase {
   public static final String SERVER_NAME_0 = "interruptjf";
   private static final String JMX_SERVER_HOSTNAME = "localhost";
   private static final int JMX_SERVER_PORT_0 = 1099;
   private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
   static String liveURI = "service:jmx:rmi:///jndi/rmi://" + JMX_SERVER_HOSTNAME + ":" + JMX_SERVER_PORT_0 + "/jmxrmi";
   static ObjectNameBuilder liveNameBuilder = ObjectNameBuilder.create(ActiveMQDefaultConfiguration.getDefaultJmxDomain(), "jfinterrupt", true);
   Process serverProcess;

   @Before
   public void before() throws Exception {
      cleanupData(SERVER_NAME_0);
      serverProcess = startServer(SERVER_NAME_0, 0, 30000);
      disableCheckThread();
   }

   private void killProcess(Process process) throws Exception {
      Runtime.getRuntime().exec("kill -9 " + process.pid());
   }

   @Test
   public void testInterruptJF() throws Throwable {
      final ConnectionFactory factory = CFUtil.createConnectionFactory("CORE", "tcp://localhost:61616");
      final String queueName = "JournalFlushInterruptTest";
      final int messageCount = 100;

      try (Connection connection = factory.createConnection()) {
         Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
         MessageProducer producer = session.createProducer(session.createQueue(queueName));
         for (int i = 0; i < messageCount; i++) {
            producer.send(session.createTextMessage("MessageCount: " + i));
         }
      }

      QueueControl queueControl = getQueueControl(liveURI, liveNameBuilder, queueName, queueName, RoutingType.ANYCAST, 5000);

      Assert.assertEquals(messageCount, queueControl.getMessageCount());
      Thread.sleep(100);

      killProcess(serverProcess);
      Assert.assertTrue(serverProcess.waitFor(1, TimeUnit.MINUTES));
      serverProcess = startServer(SERVER_NAME_0, 0, 0);

      waitForServerToStart("tcp://localhost:61616", "artemis", "artemis", 5000);
      queueControl = getQueueControl(liveURI, liveNameBuilder, queueName, queueName, RoutingType.ANYCAST, 5000);

      Assert.assertEquals(messageCount, queueControl.getMessageCount());

   }

}
