/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.terracotta.toolkit;

import org.terracotta.toolkit.ToolkitFeature;
import org.terracotta.toolkit.ToolkitObjectType;
import org.terracotta.toolkit.cache.ToolkitCache;
import org.terracotta.toolkit.cluster.ClusterInfo;
import org.terracotta.toolkit.collections.ToolkitBlockingQueue;
import org.terracotta.toolkit.collections.ToolkitList;
import org.terracotta.toolkit.collections.ToolkitMap;
import org.terracotta.toolkit.collections.ToolkitSet;
import org.terracotta.toolkit.collections.ToolkitSortedMap;
import org.terracotta.toolkit.collections.ToolkitSortedSet;
import org.terracotta.toolkit.concurrent.ToolkitBarrier;
import org.terracotta.toolkit.concurrent.atomic.ToolkitAtomicLong;
import org.terracotta.toolkit.concurrent.locks.ToolkitLock;
import org.terracotta.toolkit.concurrent.locks.ToolkitReadWriteLock;
import org.terracotta.toolkit.config.Configuration;
import org.terracotta.toolkit.events.ToolkitNotifier;
import org.terracotta.toolkit.internal.ToolkitInternal;
import org.terracotta.toolkit.internal.ToolkitLogger;
import org.terracotta.toolkit.internal.ToolkitProperties;
import org.terracotta.toolkit.internal.cache.ToolkitCacheInternal;
import org.terracotta.toolkit.internal.concurrent.locks.ToolkitLockTypeInternal;
import org.terracotta.toolkit.monitoring.OperatorEventLevel;
import org.terracotta.toolkit.nonstop.NonStop;
import org.terracotta.toolkit.nonstop.NonStopConfiguration;
import org.terracotta.toolkit.nonstop.NonStopConfigurationRegistry;
import org.terracotta.toolkit.store.ToolkitStore;

import com.tc.abortable.AbortableOperationManager;
import com.tc.platform.PlatformService;
import com.terracotta.toolkit.collections.map.ValuesResolver;
import com.terracotta.toolkit.nonstop.NonStopClusterListener;
import com.terracotta.toolkit.nonstop.NonStopConfigRegistryImpl;
import com.terracotta.toolkit.nonstop.NonStopDelegateProvider;
import com.terracotta.toolkit.nonstop.NonStopInvocationHandler;
import com.terracotta.toolkit.nonstop.NonStopManagerImpl;
import com.terracotta.toolkit.nonstop.NonStopToolkitCacheDelegateProvider;
import com.terracotta.toolkit.nonstop.NonStopToolkitStoreDelegateProvider;
import com.terracotta.toolkit.nonstop.NonstopTimeoutBehaviorResolver;
import com.terracotta.toolkit.nonstop.NonstopToolkitListDelegateProvider;
import com.terracotta.toolkit.nonstop.NonstopToolkitLockDelegateProvider;
import com.terracotta.toolkit.nonstop.NonstopToolkitReadWriteLockDelegateProvider;

import java.lang.reflect.Proxy;
import java.util.concurrent.FutureTask;

public class NonStopToolkitImpl implements ToolkitInternal {
  protected final NonStopManagerImpl           nonStopManager;
  protected final NonStopConfigRegistryImpl    nonStopConfigManager          = new NonStopConfigRegistryImpl();
  private final NonstopTimeoutBehaviorResolver nonstopTimeoutBehaviorFactory = new NonstopTimeoutBehaviorResolver();

  private final AbortableOperationManager      abortableOperationManager;
  protected final NonStopClusterListener       nonStopClusterListener;
  private final NonStop                        nonStopFeature;
  private final AsyncToolkitInitializer        asyncToolkitInitializer;

  public NonStopToolkitImpl(FutureTask<ToolkitInternal> toolkitDelegateFutureTask, PlatformService platformService) {
    abortableOperationManager = platformService.getAbortableOperationManager();
    this.nonStopManager = new NonStopManagerImpl(abortableOperationManager);
    this.nonStopClusterListener = new NonStopClusterListener(toolkitDelegateFutureTask, abortableOperationManager);
    this.nonStopConfigManager.registerForType(NonStopConfigRegistryImpl.DEFAULT_CONFIG,
                                              NonStopConfigRegistryImpl.SUPPORTED_TOOLKIT_TYPES
                                                  .toArray(new ToolkitObjectType[0]));
    this.nonStopFeature = new NonStopImpl(this);
    this.asyncToolkitInitializer = new AsyncToolkitInitializer(toolkitDelegateFutureTask, abortableOperationManager);
  }

  private ToolkitInternal getInitializedToolkit() {
    return asyncToolkitInitializer.getToolkit();
  }

