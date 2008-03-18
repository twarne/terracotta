/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.statistics.retrieval.actions;

import org.hyperic.sigar.CpuPerc;
import org.hyperic.sigar.Sigar;
import org.hyperic.sigar.SigarException;

import com.tc.exception.TCRuntimeException;
import com.tc.statistics.StatisticData;
import com.tc.statistics.StatisticRetrievalAction;
import com.tc.statistics.StatisticType;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Date;
import java.util.Locale;

public class SRACpuCombined implements StatisticRetrievalAction, SRACpuConstants  {

  public final static String ACTION_NAME = "cpu combined";

  private final static String ELEMENT_PREFIX = "cpu ";

  private final Sigar sigar;

  public SRACpuCombined() {
    sigar = new Sigar();
  }

  public StatisticData[] retrieveStatisticData() {
    try {
      NumberFormat format = NumberFormat.getNumberInstance(Locale.US);
      format.setGroupingUsed(false);
      format.setMaximumFractionDigits(3);

      CpuPerc[] cpuPercList = sigar.getCpuPercList();
      StatisticData[] data = new StatisticData[cpuPercList.length];
      for (int i = 0; i < cpuPercList.length; i++) {
        String element = ELEMENT_PREFIX + i;
        double combined = cpuPercList[i].getCombined();
        data[i] = new StatisticData(ACTION_NAME, element, Double.isNaN(combined) || Double.isInfinite(combined) ? null : new BigDecimal(format.format(combined)));
      }
      return data;
    } catch (SigarException e) {
      throw new TCRuntimeException(e);
    }
  }

  public String getName() {
    return ACTION_NAME;
  }

  public StatisticType getType() {
    return StatisticType.SNAPSHOT;
  }
}