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

package org.apache.activemq.artemis.core.protocol.openwire;

import org.apache.activemq.artemis.core.remoting.impl.netty.TransportConstants;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.balancing.RedirectHandler;
import org.apache.activemq.artemis.core.server.balancing.targets.Target;
import org.apache.activemq.artemis.spi.core.remoting.Connection;
import org.apache.activemq.artemis.utils.ConfigurationHelper;
import org.apache.activemq.command.ConnectionControl;
import org.apache.activemq.command.ConnectionInfo;

public class OpenWireRedirectHandler extends RedirectHandler {

   private final ConnectionInfo connectionInfo;

   private final OpenWireConnection connection;

   private final OpenWireProtocolManager protocolManager;


   protected OpenWireRedirectHandler(ActiveMQServer server, Connection connection, ConnectionInfo connectionInfo, OpenWireConnection openWireConnection, OpenWireProtocolManager protocolManager) {
      super(server, connectionInfo.getClientId(), connectionInfo.getUserName(), connection);
      this.connectionInfo = connectionInfo;
      this.connection = openWireConnection;
      this.protocolManager = protocolManager;
   }

   @Override
   protected void checkClientCanRedirect() throws Exception {
      if (!connectionInfo.isFaultTolerant()) {
         throw new java.lang.IllegalStateException("Client not fault tolerant");
      }
   }

   @Override
   protected void cannotRedirect() throws Exception {
   }

   @Override
   protected void redirectTo(Target target) throws Exception {
      String host = ConfigurationHelper.getStringProperty(TransportConstants.HOST_PROP_NAME, TransportConstants.DEFAULT_HOST, target.getConnector().getParams());
      int port = ConfigurationHelper.getIntProperty(TransportConstants.PORT_PROP_NAME, TransportConstants.DEFAULT_PORT, target.getConnector().getParams());

      ConnectionControl command = protocolManager.newConnectionControl();
      command.setConnectedBrokers(String.format("tcp://%s:%d", host, port));
      command.setRebalanceConnection(true);
      connection.dispatchSync(command);
   }
}
