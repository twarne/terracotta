package com.terracotta.management.l1bridge;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.terracotta.management.l1bridge.RemoteCallDescriptor;
import org.terracotta.management.resource.AgentEntity;
import org.terracotta.management.resource.AgentMetadataEntity;

import com.terracotta.management.security.impl.NullContextService;
import com.terracotta.management.security.impl.NullRequestTicketMonitor;
import com.terracotta.management.security.impl.NullUserService;
import com.terracotta.management.service.L1MBeansSource;
import com.terracotta.management.service.RemoteAgentBridgeService;
import com.terracotta.management.service.impl.TimeoutServiceImpl;
import com.terracotta.management.web.proxy.ProxyException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.ws.rs.WebApplicationException;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author Ludovic Orban
 */
public class RemoteAgentServiceTest {

  private ExecutorService executorService;

  @Before
  public void setUp() throws Exception {
    executorService = Executors.newSingleThreadExecutor();
  }

  @After
  public void tearDown() throws Exception {
    executorService.shutdown();
  }

  @Test
  public void testGetAgents() throws Exception {
    RemoteAgentBridgeService remoteAgentBridgeService = mock(RemoteAgentBridgeService.class);
    L1MBeansSource l1MBeansSource = mock(L1MBeansSource.class);

    when(l1MBeansSource.containsJmxMBeans()).thenReturn(true);
    when(remoteAgentBridgeService.getRemoteAgentNodeNames()).thenReturn(Collections.singleton("node1"));
    when(remoteAgentBridgeService.getRemoteAgentNodeDetails(anyString())).thenReturn(new HashMap<String, String>(){{
      put("Agency", "Tst");
      put("Version", "1.2.3");
    }});

    RemoteAgentService remoteAgentService = new RemoteAgentService(remoteAgentBridgeService, new NullContextService(), executorService, new NullRequestTicketMonitor(), new NullUserService(), new TimeoutServiceImpl(1000), l1MBeansSource);

    Collection<AgentEntity> agents = remoteAgentService.getAgents(Collections.<String>emptySet());
    assertThat(agents.size(), is(1));
    AgentEntity entity = agents.iterator().next();
    assertThat(entity.getAgencyOf(), equalTo("Tst"));
    assertThat(entity.getVersion(), equalTo("1.2.3"));
  }

  @Test
  public void testGetAgentsProxyToActive() throws Exception {
    RemoteAgentBridgeService remoteAgentBridgeService = mock(RemoteAgentBridgeService.class);
    L1MBeansSource l1MBeansSource = mock(L1MBeansSource.class);

    when(l1MBeansSource.containsJmxMBeans()).thenReturn(false);
    when(l1MBeansSource.getActiveL2ContainingMBeansUrl()).thenReturn("http://localhost:1234");

    RemoteAgentService remoteAgentService = new RemoteAgentService(remoteAgentBridgeService, new NullContextService(), executorService, new NullRequestTicketMonitor(), new NullUserService(), new TimeoutServiceImpl(1000), l1MBeansSource);

    try {
      remoteAgentService.getAgents(Collections.<String>emptySet());
      fail("expected ProxyException");
    } catch (ProxyException pe) {
      assertThat(pe.getActiveL2WithMBeansUrl().contains("http://localhost:1234"), is(true));
    }
  }

  @Test
  public void testGetAgentsFailWhenNoActive() throws Exception {
    RemoteAgentBridgeService remoteAgentBridgeService = mock(RemoteAgentBridgeService.class);
    L1MBeansSource l1MBeansSource = mock(L1MBeansSource.class);

    when(l1MBeansSource.containsJmxMBeans()).thenReturn(false);
    when(l1MBeansSource.getActiveL2ContainingMBeansUrl()).thenReturn(null);

    RemoteAgentService remoteAgentService = new RemoteAgentService(remoteAgentBridgeService, new NullContextService(), executorService, new NullRequestTicketMonitor(), new NullUserService(), new TimeoutServiceImpl(1000), l1MBeansSource);

    try {
      remoteAgentService.getAgents(Collections.<String>emptySet());
      fail("expected WebApplicationException");
    } catch (WebApplicationException wae) {
      assertThat(wae.getResponse().getStatus(), equalTo(404));
    }
  }

