/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.statistics.store.exceptions;

public class TCStatisticsStoreOpenErrorException extends TCStatisticsStoreException {
  public TCStatisticsStoreOpenErrorException(final Throwable cause) {
    super("Unexpected error while opening the store database.", cause);
  }
}