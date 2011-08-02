/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.objectserver.control;

import com.tc.exception.TCNotRunningException;
import com.tc.management.beans.L2DumperMBean;
import com.tc.stats.api.DSOMBean;

import java.io.IOException;
import java.net.Socket;

public abstract class ServerControlBase implements ServerControl {

  private final int                  adminPort;
  private final String               host;
  private final int                  dsoPort;

  private final ServerMBeanRetriever serverMBeanRetriever;

  public ServerControlBase(String host, int dsoPort, int adminPort) {
    this.host = host;
    this.dsoPort = dsoPort;
    this.adminPort = adminPort;
    this.serverMBeanRetriever = new ServerMBeanRetriever(host, adminPort);
  }

  public boolean isRunning() {
    Socket socket = null;
    try {
      socket = new Socket(host, adminPort);
      if (!socket.isConnected()) throw new AssertionError();
      return true;
    } catch (IOException e) {
      return false;
    } finally {
      if (socket != null) {
        try {
          socket.close();
        } catch (IOException ioe) {
          // ignore
        }
      }
    }
  }

  public int getAdminPort() {
    return adminPort;
  }

  public int getDsoPort() {
    return dsoPort;
  }

  protected String getHost() {
    return host;
  }

  public L2DumperMBean getL2DumperMBean() throws Exception {
    // Not going to cache this and check liveness because it's not used super frequently and it doesn't have an easy
    // non-destructive check method.
    if (!isRunning()) { throw new TCNotRunningException("Server is not up."); }
    return serverMBeanRetriever.getL2DumperMBean();
  }

  public DSOMBean getDSOMBean() throws Exception {
    if (!isRunning()) { throw new TCNotRunningException("Server is not up."); }
    return serverMBeanRetriever.getDSOMBean();
  }
}
