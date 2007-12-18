/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.test.server.appserver.resin3x;

import com.tc.test.server.appserver.AbstractAppServerInstallation;

import java.io.File;

/**
 * Defines the appserver name used by the installation process.
 */
public final class Resin3xAppServerInstallation extends AbstractAppServerInstallation {
  
  public Resin3xAppServerInstallation(File home, File workingDir, String majorVersion, String minorVersion) throws Exception {
    super(home, workingDir, majorVersion, minorVersion);
  }

  public String serverType() {
    return "resin";
  }
}
