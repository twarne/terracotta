/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.object.cache;

public interface Evictable {

  public void evictCache(CacheStats stat);
}
