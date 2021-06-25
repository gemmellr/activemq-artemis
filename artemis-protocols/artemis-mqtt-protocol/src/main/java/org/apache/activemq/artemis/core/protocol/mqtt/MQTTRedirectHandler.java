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

package org.apache.activemq.artemis.core.protocol.mqtt;

import io.netty.handler.codec.mqtt.MqttConnectMessage;
import io.netty.handler.codec.mqtt.MqttConnectReturnCode;
import io.netty.handler.codec.mqtt.MqttProperties;
import org.apache.activemq.artemis.core.remoting.impl.netty.TransportConstants;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.balancing.RedirectHandler;
import org.apache.activemq.artemis.core.server.balancing.targets.Target;
import org.apache.activemq.artemis.utils.ConfigurationHelper;

public class MQTTRedirectHandler extends RedirectHandler {
   private final MQTTConnection mqttConnection;

   private final MQTTSession mqttSession;


   protected MQTTRedirectHandler(ActiveMQServer server, MQTTConnection mqttConnection, MQTTSession mqttSession, MqttConnectMessage connect) {
      super(server, connect.payload().clientIdentifier(), connect.payload().userName(), mqttConnection.getTransportConnection());
      this.mqttConnection = mqttConnection;
      this.mqttSession = mqttSession;
   }


   @Override
   protected void checkClientCanRedirect() throws Exception {

   }

   @Override
   protected void cannotRedirect() throws Exception {
      mqttSession.getProtocolHandler().sendConnack(MqttConnectReturnCode.CONNECTION_REFUSED_SERVER_UNAVAILABLE);
      mqttSession.getProtocolHandler().disconnect(true);
   }

   @Override
   protected void redirectTo(Target target) throws Exception {
      String host = ConfigurationHelper.getStringProperty(TransportConstants.HOST_PROP_NAME, TransportConstants.DEFAULT_HOST, target.getConnector().getParams());
      int port = ConfigurationHelper.getIntProperty(TransportConstants.PORT_PROP_NAME, TransportConstants.DEFAULT_PORT, target.getConnector().getParams());

      MqttProperties mqttProperties = new MqttProperties();
      mqttProperties.add(new MqttProperties.StringProperty(MqttProperties.MqttPropertyType.SERVER_REFERENCE.value(), String.format("%s:%d", host, port)));

      mqttSession.getProtocolHandler().sendConnack(MqttConnectReturnCode.CONNECTION_REFUSED_USE_ANOTHER_SERVER, mqttProperties);
      mqttSession.getProtocolHandler().disconnect(true);
   }
}
