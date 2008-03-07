/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.statistics.beans.impl;

import EDU.oswego.cs.dl.util.concurrent.SynchronizedLong;

import com.tc.config.schema.NewCommonL2Config;
import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.management.AbstractTerracottaMBean;
import com.tc.net.TCSocketAddress;
import com.tc.statistics.StatisticsGathererSubSystem;
import com.tc.statistics.StatisticData;
import com.tc.statistics.beans.StatisticsLocalGathererMBean;
import com.tc.statistics.gatherer.StatisticsGathererListener;
import com.tc.statistics.gatherer.exceptions.TCStatisticsGathererException;
import com.tc.statistics.store.StatisticsStoreListener;
import com.tc.statistics.store.exceptions.TCStatisticsStoreException;
import com.tc.util.Assert;

import javax.management.MBeanNotificationInfo;
import javax.management.NotCompliantMBeanException;
import javax.management.Notification;

public class StatisticsLocalGathererMBeanImpl extends AbstractTerracottaMBean implements StatisticsLocalGathererMBean, StatisticsGathererListener, StatisticsStoreListener {
  public final static String STATISTICS_LOCALGATHERER_CONNECTED_TYPE = "tc.statistics.localgatherer.connected";
  public final static String STATISTICS_LOCALGATHERER_DISCONNECTED_TYPE = "tc.statistics.localgatherer.disconnected";
  public final static String STATISTICS_LOCALGATHERER_REINITIALIZED_TYPE = "tc.statistics.localgatherer.reinitialized";
  public final static String STATISTICS_LOCALGATHERER_CAPTURING_STARTED_TYPE = "tc.statistics.localgatherer.capturing.started";
  public final static String STATISTICS_LOCALGATHERER_CAPTURING_STOPPED_TYPE = "tc.statistics.localgatherer.capturing.stopped";
  public final static String STATISTICS_LOCALGATHERER_SESSION_CREATED_TYPE = "tc.statistics.localgatherer.session.created";
  public final static String STATISTICS_LOCALGATHERER_SESSION_CLOSED_TYPE = "tc.statistics.localgatherer.session.closed";
  public final static String STATISTICS_LOCALGATHERER_SESSION_CLEARED_TYPE = "tc.statistics.localgatherer.session.cleared";
  public final static String STATISTICS_LOCALGATHERER_ALLSESSIONS_CLEARED_TYPE = "tc.statistics.localgatherer.allsessions.cleared";
  public final static String STATISTICS_LOCALGATHERER_STORE_OPENED_TYPE = "tc.statistics.localgatherer.store.opened";
  public final static String STATISTICS_LOCALGATHERER_STORE_CLOSED_TYPE = "tc.statistics.localgatherer.store.closed";

  public final static MBeanNotificationInfo[] NOTIFICATION_INFO;

  private final static TCLogger logger = TCLogging.getLogger(StatisticsEmitterMBeanImpl.class);

  static {
    final String[] notifTypes = new String[] {
      STATISTICS_LOCALGATHERER_CONNECTED_TYPE,
      STATISTICS_LOCALGATHERER_DISCONNECTED_TYPE,
      STATISTICS_LOCALGATHERER_REINITIALIZED_TYPE,
      STATISTICS_LOCALGATHERER_CAPTURING_STARTED_TYPE,
      STATISTICS_LOCALGATHERER_CAPTURING_STOPPED_TYPE,
      STATISTICS_LOCALGATHERER_SESSION_CREATED_TYPE,
      STATISTICS_LOCALGATHERER_SESSION_CLOSED_TYPE,
      STATISTICS_LOCALGATHERER_SESSION_CLEARED_TYPE,
      STATISTICS_LOCALGATHERER_ALLSESSIONS_CLEARED_TYPE,
      STATISTICS_LOCALGATHERER_STORE_OPENED_TYPE,
      STATISTICS_LOCALGATHERER_STORE_CLOSED_TYPE
    };
    final String name = Notification.class.getName();
    final String description = "Each notification sent contains information about what happened with the local statistics gathering";
    NOTIFICATION_INFO = new MBeanNotificationInfo[] { new MBeanNotificationInfo(notifTypes, name, description) };
  }

  private final SynchronizedLong sequenceNumber;

  private final StatisticsGathererSubSystem subsystem;
  private final NewCommonL2Config config;

  public StatisticsLocalGathererMBeanImpl(final StatisticsGathererSubSystem subsystem, final NewCommonL2Config config) throws NotCompliantMBeanException {
    super(StatisticsLocalGathererMBean.class, true, true);
    Assert.assertNotNull("subsystem", subsystem);
    Assert.assertNotNull("config", config);
    sequenceNumber = new SynchronizedLong(0L);
    this.subsystem = subsystem;
    this.config = config;

    // keep at the end of the constructor to make sure that all the initialization
    // is done before registering this instance as a listener
    this.subsystem.getStatisticsGatherer().addListener(this);
    this.subsystem.getStatisticsStore().addListener(this);
  }

  public MBeanNotificationInfo[] getNotificationInfo() {
    return NOTIFICATION_INFO;
  }

  public void reset() {
  }

  public void connect() {
    try {
      subsystem.getStatisticsGatherer().connect(TCSocketAddress.LOOPBACK_IP, config.jmxPort().getInt());
    } catch (TCStatisticsGathererException e) {
      throw new RuntimeException(e);
    }
  }