  @Test
  public void testGetAgentsMetadata() throws Exception {
    RemoteAgentBridgeService remoteAgentBridgeService = mock(RemoteAgentBridgeService.class);
    L1MBeansSource l1MBeansSource = mock(L1MBeansSource.class);

    when(l1MBeansSource.containsJmxMBeans()).thenReturn(true);
    when(remoteAgentBridgeService.getRemoteAgentNodeNames()).thenReturn(Collections.singleton("node1"));
    when(remoteAgentBridgeService.getRemoteAgentNodeDetails(anyString())).thenReturn(new HashMap<String, String>(){{
      put("Agency", "Tst");
      put("Version", "1.2.3");
    }});

    AgentMetadataEntity ame = new AgentMetadataEntity();
    ame.setAgencyOf("Tst");
    ame.setVersion("1.2.3");
    when(remoteAgentBridgeService.invokeRemoteMethod(anyString(), any(RemoteCallDescriptor.class))).thenReturn(serialize(Collections.singleton(ame)));

    RemoteAgentService remoteAgentService = new RemoteAgentService(remoteAgentBridgeService, new NullContextService(), executorService, new NullRequestTicketMonitor(), new NullUserService(), new TimeoutServiceImpl(1000), l1MBeansSource);

    Collection<AgentMetadataEntity> agents = remoteAgentService.getAgentsMetadata(Collections.<String>emptySet());
    assertThat(agents.size(), is(1));
    AgentMetadataEntity entity = agents.iterator().next();
    assertThat(entity.getAgentId(), equalTo("node1"));
    assertThat(entity.getAgencyOf(), equalTo("Tst"));
    assertThat(entity.getVersion(), equalTo("1.2.3"));
  }


  @Test
  public void testGetAgentsMetadataProxyToActive() throws Exception {
    RemoteAgentBridgeService remoteAgentBridgeService = mock(RemoteAgentBridgeService.class);
    L1MBeansSource l1MBeansSource = mock(L1MBeansSource.class);

    when(l1MBeansSource.containsJmxMBeans()).thenReturn(false);
    when(l1MBeansSource.getActiveL2ContainingMBeansUrl()).thenReturn("http://localhost:1234");

    RemoteAgentService remoteAgentService = new RemoteAgentService(remoteAgentBridgeService, new NullContextService(), executorService, new NullRequestTicketMonitor(), new NullUserService(), new TimeoutServiceImpl(1000), l1MBeansSource);

    try {
      remoteAgentService.getAgentsMetadata(Collections.<String>emptySet());
      fail("expected ProxyException");
    } catch (ProxyException pe) {
      assertThat(pe.getActiveL2WithMBeansUrl().contains("http://localhost:1234"), is(true));
    }
  }

  @Test
  public void testGetAgentsMetadataFailWhenNoActive() throws Exception {
    RemoteAgentBridgeService remoteAgentBridgeService = mock(RemoteAgentBridgeService.class);
    L1MBeansSource l1MBeansSource = mock(L1MBeansSource.class);

    when(l1MBeansSource.containsJmxMBeans()).thenReturn(false);
    when(l1MBeansSource.getActiveL2ContainingMBeansUrl()).thenReturn(null);

    RemoteAgentService remoteAgentService = new RemoteAgentService(remoteAgentBridgeService, new NullContextService(), executorService, new NullRequestTicketMonitor(), new NullUserService(), new TimeoutServiceImpl(1000), l1MBeansSource);

    try {
      remoteAgentService.getAgentsMetadata(Collections.<String>emptySet());
      fail("expected WebApplicationException");
    } catch (WebApplicationException wae) {
      assertThat(wae.getResponse().getStatus(), equalTo(404));
    }
  }

  private static byte[] serialize(Object obj) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    ObjectOutputStream oos = new ObjectOutputStream(baos);
    oos.writeObject(obj);
    oos.close();
    return baos.toByteArray();
  }

}
