/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tctest;

import org.apache.commons.io.CopyUtils;

import com.tc.config.schema.builder.InstrumentedClassConfigBuilder;
import com.tc.config.schema.setup.FatalIllegalConfigurationChangeHandler;
import com.tc.config.schema.setup.L1TVSConfigurationSetupManager;
import com.tc.config.schema.setup.StandardTVSConfigurationSetupManagerFactory;
import com.tc.config.schema.setup.TVSConfigurationSetupManagerFactory;
import com.tc.config.schema.test.InstrumentedClassConfigBuilderImpl;
import com.tc.config.schema.test.TerracottaConfigBuilder;
import com.tc.object.config.StandardDSOClientConfigHelper;
import com.tc.util.Assert;
import com.tc.util.PortChooser;
import com.tctest.runner.AbstractTransparentApp;
import com.tctest.runner.TransparentAppConfig;

import java.io.File;
import java.io.FileOutputStream;

public class ClusterMembershipEventJMXTest extends TransparentTestBase {

  private static final int NODE_COUNT = 1;
  private int              port;
  private File             configFile;

  protected boolean canRunCrash() {
    return true;
  }

  public void doSetUp(TransparentTestIface t) throws Exception {
    t.getTransparentAppConfig().setClientCount(NODE_COUNT);
    t.initializeTestRunner();
    TransparentAppConfig cfg = t.getTransparentAppConfig();
    cfg.setAttribute(ClusterMembershipEventJMXTestApp.CONFIG_FILE, getConfigFile().getAbsolutePath());
    cfg.setAttribute(ClusterMembershipEventJMXTestApp.PORT_NUMBER, String.valueOf(port));
    cfg.setAttribute(ClusterMembershipEventJMXTestApp.HOST_NAME, "localhost");
  }

  protected Class getApplicationClass() {
    return ClusterMembershipEventJMXTestApp.class;
  }

  public void setUp() throws Exception {
    TVSConfigurationSetupManagerFactory factory;
    factory = new StandardTVSConfigurationSetupManagerFactory(new String[] {
        StandardTVSConfigurationSetupManagerFactory.CONFIG_SPEC_ARGUMENT_WORD, getConfigFile().getAbsolutePath() },
                                                              true, new FatalIllegalConfigurationChangeHandler());

    L1TVSConfigurationSetupManager manager = factory.createL1TVSConfigurationSetupManager();
    super.setUp(factory, new StandardDSOClientConfigHelper(manager));
    doSetUp(this);
  }

  private synchronized File getConfigFile() {
    if (configFile == null) {
      try {
        // XXX: ERR! HACK! Will collide eventually
        port = new PortChooser().chooseRandomPort();

        configFile = getTempFile("config-file.xml");
        TerracottaConfigBuilder builder = createConfig(port);
        FileOutputStream out = new FileOutputStream(configFile);
        CopyUtils.copy(builder.toString(), out);
        out.close();
      } catch (Exception e) {
        throw Assert.failure("Can't create config file", e);
      }
    }
    return configFile;
  }

  public static TerracottaConfigBuilder createConfig(int port) {
    String testClassName = ClusterMembershipEventJMXTestApp.class.getName();
    String testClassSuperName = AbstractTransparentApp.class.getName();
    String clientClassName = ClusterMembershipEventJMXTestApp.L1Client.class.getName();

    TerracottaConfigBuilder out = new TerracottaConfigBuilder();

    out.getServers().getL2s()[0].setDSOPort(port);


    InstrumentedClassConfigBuilder instrumented1 = new InstrumentedClassConfigBuilderImpl();
    instrumented1.setClassExpression(testClassName + "*");

    InstrumentedClassConfigBuilder instrumented2 = new InstrumentedClassConfigBuilderImpl();
    instrumented2.setClassExpression(testClassSuperName + "*");

    out.getApplication().getDSO().setInstrumentedClasses(
                                                         new InstrumentedClassConfigBuilder[] { instrumented1,
                                                             instrumented2 });

    return out;
  }
}
