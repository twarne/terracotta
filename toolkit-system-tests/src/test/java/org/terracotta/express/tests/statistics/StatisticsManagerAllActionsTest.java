/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package org.terracotta.express.tests.statistics;

import org.terracotta.test.util.JMXUtils;

import com.tc.statistics.StatisticData;
import com.tc.statistics.StatisticType;
import com.tc.statistics.beans.StatisticsEmitterMBean;
import com.tc.statistics.beans.StatisticsMBeanNames;
import com.tc.statistics.beans.StatisticsManagerMBean;
import com.tc.statistics.retrieval.actions.SRAShutdownTimestamp;
import com.tc.statistics.retrieval.actions.SRAStartupTimestamp;
import com.tc.test.config.model.TestConfig;
import com.tc.util.UUID;

import java.util.ArrayList;
import java.util.List;

import javax.management.MBeanServerConnection;
import javax.management.MBeanServerInvocationHandler;
import javax.management.remote.JMXConnector;

public class StatisticsManagerAllActionsTest extends AbstractStatisticsTestBase {
  public StatisticsManagerAllActionsTest(TestConfig testConfig) {
    super(testConfig);
  }

  @Override
  protected void duringRunningCluster() throws Exception {
    waitForAllNodesToConnectToGateway(StatisticsGatewayTestClient.NODE_COUNT + 1);

    JMXConnector jmxc = JMXUtils.getJMXConnector("localhost", getGroupData(0).getJmxPort(0));
    MBeanServerConnection mbsc = jmxc.getMBeanServerConnection();

    StatisticsManagerMBean stat_manager = MBeanServerInvocationHandler
        .newProxyInstance(mbsc, StatisticsMBeanNames.STATISTICS_MANAGER, StatisticsManagerMBean.class, false);
    StatisticsEmitterMBean stat_emitter = MBeanServerInvocationHandler
        .newProxyInstance(mbsc, StatisticsMBeanNames.STATISTICS_EMITTER, StatisticsEmitterMBean.class, false);

    List<StatisticData> data = new ArrayList<StatisticData>();
    CollectingNotificationListener listener = new CollectingNotificationListener(1);
    mbsc.addNotificationListener(StatisticsMBeanNames.STATISTICS_EMITTER, listener, null, data);
    stat_emitter.enable();

    String sessionid = UUID.getUUID().toString();
    stat_manager.createSession(sessionid);

    // register all the supported statistics
    String[] statistics = stat_manager.getSupportedStatistics();
    for (String statistic : statistics) {
      stat_manager.enableStatistic(sessionid, statistic);
      assertTrue(StatisticType.getAllTypes().contains(StatisticType.getType(stat_manager.getStatisticType(statistic))));
    }

    // start capturing
    stat_manager.startCapturing(sessionid);

    // wait for 10 seconds
    Thread.sleep(10000);

    // stop capturing and wait for the last data
    synchronized (listener) {
      stat_manager.stopCapturing(sessionid);
      while (!listener.getShutdown()) {
        listener.wait(2000);
      }
    }

    // disable the notification and detach the listener
    stat_emitter.disable();
    mbsc.removeNotificationListener(StatisticsMBeanNames.STATISTICS_EMITTER, listener);

    // check the data
    assertTrue(data.size() > 2);
    assertEquals(SRAStartupTimestamp.ACTION_NAME, data.get(0).getName());
    assertEquals(SRAShutdownTimestamp.ACTION_NAME, data.get(data.size() - 1).getName());

    // we can't check the statistics that we've received since there are
    // statistics that do not have data until there are some transaction
    // between the L1 and L2.
    // e.g. SRAMessages, SRAL2FaultsFromDisk, SRADistributedGC
  }

}
