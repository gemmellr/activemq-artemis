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

package org.apache.activemq.artemis.core.protocol.core.impl;

import org.apache.activemq.artemis.api.core.DisconnectReason;
import org.apache.activemq.artemis.core.protocol.core.CoreRemotingConnection;
import org.apache.activemq.artemis.core.server.ActiveMQMessageBundle;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.balancing.RedirectHandler;
import org.apache.activemq.artemis.core.server.balancing.targets.Target;

public class ActiveMQRedirectHandler extends RedirectHandler {

   private final CoreRemotingConnection connection;


   public ActiveMQRedirectHandler(ActiveMQServer server, CoreRemotingConnection connection, String username) {
      super(server, connection.getClientID(), username, connection.getTransportConnection());
      this.connection = connection;
   }


   @Override
   public void checkClientCanRedirect() throws Exception {
      if (!connection.isVersionSupportRedirect()) {
         throw ActiveMQMessageBundle.BUNDLE.incompatibleClientServer();
      }
   }

   @Override
   public void cannotRedirect() throws Exception {
      throw ActiveMQMessageBundle.BUNDLE.cannotRedirect();
   }

   @Override
   public void redirectTo(Target target) throws Exception {
      connection.disconnect(DisconnectReason.REDIRECT, target.getNodeID(), target.getConnector());

      throw ActiveMQMessageBundle.BUNDLE.redirectConnection(target.getConnector());
   }
}
