/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.net.protocol.transport;

/**
 *
 */
public interface TransportHandshakeErrorHandler {

  void handleHandshakeError(TransportHandshakeErrorContext e);

}
