/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.admin.common;

import javax.swing.table.DefaultTableModel;

public class XTableModel extends DefaultTableModel {
  public XTableModel() {
    super();
  }

  public XTableModel(Object[] columnNames) {
    super(columnNames, 0);
  }
}
