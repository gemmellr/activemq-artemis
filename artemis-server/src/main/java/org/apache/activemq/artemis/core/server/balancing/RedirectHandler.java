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

package org.apache.activemq.artemis.core.server.balancing;

import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.ActiveMQServerLogger;
import org.apache.activemq.artemis.core.server.balancing.targets.Target;
import org.apache.activemq.artemis.spi.core.remoting.Connection;

public abstract class RedirectHandler {
   private final ActiveMQServer server;

   private final String clientID;

   private final String username;

   private final Connection transportConnection;


   public ActiveMQServer getServer() {
      return server;
   }

   public String getClientID() {
      return clientID;
   }

   public String getUserame() {
      return username;
   }

   public Connection getTransportConnection() {
      return transportConnection;
   }


   protected RedirectHandler(ActiveMQServer server, String clientID, String username, Connection transportConnection) {
      this.server = server;
      this.clientID = clientID;
      this.username = username;
      this.transportConnection = transportConnection;
   }

   protected abstract void checkClientCanRedirect() throws Exception;

   protected abstract void cannotRedirect() throws Exception;

   protected abstract void redirectTo(Target target) throws Exception;


   public boolean redirect() throws Exception {
      if (getTransportConnection().getRedirectTo() == null) {
         return false;
      }

      checkClientCanRedirect();

      BrokerBalancer brokerBalancer = getServer().getBalancerManager().getBalancer(getTransportConnection().getRedirectTo());

      if (brokerBalancer == null) {
         ActiveMQServerLogger.LOGGER.brokerBalancerNotFound(getTransportConnection().getRedirectTo());

         cannotRedirect();

         return true;
      }

      Target target = brokerBalancer.getTarget(getTransportConnection(), getClientID(), getUserame());

      if (target == null) {
         ActiveMQServerLogger.LOGGER.cannotRedirectClientConnection(getTransportConnection());

         cannotRedirect();

         return true;
      }

      ActiveMQServerLogger.LOGGER.redirectClientConnection(getTransportConnection(), target);

      if (!target.isLocal()) {
         redirectTo(target);

         return true;
      }

      return false;
   }
}
