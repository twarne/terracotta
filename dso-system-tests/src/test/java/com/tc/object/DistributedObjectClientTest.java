package com.tc.object;

import com.tc.abortable.NullAbortableOperationManager;
import com.tc.cluster.DsoClusterEvent;
import com.tc.cluster.DsoClusterImpl;
import com.tc.cluster.DsoClusterListener;
import com.tc.config.schema.setup.L1ConfigurationSetupManager;
import com.tc.config.schema.setup.L2ConfigurationSetupManager;
import com.tc.config.schema.setup.TestConfigurationSetupManagerFactory;
import com.tc.exception.TCRuntimeException;
import com.tc.lang.StartupHelper;
import com.tc.lang.TCThreadGroup;
import com.tc.lang.ThrowableHandlerImpl;
import com.tc.logging.TCLogging;
import com.tc.net.GroupID;
import com.tc.net.NodeID;
import com.tc.net.protocol.tcm.TCMessageType;
import com.tc.net.protocol.tcm.msgs.PingMessage;
import com.tc.object.bytecode.MockClassProvider;
import com.tc.object.config.PreparedComponentsFromL2Connection;
import com.tc.object.config.StandardDSOClientConfigHelperImpl;
import com.tc.objectserver.impl.DistributedObjectServer;
import com.tc.objectserver.managedobject.ManagedObjectStateFactory;
import com.tc.platform.rejoin.RejoinManagerImpl;
import com.tc.platform.rejoin.RejoinManagerInternal;
import com.tc.properties.TCPropertiesConsts;
import com.tc.server.BasicServerEvent;
import com.tc.server.ServerEvent;
import com.tc.server.ServerEventType;
import com.tc.server.TCServer;
import com.tc.server.TCServerImpl;
import com.tc.util.PortChooser;
import com.tcclient.cluster.DsoClusterInternal;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.terracotta.test.util.WaitUtil;

