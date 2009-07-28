/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.net.groups;

import com.tc.async.api.ConfigurationContext;
import com.tc.async.api.StageManager;
import com.tc.async.impl.ConfigurationContextImpl;
import com.tc.async.impl.StageManagerImpl;
import com.tc.io.TCByteBufferInput;
import com.tc.io.TCByteBufferOutput;
import com.tc.lang.TCThreadGroup;
import com.tc.lang.ThrowableHandler;
import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.net.NodeID;
import com.tc.net.TCSocketAddress;
import com.tc.net.protocol.transport.ClientConnectionEstablisher;
import com.tc.net.protocol.transport.ClientMessageTransport;
import com.tc.net.protocol.transport.NullConnectionPolicy;
import com.tc.net.proxy.TCPProxy;
import com.tc.properties.TCPropertiesConsts;
import com.tc.properties.TCPropertiesImpl;
import com.tc.test.TCTestCase;
import com.tc.util.Assert;
import com.tc.util.PortChooser;
import com.tc.util.concurrent.NoExceptionLinkedQueue;
import com.tc.util.concurrent.QueueFactory;
import com.tc.util.concurrent.ThreadUtil;
import com.tc.util.runtime.ThreadDumpUtil;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Collections;

public class TCGroupManagerNodeJoinedTest extends TCTestCase {

  private final static String   LOCALHOST = "localhost";
  private static final TCLogger logger    = TCLogging.getLogger(TCGroupManagerNodeJoinedTest.class);
  private TCThreadGroup         threadGroup;
  private TCGroupManagerImpl[]  groupManagers;
  private MyListener[]          listeners;

  public TCGroupManagerNodeJoinedTest() {
    // disableAllUntil("2009-03-15");
  }

  public void setUp() {
    threadGroup = new TCThreadGroup(new ThrowableHandler(logger), "TCGroupManagerNodeJoinedTest");
  }

  public void testNodejoinedTwoServers() throws Exception {
    Thread throwableThread = new Thread(threadGroup, new Runnable() {
      public void run() {
        try {
          nodesSetupAndJoined(2);
        } catch (Exception e) {
          throw new RuntimeException("testStateManagerTwoServers failed! " + e);
        }
      }
    });
    throwableThread.start();
    throwableThread.join();
  }

  public void testNoConnectionThreadLeak() throws Exception {
    Thread testDEV3101NoL2Reconnect = new Thread(threadGroup, new Runnable() {
      public void run() {
        try {
          nodesSetupAndJoined_DEV3101(2);
        } catch (Exception e) {
          throw new RuntimeException("DEV-3101 without L2 Reconnect failed: " + e);
        }
      }
    });

    testDEV3101NoL2Reconnect.start();
    testDEV3101NoL2Reconnect.join();
  }

  public void testNoConnectionThreadLeakOnL2Reconnect() throws Exception {
    Thread testDEV3101WithL2Reconnect = new Thread(threadGroup, new Runnable() {
      public void run() {
        try {
          TCPropertiesImpl.getProperties().setProperty(TCPropertiesConsts.L2_NHA_TCGROUPCOMM_RECONNECT_ENABLED, "true");
          nodesSetupAndJoined_DEV3101(2);
          TCPropertiesImpl.getProperties()
              .setProperty(TCPropertiesConsts.L2_NHA_TCGROUPCOMM_RECONNECT_ENABLED, "false");
        } catch (Exception e) {
          throw new RuntimeException("DEV-3101 without L2 Reconnect failed: " + e);
        }
      }
    });

    testDEV3101WithL2Reconnect.start();
    testDEV3101WithL2Reconnect.join();
  }

  public void testNodejoinedThreeServers() throws Exception {
    Thread throwableThread = new Thread(threadGroup, new Runnable() {
      public void run() {
        try {
          nodesSetupAndJoined(3);
        } catch (Exception e) {
          throw new RuntimeException("testStateManagerTwoServers failed! " + e);
        }
      }
    });
    throwableThread.start();
    throwableThread.join();
  }

  public void testNodejoinedSixServers() throws Exception {
    Thread throwableThread = new Thread(threadGroup, new Runnable() {
      public void run() {
        try {
          nodesSetupAndJoined(6);
        } catch (Exception e) {
          throw new RuntimeException("testStateManagerTwoServers failed! " + e);
        }
      }
    });
    throwableThread.start();
    throwableThread.join();
  }

  // -----------------------------------------------------------------------

