/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.statistics;

import EDU.oswego.cs.dl.util.concurrent.CopyOnWriteArraySet;

import com.tc.util.Assert;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

/**
 * This class indicates how a {@link StatisticRetrievalAction} should be
 * automatically captured during a capturing session.
 */
public class StatisticType {
  private final static Set TYPES = new CopyOnWriteArraySet();

  /**
   * Statistic will be automatically captured at the beginning of a capturing
   * session.
   */
  public final static StatisticType STARTUP = new StatisticType("STARTUP");

  /**
   * Statistics will be automatically captured during the capturing session,
   * based on the frequency that has been setup on the configuration.
   */
  public final static StatisticType SNAPSHOT = new StatisticType("SNAPSHOT");

  /**
   * Statistics of this type will not captured automatically. They can however
   * be captured on an as-needed basis through API calls.
   */
  public static final StatisticType TRIGGERED = new StatisticType("TRIGGERED");

  private final String identifier;

  private StatisticType(String identifier) {
    Assert.assertNotNull("identifier", identifier);
    this.identifier = identifier;
    TYPES.add(this);
  }

  public static Collection getAllTypes() {
    return Collections.unmodifiableCollection(TYPES);
  }

  public String toString() {
    return identifier;
  }

  public int hashCode() {
    return identifier.hashCode();
  }

  public boolean equals(Object object) {
    if (null == object) {
      return false;
    }

    return (object instanceof StatisticType)
           && ((StatisticType)object).identifier.equals(identifier);
  }
}
