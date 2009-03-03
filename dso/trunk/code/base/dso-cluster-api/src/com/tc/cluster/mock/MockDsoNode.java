/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.cluster.mock;

import com.tc.cluster.DsoNode;

class MockDsoNode implements DsoNode {

  public String getId() {
    return "Client[1]";
  }

  public String getHostname() {
    return "localhost";
  }

  public String getIp() {
    return "127.0.0.1";
  }
}
