/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.net.groups;

import com.tc.net.TCSocketAddress;

public class Node {

  private final String host;
  private final int    port;
  private final int    groupPort;
  private final int    hashCode;
  private final String bind;

  public Node(final String host, final int port) {
    this(host, port, TCSocketAddress.WILDCARD_IP);
  }

  public Node(final String host, final int port, final String bind) {
    this(host, port, 0, bind);
  }

  public Node(final String host, final int port, final int groupPort, final String bind) {
    checkArgs(host, port);
    this.host = host.trim();
    this.port = port;
    this.groupPort = groupPort;
    this.bind = bind; // not part of equals()
    this.hashCode = (host + "-" + port).hashCode();
  }

  public String getHost() {
    return host;
  }

  public int getPort() {
    return port;
  }

  public int getGroupPort() {
    return groupPort;
  }

  public String getBind() {
    return bind;
  }

  public boolean equals(Object obj) {
    if (obj instanceof Node) {
      Node that = (Node) obj;
      return this.port == that.port && this.host.equals(that.host);
    }
    return false;
  }

  public int hashCode() {
    return hashCode;
  }

  private static void checkArgs(final String host, final int port) throws IllegalArgumentException {
    if (host == null || host.trim().length() == 0) { throw new IllegalArgumentException("Invalid host name: " + host); }
    if (port < 0) { throw new IllegalArgumentException("Invalid port number: " + port); }
  }

  public String toString() {
    return "Node{host=" + host + ":" + port + "}";
  }

  public String getServerNodeName() {
    return (host + ":" + port);
  }

}
