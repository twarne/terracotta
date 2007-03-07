/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.server;

import com.tc.config.schema.setup.FatalIllegalConfigurationChangeHandler;
import com.tc.config.schema.setup.StandardTVSConfigurationSetupManagerFactory;
import com.tc.config.schema.setup.TVSConfigurationSetupManagerFactory;
import com.tc.lang.StartupHelper;
import com.tc.lang.TCThreadGroup;
import com.tc.lang.ThrowableHandler;
import com.tc.logging.TCLogging;

public class TCServerMain {

  public static void main(final String[] args) {
    ThrowableHandler throwableHandler = new ThrowableHandler(TCLogging.getLogger(TCServerMain.class));

    try {
      final TCThreadGroup threadGroup = new TCThreadGroup(throwableHandler);

      StartupHelper.StartupAction startupAction = new StartupHelper.StartupAction() {
        public void execute() throws Throwable {
          final TVSConfigurationSetupManagerFactory factory = new StandardTVSConfigurationSetupManagerFactory(
                                                                                                              args,
                                                                                                              true,
                                                                                                              new FatalIllegalConfigurationChangeHandler());
          final AbstractServerFactory serverFactory = AbstractServerFactory.getFactory();
          final TCServer server = serverFactory.createServer(factory.createL2TVSConfigurationSetupManager(null),
                                                             threadGroup);
          server.start();
        }
      };

      new StartupHelper(threadGroup, startupAction, "TCServerMain startup").startUp();
    } catch (Throwable t) {
      throwableHandler.handleThrowable(Thread.currentThread(), t);
    }
  }
}
