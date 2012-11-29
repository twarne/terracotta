package com.tc.object;

import com.tc.net.GroupID;

/**
 * @author tim
 */
public class RemoteResourceManagerImpl implements RemoteResourceManager {
  private static final int MAX_THROTTLE_MS = 2000; // TODO: Make this configurable?

  private volatile boolean throttleStateInitialized = false;
  private volatile boolean throwException = false;
  private volatile long throttleTime = 0;

  @Override
  public synchronized void handleThrottleMessage(final GroupID groupID, final boolean exception, final float throttle) {
    throwException = exception;
    throttleTime = (long)(throttle * MAX_THROTTLE_MS);
    throttleStateInitialized = true;
    notifyAll();
  }

  @Override
  public void throttleIfMutationIfNecessary(final ObjectID parentObject) {
    if (!throttleStateInitialized) {
      synchronized (this) {
        boolean interrupted = false;
        while (!throttleStateInitialized) {
          try {
            wait();
          } catch (InterruptedException e) {
            interrupted = true;
          }
        }
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }

    if (throwException) {
      throw new OutOfResourceException("Server is full.");
    } else if (throttleTime > 0) {
      try {
        Thread.sleep(throttleTime);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
