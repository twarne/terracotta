/*
 * All content copyright (c) 2003-2009 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.objectserver.clustermetadata;

import com.tc.io.serializer.TCObjectOutputStream;
import com.tc.logging.TCLogger;
import com.tc.net.NodeID;
import com.tc.net.protocol.tcm.MessageChannel;
import com.tc.net.protocol.tcm.TCMessageType;
import com.tc.object.ObjectID;
import com.tc.object.msg.KeysForOrphanedValuesMessage;
import com.tc.object.msg.KeysForOrphanedValuesResponseMessage;
import com.tc.object.msg.NodeMetaDataMessage;
import com.tc.object.msg.NodeMetaDataResponseMessage;
import com.tc.object.msg.NodesWithObjectsMessage;
import com.tc.object.msg.NodesWithObjectsResponseMessage;
import com.tc.object.net.DSOChannelManager;
import com.tc.object.net.NoSuchChannelException;
import com.tc.objectserver.api.ObjectManager;
import com.tc.objectserver.core.api.ManagedObject;
import com.tc.objectserver.core.api.ManagedObjectState;
import com.tc.objectserver.l1.api.ClientStateManager;
import com.tc.objectserver.managedobject.PartialMapManagedObjectState;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ServerClusterMetaDataManagerImpl implements ServerClusterMetaDataManager {

  private final TCLogger           logger;
  private final ClientStateManager clientStateManager;
  private final ObjectManager      objectManager;
  private final DSOChannelManager  channelManager;

  public ServerClusterMetaDataManagerImpl(final TCLogger logger, final ClientStateManager clientStateManager,
                                          final ObjectManager objectManager, final DSOChannelManager channelManager) {
    this.logger = logger;
    this.clientStateManager = clientStateManager;
    this.objectManager = objectManager;
    this.channelManager = channelManager;
  }

  public void handleMessage(final NodesWithObjectsMessage message) {
    NodesWithObjectsResponseMessage responseMessage = (NodesWithObjectsResponseMessage) message.getChannel()
        .createMessage(TCMessageType.NODES_WITH_OBJECTS_RESPONSE_MESSAGE);

    final Map<ObjectID, Set<NodeID>> response = new HashMap<ObjectID, Set<NodeID>>();

    Set<ObjectID> objectIDs = message.getObjectIDs();
    for (ObjectID objectID : objectIDs) {
      Set<NodeID> referencingNodeIDs = new HashSet<NodeID>();
      for (NodeID nodeID : clientStateManager.getConnectedClientIDs()) {
        if (clientStateManager.hasReference(nodeID, objectID)) {
          referencingNodeIDs.add(nodeID);
        }
      }
      response.put(objectID, referencingNodeIDs);
    }
    responseMessage.initialize(message.getThreadID(), response);
    responseMessage.send();
  }

  public void handleMessage(final KeysForOrphanedValuesMessage message) {
    KeysForOrphanedValuesResponseMessage responseMessage = (KeysForOrphanedValuesResponseMessage) message.getChannel()
        .createMessage(TCMessageType.KEYS_FOR_ORPHANED_VALUES_RESPONSE_MESSAGE);

    final Set<Object> response = new HashSet<Object>();

    final ManagedObject managedMap = objectManager.getObjectByID(message.getMapObjectID());
    try {
      final ManagedObjectState state = managedMap.getManagedObjectState();
      if (state instanceof PartialMapManagedObjectState) {
        final Set<NodeID> connectedClients = clientStateManager.getConnectedClientIDs();

        Map realMap = ((PartialMapManagedObjectState) state).getMap();
        for (Map.Entry entry : (Set<Map.Entry>) realMap.entrySet()) {
          if (entry.getValue() instanceof ObjectID) {
            boolean isOrphan = true;

            for (NodeID nodeID : connectedClients) {
              if (clientStateManager.hasReference(nodeID, (ObjectID) entry.getValue())) {
                isOrphan = false;
                break;
              }
            }

            if (isOrphan) {
              response.add(entry.getKey());
            }
          }
        }
      } else {
        logger.error("Received keys for orphaned values message for object '" + message.getMapObjectID()
                     + "' whose managed state isn't a partial map, returning an empty set.");
      }
    } finally {
      objectManager.releaseReadOnly(managedMap);
    }

    // write the DNA of the orphaned keys into a byte array
    final ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
    final TCObjectOutputStream objectOut = new TCObjectOutputStream(bytesOut);
    objectOut.writeInt(response.size());
    for (Object key : response) {
      objectOut.writeObject(key);
    }
    objectOut.flush();

    // create and send the response message
    responseMessage.initialize(message.getThreadID(), bytesOut.toByteArray());
    responseMessage.send();
  }

  public void handleMessage(final NodeMetaDataMessage message) {
    NodeMetaDataResponseMessage responseMessage = (NodeMetaDataResponseMessage) message.getChannel()
        .createMessage(TCMessageType.NODE_META_DATA_RESPONSE_MESSAGE);

    String ip;
    String hostname;

    try {
      final MessageChannel channel = channelManager.getActiveChannel(message.getNodeID());
      final InetAddress address = channel.getRemoteAddress().getAddress();
      ip = address.getHostAddress();
      hostname = address.getHostName();
    } catch (NoSuchChannelException e) {
      logger.error("Couldn't find channel for node  '" + message.getNodeID()
                   + "' sending empty meta data as a response");
      ip = null;
      hostname = null;
    }

    responseMessage.initialize(message.getThreadID(), ip, hostname);
    responseMessage.send();
  }
}
