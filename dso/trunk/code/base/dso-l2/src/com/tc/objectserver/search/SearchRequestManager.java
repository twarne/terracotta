/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.objectserver.search;

import com.tc.net.ClientID;
import com.tc.net.GroupID;
import com.tc.object.SearchRequestID;
import com.tc.object.metadata.NVPair;
import com.tc.search.IndexQueryResult;
import com.tc.search.aggregator.Aggregator;

import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * Manager query request from the client.
 * 
 * @author Nabib El-Rahman
 */
public interface SearchRequestManager {

  /**
   * Query request. TODO: currently just requesting an attribute and value to match against, this will change
   * drastically when query is built out.
   * 
   * @param clientID
   * @param requestID
   * @param groupIDFrom
   * @param cachename
   * @param queryStack
   * @param includeKeys
   * @param includeValues
   * @param attributeSet
   * @param sortAttributes
   * @param aggregators
   * @param maxResults
   * @param batchSize
   * @param prefetchFirstBatch
   */
  public void queryRequest(ClientID clientID, SearchRequestID requestID, GroupID groupIDFrom, String cachename,
                           LinkedList queryStack, boolean includeKeys, boolean includeValues, Set<String> attributeSet,
                           List<NVPair> sortAttributes, List<NVPair> aggregators, int maxResults, int batchSize,
                           boolean prefetchFirstBatch);

  /**
   * Query response.
   * 
   * @param SearchQueryContext queriedContext
   * @param List<SearchQueryResult> results
   * @param aggregatorResults
   */
  public void queryResponse(SearchQueryContext queriedContext, List<IndexQueryResult> results,
                            List<Aggregator> aggregators, int batchSize, boolean prefetchFirstBatch);

  /**
   * Query error response
   */
  public void queryErrorResponse(SearchQueryContext sqc, String message);

}
