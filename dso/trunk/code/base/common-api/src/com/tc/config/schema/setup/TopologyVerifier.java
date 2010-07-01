/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.config.schema.setup;

import com.tc.config.schema.ActiveServerGroupConfig;
import com.tc.config.schema.ActiveServerGroupsConfig;
import com.tc.config.schema.repository.MutableBeanRepository;
import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.server.ServerConnectionValidator;
import com.tc.util.Assert;
import com.terracottatech.config.BindPort;
import com.terracottatech.config.Ha;
import com.terracottatech.config.MirrorGroup;
import com.terracottatech.config.Server;
import com.terracottatech.config.Servers;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class TopologyVerifier {
  private final Servers                   oldServersBean;
  private final Servers                   newServersBean;
  private final ActiveServerGroupsConfig  oldGroupsInfo;
  private final ServerConnectionValidator serverConnectionValidator;

  private static final TCLogger           logger = TCLogging.getLogger(TopologyVerifier.class);

  TopologyVerifier(MutableBeanRepository oldServers, MutableBeanRepository newServers,
                   ActiveServerGroupsConfig oldGroupsInfo, ServerConnectionValidator serverConnectionValidator) {
    this.oldServersBean = (Servers) oldServers.bean();
    this.newServersBean = (Servers) newServers.bean();
    this.oldGroupsInfo = oldGroupsInfo;
    this.serverConnectionValidator = serverConnectionValidator;
  }

  /**
   * 
   */
  public TopologyReloadStatus checkAndValidateConfig() {
    // first check if all existing servers config is not changed
    // check ports, dgc info, persistent info
    TopologyReloadStatus topologyStatus = checkExistingServerConfigIsSame();
    if (topologyStatus != TopologyReloadStatus.TOPOLOGY_CHANGE_ACCEPTABLE) { return topologyStatus; }

    // check if group names consist of the same members as the older ones
    topologyStatus = checkGroupInfo();
    if (topologyStatus != TopologyReloadStatus.TOPOLOGY_CHANGE_ACCEPTABLE) { return topologyStatus; }

    // Check if removed members are still alive
    return checkIfServersAlive();
  }

  private TopologyReloadStatus checkIfServersAlive() {
    Set<String> membersRemoved = getRemovedMembers();
    for (String member : membersRemoved) {
      if (serverConnectionValidator.isAlive(member)) {
        logger.warn("Reloading servers config failed as " + member + " is still alive.");
        return TopologyReloadStatus.SERVER_STILL_ALIVE;
      }
    }

    return TopologyReloadStatus.TOPOLOGY_CHANGE_ACCEPTABLE;
  }

  private TopologyReloadStatus checkGroupInfo() {
    if (isGroupsSizeEqualsOne()) { return TopologyReloadStatus.TOPOLOGY_CHANGE_ACCEPTABLE; }

    if (!isGroupNameSpecified()) { return TopologyReloadStatus.SPECIFY_MIRROR_GROUPS; }

    if (!isGroupNameSame()) { return TopologyReloadStatus.TOPOLOGY_CHANGE_UNACCEPTABLE; }

    if (!isHaModeSame()) { return TopologyReloadStatus.TOPOLOGY_CHANGE_UNACCEPTABLE; }

    if (isMemberMovedToDifferentGroup()) { return TopologyReloadStatus.TOPOLOGY_CHANGE_UNACCEPTABLE; }

    return TopologyReloadStatus.TOPOLOGY_CHANGE_ACCEPTABLE;
  }

  private boolean isGroupsSizeEqualsOne() {
    return oldGroupsInfo.getActiveServerGroupCount() == 1
           && (!newServersBean.isSetMirrorGroups() || newServersBean.getMirrorGroups().getMirrorGroupArray().length == 1);
  }

  private boolean isGroupNameSpecified() {
    MirrorGroup[] newGroupsInfo = newServersBean.getMirrorGroups().getMirrorGroupArray();

    // check to see the group names for all new servers are set
    for (MirrorGroup newGroup : newGroupsInfo) {
      if (!newGroup.isSetGroupName()) { return false; }
    }
    return true;
  }

  private boolean isMemberMovedToDifferentGroup() {
    MirrorGroup[] newGroupsInfo = newServersBean.getMirrorGroups().getMirrorGroupArray();
    for (MirrorGroup newGroupInfo : newGroupsInfo) {
      String groupName = newGroupInfo.getGroupName();
      for (String member : newGroupInfo.getMembers().getMemberArray()) {
        String previousGrpName = getPreviousGroupName(member);
        if (previousGrpName != null && !groupName.equals(previousGrpName)) {
          logger.warn(member + " group was changed. This is not supported currently.");
          return true;
        }
      }
    }

    return false;
  }

  private String getPreviousGroupName(String member) {
    for (ActiveServerGroupConfig groupInfo : oldGroupsInfo.getActiveServerGroupArray()) {
      if (groupInfo.isMember(member)) { return groupInfo.getGroupName(); }
    }
    return null;
  }

  private boolean isGroupNameSame() {
    MirrorGroup[] newGroupsInfo = newServersBean.getMirrorGroups().getMirrorGroupArray();

    Set<String> newGroupNames = new HashSet<String>();
    for (MirrorGroup newGroup : newGroupsInfo) {
      newGroupNames.add(newGroup.getGroupName());
    }

    Set<String> oldGroupNames = new HashSet<String>();
    for (ActiveServerGroupConfig oldGroupInfo : this.oldGroupsInfo.getActiveServerGroupArray()) {
      oldGroupNames.add(oldGroupInfo.getGroupName());
    }

    boolean areGroupNamesSame = oldGroupNames.equals(newGroupNames);
    if (!areGroupNamesSame) {
      logger.warn("The group names have changed. Groups before=" + oldGroupNames + " Groups after=" + newGroupNames);
    }

    return areGroupNamesSame;
  }

  private boolean isHaModeSame() {
    MirrorGroup[] newGroupsInfo = newServersBean.getMirrorGroups().getMirrorGroupArray();
    for (MirrorGroup newGroupInfo : newGroupsInfo) {
      String groupName = newGroupInfo.getGroupName();
      for (String member : newGroupInfo.getMembers().getMemberArray()) {
        ActiveServerGroupConfig oldAsgc = this.oldGroupsInfo.getActiveServerGroupForL2(member);
        if (oldAsgc != null) {
          Ha newHa = newGroupInfo.getHa();
          Ha oldHa = oldAsgc.getHaHolder().getHa();
          if (!oldHa.getMode().equals(newHa.getMode())) {
            logger.warn("The mirror group " + groupName + " High Availability mode has changed.");
            return false;
          }
          break;
        }
      }
    }
    return true;
  }

  private TopologyReloadStatus checkExistingServerConfigIsSame() {
    Server[] oldServerArray = oldServersBean.getServerArray();
    Map<String, Server> oldServersInfo = new HashMap<String, Server>();
    for (Server server : oldServerArray) {
      oldServersInfo.put(server.getName(), server);
    }

    Server[] newServerArray = newServersBean.getServerArray();
    boolean isTopologyChanged = !(newServerArray.length == oldServerArray.length);
    for (Server newServer : newServerArray) {
      Server oldServer = oldServersInfo.get(newServer.getName());
      if (oldServer != null && !checkServer(oldServer, newServer)) { return TopologyReloadStatus.TOPOLOGY_CHANGE_UNACCEPTABLE; }

      if (oldServer == null) {
        isTopologyChanged = true;
      }
    }

    if (!isTopologyChanged) { return TopologyReloadStatus.TOPOLOGY_UNCHANGED; }

    return TopologyReloadStatus.TOPOLOGY_CHANGE_ACCEPTABLE;
  }

  private Set<String> getRemovedMembers() {
    Server[] oldServerArray = oldServersBean.getServerArray();
    HashSet<String> oldServerNames = new HashSet<String>();
    for (Server server : oldServerArray) {
      oldServerNames.add(server.getName());
    }

    Server[] newServerArray = newServersBean.getServerArray();
    for (Server newServer : newServerArray) {
      oldServerNames.remove(newServer.getName());
    }

    return oldServerNames;
  }

  /**
   * check ports, persistence and mode
   */
  private boolean checkServer(Server oldServer, Server newServer) {
    if (!validatePorts(oldServer.getDsoPort(), newServer.getDsoPort())
        || !validatePorts(oldServer.getJmxPort(), newServer.getJmxPort())
        || !validatePorts(oldServer.getL2GroupPort(), newServer.getL2GroupPort())) {
      logger.warn("Server port configuration was changed for server " + oldServer.getName()
                  + ". [dso-port, l2-group-port, jmx-port] [ {" + oldServer.getDsoPort().getBind() + ":"
                  + oldServer.getDsoPort().getIntValue() + "}, {" + oldServer.getL2GroupPort().getBind() + ":"
                  + oldServer.getL2GroupPort().getIntValue() + "}, {" + oldServer.getJmxPort().getBind() + ":"
                  + oldServer.getJmxPort().getIntValue() + "}] to [ {" + newServer.getDsoPort().getBind() + ":"
                  + newServer.getDsoPort().getIntValue() + "}, {" + newServer.getL2GroupPort().getBind() + ":"
                  + newServer.getL2GroupPort().getIntValue() + "}, {" + newServer.getJmxPort().getBind() + ":"
                  + newServer.getJmxPort().getIntValue() + "}]");
      return false;
    }

    if (oldServer.isSetDso() && oldServer.getDso().isSetGarbageCollection()) {
      if (!newServer.isSetDso() || !newServer.getDso().isSetGarbageCollection()) { return false; }

      if ((oldServer.getDso().getGarbageCollection().getEnabled() != newServer.getDso().getGarbageCollection()
          .getEnabled())
          || oldServer.getDso().getGarbageCollection().getInterval() != newServer.getDso().getGarbageCollection()
              .getInterval()) {
        logger.warn("Server Garbage Collection Info changed for server " + oldServer.getName());
        return false;
      }
    }

    return true;
  }

  private boolean validatePorts(BindPort oldValue, BindPort newValue) {
    Assert.assertNotNull(oldValue);
    Assert.assertNotNull(newValue);

    if (oldValue.getIntValue() != newValue.getIntValue()) return false;
    return validatePortAddress(oldValue.getBind(), newValue.getBind());
  }

  private boolean validatePortAddress(String oldValue, String newValue) {
    if (oldValue != null && newValue != null) {
      return oldValue.equals(newValue);
    } else {
      return (oldValue == newValue);
    }
  }
}
