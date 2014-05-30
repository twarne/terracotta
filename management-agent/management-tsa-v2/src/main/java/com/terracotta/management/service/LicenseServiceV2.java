/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.terracotta.management.service;

import org.terracotta.management.ServiceExecutionException;

import com.terracotta.management.resource.LicenseEntityV2;

import java.util.Collection;
import java.util.Set;

/**
 * An interface for service implementations providing license properties
 * 
 * @author Hung Huynh
 */
public interface LicenseServiceV2 {

  /**
   * Get the license properties
   * 
   * @return a collection of license properties
   * @throws ServiceExecutionException
   */
  Collection<LicenseEntityV2> getLicenseProperties(Set<String> serverNames) throws ServiceExecutionException;

}
