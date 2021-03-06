/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */

package org.elasticsearch.plugin.readonlyrest.utils.containers;

import com.google.common.collect.Lists;
import com.unboundid.ldap.sdk.AddRequest;
import com.unboundid.ldap.sdk.BindResult;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPConnectionOptions;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldif.LDIFReader;
import org.elasticsearch.plugin.readonlyrest.ldap.UnboundidLdapClient;
import org.elasticsearch.plugin.readonlyrest.utils.containers.exceptions.ContainerCreationException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.WaitStrategy;
import org.testcontainers.images.builder.ImageFromDockerfile;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

import static org.elasticsearch.plugin.readonlyrest.utils.containers.ContainerUtils.checkTimeout;

public class LdapContainer extends GenericContainer<LdapContainer> {

  private static Logger logger = Logger.getLogger(LdapContainer.class.getName());

  private static int LDAP_PORT = 389;
  private static Duration LDAP_CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static Duration CONTAINER_STARTUP_TIMEOUT = Duration.ofSeconds(60);
  private static String LDAP_DOMAIN = "example.com";
  private static String LDAP_ORGANISATION = "Example";
  private static String LDAP_ADMIN = "admin";
  private static String LDAP_ADMIN_PASSWORD = "password";
  private static Duration WAIT_BETWEEN_RETRIES = Duration.ofSeconds(1);

  private LdapContainer(ImageFromDockerfile imageFromDockerfile) {
    super(imageFromDockerfile);
  }

  public static LdapContainer create(String ldapInitScript) {
    File ldapInitScriptFile = ContainerUtils.getResourceFile(ldapInitScript);
    logger.info("Creating LDAP container ...");
    LdapContainer container = new LdapContainer(
      new ImageFromDockerfile()
        .withDockerfileFromBuilder(builder -> builder
          .from("osixia/openldap:1.1.7")
          .env("LDAP_ORGANISATION", LDAP_ORGANISATION)
          .env("LDAP_DOMAIN", LDAP_DOMAIN)
          .env("LDAP_ADMIN_PASSWORD", LDAP_ADMIN_PASSWORD)
          .build()));
    return container
      .withExposedPorts(LDAP_PORT)
      .waitingFor(
        container.ldapWaitStrategy(ldapInitScriptFile)
          .withStartupTimeout(CONTAINER_STARTUP_TIMEOUT)
      );
  }

  public String getLdapHost() {
    return this.getContainerIpAddress();
  }

  public Integer getLdapPort() {
    return this.getMappedPort(LDAP_PORT);
  }

  public UnboundidLdapClient.BindDnPassword getBindDNAndPassword() {
    List<String> dnParts = Lists.newArrayList(LDAP_DOMAIN.split("\\."));
    if (dnParts.isEmpty()) throw new IllegalArgumentException("Wrong domain defined " + LDAP_DOMAIN);
    String dnString = dnParts.stream().map(part -> "dc=" + part).reduce("", (s, s2) -> s + "," + s2);
    String bindDN = String.format("cn=%s%s", LDAP_ADMIN, dnString);
    return new UnboundidLdapClient.BindDnPassword(bindDN, LDAP_ADMIN_PASSWORD);
  }

  private WaitStrategy ldapWaitStrategy(File initialDataLdif) {
    return new GenericContainer.AbstractWaitStrategy() {

      @Override
      protected void waitUntilReady() {
        logger.info("Waiting for LDAP container ...");
        Optional<LDAPConnection> connection = tryConnect(getLdapHost(), getLdapPort());
        if (connection.isPresent()) {
          try {
            initLdap(connection.get(), initialDataLdif);
          } catch (Exception e) {
            throw new IllegalStateException(e);
          } finally {
            connection.get().close();
          }
        }
        else {
          throw new IllegalStateException("Cannot connect");
        }
        logger.info("LDAP container stated");
      }

      private LDAPConnection createConnection() {
        LDAPConnectionOptions options = new LDAPConnectionOptions();
        options.setConnectTimeoutMillis((int) LDAP_CONNECT_TIMEOUT.toMillis());
        return new LDAPConnection(options);
      }

      private Optional<LDAPConnection> tryConnect(String address, Integer port) {
        final Instant startTime = Instant.now();
        final LDAPConnection connection = createConnection();
        do {
          try {
            connection.connect(address, port);
            Thread.sleep(WAIT_BETWEEN_RETRIES.toMillis());
          } catch (Exception ignored) {
          }
        } while (!connection.isConnected() && !checkTimeout(startTime, startupTimeout));
        return Optional.of(connection);
      }

      private void initLdap(LDAPConnection connection, File initialDataLdif) throws Exception {
        LDAPConnection bindedConnection = bind(connection);
        LDIFReader r = new LDIFReader(initialDataLdif.getAbsoluteFile());
        Entry readEntry;
        while ((readEntry = r.readEntry()) != null) {
          bindedConnection.add(new AddRequest(readEntry.toLDIF()));
        }
      }

      private LDAPConnection bind(LDAPConnection connection) throws Exception {
        UnboundidLdapClient.BindDnPassword bindDNAndPassword = getBindDNAndPassword();
        BindResult bindResult = connection.bind(bindDNAndPassword.getDn(), bindDNAndPassword.getPassword());
        if (!ResultCode.SUCCESS.equals(bindResult.getResultCode())) {
          throw new ContainerCreationException("Cannot init LDAP due to bind problem");
        }
        return connection;
      }
    };
  }
}
