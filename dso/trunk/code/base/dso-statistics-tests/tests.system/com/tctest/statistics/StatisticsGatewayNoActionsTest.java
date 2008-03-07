/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tctest.statistics;

import com.tc.management.JMXConnectorProxy;
import com.tc.statistics.StatisticData;
import com.tc.statistics.beans.StatisticsGatewayMBean;
import com.tc.statistics.beans.StatisticsMBeanNames;
import com.tc.statistics.retrieval.actions.SRAShutdownTimestamp;
import com.tc.statistics.retrieval.actions.SRAStartupTimestamp;
import com.tc.util.UUID;
import com.tctest.TransparentTestBase;
import com.tctest.TransparentTestIface;

import java.util.ArrayList;
import java.util.List;

import javax.management.MBeanServerConnection;
import javax.management.MBeanServerInvocationHandler;

public class StatisticsGatewayNoActionsTest extends TransparentTestBase {
  protected void duringRunningCluster() throws Exception {
    JMXConnectorProxy jmxc = new JMXConnectorProxy("localhost", getAdminPort());
    MBeanServerConnection mbsc = jmxc.getMBeanServerConnection();

    StatisticsGatewayMBean stat_gateway = (StatisticsGatewayMBean)MBeanServerInvocationHandler
        .newProxyInstance(mbsc, StatisticsMBeanNames.STATISTICS_GATEWAY, StatisticsGatewayMBean.class, false);

    List data = new ArrayList();
    CollectingNotificationListener listener = new CollectingNotificationListener(StatisticsGatewayNoActionsTestApp.NODE_COUNT + 1);
    mbsc.addNotificationListener(StatisticsMBeanNames.STATISTICS_GATEWAY, listener, null, data);
    stat_gateway.enable();

    String sessionid = UUID.getUUID().toString();
    stat_gateway.createSession(sessionid);

    // register all the supported statistics
    String[] statistics = stat_gateway.getSupportedStatistics();
    for (int i = 0; i < statistics.length; i++) {
      stat_gateway.enableStatistic(sessionid, statistics[i]);
    }

    // remove all statistics
    stat_gateway.disableAllStatistics(sessionid);

    // start capturing
    stat_gateway.startCapturing(sessionid);

    // wait for 10 seconds
    Thread.sleep(10000);

    // stop capturing and wait for the last data
    synchronized (listener) {
      stat_gateway.stopCapturing(sessionid);
      while (!listener.getShutdown()) {
        listener.wait(2000);
      }
    }

    // disable the notification and detach the listener
    stat_gateway.disable();
    mbsc.removeNotificationListener(StatisticsMBeanNames.STATISTICS_GATEWAY, listener);

    // check the data
    assertEquals((StatisticsGatewayNoActionsTestApp.NODE_COUNT + 1) * 2, data.size());
    assertEquals(SRAStartupTimestamp.ACTION_NAME, ((StatisticData)data.get(0)).getName());
    assertEquals(SRAShutdownTimestamp.ACTION_NAME, ((StatisticData)data.get(data.size() - 1)).getName());
  }

  protected Class getApplicationClass() {
    return StatisticsManagerNoActionsTestApp.class;
  }

  public void doSetUp(TransparentTestIface t) throws Exception {
    t.getTransparentAppConfig().setClientCount(StatisticsGatewayNoActionsTestApp.NODE_COUNT);
    t.initializeTestRunner();
  }
}