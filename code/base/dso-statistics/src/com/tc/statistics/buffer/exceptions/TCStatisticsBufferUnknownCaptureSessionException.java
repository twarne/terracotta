/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.statistics.buffer.exceptions;

public class TCStatisticsBufferUnknownCaptureSessionException extends TCStatisticsBufferException {
  private final String sessionId;

  public TCStatisticsBufferUnknownCaptureSessionException(final String sessionId, final Throwable cause) {
    super("The capture session with cluster-wide ID '" + sessionId + "' couldn't be found.", cause);
    this.sessionId = sessionId;
  }

  public String getSessionId() {
    return sessionId;
  }
}