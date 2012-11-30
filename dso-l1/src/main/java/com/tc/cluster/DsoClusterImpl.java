/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.cluster;

import com.tc.async.api.Sink;
import com.tc.async.api.Stage;
import com.tc.cluster.exceptions.UnclusteredObjectException;
import com.tc.exception.TCNotRunningException;
import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.net.ClientID;
import com.tc.net.NodeID;
import com.tc.object.ClientObjectManager;
import com.tc.object.ClusterMetaDataManager;
import com.tc.object.bytecode.Manageable;
import com.tc.object.bytecode.TCMap;
import com.tc.object.bytecode.TCServerMap;
import com.tc.platform.rejoin.RejoinManagerInternal;
import com.tc.properties.TCPropertiesConsts;
import com.tc.properties.TCPropertiesImpl;
import com.tc.util.Assert;
import com.tc.util.Util;
import com.tcclient.cluster.ClusterInternalEventsContext;
import com.tcclient.cluster.ClusterNodeStatus;
import com.tcclient.cluster.DsoClusterInternal;
import com.tcclient.cluster.DsoClusterInternalEventsGun;
import com.tcclient.cluster.DsoNode;
import com.tcclient.cluster.DsoNodeImpl;
import com.tcclient.cluster.DsoNodeInternal;
import com.tcclient.cluster.DsoNodeMetaData;
import com.tcclient.cluster.OutOfBandDsoClusterListener;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class DsoClusterImpl implements DsoClusterInternal, DsoClusterInternalEventsGun {

  private static final TCLogger                          LOGGER               = TCLogging
                                                                                  .getLogger(DsoClusterImpl.class);

  private volatile ClientID                              currentClientID;
  private volatile DsoNodeInternal                       currentNode;

  private final DsoClusterTopologyImpl                   topology             = new DsoClusterTopologyImpl();
  private final CopyOnWriteArrayList<DsoClusterListener> listeners            = new CopyOnWriteArrayList<DsoClusterListener>();
  private final Object                                   nodeJoinsClusterSync = new Object();

  private final ReentrantReadWriteLock                   stateLock            = new ReentrantReadWriteLock();
  private final ReentrantReadWriteLock.ReadLock          stateReadLock        = stateLock.readLock();
  private final ReentrantReadWriteLock.WriteLock         stateWriteLock       = stateLock.writeLock();
  private final ClusterNodeStatus                        nodeStatus           = new ClusterNodeStatus();
  private final FiredEventsStatus                        firedEventsStatus    = new FiredEventsStatus();
  private final OutOfBandNotifier                        outOfBandNotifier    = new OutOfBandNotifier();

  private ClusterMetaDataManager                         clusterMetaDataManager;
  private Sink                                           eventsProcessorSink;

  private final RejoinManagerInternal                    rejoinManager;

  public DsoClusterImpl(RejoinManagerInternal rejoinManager) {
    this.rejoinManager = rejoinManager;
  }

  @Override
  public void init(final ClusterMetaDataManager metaDataManager, final ClientObjectManager objectManager,
                   final Stage dsoClusterEventsStage) {
    this.clusterMetaDataManager = metaDataManager;
    this.eventsProcessorSink = dsoClusterEventsStage.getSink();

    for (DsoNodeInternal node : topology.getInternalNodes()) {
      retrieveMetaDataForDsoNode(node);
    }
    outOfBandNotifier.start();
  }

  @Override
  public void shutdown() {
    this.outOfBandNotifier.shutdown();
  }

  @Override
  public void addClusterListener(final DsoClusterListener listener) {

    boolean added = listeners.addIfAbsent(listener);

    if (added) {
      if (nodeStatus.getState().isNodeLeft()) {
        fireEvent(DsoClusterEventType.NODE_LEFT, new DsoClusterEventImpl(currentNode), listener);
      } else {
        if (nodeStatus.getState().isNodeJoined()) {
          fireNodeJoinedInternal(currentNode, new DsoClusterEventImpl(currentNode), listener);
        }
        if (nodeStatus.getState().areOperationsEnabled()) {
          fireEvent(DsoClusterEventType.OPERATIONS_ENABLED, new DsoClusterEventImpl(currentNode), listener);
        }
      }
    }
  }

  @Override
  public void removeClusterListener(final DsoClusterListener listener) {
    listeners.remove(listener);
  }

  @Override
  public DsoNode getCurrentNode() {
    stateReadLock.lock();
    try {
      return currentNode;
    } finally {
      stateReadLock.unlock();
    }
  }

  @Override
  public DsoClusterTopology getClusterTopology() {
    return topology;
  }

  @Override
  public <K> Map<K, Set<DsoNode>> getNodesWithKeys(final Map<K, ?> map, final Collection<? extends K> keys)
      throws UnclusteredObjectException {
    Assert.assertNotNull(clusterMetaDataManager);

    if (null == keys || 0 == keys.size() || null == map) { return Collections.emptyMap(); }

    Map<K, Set<DsoNode>> result = new HashMap<K, Set<DsoNode>>();

    if (map instanceof Manageable) {
      Manageable manageable = (Manageable) map;
      if (manageable.__tc_isManaged()) {
        Map<K, Set<NodeID>> rawResult = null;
        if (manageable instanceof TCMap) {
          rawResult = clusterMetaDataManager.getNodesWithKeys((TCMap) map, keys);
        } else if (manageable instanceof TCServerMap) {
          rawResult = clusterMetaDataManager.getNodesWithKeys((TCServerMap) map, keys);
        }

        if (rawResult != null) {
          for (Map.Entry<K, Set<NodeID>> entry : rawResult.entrySet()) {
            Set<DsoNode> dsoNodes = new HashSet<DsoNode>(rawResult.entrySet().size(), 1.0f);
            for (NodeID nodeID : entry.getValue()) {
              DsoNodeInternal dsoNode = topology.getAndRegisterDsoNode((ClientID) nodeID);
              dsoNodes.add(dsoNode);
            }
            result.put(entry.getKey(), dsoNodes);
          }
        }
      }
    }
    return result;
  }

  @Override
  public DsoNodeMetaData retrieveMetaDataForDsoNode(final DsoNodeInternal node) {
    Assert.assertNotNull(clusterMetaDataManager);
    return clusterMetaDataManager.retrieveMetaDataForDsoNode(node);
  }

  @Override
  public boolean isNodeJoined() {
    return nodeStatus.getState().isNodeJoined();
  }

  @Override
  public boolean areOperationsEnabled() {
    return nodeStatus.getState().areOperationsEnabled();
  }

  @Override
  public DsoNode waitUntilNodeJoinsCluster() {
    /*
     * It might be nice to throw InterruptedException here, but since the method is defined inside tim-api, we can't so
     * re-interrupting once the node is identified is the best option we have available
     */
    boolean interrupted = false;
    try {
      synchronized (nodeJoinsClusterSync) {
        while (currentNode == null) {
          try {
            nodeJoinsClusterSync.wait();
          } catch (InterruptedException e) {
            interrupted = true;
          }
        }
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
    return currentNode;
  }

  private void notifyWaiters() {
    synchronized (nodeJoinsClusterSync) {
      nodeJoinsClusterSync.notifyAll();
    }
  }

  @Override
  public void fireThisNodeJoined(final ClientID nodeId, final ClientID[] clusterMembers) {
    stateWriteLock.lock();
    try {
      ClientID newNodeId = nodeId;

      rejoinManager.thisNodeJoinedCallback(this, newNodeId);

      if (currentNode != null) {
        // node rejoined, update current node
        currentClientID = newNodeId;
        currentNode = topology.updateOnRejoin(currentClientID, clusterMembers);
        return;
      } else {
        currentClientID = newNodeId;
        currentNode = topology.registerThisDsoNode(nodeId);
        nodeStatus.nodeJoined();
        nodeStatus.operationsEnabled();

        for (ClientID otherNodeId : clusterMembers) {
          if (!currentClientID.equals(otherNodeId)) {
            topology.registerDsoNode(otherNodeId);
          }
        }
      }

    } finally {
      stateWriteLock.unlock();

      if (currentNode != null) {
        notifyWaiters();
      } else {
        fireNodeJoined(nodeId);
      }

      fireOperationsEnabled();
    }

  }

  @Override
  public void fireThisNodeLeft() {
    boolean fireOperationsDisabled = false;
    stateWriteLock.lock();
    try {
      // We may get a node left event without ever seeing a node joined event, just ignore
      // the node left event in that case
      if (!nodeStatus.getState().isNodeJoined()) { return; }
      if (nodeStatus.getState().areOperationsEnabled()) {
        fireOperationsDisabled = true;
      }
      nodeStatus.nodeLeft();
    } finally {
      stateWriteLock.unlock();
    }

    if (fireOperationsDisabled) {
      fireOperationsDisabledNoCheck();
    } else {
      // don't fire node left right away, but wait until operations disabled is fired first
      firedEventsStatus.waitUntilOperationsDisabledFired();
    }
    fireNodeLeft(new ClientID(currentNode.getChannelId()));
  }

  @Override
  public void fireNodeJoined(final ClientID nodeId) {
    if (topology.containsDsoNode(nodeId)) { return; }

    final DsoClusterEvent event = new DsoClusterEventImpl(topology.getAndRegisterDsoNode(nodeId));
    for (DsoClusterListener listener : listeners) {
      fireNodeJoinedInternal(topology.getInternalNode(nodeId), event, listener);
    }
  }

  private void fireNodeJoinedInternal(final DsoNodeInternal node, final DsoClusterEvent event,
                                      final DsoClusterListener listener) {
    if (node != null) {
      retrieveMetaDataForDsoNode(node);
    }
    fireEvent(DsoClusterEventType.NODE_JOIN, event, listener);
  }

  @Override
  public void fireNodeRejoined(ClientID newNodeId) {

    fireRejoinEvent(new DsoNodeImpl(newNodeId.toString(), newNodeId.toLong(), false));
  }

  private void fireRejoinEvent(DsoNodeImpl newNode) {
    final DsoClusterEvent event = new DsoClusterEventImpl(newNode);
    for (DsoClusterListener l : listeners) {
      fireEvent(DsoClusterEventType.NODE_REJOINED, event, l);
    }
  }

  @Override
  public void fireNodeLeft(final ClientID nodeId) {
    DsoNodeInternal node = topology.getAndRemoveDsoNode(nodeId);
    if (node == null) { return; }
    final DsoClusterEvent event = new DsoClusterEventImpl(node);

    for (DsoClusterListener listener : listeners) {
      fireEvent(DsoClusterEventType.NODE_LEFT, event, listener);
    }
  }

  @Override
  public void fireOperationsEnabled() {
    if (currentNode != null) {
      stateWriteLock.lock();
      try {
        if (nodeStatus.getState().areOperationsEnabled()) { return; }
        nodeStatus.operationsEnabled();
      } finally {
        stateWriteLock.unlock();
      }

      final DsoClusterEvent event = new DsoClusterEventImpl(currentNode);
      for (DsoClusterListener listener : listeners) {
        fireEvent(DsoClusterEventType.OPERATIONS_ENABLED, event, listener);
      }
      firedEventsStatus.operationsEnabledFired();
    }
  }

  @Override
  public void fireOperationsDisabled() {
    stateWriteLock.lock();
    try {
      if (!nodeStatus.getState().areOperationsEnabled()) { return; }
      nodeStatus.operationsDisabled();
    } finally {
      stateWriteLock.unlock();
    }

    fireOperationsDisabledNoCheck();
  }

  private void fireOperationsDisabledNoCheck() {
    final DsoClusterEvent event = new DsoClusterEventImpl(currentNode);
    for (DsoClusterListener listener : listeners) {
      fireEvent(DsoClusterEventType.OPERATIONS_DISABLED, event, listener);
    }
    firedEventsStatus.operationsDisabledFired();
  }

  private void fireEvent(final DsoClusterEventType eventType, final DsoClusterEvent event,
                         final DsoClusterListener listener) {
    /**
     * use out-of-band notification depending on listener otherwise use the single threaded eventProcessorSink to
     * process the cluster event.
     */
    if (useOOBNotification(eventType, event, listener)) {
      outOfBandNotifier.submit(new Runnable() {
        @Override
        public void run() {
          notifyDsoClusterListener(eventType, event, listener);
        }
      });
    } else {
      this.eventsProcessorSink.add(new ClusterInternalEventsContext(eventType, event, listener));
    }
  }

  private boolean useOOBNotification(DsoClusterEventType eventType, DsoClusterEvent event, DsoClusterListener listener) {
    if (listener instanceof OutOfBandDsoClusterListener) {
      return ((OutOfBandDsoClusterListener) listener).useOutOfBandNotification(eventType, event);
    } else {
      return false;
    }
  }

  @Override
  public void notifyDsoClusterListener(DsoClusterEventType eventType, DsoClusterEvent event, DsoClusterListener listener) {
    try {
      switch (eventType) {
        case NODE_JOIN:
          listener.nodeJoined(event);
          break;
        case NODE_LEFT:
          listener.nodeLeft(event);
          break;
        case OPERATIONS_ENABLED:
          listener.operationsEnabled(event);
          break;
        case OPERATIONS_DISABLED:
          listener.operationsDisabled(event);
          break;
        case NODE_REJOINED:
          listener.nodeRejoined(event);
          break;
        default:
          throw new AssertionError("Unknown type of cluster event - " + eventType);
      }
    } catch (TCNotRunningException tcnre) {
      LOGGER.error("Ignoring TCNotRunningException when notifying " + event + " : " + eventType);
    } catch (Throwable t) {
      LOGGER.error("Problem firing the cluster event : " + eventType + " - " + event, t);
    }

  }

  private static final class FiredEventsStatus {

    private DsoClusterEventType lastFiredEvent = null;

    public synchronized void operationsDisabledFired() {
      lastFiredEvent = DsoClusterEventType.OPERATIONS_DISABLED;
      this.notifyAll();
    }

    public synchronized void operationsEnabledFired() {
      lastFiredEvent = DsoClusterEventType.OPERATIONS_ENABLED;
      this.notifyAll();
    }

    public synchronized void waitUntilOperationsDisabledFired() {
      boolean interrupted = false;
      try {
        while (lastFiredEvent != DsoClusterEventType.OPERATIONS_DISABLED) {
          try {
            this.wait();
          } catch (InterruptedException e) {
            interrupted = true;
          }
        }
      } finally {
        Util.selfInterruptIfNeeded(interrupted);
      }
    }
  }

  private static class OutOfBandNotifier {
    private static final String                 TASK_THREAD_PREFIX   = "Out of band notifier";
    private static final long                   TASK_RUN_TIME_MILLIS = TCPropertiesImpl
                                                                         .getProperties()
                                                                         .getLong(TCPropertiesConsts.L1_CLUSTEREVENTS_OOB_JOINTIME_MILLIS,
                                                                                  100);
    private final LinkedBlockingQueue<Runnable> taskQueue            = new LinkedBlockingQueue<Runnable>();
    private volatile long                       count                = 0;
    private volatile boolean                    shutdown;

    private void submit(final Runnable taskToExecute) {
      taskQueue.add(taskToExecute);
    }

    private void start() {
      Thread outOfBandNotifierThread = new Thread(new Runnable() {

        @Override
        public void run() {

          Runnable taskToExecute;
          while (true) {

            if (shutdown) { return; }

            try {
              taskToExecute = taskQueue.take();
            } catch (InterruptedException e) {
              continue;
            }

            Thread oobTask = new Thread(taskToExecute, TASK_THREAD_PREFIX + " - " + count++);
            oobTask.setDaemon(true);
            oobTask.start();
            try {
              oobTask.join(TASK_RUN_TIME_MILLIS);
            } catch (InterruptedException e) {
              continue;
            }
          }

        }
      }, TASK_THREAD_PREFIX + " - Main");

      outOfBandNotifierThread.setDaemon(true);
      outOfBandNotifierThread.start();
    }

    public void shutdown() {
      this.shutdown = true;
      this.taskQueue.add(new Runnable() {
        @Override
        public void run() {
          // dummy task to notify other thread
        }
      });
    }
  }

}