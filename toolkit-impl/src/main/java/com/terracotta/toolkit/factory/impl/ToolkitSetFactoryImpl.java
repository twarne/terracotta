/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.terracotta.toolkit.factory.impl;

import org.terracotta.toolkit.config.Configuration;
import org.terracotta.toolkit.internal.ToolkitInternal;

import com.terracotta.toolkit.collections.ToolkitSetImpl;
import com.terracotta.toolkit.collections.map.ToolkitMapImpl;
import com.terracotta.toolkit.factory.ToolkitObjectFactory;
import com.terracotta.toolkit.object.ToolkitObjectType;
import com.terracotta.toolkit.roots.ToolkitTypeRootsFactory;
import com.terracotta.toolkit.roots.impl.ToolkitTypeConstants;
import com.terracotta.toolkit.type.IsolatedToolkitTypeFactory;

public class ToolkitSetFactoryImpl extends AbstractPrimaryToolkitObjectFactory<ToolkitSetImpl, ToolkitMapImpl> {

  private static final SetIsolatedTypeFactory FACTORY = new SetIsolatedTypeFactory();

  public ToolkitSetFactoryImpl(ToolkitInternal toolkit, ToolkitTypeRootsFactory rootsFactory) {
    super(toolkit, rootsFactory.createAggregateIsolatedTypeRoot(ToolkitTypeConstants.TOOLKIT_SET_ROOT_NAME, FACTORY));
  }

  @Override
  public ToolkitObjectType getManufacturedToolkitObjectType() {
    return ToolkitObjectType.SET;
  }

  private static class SetIsolatedTypeFactory implements IsolatedToolkitTypeFactory<ToolkitSetImpl, ToolkitMapImpl> {

    @Override
    public ToolkitSetImpl createIsolatedToolkitType(ToolkitObjectFactory<ToolkitSetImpl> factory, String name,
                                                    Configuration config, ToolkitMapImpl tcClusteredObject) {
      return new ToolkitSetImpl(factory, tcClusteredObject);
    }

    @Override
    public ToolkitMapImpl createTCClusteredObject(Configuration config) {
      return new ToolkitMapImpl();
    }

  }

}
