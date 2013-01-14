/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.objectserver.dgc.impl;

import com.tc.object.ObjectID;
import com.tc.objectserver.context.DGCResultContext;
import com.tc.objectserver.dgc.api.GarbageCollector;
import com.tc.objectserver.dgc.api.GarbageCollectorEventListener;
import com.tc.objectserver.impl.ObjectManagerConfig;
import com.tc.text.PrettyPrinter;
import com.tc.util.concurrent.LifeCycleState;

import java.util.Collection;

import junit.framework.TestCase;

/**
 * this tests the frequency of young and full gc
 */
public class GarbageCollectorThreadTest extends TestCase {

  private static final long TEST_DURATION_MILLIS = 30000L;
  private static final long YOUNG_GC_FREQUENCY   = 3000L;
  private static final long FULL_GC_FREQUENCY    = 10000L;

  public void testYoungGCOnNoFullGC() {

    ObjectManagerConfig config = new ObjectManagerConfig(FULL_GC_FREQUENCY, false, true, true, true,
                                                         YOUNG_GC_FREQUENCY, 1000);
    TestGarbageCollector collector = new TestGarbageCollector();

    ThreadGroup gp = new ThreadGroup("test group");
    GarbageCollectorThread garbageCollectorThread = new GarbageCollectorThread(gp, "YoungGCOnThread", collector, config);
    garbageCollectorThread.start();

    try {
      Thread.sleep(TEST_DURATION_MILLIS);
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }

    garbageCollectorThread.requestStop();

    assertTrue("young gc on only", collector.fullGCCount == 0);
    assertTrue("should call full young gc", collector.youngGCCount > 0);

  }

  public void testYoungGCOn() {

    ObjectManagerConfig config = new ObjectManagerConfig(FULL_GC_FREQUENCY, true, true, true, true, YOUNG_GC_FREQUENCY,
                                                         1000);
    TestGarbageCollector collector = new TestGarbageCollector();

    ThreadGroup gp = new ThreadGroup("test group");
    GarbageCollectorThread garbageCollectorThread = new GarbageCollectorThread(gp, "YoungGCOnThread", collector, config);
    garbageCollectorThread.start();

    try {
      Thread.sleep(TEST_DURATION_MILLIS);
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }

    garbageCollectorThread.requestStop();

    assertTrue("young and full gc on", collector.fullGCCount > 0);
    assertTrue("should call full young gc", collector.youngGCCount > 0);

  }

  public void testYoungGCOff() {

    ObjectManagerConfig config = new ObjectManagerConfig(FULL_GC_FREQUENCY, true, true, true, false, -1, 1000);
    TestGarbageCollector collector = new TestGarbageCollector();

    ThreadGroup gp = new ThreadGroup("test group");
    GarbageCollectorThread garbageCollectorThread = new GarbageCollectorThread(gp, "YoungGCOffThread", collector,
                                                                               config);
    garbageCollectorThread.start();

    try {
      Thread.sleep(TEST_DURATION_MILLIS);
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }

    garbageCollectorThread.requestStop();

    assertTrue("should not call young gen when young is configured to be off", collector.youngGCCount == 0);
    assertTrue("should call full gc", collector.fullGCCount > 0);

  }

  private static final class TestGarbageCollector implements GarbageCollector {

    public long youngGCCount = 0;
    public long fullGCCount  = 0;

    @Override
    public void addListener(GarbageCollectorEventListener listener) {
      //
    }

    @Override
    public void changed(ObjectID changedObject, ObjectID oldReference, ObjectID newReference) {
      //
    }

    @Override
    public void deleteGarbage(DGCResultContext resultContext) {
      //
    }

    @Override
    public void waitToDisableGC() {
      // do nothing
    }

    @Override
    public boolean requestDisableGC() {
      return false;
    }

    @Override
    public void enableGC() {
      //
    }

    @Override
    public boolean isDisabled() {
      return false;
    }

    @Override
    public boolean isPaused() {
      return false;
    }

    @Override
    public boolean isPausingOrPaused() {
      return false;
    }

    @Override
    public boolean isStarted() {
      return true;
    }

    @Override
    public void notifyGCComplete() {
      //
    }

    @Override
    public void notifyNewObjectInitalized(ObjectID id) {
      //
    }

    @Override
    public void notifyObjectCreated(ObjectID id) {
      //
    }

    @Override
    public void notifyObjectsEvicted(Collection evicted) {
      //
    }

    @Override
    public void notifyReadyToGC() {
      //
    }

    @Override
    public void requestGCPause() {
      //
    }

    @Override
    public void setPeriodicEnabled(final boolean periodEnable) {
      // do nothing
    }

    @Override
    public boolean isPeriodicEnabled() {
      return false;
    }

    @Override
    public void setState(LifeCycleState st) {
      //
    }

    @Override
    public void start() {
      //
    }

    @Override
    public void stop() {
      //
    }

    @Override
    public PrettyPrinter prettyPrint(PrettyPrinter out) {
      return null;
    }

    @Override
    public boolean requestGCStart() {
      return true;
    }

    @Override
    public void waitToStartGC() {
      // do nothing
    }

    @Override
    public void waitToStartInlineGC() {
      // do nothing
    }

    @Override
    public void doGC(GCType type) {
      if (GCType.FULL_GC.equals(type)) {
        this.fullGCCount++;

      }
      if (GCType.YOUNG_GEN_GC.equals(type)) {
        this.youngGCCount++;
      }
    }

    @Override
    public boolean isDelete() {
      return false;
    }

    @Override
    public boolean requestGCDeleteStart() {
      return false;
    }

  }

}