  public void disconnect() {
    try {
      subsystem.getStatisticsGatherer().disconnect();
    } catch (TCStatisticsGathererException e) {
      throw new RuntimeException(e);
    }
  }

  public void reinitialize() {
    try {
      subsystem.reinitialize();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public void createSession(final String sessionId) {
    try {
      subsystem.getStatisticsGatherer().createSession(sessionId);
    } catch (TCStatisticsGathererException e) {
      throw new RuntimeException(e);
    }
  }

  public String getActiveSessionId() {
    return subsystem.getStatisticsGatherer().getActiveSessionId();
  }

  public String[] getAvailableSessionIds() {
    try {
      return subsystem.getStatisticsStore().getAvailableSessionIds();
    } catch (TCStatisticsStoreException e) {
      throw new RuntimeException(e);
    }
  }

  public void closeSession() {
    try {
      subsystem.getStatisticsGatherer().closeSession();
    } catch (TCStatisticsGathererException e) {
      throw new RuntimeException(e);
    }
  }

  public String[] getSupportedStatistics() {
    try {
      return subsystem.getStatisticsGatherer().getSupportedStatistics();
    } catch (TCStatisticsGathererException e) {
      throw new RuntimeException(e);
    }
  }

  public void enableStatistics(String[] names) {
    try {
      subsystem.getStatisticsGatherer().enableStatistics(names);
    } catch (TCStatisticsGathererException e) {
      throw new RuntimeException(e);
    }
  }

  public StatisticData[] captureStatistic(final String name) {
    try {
      return subsystem.getStatisticsGatherer().captureStatistic(name);
    } catch (TCStatisticsGathererException e) {
      throw new RuntimeException(e);
    }
  }

  public void startCapturing() {
    try {
      subsystem.getStatisticsGatherer().startCapturing();
    } catch (TCStatisticsGathererException e) {
      throw new RuntimeException(e);
    }
  }

  public void stopCapturing() {
    try {
      subsystem.getStatisticsGatherer().stopCapturing();
    } catch (TCStatisticsGathererException e) {
      throw new RuntimeException(e);
    }
  }

  public void setGlobalParam(final String key, final Object value) {
    try {
      subsystem.getStatisticsGatherer().setGlobalParam(key, value);
    } catch (TCStatisticsGathererException e) {
      throw new RuntimeException(e);
    }
  }

  public Object getGlobalParam(final String key) {
    try {
      return subsystem.getStatisticsGatherer().getGlobalParam(key);
    } catch (TCStatisticsGathererException e) {
      throw new RuntimeException(e);
    }
  }

  public void setSessionParam(final String key, final Object value) {
    try {
      subsystem.getStatisticsGatherer().setSessionParam(key, value);
    } catch (TCStatisticsGathererException e) {
      throw new RuntimeException(e);
    }
  }

  public Object getSessionParam(final String key) {
    try {
      return subsystem.getStatisticsGatherer().getSessionParam(key);
    } catch (TCStatisticsGathererException e) {
      throw new RuntimeException(e);
    }
  }

  public void clearStatistics(final String sessionId) {
    try {
      subsystem.getStatisticsStore().clearStatistics(sessionId);
    } catch (TCStatisticsStoreException e) {
      throw new RuntimeException(e);
    }
  }

  public void clearAllStatistics() {
    try {
      subsystem.getStatisticsStore().clearAllStatistics();
    } catch (TCStatisticsStoreException e) {
      throw new RuntimeException(e);
    }
  }

  private void createAndSendNotification(final String type, final Object data) {
    final Notification notification = new Notification(type, StatisticsLocalGathererMBeanImpl.this, sequenceNumber.increment(), System.currentTimeMillis());
    notification.setUserData(data);
    sendNotification(notification);
  }

  public void connected(String managerHostName, int managerPort) {
    createAndSendNotification(STATISTICS_LOCALGATHERER_CONNECTED_TYPE, managerHostName + ":" + managerPort);
  }

  public void disconnected() {
    createAndSendNotification(STATISTICS_LOCALGATHERER_DISCONNECTED_TYPE, null);
  }

  public void reinitialized() {
    createAndSendNotification(STATISTICS_LOCALGATHERER_REINITIALIZED_TYPE, null);
  }

  public void capturingStarted(String sessionId) {
    createAndSendNotification(STATISTICS_LOCALGATHERER_CAPTURING_STARTED_TYPE, sessionId);
  }

  public void capturingStopped(String sessionId) {
    createAndSendNotification(STATISTICS_LOCALGATHERER_CAPTURING_STOPPED_TYPE, sessionId);
  }

  public void sessionCreated(String sessionId) {
    createAndSendNotification(STATISTICS_LOCALGATHERER_SESSION_CREATED_TYPE, sessionId);
  }

  public void sessionClosed(String sessionId) {
    createAndSendNotification(STATISTICS_LOCALGATHERER_SESSION_CLOSED_TYPE, sessionId);
  }

  public void sessionCleared(String sessionId) {
    createAndSendNotification(STATISTICS_LOCALGATHERER_SESSION_CLEARED_TYPE, sessionId);
  }

  public void allSessionsCleared() {
    createAndSendNotification(STATISTICS_LOCALGATHERER_ALLSESSIONS_CLEARED_TYPE, null);
  }

  public void opened() {
    createAndSendNotification(STATISTICS_LOCALGATHERER_STORE_OPENED_TYPE, null);
  }

  public void closed() {
    createAndSendNotification(STATISTICS_LOCALGATHERER_STORE_CLOSED_TYPE, null);
  }
}