  @Override
  public <E> ToolkitList<E> getList(String name, Class<E> klazz) {
    NonStopConfiguration nonStopConfiguration = this.nonStopConfigManager.getConfigForInstance(name,
                                                                                               ToolkitObjectType.LIST);
    if (!nonStopConfiguration.isEnabled()) { return getInitializedToolkit().getList(name, klazz); }

    NonStopDelegateProvider<ToolkitList<E>> nonStopDelegateProvider = new NonstopToolkitListDelegateProvider(

    nonStopConfigManager, nonstopTimeoutBehaviorFactory, asyncToolkitInitializer, name, klazz);
    return (ToolkitList<E>) Proxy
        .newProxyInstance(this.getClass().getClassLoader(), new Class[] { ToolkitList.class },
                          new NonStopInvocationHandler<ToolkitList<E>>(nonStopManager, nonStopDelegateProvider,
                                                                       nonStopClusterListener));
  }

  @Override
  public <V> ToolkitStore<String, V> getStore(String name, Configuration configuration, Class<V> klazz) {
    // TODO: refactor in a factory ?
    NonStopConfiguration nonStopConfiguration = this.nonStopConfigManager.getConfigForInstance(name,
                                                                                               ToolkitObjectType.STORE);
    if (!nonStopConfiguration.isEnabled()) { return getInitializedToolkit().getStore(name, configuration, klazz); }

    NonStopDelegateProvider<ToolkitStore<String, V>> nonStopDelegateProvider = new NonStopToolkitStoreDelegateProvider(

    nonStopConfigManager, nonstopTimeoutBehaviorFactory, asyncToolkitInitializer, name, klazz, configuration);
    return (ToolkitStore<String, V>) Proxy
        .newProxyInstance(this.getClass().getClassLoader(), new Class[] { ToolkitCacheInternal.class,
            ValuesResolver.class }, new NonStopInvocationHandler<ToolkitStore<String, V>>(nonStopManager,
                                                                                          nonStopDelegateProvider,
                                                                                          nonStopClusterListener));
  }

  @Override
  public <V> ToolkitStore<String, V> getStore(String name, Class<V> klazz) {
    return getStore(name, null, klazz);
  }

  @Override
  public <K, V> ToolkitMap<K, V> getMap(String name, Class<K> keyKlazz, Class<V> valueKlazz) {
    return getInitializedToolkit().getMap(name, keyKlazz, valueKlazz);
  }

  @Override
  public <K extends Comparable<? super K>, V> ToolkitSortedMap<K, V> getSortedMap(String name, Class<K> keyKlazz,
                                                                                  Class<V> valueKlazz) {
    return getInitializedToolkit().getSortedMap(name, keyKlazz, valueKlazz);
  }

  @Override
  public <E> ToolkitBlockingQueue<E> getBlockingQueue(String name, int capacity, Class<E> klazz) {
    return getInitializedToolkit().getBlockingQueue(name, capacity, klazz);
  }

  @Override
  public <E> ToolkitBlockingQueue<E> getBlockingQueue(String name, Class<E> klazz) {
    return getInitializedToolkit().getBlockingQueue(name, klazz);
  }

  @Override
  public ClusterInfo getClusterInfo() {
    return getInitializedToolkit().getClusterInfo();
  }

  @Override
  public ToolkitLock getLock(String name) {
    return getLock(name, ToolkitLockTypeInternal.WRITE);
  }

  @Override
  public ToolkitReadWriteLock getReadWriteLock(String name) {
    NonStopConfiguration nonStopConfiguration = this.nonStopConfigManager
        .getConfigForInstance(name, ToolkitObjectType.READ_WRITE_LOCK);
    if (!nonStopConfiguration.isEnabled()) { return getInitializedToolkit().getReadWriteLock(name); }

    NonStopDelegateProvider<ToolkitReadWriteLock> nonStopDelegateProvider = new NonstopToolkitReadWriteLockDelegateProvider(

    nonStopConfigManager, nonstopTimeoutBehaviorFactory, asyncToolkitInitializer, name);
    return (ToolkitReadWriteLock) Proxy
        .newProxyInstance(this.getClass().getClassLoader(), new Class[] { ToolkitReadWriteLock.class },
                          new NonStopInvocationHandler<ToolkitReadWriteLock>(nonStopManager, nonStopDelegateProvider,
                                                                             nonStopClusterListener));
  }

  @Override
  public <E> ToolkitNotifier<E> getNotifier(String name, Class<E> klazz) {
    return getInitializedToolkit().getNotifier(name, klazz);
  }

  @Override
  public ToolkitAtomicLong getAtomicLong(String name) {
    return getInitializedToolkit().getAtomicLong(name);
  }

