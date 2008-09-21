/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.l2.objectserver;

import com.tc.l2.msg.ObjectSyncMessage;
import com.tc.net.NodeID;
import com.tc.object.tx.ServerTransactionID;
import com.tc.object.tx.TransactionID;
import com.tc.objectserver.tx.ServerTransaction;

import java.util.concurrent.atomic.AtomicLong;

public class ServerTransactionFactory {

  private AtomicLong tid = new AtomicLong();

  public static ServerTransaction createTxnFrom(ObjectSyncMessage syncMsg) {
    ObjectSyncServerTransaction txn = new ObjectSyncServerTransaction(syncMsg.getServerTransactionID(), syncMsg
        .getOids(), syncMsg.getDnaCount(), syncMsg.getDNAs(), syncMsg.getRootsMap(), syncMsg.messageFrom());
    return txn;
  }

  private TransactionID getNextTransactionID() {
    return new TransactionID(tid.incrementAndGet());
  }

  /**
   * Since the transaction IDs that are generated by this factory are not coming from a persisted DB this will
   * regenerate the same TransactionID on server restart, but the nodeID should be different.
   */
  public ServerTransactionID getNextServerTransactionID(NodeID localNodeID) {
    return new ServerTransactionID(localNodeID, getNextTransactionID());
  }

}
