/*
 * All content copyright (c) 2003-2007 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.object;

import com.tc.net.groups.ClientID;

public interface ClientIDProvider {
  
  public ClientID getClientID();

}
