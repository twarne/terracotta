/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.statistics.database.exceptions;

public class TCStatisticsDatabaseNotFoundException extends TCStatisticsDatabaseException {
  private final String driver;

  public TCStatisticsDatabaseNotFoundException(final String driver, final Throwable cause) {
    super("Unable to load JDBC driver '" + driver + "'", cause);
    this.driver = driver;
  }

  public String getDriver() {
    return driver;
  }
}
