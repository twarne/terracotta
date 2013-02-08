/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.runtime;

import com.tc.exception.TCRuntimeException;
import com.tc.lang.TCThreadGroup;
import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.util.runtime.Os;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class TCMemoryManagerImpl implements TCMemoryManager {

  private static final TCLogger logger        = TCLogging.getLogger(TCMemoryManagerImpl.class);
  private static final String          CMS_NAME      = "ConcurrentMarkSweep";
  private static final String          CMS_WARN_MESG = "Terracotta does not recommend ConcurrentMarkSweep Collector.";
  private static final int      LEAST_COUNT = 2;
  private static final long     SLEEP_INTERVAL = 3000;

  private final List            listeners     = new CopyOnWriteArrayList();


  private MemoryMonitor         monitor;

  private final TCThreadGroup   threadGroup;

  public TCMemoryManagerImpl(TCThreadGroup threadGroup) {
    this.threadGroup = threadGroup;
  }

  // CDV-1181 warn if using CMS
  @Override
  public void checkGarbageCollectors() {
    List<GarbageCollectorMXBean> gcmbeans = ManagementFactory.getGarbageCollectorMXBeans();
    boolean foundCMS = false;
    for (GarbageCollectorMXBean mbean : gcmbeans) {
      String gcname = mbean.getName();
      logger.info("GarbageCollector: " + gcname);
      if (CMS_NAME.equals(gcname)) {
        foundCMS = true;
      }
    }
    if (foundCMS) {
      logger.warn(CMS_WARN_MESG);
    }
  }

  private void verifyInput(long sleep, int lc) {
    if (sleep <= 0) { throw new AssertionError("Sleep Interval cannot be <= 0 : sleep Interval = " + sleep); }
    if (lc <= 0 || lc >= 100) { throw new AssertionError("Least Count should be > 0 && < 100 : " + lc
                                                         + " Outside range"); }
  }

  @Override
  public void registerForMemoryEvents(MemoryEventsListener listener) {
    listeners.add(listener);
    startMonitorIfNecessary();
  }

  @Override
  public void unregisterForMemoryEvents(MemoryEventsListener listener) {
    listeners.remove(listener);
    stopMonitorIfNecessary();
  }

  private synchronized void stopMonitorIfNecessary() {
    if (listeners.size() == 0) {
      stopMonitorThread();
    }
  }

  /**
   * XXX: Should we wait for the monitor thread to stop completely.
   */
  private void stopMonitorThread() {
    if (monitor != null) {
      monitor.stop();
      monitor = null;
    }
  }

  private synchronized void startMonitorIfNecessary() {
    if (listeners.size() > 0 && monitor == null) {
      this.monitor = new MemoryMonitor(TCRuntime.getJVMMemoryManager(), SLEEP_INTERVAL);
      Thread t = new Thread(this.threadGroup, this.monitor);
      t.setDaemon(true);
      if (Os.isSolaris()) {
        t.setPriority(Thread.MAX_PRIORITY);
        t.setName("TC Memory Monitor(High Priority)");
      } else {
        t.setName("TC Memory Monitor");
      }
      t.start();
    }
  }

  private void fireMemoryEvent(MemoryUsage mu) {
    for (Iterator i = listeners.iterator(); i.hasNext();) {
      MemoryEventsListener listener = (MemoryEventsListener) i.next();
      listener.memoryUsed(mu);
    }
  }

  public class MemoryMonitor implements Runnable {

    private final JVMMemoryManager manager;
    private volatile boolean       run = true;
    private int                    lastUsed;
    private long                   sleepTime;

    public MemoryMonitor(JVMMemoryManager manager, long sleepInterval) {
      this.manager = manager;
      this.sleepTime = sleepInterval;
    }

    public void stop() {
      run = false;
    }

    @Override
    public void run() {
      logger.debug("Starting Memory Monitor - sleep interval - " + sleepTime);
      while (run) {
        try {
          Thread.sleep(sleepTime);
          MemoryUsage mu = manager.getMemoryUsage();
          fireMemoryEvent(mu);
          adjust(mu);
        } catch (Throwable t) {
          // for debugging pupose
          StackTraceElement[] trace = t.getStackTrace();
          for (StackTraceElement element : trace)
            logger.warn(element.toString());
          logger.error(t);
          throw new TCRuntimeException(t);
        }
      }
      logger.debug("Stopping Memory Monitor - sleep interval - " + sleepTime);
    }

    private void adjust(MemoryUsage mu) {
      int usedPercentage = mu.getUsedPercentage();
      try {
        if (lastUsed != 0 && lastUsed < usedPercentage) {
          int diff = usedPercentage - lastUsed;
          long l_sleep = this.sleepTime;
          if (diff > LEAST_COUNT * 1.5 && l_sleep > 1) {
            // decrease sleep time
            this.sleepTime = Math.max(1, l_sleep * LEAST_COUNT / diff);
            logger.info("Sleep time changed to : " + this.sleepTime);
          } else if (diff < LEAST_COUNT * 0.5 && l_sleep < SLEEP_INTERVAL) {
            // increase sleep time
            this.sleepTime = Math.min(SLEEP_INTERVAL, l_sleep * LEAST_COUNT / diff);
            logger.info("Sleep time changed to : " + this.sleepTime);
          }
        }
      } finally {
        lastUsed = usedPercentage;
      }
    }
  }

  @Override
  public synchronized void shutdown() {
    stopMonitorThread();
  }

}