  private void nodesSetupAndJoined(int nodes) throws Exception {
    System.out.println("*** Testing nodejoined for " + nodes + " servers");

    listeners = new MyListener[nodes];
    groupManagers = new TCGroupManagerImpl[nodes];
    Node[] allNodes = new Node[nodes];
    PortChooser pc = new PortChooser();
    for (int i = 0; i < nodes; ++i) {
      int port = pc.chooseRandom2Port();
      allNodes[i] = new Node(LOCALHOST, port, port + 1, TCSocketAddress.WILDCARD_IP);
    }

    for (int i = 0; i < nodes; ++i) {
      StageManager stageManager = new StageManagerImpl(threadGroup, new QueueFactory());
      TCGroupManagerImpl gm = new TCGroupManagerImpl(new NullConnectionPolicy(), allNodes[i].getHost(), allNodes[i]
          .getPort(), allNodes[i].getGroupPort(), stageManager);
      ConfigurationContext context = new ConfigurationContextImpl(stageManager);
      stageManager.startAll(context, Collections.EMPTY_LIST);
      gm.setDiscover(new TCGroupMemberDiscoveryStatic(gm));

      groupManagers[i] = gm;
      MyGroupEventListener gel = new MyGroupEventListener(gm);
      listeners[i] = new MyListener();
      gm.registerForMessages(TestMessage.class, listeners[i]);
      gm.registerForGroupEvents(gel);

    }

    // joining
    System.out.println("*** Start Joining...");
    for (int i = 0; i < nodes; ++i) {
      groupManagers[i].join(allNodes[i], allNodes);
    }
    ThreadUtil.reallySleep(1000 * nodes);

    // verification
    for (int i = 0; i < nodes; ++i) {
      // every node shall receive hello message from reset of nodes
      assertEquals(nodes - 1, listeners[i].size());
    }

    System.out.println("VERIFIED");
    shutdown();
  }

  public void nodesSetupAndJoined_DEV3101(int nodes) throws Exception {
    System.out.println("*** Testing DEV3101 1");
    Assert.assertEquals(2, nodes);

    int count = getThreadCountByName(ClientConnectionEstablisher.RECONNECT_THREAD_NAME);
    System.out.println("XXX Thread count : " + ClientConnectionEstablisher.RECONNECT_THREAD_NAME + " - " + count);

    listeners = new MyListener[nodes];
    groupManagers = new TCGroupManagerImpl[nodes];
    Node[] allNodes = new Node[nodes];
    Node[] proxiedAllNodes = new Node[nodes];
    TCPProxy[] proxy = new TCPProxy[nodes];
    PortChooser pc = new PortChooser();
    for (int i = 0; i < nodes; ++i) {
      int port = pc.chooseRandom2Port();
      allNodes[i] = new Node(LOCALHOST, port, port + 1, TCSocketAddress.WILDCARD_IP);

      int proxyPort = pc.chooseRandomPort();
      proxy[i] = new TCPProxy(proxyPort, InetAddress.getByName(LOCALHOST), port + 1, 0, false, null);
      proxy[i].start();
      proxiedAllNodes[i] = new Node(LOCALHOST, port, proxyPort, TCSocketAddress.WILDCARD_IP);
    }

    for (int i = 0; i < nodes; ++i) {
      StageManager stageManager = new StageManagerImpl(threadGroup, new QueueFactory());
      TCGroupManagerImpl gm = new TCGroupManagerImpl(new NullConnectionPolicy(), allNodes[i].getHost(), allNodes[i]
          .getPort(), allNodes[i].getGroupPort(), stageManager);
      ConfigurationContext context = new ConfigurationContextImpl(stageManager);
      stageManager.startAll(context, Collections.EMPTY_LIST);
      gm.setDiscover(new TCGroupMemberDiscoveryStatic(gm));

      groupManagers[i] = gm;
      MyGroupEventListener gel = new MyGroupEventListener(gm);
      listeners[i] = new MyListener();
      gm.registerForMessages(TestMessage.class, listeners[i]);
      gm.registerForGroupEvents(gel);

    }

    // joining
    System.out.println("*** Start Joining...");
    for (int i = 0; i < nodes; ++i) {
      groupManagers[i].join(allNodes[i], proxiedAllNodes);
    }
    ThreadUtil.reallySleep(1000 * nodes);

    // verification
    for (int i = 0; i < nodes; ++i) {
      // every node shall receive hello message from reset of nodes
      assertEquals(nodes - 1, listeners[i].size());
    }

    System.out.println("XXX 1st verification done");

    ThreadUtil.reallySleep(5000);
    for (int i = 0; i < nodes; ++i) {
      proxy[i].stop();
    }

    System.out.println("XXX Node 0 Zapped Node 1");
    groupManagers[0].addZappedNode(groupManagers[1].getLocalNodeID());

    System.out.println("XXX proxy stopped");
    if (TCPropertiesImpl.getProperties().getBoolean(TCPropertiesConsts.L2_NHA_TCGROUPCOMM_RECONNECT_ENABLED)) {
      ThreadUtil.reallySleep(TCPropertiesImpl.getProperties()
          .getLong(TCPropertiesConsts.L2_NHA_TCGROUPCOMM_RECONNECT_TIMEOUT));
    }
    ThreadUtil.reallySleep(5000);

    for (int i = 0; i < nodes; ++i) {
      proxy[i].start();
    }
    System.out.println("XXX proxy resumed. Grp Mgrs discovery started for 20 seconds");

    // let the restores/reconnects along with the zapping problem happen for 20 seconds
    ThreadUtil.reallySleep(20000);

    System.out.println("XXX STOPPING Grp Mgrs discovery");
    for (int i = 0; i < nodes; ++i) {
      groupManagers[i].getDiscover().stop(Integer.MAX_VALUE);
    }

    System.out.println("XXX Waiting for all restore connection close");
    if (TCPropertiesImpl.getProperties().getBoolean(TCPropertiesConsts.L2_NHA_TCGROUPCOMM_RECONNECT_ENABLED)) {
      ThreadUtil.reallySleep(ClientMessageTransport.TRANSPORT_HANDSHAKE_SYNACK_TIMEOUT);
    }

    ThreadUtil.reallySleep(5000);
    ensureThreadAbsent(ClientConnectionEstablisher.RECONNECT_THREAD_NAME, count);
    shutdown();
  }

