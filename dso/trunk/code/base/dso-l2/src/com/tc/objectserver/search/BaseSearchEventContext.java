/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.objectserver.search;

import com.tc.async.api.MultiThreadedEventContext;
import com.tc.object.tx.ServerTransactionID;
import com.tc.objectserver.metadata.MetaDataProcessingContext;

class BaseSearchEventContext implements SearchEventContext, MultiThreadedEventContext {

  private final MetaDataProcessingContext metaDataContext;
  private final ServerTransactionID       txnID;
  private final String                    cacheName;

  public BaseSearchEventContext(ServerTransactionID txnID, String cacheName, MetaDataProcessingContext metaDataContext) {
    this.txnID = txnID;
    this.cacheName = cacheName;
    this.metaDataContext = metaDataContext;
  }

  public final Object getKey() {
    // XXX: This need to kept consistent with com.tc.objectserver.search.SearchQueryContext.getKey()
    return txnID.getSourceID();
  }

  public MetaDataProcessingContext getMetaDataProcessingContext() {
    return metaDataContext;
  }

  public String getCacheName() {
    return cacheName;
  }

}
