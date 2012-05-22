/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tctest;

import com.tc.dump.DumpServer;
import com.tc.gcrunner.GCRunner;
import com.tc.net.Netstat;
import com.tc.net.Netstat.SocketConnection;
import com.tc.object.BaseDSOTestCase;
import com.tc.objectserver.control.ServerMBeanRetriever;
import com.tc.server.util.ClusterDumper;
import com.tc.server.util.ServerStat;
import com.tc.test.process.ExternalDsoServer;
import com.tc.util.CallableWaiter;
import com.tc.util.TcConfigBuilder;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;

import junit.framework.Assert;

public class JMXConnectionLeakTest extends BaseDSOTestCase {
  private TcConfigBuilder      configBuilder;
  private ExternalDsoServer    server;
  private int                  jmxPort;
  private ServerMBeanRetriever serverMBeanRetriever;

  @Override
  protected void setUp() throws Exception {
    configBuilder = new TcConfigBuilder("/com/tctest/tc-one-server-config.xml");
    configBuilder.randomizePorts();
    server = new ExternalDsoServer(getWorkDir("server1"), configBuilder.newInputStream(), "server1");
    jmxPort = configBuilder.getJmxPort(0);
    serverMBeanRetriever = new ServerMBeanRetriever("localhost", jmxPort);
    server.start();
    System.out.println("server1 started");
    waitTillBecomeActive();
    System.out.println("server1 became active");
  }

  public void testLeak() throws Exception {
    int initialCount = getNetInfoEstablishedConnectionsCount(jmxPort);

    ServerStat.main(getServerStatArgs());
    Assert.assertEquals(initialCount, getNetInfoEstablishedConnectionsCount(jmxPort));
    String[] args = getHostNamePortMainArgs();
    ClusterDumper.main(args);
    Assert.assertEquals(initialCount, getNetInfoEstablishedConnectionsCount(jmxPort));
    DumpServer.main(args);
    Assert.assertEquals(initialCount, getNetInfoEstablishedConnectionsCount(jmxPort));
    GCRunner.main(args);
    Assert.assertEquals(initialCount, getNetInfoEstablishedConnectionsCount(jmxPort));

  }

  private String[] getHostNamePortMainArgs() {
    String[] args = { "-n", "localhost", "-p", Integer.toString(jmxPort) };
    return args;
  }

  private String[] getServerStatArgs() {
    String[] args = { "-f", server.getConfigFile().getAbsolutePath() };
    return args;
  }

  private int getNetInfoEstablishedConnectionsCount(int bindPort) {
    int establishedConnections = 0;

    List<SocketConnection> connections = Netstat.getEstablishedTcpConnections();

    System.out.println("XXX Established connections if any");
    for (SocketConnection connection : connections) {
      long port = connection.getLocalPort();
      long remotePort = connection.getRemotePort();
      if ((bindPort == port || bindPort == remotePort) && connection.getLocalAddr().isLoopbackAddress()
          && connection.getRemoteAddr().isLoopbackAddress()) {
        establishedConnections++;
        System.out.println("XXX " + connection);
      }
    }
    return establishedConnections;
  }

  private void waitTillBecomeActive() throws Exception {
    CallableWaiter.waitOnCallable(new Callable<Boolean>() {
      @Override
      public Boolean call() throws Exception {
        return serverMBeanRetriever.getTCServerInfoMBean().isActive();
      }
    });
  }

  @Override
  protected void tearDown() throws Exception {
    System.err.println("in tearDown");
    if (server != null) server.stop();
  }

  private File getWorkDir(final String subDir) throws IOException {
    File workDir = new File(getTempDirectory(), subDir);
    workDir.mkdirs();
    return workDir;
  }
}
