/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.objectserver.tx;

import com.tc.net.groups.NodeID;
import com.tc.object.tx.TransactionID;
import com.tc.object.tx.TxnBatchID;

public interface TransactionBatchManager {

  public void defineBatch(NodeID node, TxnBatchID batchID, int numTxns) throws BatchDefinedException;

  public boolean batchComponentComplete(NodeID committerID, TxnBatchID batchID, TransactionID txnID)
      throws NoSuchBatchException;

  public void shutdownNode(NodeID nodeID);
}