  @Override
  public ToolkitBarrier getBarrier(String name, int parties) {
    return getInitializedToolkit().getBarrier(name, parties);
  }

  @Override
  public void fireOperatorEvent(OperatorEventLevel level, String applicationName, String eventMessage) {
    getInitializedToolkit().fireOperatorEvent(level, applicationName, eventMessage);
  }

  @Override
  public <E extends Comparable<? super E>> ToolkitSortedSet<E> getSortedSet(String name, Class<E> klazz) {
    return getInitializedToolkit().getSortedSet(name, klazz);
  }

  @Override
  public <E> ToolkitSet<E> getSet(String name, Class<E> klazz) {
    return getInitializedToolkit().getSet(name, klazz);
  }

  @Override
  public <V> ToolkitCache<String, V> getCache(String name, Configuration configuration, Class<V> klazz) {
    NonStopConfiguration nonStopConfiguration = this.nonStopConfigManager.getConfigForInstance(name,
                                                                                               ToolkitObjectType.CACHE);
    if (!nonStopConfiguration.isEnabled()) { return getInitializedToolkit().getCache(name, configuration, klazz); }

    NonStopDelegateProvider<ToolkitCacheInternal<String, V>> nonStopDelegateProvider = new NonStopToolkitCacheDelegateProvider(

    nonStopConfigManager, nonstopTimeoutBehaviorFactory, asyncToolkitInitializer, name, klazz, configuration);
    return (ToolkitCache<String, V>) Proxy
        .newProxyInstance(this.getClass().getClassLoader(), new Class[] { ToolkitCacheInternal.class,
                              ValuesResolver.class },
                          new NonStopInvocationHandler<ToolkitCacheInternal<String, V>>(nonStopManager,
                                                                                        nonStopDelegateProvider,
                                                                                        nonStopClusterListener));
  }

  @Override
  public <V> ToolkitCache<String, V> getCache(String name, Class<V> klazz) {
    return getCache(name, null, klazz);
  }

  @Override
  public boolean isCapabilityEnabled(String capability) {
    return getInitializedToolkit().isCapabilityEnabled(capability);
  }

  @Override
  public void shutdown() {
    nonStopManager.shutdown();
    getInitializedToolkit().shutdown();
  }

  public NonStopConfigurationRegistry getNonStopConfigurationToolkitRegistry() {
    return nonStopConfigManager;
  }

  public void start(NonStopConfiguration configuration) {
    nonStopConfigManager.registerForThread(configuration);

    if (configuration.isEnabled()) {
      nonStopManager.begin(configuration.getTimeoutMillis());
    }
  }

  public void stop() {
    NonStopConfiguration configuration = nonStopConfigManager.deregisterForThread();

    if (configuration.isEnabled()) {
      nonStopManager.finish();
    }
  }

  @Override
  public ToolkitLock getLock(String name, ToolkitLockTypeInternal lockType) {
    NonStopConfiguration nonStopConfiguration = this.nonStopConfigManager.getConfigForInstance(name,
                                                                                               ToolkitObjectType.LOCK);
    if (!nonStopConfiguration.isEnabled()) { return getInitializedToolkit().getLock(name); }

    NonStopDelegateProvider<ToolkitLock> nonStopDelegateProvider = new NonstopToolkitLockDelegateProvider(

    nonStopConfigManager, nonstopTimeoutBehaviorFactory, asyncToolkitInitializer, name, lockType);
    return (ToolkitLock) Proxy.newProxyInstance(this.getClass().getClassLoader(), new Class[] { ToolkitLock.class },
                                                new NonStopInvocationHandler<ToolkitLock>(nonStopManager,
                                                                                          nonStopDelegateProvider,
                                                                                          nonStopClusterListener));
  }

  @Override
  public void registerBeforeShutdownHook(Runnable hook) {
    getInitializedToolkit().registerBeforeShutdownHook(hook);
  }

  @Override
  public void waitUntilAllTransactionsComplete() {
    getInitializedToolkit().waitUntilAllTransactionsComplete();
  }

  @Override
  public ToolkitLogger getLogger(String name) {
    return getInitializedToolkit().getLogger(name);
  }

  @Override
  public String getClientUUID() {
    return getInitializedToolkit().getClientUUID();
  }

  @Override
  public ToolkitProperties getProperties() {
    return getInitializedToolkit().getProperties();
  }

  @Override
  public <T extends ToolkitFeature> T getFeature(Class<T> clazz) {
    if (clazz.isInstance(nonStopFeature)) { return (T) nonStopFeature; }
    throw new UnsupportedOperationException("Class " + clazz
                                            + " doesn't have a feature asscociated with the current Toolkit");
  }
}
