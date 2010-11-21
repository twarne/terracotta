/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.objectserver.search;

import com.tc.object.metadata.NVPair;
import com.tc.object.metadata.ValueType;
import com.tc.search.SortOperations;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class NullIndexManager implements IndexManager {

  public boolean createIndex(String name, Map<String, ValueType> schema) {
    return false;
  }

  public boolean deleteIndex(String name) {
    return false;
  }

  public Index getIndex(String name) {
    return new NullIndex();
  }

  public SearchResult searchIndex(String name, LinkedList queryStack, boolean includeKeys, Set<String> attributeSet,
                                  Map<String, SortOperations> sortAttributes, List<NVPair> aggregators, int maxResults) {
    return null;
  }

  public void shutdown() {
    // no nothing
  }

  private static final class NullIndex implements Index {

    public void close() {
      //
    }

    public void remove(Object key) {
      //
    }

    public void upsert(Object key, List<NVPair> attributes) {
      //
    }

    public SearchResult search(LinkedList queryStack, boolean includeKeys, Set<String> attributeSet,
                               Map<String, SortOperations> sortAttributes, List<NVPair> aggregators, int maxResults) {
      return null;
    }

  }

}
