/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.artemis.cli.commands.messages;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import javax.jms.JMSSecurityException;

import org.apache.activemq.artemis.api.core.management.ManagementHelper;
import org.apache.activemq.artemis.cli.Shell;
import org.apache.activemq.artemis.cli.commands.ActionContext;
import org.apache.activemq.artemis.cli.commands.InputAbstract;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import picocli.CommandLine.Option;

public class BasicConnectionAbstract extends InputAbstract {
   @Option(names = "--url", description = "Connection URL. Default: build URL from the 'artemis' acceptor defined in the broker.xml or tcp://localhost:61616 if the acceptor cannot be parsed.")
   protected String brokerURL = DEFAULT_BROKER_URL;

   @Option(names = "--acceptor", description = "Name used to find the default connection URL on the acceptor list. If an acceptor with that name cannot be found the CLI will look for a connector with the same name.")
   protected String acceptor;

   @Option(names = "--user", description = "User used to connect.")
   protected String user;

   @Option(names = "--password", description = "Password used to connect.")
   protected String password;

   protected static ThreadLocal<ConnectionInformation> CONNECTION_INFORMATION = new ThreadLocal<>();

   static class ConnectionInformation {
      String uri, user, password;

      private ConnectionInformation(String uri, String user, String password) {
         this.uri = uri;
         this.user = user;
         this.password = password;
      }
   }

   public String getBrokerURL() {
      return brokerURL;
   }

   public void setBrokerURL(String brokerURL) {
      this.brokerURL = brokerURL;
   }

   public String getAcceptor() {
      return acceptor;
   }

   public BasicConnectionAbstract setAcceptor(String acceptor) {
      this.acceptor = acceptor;
      return this;
   }

   public String getUser() {
      return user;
   }

   public BasicConnectionAbstract setUser(String user) {
      this.user = user;
      return this;
   }

   public String getPassword() {
      return password;
   }

   public BasicConnectionAbstract setPassword(String password) {
      this.password = password;
      return this;
   }

   @SuppressWarnings("StringEquality")
   @Override
   public Object execute(ActionContext context) throws Exception {
      super.execute(context);

      recoverConnectionInformation();

      // it is intentional to make a comparison on the String object here
      // this is to test if the original option was switched or not.
      // we don't care about being .equals at all.
      // as a matter of fact if you pass brokerURL in a way it's equals to DEFAULT_BROKER_URL,
      // we should not the broker URL Instance
      // and still honor the one passed by parameter.
      // SupressWarnings was added to this method to supress the false positive here from error-prone.
      if (brokerURL == DEFAULT_BROKER_URL) {
         String brokerURLInstance = getBrokerURLInstance(acceptor);

         if (brokerURLInstance != null) {
            brokerURL = brokerURLInstance;
         }
      }

      context.out.println("Connection brokerURL = " + brokerURL);

      return null;
   }

   protected ConnectionFactory createConnectionFactory() throws Exception {
      recoverConnectionInformation();
      return createConnectionFactory(brokerURL, user, password);
   }

   protected ConnectionFactory createConnectionFactory(String brokerURL,
                                                               String user,
                                                               String password) throws Exception {
      recoverConnectionInformation();

      ActiveMQConnectionFactory cf = new ActiveMQConnectionFactory(brokerURL, user, password);
      try {
         tryConnect(brokerURL, user, password, cf);
         return cf;
      } catch (JMSSecurityException e) {
         // if a security exception will get the user and password through an input
         if (getActionContext() != null) {
            getActionContext().err.println("Connection failed::" + e.getMessage());
         }
         user = inputUser(user);
         password = inputPassword(password);
         cf = new ActiveMQConnectionFactory(brokerURL, user, password);
         try {
            tryConnect(brokerURL, user, password, cf);
         } catch (Exception e2) {
         }
         return cf;
      } catch (JMSException e) {
         // if a connection exception will ask for the URL, user and password
         if (getActionContext() != null) {
            getActionContext().err.println("Connection failed::" + e.getMessage());
         }
         brokerURL = inputBrokerURL(brokerURL);
         user = inputUser(user);
         password = inputPassword(password);
         cf = new ActiveMQConnectionFactory(brokerURL, user, password);
         try {
            tryConnect(brokerURL, user, password, cf);
         } catch (Exception e2) {
         }
         return cf;
      }
   }

   protected void recoverConnectionInformation() {
      if (CONNECTION_INFORMATION.get() != null) {
         ConnectionInformation connectionInfo = CONNECTION_INFORMATION.get();
         if (this.user == null) {
            this.user  = connectionInfo.user;
         }
         if (this.password == null) {
            this.password  = connectionInfo.password;
         }
         if (this.brokerURL == null || this.brokerURL == DEFAULT_BROKER_URL) {
            this.brokerURL  = connectionInfo.uri;
         }
      }
   }

   protected void saveConnectionInfo(String brokerURL, String user, String password) {
      if (Shell.inShell() && CONNECTION_INFORMATION.get() == null) {
         CONNECTION_INFORMATION.set(new ConnectionInformation(brokerURL, user, password));
         getActionContext().out.println("CLI connected to broker " + brokerURL + ", user:" + user);
         this.brokerURL = brokerURL;
         this.user = user;
         this.password = password;
      }
   }

   protected void tryConnect(String brokerURL,
                          String user,
                          String password,
                          ConnectionFactory cf) throws JMSException {
      Connection connection = cf.createConnection();
      connection.close();
      saveConnectionInfo(brokerURL, user, password);
   }

   protected String inputBrokerURL(String defaultValue) {
      return input("--url", "Type in the connection URL for a retry (e.g. tcp://localhost:61616)", defaultValue);
   }

   protected String inputUser(String user) {
      if (user == null) {
         this.user = input("--user", "Type the username for a retry", null);
         return this.user;
      }
      return user;
   }

   protected String inputPassword(String password) {
      if (password == null) {
         this.password = inputPassword("--password", "Type the password for a retry", null);
         return this.password;
      }
      return password;
   }

   protected void performCoreManagement(ManagementHelper.MessageAcceptor setup, ManagementHelper.MessageAcceptor ok, ManagementHelper.MessageAcceptor failed) throws Exception {
      try (ActiveMQConnectionFactory factory = (ActiveMQConnectionFactory) createConnectionFactory()) {
         ManagementHelper.doManagement(factory.getServerLocator(), user, password, setup, ok, failed);
      }
   }

   protected void performCoreManagement(String uri, String user, String password, ManagementHelper.MessageAcceptor setup, ManagementHelper.MessageAcceptor ok, ManagementHelper.MessageAcceptor failed) throws Exception {
      try (ActiveMQConnectionFactory factory = (ActiveMQConnectionFactory) createConnectionFactory(uri, user, password)) {
         ManagementHelper.doManagement(factory.getServerLocator(), user, password, setup, ok, failed);
      }
   }
}