  private void ensureThreadAbsent(String absentThreadName, int allowedLimit) {
    Thread[] allThreads = ThreadDumpUtil.getAllThreads();
    int count = 0;
    for (Thread t : allThreads) {
      System.out.println("XXX " + t);
      if (t.getName().contains(absentThreadName)) {
        count++;
      }
    }
    System.out.println("XXX count = " + count + "; allowedlimit = " + allowedLimit);
    Assert.eval(count <= allowedLimit);
  }

  private int getThreadCountByName(String threadName) {
    Thread[] allThreads = ThreadDumpUtil.getAllThreads();
    int count = 0;
    for (Thread t : allThreads) {
      if (t.getName().contains(threadName)) {
        count++;
      }
    }
    return count;
  }

  private void shutdown() {
    shutdown(groupManagers, 0, groupManagers.length);
  }

  private void shutdown(TCGroupManagerImpl[] groupMgr, int start, int end) {
    for (int i = start; i < end; ++i) {
      try {
        groupMgr[i].stop(1000);
      } catch (Exception ex) {
        System.out.println("*** Failed to stop Server[" + i + "] " + groupMgr[i] + " " + ex);
      }
    }
    System.out.println("*** shutdown done");
  }

  private static class MyGroupEventListener implements GroupEventsListener {

    private final GroupManager groupManager;
    private final NodeID       gmNodeID;

    public MyGroupEventListener(GroupManager groupManager) {
      this.groupManager = groupManager;
      this.gmNodeID = groupManager.getLocalNodeID();
    }

    public void nodeJoined(NodeID nodeID) {
      System.err.println("\n### " + gmNodeID + ": nodeJoined -> " + nodeID);
      try {
        groupManager.sendTo(nodeID, new TestMessage("Hello " + nodeID));
      } catch (GroupException e) {
        throw new RuntimeException(e);
      }
    }

    public void nodeLeft(NodeID nodeID) {
      System.err.println("\n### " + gmNodeID + ": nodeLeft -> " + nodeID);
    }
  }

  private static final class MyListener implements GroupMessageListener {

    NoExceptionLinkedQueue queue = new NoExceptionLinkedQueue();
    private int            count = 0;

    public void messageReceived(NodeID fromNode, GroupMessage msg) {
      queue.put(msg);
      ++count;
    }

    public GroupMessage take() {
      --count;
      return (GroupMessage) queue.take();
    }

    public int size() {
      return count;
    }

  }

  private static final class TestMessage extends AbstractGroupMessage {

    // to make serialization sane
    public TestMessage() {
      super(0);
    }

    public TestMessage(String message) {
      super(0);
      this.msg = message;
    }

    protected void basicDeserializeFrom(TCByteBufferInput in) throws IOException {
      msg = in.readString();
    }

    protected void basicSerializeTo(TCByteBufferOutput out) {
      out.writeString(msg);
    }

    String msg;

    public int hashCode() {
      return msg.hashCode();
    }

    public boolean equals(Object o) {
      if (o instanceof TestMessage) {
        TestMessage other = (TestMessage) o;
        return this.msg.equals(other.msg);
      }
      return false;
    }

    public String toString() {
      return "TestMessage [ " + msg + "]";
    }
  }

}
