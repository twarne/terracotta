/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.object;


public class TCObjectLogical extends TCObjectImpl {

  public TCObjectLogical(final ObjectID id, final Object peer, final TCClass tcc, final boolean isNew) {
    super(id, peer, tcc, isNew);
  }

  @Override
  public void logicalInvoke(final int method, final String methodName, final Object[] parameters) {
    getObjectManager().getTransactionManager().logicalInvoke(this, method, methodName, parameters);
  }

  @Override
  protected int clearReferences(final Object pojo, final int toClear) {
    // if (!(pojo instanceof Clearable)) { //dev-6999
    // Assert.fail("TCObjectLogical.clearReferences expected Clearable but got "
    // + (pojo == null ? "null" : pojo.getClass().getName()));
    // }
    // final Clearable clearable = (Clearable) pojo;
    return 0;
  }

  @Override
  public void unresolveReference(final String fieldName) {
    throw new AssertionError();
  }
}
