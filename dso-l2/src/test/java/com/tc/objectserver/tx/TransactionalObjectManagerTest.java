/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.objectserver.tx;

import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.mockito.InOrder;

import com.tc.net.ClientID;
import com.tc.object.ObjectID;
import com.tc.object.tx.ServerTransactionID;
import com.tc.object.tx.TransactionID;
import com.tc.objectserver.context.ApplyTransactionContext;
import com.tc.objectserver.core.api.ManagedObject;
import com.tc.objectserver.core.api.ServerConfigurationContext;
import com.tc.objectserver.gtx.TestGlobalTransactionManager;
import com.tc.objectserver.impl.TestObjectManager;
import com.tc.objectserver.managedobject.ApplyTransactionInfo;
import com.tc.test.TCTestCase;
import com.tc.util.ObjectIDSet;

import java.util.Collection;
import java.util.Collections;

import static java.util.Arrays.asList;
import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.core.IsNot.not;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.anyCollection;
import static org.mockito.Matchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TransactionalObjectManagerTest extends TCTestCase {

  private TestObjectManager                      objectManager;
  private TestTransactionalStageCoordinator      coordinator;
  private TransactionalObjectManagerImpl txObjectManager;
  private TestGlobalTransactionManager gtxMgr;

  @Override
  public void setUp() {
    this.objectManager = new TestObjectManager();
    this.coordinator = spy(new TestTransactionalStageCoordinator());
    this.gtxMgr = new TestGlobalTransactionManager();
    this.txObjectManager = new TransactionalObjectManagerImpl(this.objectManager, gtxMgr, this.coordinator);
    ServerConfigurationContext scc = mock(ServerConfigurationContext.class);
    when(scc.getTransactionManager()).thenReturn(new TestServerTransactionManager());
  }

  public void testSimpleLookup() throws Exception {
    txObjectManager.addTransactions(asList(createTransaction(0, 1, asList(1L), asList(2L, 3L))));
    verify(coordinator).initiateLookup();

    txObjectManager.lookupObjectsForTransactions();
    verify(coordinator).addToApplyStage(argThat(hasTransactionID(0, 1)));

    ApplyTransactionInfo applyTransactionInfo = applyInfoWithTransactionID(0, 1);
    txObjectManager.applyTransactionComplete(applyTransactionInfo);
    verify(applyTransactionInfo).addObjectsToBeReleased(anyCollection());
  }

  public void testOverlappedLookups() throws Exception {
    txObjectManager.addTransactions(asList(createTransaction(0, 1, Collections.EMPTY_SET, asList(1L, 2L))));

    txObjectManager.lookupObjectsForTransactions();
    verify(coordinator).addToApplyStage(argThat(hasTransactionID(0, 1)));

    txObjectManager.addTransactions(asList(createTransaction(0, 2, Collections.EMPTY_SET, asList(2L, 3L))));
    verify(coordinator, never()).addToApplyStage(argThat(hasTransactionID(0, 2)));

    ApplyTransactionInfo applyTransactionInfo = applyInfoWithTransactionID(0, 1);
    txObjectManager.applyTransactionComplete(applyTransactionInfo);

    objectManager.releaseAll(applyTransactionInfo.getObjectsToRelease());

    txObjectManager.lookupObjectsForTransactions();
    verify(coordinator).addToApplyStage(argThat(hasTransactionID(0 , 2)));
  }

  public void testProcessPendingInOrder() throws Exception {
    txObjectManager.addTransactions(asList(createTransaction(0, 1, Collections.EMPTY_SET, asList(1L))));
    txObjectManager.lookupObjectsForTransactions();
    verify(coordinator).addToApplyStage(argThat(hasTransactionID(0, 1)));

    // Finish the transaction but don't release Object1 yet, this will force later transactions to go pending on object1
    ApplyTransactionInfo applyTransactionInfo1 = applyInfoWithTransactionID(0, 1);
    txObjectManager.applyTransactionComplete(applyTransactionInfo1);

    txObjectManager.addTransactions(asList(createTransaction(0, 2, Collections.EMPTY_SET, asList(1L))));
    txObjectManager.lookupObjectsForTransactions();
    verify(coordinator, never()).addToApplyStage(argThat(hasTransactionID(0, 2)));

    // This transaction goes through because it does not use object1
    txObjectManager.addTransactions(asList(createTransaction(0, 3, Collections.EMPTY_SET, asList(2L))));
    txObjectManager.lookupObjectsForTransactions();
    verify(coordinator).addToApplyStage(argThat(hasTransactionID(0, 3)));

    txObjectManager.addTransactions(asList(createTransaction(0, 4, Collections.EMPTY_SET, asList(1L)),
        createTransaction(0, 5, Collections.EMPTY_SET, asList(1L))));
    txObjectManager.lookupObjectsForTransactions();
    verify(coordinator, never()).addToApplyStage(argThat(hasTransactionID(0, 4)));
    verify(coordinator, never()).addToApplyStage(argThat(hasTransactionID(0, 5)));

    // Release the object and verify that all unblocked transactions are run in order.
    objectManager.releaseAll(applyTransactionInfo1.getObjectsToRelease());

    InOrder inOrder = inOrder(coordinator);
    txObjectManager.lookupObjectsForTransactions();
    inOrder.verify(coordinator).addToApplyStage(argThat(hasTransactionID(0, 2)));
    inOrder.verify(coordinator).addToApplyStage(argThat(hasTransactionID(0, 4)));
    inOrder.verify(coordinator).addToApplyStage(argThat(hasTransactionID(0, 5)));

    ApplyTransactionInfo applyTransactionInfo2 = applyInfoWithTransactionID(0, 3);
    txObjectManager.applyTransactionComplete(applyTransactionInfo2);
    objectManager.releaseAll(applyTransactionInfo2.getObjectsToRelease());

    txObjectManager.applyTransactionComplete(applyInfoWithTransactionID(0, 2));
    txObjectManager.applyTransactionComplete(applyInfoWithTransactionID(0, 4));

    ApplyTransactionInfo applyTransactionInfo5 = applyInfoWithTransactionID(0, 5);
    txObjectManager.applyTransactionComplete(applyTransactionInfo5);
    assertThat(applyTransactionInfo5.getObjectsToRelease(), containsObjectWithID(new ObjectID(1)));
  }

  public void testAlreadyCommittedTransaction() throws Exception {
    gtxMgr.commit(null, new ServerTransactionID(new ClientID(0), new TransactionID(1)));
    txObjectManager.addTransactions(asList(createTransaction(0, 1, Collections.EMPTY_SET, asList(1L))));
    txObjectManager.lookupObjectsForTransactions();
    verify(coordinator).addToApplyStage(argThat(allOf(hasTransactionID(0, 1), not(needsApply()))));

    ApplyTransactionInfo applyTransactionInfo = applyInfoWithTransactionID(0, 1);
    txObjectManager.applyTransactionComplete(applyTransactionInfo);
    assertThat(applyTransactionInfo.getObjectsToRelease(), containsObjectWithID(new ObjectID(1L)));
    objectManager.releaseAll(applyTransactionInfo.getObjectsToRelease());
  }

  public void testCheckoutBatching() throws Exception {
    txObjectManager.addTransactions(asList(createTransaction(0, 1, Collections.EMPTY_SET, asList(1L))));
    txObjectManager.addTransactions(asList(createTransaction(0, 2, asList(2L), asList(1L))));
    txObjectManager.addTransactions(asList(createTransaction(0, 3, Collections.EMPTY_SET, asList(1L, 3L))));
    txObjectManager.lookupObjectsForTransactions();

    InOrder inOrder = inOrder(coordinator);
    inOrder.verify(coordinator).addToApplyStage(argThat(hasTransactionID(0, 1)));
    inOrder.verify(coordinator).addToApplyStage(argThat(hasTransactionID(0, 2)));
    inOrder.verify(coordinator, never()).addToApplyStage(argThat(hasTransactionID(0, 3)));

    ApplyTransactionInfo applyTransactionInfo1 = applyInfoWithTransactionID(0, 1);
    txObjectManager.applyTransactionComplete(applyTransactionInfo1);
    verify(applyTransactionInfo1, never()).addObjectsToBeReleased(anyCollection());

    ApplyTransactionInfo applyTransactionInfo2 = applyInfoWithTransactionID(0, 2);
    txObjectManager.applyTransactionComplete(applyTransactionInfo2);
    verify(applyTransactionInfo2).addObjectsToBeReleased(argThat(containsObjectWithID(new ObjectID(1))));
    objectManager.releaseAll(applyTransactionInfo2.getObjectsToRelease());

    txObjectManager.lookupObjectsForTransactions();

    verify(coordinator).addToApplyStage(argThat(hasTransactionID(0, 3)));
  }

  private ApplyTransactionInfo applyInfoWithTransactionID(long clientId, long transactionID) {
    return spy(new ApplyTransactionInfo(true, new ServerTransactionID(new ClientID(clientId), new TransactionID(transactionID)), true));
  }

  private Matcher<Collection<ManagedObject>> containsObjectWithID(final ObjectID id) {
    return new BaseMatcher<Collection<ManagedObject>>() {
      @Override
      public boolean matches(final Object o) {
        if (o instanceof Collection) {
          for (Object obj : (Collection) o) {
            if (obj instanceof ManagedObject && id.equals(((ManagedObject)obj).getID())) {
              return true;
            }
          }
        }
        return false;
      }

      @Override
      public void describeTo(final Description description) {
        //
      }
    };
  }

  private Matcher<ApplyTransactionContext> hasTransactionID(final long clientId, final long transactionID) {
    return new BaseMatcher<ApplyTransactionContext>() {
      @Override
      public boolean matches(final Object o) {
        if (o instanceof ApplyTransactionContext) {
          return ((ApplyTransactionContext)o).getTxn()
              .getServerTransactionID()
              .equals(new ServerTransactionID(new ClientID(clientId), new TransactionID(transactionID)));
        } else {
          return false;
        }
      }

      @Override
      public void describeTo(final Description description) {
        //
      }
    };
  }

  private Matcher<ApplyTransactionContext> needsApply() {
    return new BaseMatcher<ApplyTransactionContext>() {
      @Override
      public boolean matches(final Object o) {
        if (o instanceof ApplyTransactionContext) {
          return ((ApplyTransactionContext)o).needsApply();
        } else {
          return false;
        }
      }

      @Override
      public void describeTo(final Description description) {
        //
      }
    };
  }

  private ServerTransaction createTransaction(long clientId, long txId, Collection<Long> newObjects, Collection<Long> objects) {
    ServerTransaction transaction = mock(ServerTransaction.class);
    ObjectIDSet newObjectIDs = new ObjectIDSet();
    for (long l : newObjects) {
      newObjectIDs.add(new ObjectID(l));
    }
    ObjectIDSet objectIDs = new ObjectIDSet();
    for (long l : objects) {
      objectIDs.add(new ObjectID(l));
    }
    when(transaction.getServerTransactionID()).thenReturn(new ServerTransactionID(new ClientID(clientId), new TransactionID(txId)));
    when(transaction.getNewObjectIDs()).thenReturn(newObjectIDs);
    when(transaction.getObjectIDs()).thenReturn(objectIDs);
    return transaction;
  }
}
