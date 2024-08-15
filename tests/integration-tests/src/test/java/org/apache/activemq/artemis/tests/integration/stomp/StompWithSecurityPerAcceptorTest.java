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
package org.apache.activemq.artemis.tests.integration.stomp;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.URL;
import java.util.Arrays;
import java.util.Collection;

import org.apache.activemq.artemis.core.config.Configuration;
import org.apache.activemq.artemis.core.protocol.stomp.Stomp;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.ActiveMQServers;
import org.apache.activemq.artemis.spi.core.security.ActiveMQJAASSecurityManager;
import org.apache.activemq.artemis.tests.extensions.parameterized.ParameterizedTestExtension;
import org.apache.activemq.artemis.tests.extensions.parameterized.Parameters;
import org.apache.activemq.artemis.tests.integration.stomp.util.ClientStompFrame;
import org.apache.activemq.artemis.tests.integration.stomp.util.StompClientConnection;
import org.apache.activemq.artemis.tests.integration.stomp.util.StompClientConnectionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(ParameterizedTestExtension.class)
public class StompWithSecurityPerAcceptorTest extends StompTestBase {

   @Parameters(name = "{0}")
   public static Collection<Object[]> data() {
      return Arrays.asList(new Object[][]{{"ws+v10.stomp"}, {"tcp+v10.stomp"}});
   }

   public StompWithSecurityPerAcceptorTest(String scheme) {
      super(scheme);
   }

   static {
      String path = System.getProperty("java.security.auth.login.config");
      if (path == null) {
         URL resource = StompWithSecurityPerAcceptorTest.class.getClassLoader().getResource("login.config");
         if (resource != null) {
            path = resource.getFile();
            System.setProperty("java.security.auth.login.config", path);
         }
      }
   }

   @Override
   public boolean isSecurityEnabled() {
      return true;
   }

   @Override
   @BeforeEach
   public void setUp() throws Exception {
      uri = new URI(scheme + "://" + hostname + ":" + port);

      server = createServer();
      server.start();

      waitForServerToStart(server);
   }

   @Override
   protected ActiveMQServer createServer() throws Exception {
      Configuration config = createBasicConfig()
         .setSecurityEnabled(isSecurityEnabled())
         .setPersistenceEnabled(isPersistenceEnabled())
         .addAcceptorConfiguration("stomp", "tcp://localhost:61613?securityDomain=PropertiesLogin");

      server = addServer(ActiveMQServers.newActiveMQServer(config, ManagementFactory.getPlatformMBeanServer(), new ActiveMQJAASSecurityManager()));
      return server;
   }

   @TestTemplate
   public void testSecurityPerAcceptorPositive() throws Exception {
      StompClientConnection conn = StompClientConnectionFactory.createClientConnection(uri);
      ClientStompFrame frame = conn.connect("first", "secret");
      assertTrue(frame.getCommand().equals(Stomp.Responses.CONNECTED));
   }

   @TestTemplate
   public void testSecurityPerAcceptorNegative() throws Exception {
      StompClientConnection conn = StompClientConnectionFactory.createClientConnection(uri);
      ClientStompFrame frame = conn.connect("fail", "secret");
      assertTrue(frame.getCommand().equals(Stomp.Responses.ERROR));
   }
}