import java.util.EnumSet;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.tc.server.ServerEventType.EVICT;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class DistributedObjectClientTest extends BaseDSOTestCase {

  private PreparedComponentsFromL2Connection preparedComponentsFromL2Connection;
  private boolean                            originalReconnect;

  @Override
  protected void setUp() throws Exception {
    super.setUp();

    originalReconnect = tcProps.getBoolean(TCPropertiesConsts.L2_L1RECONNECT_ENABLED);
    tcProps.setProperty(TCPropertiesConsts.L2_L1RECONNECT_ENABLED, "true");
    tcProps.setProperty(TCPropertiesConsts.L1_SERVER_EVENT_DELIVERY_TIMEOUT_INTERVAL, "1");
    System.setProperty("com.tc." + TCPropertiesConsts.L2_L1RECONNECT_ENABLED, "true");
  }

  protected final TCThreadGroup group = new TCThreadGroup(
          new ThrowableHandlerImpl(TCLogging
                  .getLogger(DistributedObjectServer.class)));

  protected class StartAction implements StartupHelper.StartupAction {
    private final int tsaPort;
    private final int jmxPort;
    private TCServer  server = null;

    private StartAction(final int tsaPort, final int jmxPort) {
      this.tsaPort = tsaPort;
      this.jmxPort = jmxPort;
    }

    public int getTsaPort() {
      return tsaPort;
    }

    public int getJmxPort() {
      return jmxPort;
    }

    public TCServer getServer() {
      return server;
    }

    @Override
    public void execute() throws Throwable {
      ManagedObjectStateFactory.disableSingleton(true);
      TestConfigurationSetupManagerFactory factory = configFactory();
      L2ConfigurationSetupManager manager = factory.createL2TVSConfigurationSetupManager(null, true);

      manager.dsoL2Config().tsaPort().setIntValue(tsaPort);
      manager.dsoL2Config().tsaPort().setBind("127.0.0.1");

      manager.commonl2Config().jmxPort().setIntValue(jmxPort);
      manager.commonl2Config().jmxPort().setBind("127.0.0.1");
      server = new TCServerImpl(manager);
      server.start();
    }
  }

  protected TCServer startupServer(final int tsaPort, final int jmxPort) {
    StartAction start_action = new StartAction(tsaPort, jmxPort);
    new StartupHelper(group, start_action).startUp();
    final TCServer server = start_action.getServer();
    return server;
  }

  public void testNodeErrorEventWhenUncaughtExceptionTriggered() throws Exception {
    final PortChooser pc = new PortChooser();
    final int tsaPort = pc.chooseRandomPort();
    final int jmxPort = pc.chooseRandomPort();

    final TCServerImpl server = (TCServerImpl) startupServer(tsaPort, jmxPort);
    server.getDSOServer().getCommunicationsManager().addClassMapping(TCMessageType.PING_MESSAGE, PingMessage.class);

    configFactory().addServerToL1Config("127.0.0.1", tsaPort, jmxPort);
    L1ConfigurationSetupManager manager = super.createL1ConfigManager();
    preparedComponentsFromL2Connection = new PreparedComponentsFromL2Connection(manager);

    RejoinManagerInternal rejoinManagerInternal = new RejoinManagerImpl(false);
    TCThreadGroup threadGroup = new TCThreadGroup(new ThrowableHandlerImpl(TCLogging.getLogger(DistributedObjectClient.class)));
    DsoClusterInternal dsoCluster = new DsoClusterImpl(rejoinManagerInternal);
    DistributedObjectClient client = new DistributedObjectClient(new StandardDSOClientConfigHelperImpl(manager),
            threadGroup,
            new MockClassProvider(),
            preparedComponentsFromL2Connection,
            dsoCluster,
            new NullAbortableOperationManager(),
            rejoinManagerInternal);
    client.start();
    dsoCluster.init(client.getClusterMetaDataManager(), client.getObjectManager(), client.getClusterEventsStage());
    DsoClusterListener mockDsoClusterListener = mock(DsoClusterListener.class);
    dsoCluster.addClusterListener(mockDsoClusterListener);

    Thread workerThread = new Thread(threadGroup, new Runnable() {
      @Override
      public void run() {
        throw new TCRuntimeException("uncaught exception");
      }
    });
    workerThread.start();
    Thread.sleep(1000);
    verify(mockDsoClusterListener).nodeError(any(DsoClusterEvent.class));
  }

  public void testRougeListener() throws Exception {
    final PortChooser pc = new PortChooser();
    final int tsaPort = pc.chooseRandomPort();
    final int jmxPort = pc.chooseRandomPort();

    final TCServerImpl server = (TCServerImpl) startupServer(tsaPort, jmxPort);
    server.getDSOServer().getCommunicationsManager().addClassMapping(TCMessageType.PING_MESSAGE, PingMessage.class);

    configFactory().addServerToL1Config("127.0.0.1", tsaPort, jmxPort);
    L1ConfigurationSetupManager manager = super.createL1ConfigManager();
    preparedComponentsFromL2Connection = new PreparedComponentsFromL2Connection(manager);

    RejoinManagerInternal rejoinManagerInternal = new RejoinManagerImpl(false);
    TCThreadGroup threadGroup = new TCThreadGroup(new ThrowableHandlerImpl(TCLogging.getLogger(DistributedObjectClient.class)));
    DsoClusterInternal dsoCluster = new DsoClusterImpl(rejoinManagerInternal);
    final DistributedObjectClient client = new DistributedObjectClient(new StandardDSOClientConfigHelperImpl(manager),
        threadGroup,
        new MockClassProvider(),
        preparedComponentsFromL2Connection,
        dsoCluster,
        new NullAbortableOperationManager(),
        rejoinManagerInternal);

    ServerEventDestination dest = mock(ServerEventDestination.class);
    final AtomicBoolean mockCompleted = new AtomicBoolean(false);


    Mockito.doAnswer(new Answer<Void>() {
      @Override
      public Void answer(InvocationOnMock invocationOnMock) throws Throwable {
        Thread.sleep(1*10*1000);
        mockCompleted.set(true);
        return null;
      }
    }).when(dest).handleServerEvent(any(ServerEvent.class));
    Mockito.doAnswer(new Answer<String>() {
      @Override
      public String answer(InvocationOnMock invocationOnMock) throws Throwable {
        return "cache1";
      }
    }).when(dest).getDestinationName();
    client.start();
    client.getServerEventListenerManager().registerListener(dest, EnumSet.of(ServerEventType.EVICT));
    dsoCluster.init(client.getClusterMetaDataManager(), client.getObjectManager(), client.getClusterEventsStage());
    DsoClusterListener mockDsoClusterListener = mock(DsoClusterListener.class);
    dsoCluster.addClusterListener(mockDsoClusterListener);
    final NodeID remoteNode = new GroupID(1);
    final ServerEvent event1 = new BasicServerEvent(EVICT, "key-1", "cache1");

    Thread workerThread = new Thread(threadGroup, new Runnable() {
      @Override
      public void run() {
        client.getServerEventListenerManager().dispatch(event1, remoteNode);
      }
    });
    workerThread.start();
    WaitUtil.waitUntilCallableReturnsTrue(new Callable<Boolean>() {
      @Override
      public Boolean call() throws Exception {
        return mockCompleted.get();
      }
    });
    verify(mockDsoClusterListener).nodeError(any(DsoClusterEvent.class));
  }

  @Override
  protected synchronized void tearDown() throws Exception {
    super.tearDown();
    tcProps.setProperty(TCPropertiesConsts.L2_L1RECONNECT_ENABLED, "" + originalReconnect);
    System.setProperty("com.tc." + TCPropertiesConsts.L2_L1RECONNECT_ENABLED, "" + originalReconnect);
  }
}
