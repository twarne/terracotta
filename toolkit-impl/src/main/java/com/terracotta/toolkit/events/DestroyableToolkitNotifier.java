/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.terracotta.toolkit.events;

import org.terracotta.toolkit.ToolkitObjectType;
import org.terracotta.toolkit.events.ToolkitNotificationEvent;
import org.terracotta.toolkit.events.ToolkitNotificationListener;
import org.terracotta.toolkit.events.ToolkitNotifier;

import com.google.common.base.Preconditions;
import com.terracotta.toolkit.factory.ToolkitObjectFactory;
import com.terracotta.toolkit.object.AbstractDestroyableToolkitObject;
import com.terracotta.toolkit.rejoin.RejoinAwareToolkitObject;
import com.terracotta.toolkit.type.IsolatedClusteredObjectLookup;
import com.terracotta.toolkit.util.ToolkitInstanceProxy;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class DestroyableToolkitNotifier<T> extends AbstractDestroyableToolkitObject<ToolkitNotifier> implements
    ToolkitNotifier<T>, ToolkitNotificationListener<T>, RejoinAwareToolkitObject {

  private volatile ToolkitNotifier<T>                                notifier;
  private final String                                               name;
  private final CopyOnWriteArrayList<ToolkitNotificationListener<T>> listeners  = new CopyOnWriteArrayList<ToolkitNotificationListener<T>>();
  private boolean                                                    registered = false;
  private final IsolatedClusteredObjectLookup<ToolkitNotifierImpl>   lookup;

  public DestroyableToolkitNotifier(ToolkitObjectFactory factory,
                                    IsolatedClusteredObjectLookup<ToolkitNotifierImpl> lookup,
                                    ToolkitNotifierImpl<T> notifierImpl, String name) {
    super(factory);
    this.lookup = lookup;
    this.name = name;
    this.notifier = notifierImpl;
    notifierImpl.setApplyDestroyCallback(getDestroyApplicator());
  }

  @Override
  public void rejoinStarted() {
    this.notifier = ToolkitInstanceProxy.newRejoinInProgressProxy(name, ToolkitNotifier.class);
  }

  @Override
  public void rejoinCompleted() {
    if (!isDestroyed()) {
      ToolkitNotifierImpl afterRejoin = lookup.lookupOrCreateClusteredObject(name, ToolkitObjectType.NOTIFIER, null);
      Preconditions.checkNotNull(afterRejoin);
      this.notifier = afterRejoin;
      if (listeners.size() > 0) {
        notifier.addNotificationListener(this);
      }
    }
  }

  @Override
  public void applyDestroy() {
    this.notifier = ToolkitInstanceProxy.newDestroyedInstanceProxy(name, ToolkitNotifier.class);
  }

  @Override
  public void doDestroy() {
    notifier.destroy();
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public synchronized void addNotificationListener(ToolkitNotificationListener<T> listener) {
    if (listeners.addIfAbsent(listener)) {
      if (!registered && listeners.size() > 0) {
        notifier.addNotificationListener(this);
        registered = true;
      }
    }
  }

  @Override
  public synchronized void removeNotificationListener(ToolkitNotificationListener<T> listener) {
    if (listeners.remove(listener)) {
      if (registered && listeners.size() == 0) {
        notifier.removeNotificationListener(this);
        registered = false;
      }
    }
  }

  @Override
  public void notifyListeners(T msg) {
    notifier.notifyListeners(msg);
  }

  @Override
  public List<ToolkitNotificationListener<T>> getNotificationListeners() {
    return Collections.unmodifiableList(listeners);
  }

  @Override
  public void onNotification(ToolkitNotificationEvent<T> event) {
    for (ToolkitNotificationListener<T> listener : listeners) {
      listener.onNotification(event);
    }
  }
}
