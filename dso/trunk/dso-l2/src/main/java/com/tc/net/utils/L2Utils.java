/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.net.utils;

import com.google.common.base.Preconditions;
import com.tc.bytes.TCByteBufferFactory;
import com.tc.properties.TCPropertiesConsts;
import com.tc.properties.TCPropertiesImpl;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class L2Utils {
  private static final int MAX_DEFAULT_COMM_THREADS = 16;
  private static final int MAX_DEFAULT_STAGE_THREADS = 16;
  private static final int MAX_APPLY_STAGE_THREADS = 64;
  public static final long MIN_COMMS_DIRECT_MEMORY_REQUIREMENT = 4 * 1024 * 1024;  // 4MiB
  public static final long MAX_COMMS_DIRECT_MEMORY_REQUIREMENT = 256 * 1024 * 1024; // 256MiB

  public static int getOptimalCommWorkerThreads() {
    int def = Math.min(Runtime.getRuntime().availableProcessors() * 2, MAX_DEFAULT_COMM_THREADS);
    return TCPropertiesImpl.getProperties().getInt("l2.tccom.workerthreads", def);
  }

  public static int getOptimalStageWorkerThreads() {
    int def = Math.min(Runtime.getRuntime().availableProcessors() * 2, MAX_DEFAULT_STAGE_THREADS);
    return TCPropertiesImpl.getProperties().getInt("l2.seda.stage.workerthreads", def);
  }

  /**
   * Calculates the optimal number of worker threads for the apply stage.
   * <p/>{@code l2.seda.apply.stage.threads} configuration property overrides this value.
   *
   * @return the optimal number of threads for the apply stage
   */
  public static int getOptimalApplyStageWorkerThreads(boolean restartable) {
    final int cpus = Runtime.getRuntime().availableProcessors();
    // in restartable mode wait/compute time ratio is different due to disk I/O
    final int threadsCount = (restartable) ? calculateOptimalThreadsCount(cpus, 30, 70, BigDecimal.ONE)
        : calculateOptimalThreadsCount(cpus, 0, 100, BigDecimal.ONE);
    return TCPropertiesImpl.getProperties().getInt(TCPropertiesConsts.L2_SEDA_APPLY_STAGE_THREADS,
        Math.min(threadsCount, MAX_APPLY_STAGE_THREADS));
  }

  /**
   * Calculates the optimal number of worker threads based on the formula below:
   * <p/> {@code N_threads = N_cpu * U_cpu * (1 + W/C)}, where
   * <ul>
   * <li>{@code N_cpu} - number of CPUs.</li>
   * <li>{@code U_cpu} - target CPU utilization, 0 <= U_cpu <= 1.</li>
   * <li>{@code W/C} - ratio of wait time to compute time. Empirical value.</li>
   * </ul>
   * <p/> If the task is pure computational, that is {@code wait = 0},
   * one more thread added to compensate any possible pauses (e.g. page fault).
   *
   * @return the optimal number of threads
   */
  public static int calculateOptimalThreadsCount(final int cpus, final long wait, final long compute,
                                                 final BigDecimal targetUtilization) {
    Preconditions.checkArgument(compute > 0);
    Preconditions.checkArgument(wait >= 0);
    Preconditions.checkArgument(cpus > 0);
    Preconditions.checkArgument(targetUtilization != null
                                && targetUtilization.compareTo(BigDecimal.ZERO) > 0
                                && targetUtilization.compareTo(BigDecimal.ONE) <= 0);

    final BigDecimal cpusCount = new BigDecimal(cpus);
    final BigDecimal waitTime = new BigDecimal(wait);
    final BigDecimal computeTime = new BigDecimal(compute);
    int threadsCount = cpusCount.multiply(targetUtilization).multiply(BigDecimal.ONE
        .add(waitTime.divide(computeTime, 1, RoundingMode.HALF_UP))).setScale(0, RoundingMode.HALF_UP).intValue();
    return (wait == 0) ? threadsCount + 1 : threadsCount;
  }

  /**
   * Calculates max possible direct memory consumption by TC Communication system. In fact, TC Comms can ask for more
   * direct byte buffers than computed here if the buffer pool is fully used up, but its rare though.
   *
   * @return long - maximum consumable direct memory byte buffers in bytes by the comms system.
   */
  public static long getMaxDirectMemmoryConsumable() {

    // L2<==L1, L2<==>L2
    final int totalCommsThreads = getOptimalCommWorkerThreads() * 2;
    final boolean poolingEnabled = TCPropertiesImpl.getProperties()
        .getBoolean(TCPropertiesConsts.TC_BYTEBUFFER_POOLING_ENABLED);
    final int directMemoryCommonPool = (TCPropertiesImpl.getProperties()
        .getInt(TCPropertiesConsts.TC_BYTEBUFFER_COMMON_POOL_MAXCOUNT, 3000));
    final int directMemoryThreadLocalPool = TCPropertiesImpl.getProperties()
        .getInt(TCPropertiesConsts.TC_BYTEBUFFER_THREADLOCAL_POOL_MAXCOUNT, 2000);

    long totalDirectMemeoryNeeded;
    if (poolingEnabled) {
      totalDirectMemeoryNeeded = (totalCommsThreads * directMemoryThreadLocalPool * TCByteBufferFactory.FIXED_BUFFER_SIZE)
                                 + (directMemoryCommonPool * TCByteBufferFactory.FIXED_BUFFER_SIZE);
    } else {
      int maxPossbileMessageBytesSend = (TCPropertiesImpl.getProperties()
          .getBoolean(TCPropertiesConsts.TC_MESSAGE_GROUPING_ENABLED) ? TCPropertiesImpl.getProperties()
                                                                            .getInt(TCPropertiesConsts.TC_MESSAGE_GROUPING_MAXSIZE_KB) * 1024 : 1024);
      totalDirectMemeoryNeeded = totalCommsThreads * maxPossbileMessageBytesSend * 4;
    }

    totalDirectMemeoryNeeded = (totalDirectMemeoryNeeded < MIN_COMMS_DIRECT_MEMORY_REQUIREMENT ? MIN_COMMS_DIRECT_MEMORY_REQUIREMENT
        : totalDirectMemeoryNeeded);
    return (totalDirectMemeoryNeeded > MAX_COMMS_DIRECT_MEMORY_REQUIREMENT ? MAX_COMMS_DIRECT_MEMORY_REQUIREMENT
        : totalDirectMemeoryNeeded);
  }
}
