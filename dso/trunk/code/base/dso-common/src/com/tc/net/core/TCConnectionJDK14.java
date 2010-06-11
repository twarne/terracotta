/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.net.core;

import EDU.oswego.cs.dl.util.concurrent.CopyOnWriteArrayList;
import EDU.oswego.cs.dl.util.concurrent.Latch;
import EDU.oswego.cs.dl.util.concurrent.SynchronizedBoolean;
import EDU.oswego.cs.dl.util.concurrent.SynchronizedLong;

import com.tc.bytes.TCByteBuffer;
import com.tc.bytes.TCByteBufferFactory;
import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.net.NIOWorkarounds;
import com.tc.net.TCSocketAddress;
import com.tc.net.core.event.TCConnectionEventCaller;
import com.tc.net.core.event.TCConnectionEventListener;
import com.tc.net.protocol.TCNetworkMessage;
import com.tc.net.protocol.TCProtocolAdaptor;
import com.tc.net.protocol.transport.WireProtocolGroupMessageImpl;
import com.tc.net.protocol.transport.WireProtocolHeader;
import com.tc.net.protocol.transport.WireProtocolMessage;
import com.tc.net.protocol.transport.WireProtocolMessageImpl;
import com.tc.properties.TCPropertiesConsts;
import com.tc.properties.TCPropertiesImpl;
import com.tc.util.Assert;
import com.tc.util.TCTimeoutException;
import com.tc.util.concurrent.SetOnceFlag;
import com.tc.util.concurrent.SetOnceRef;
import com.tc.util.concurrent.ThreadUtil;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.ScatteringByteChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JDK14 (nio) implementation of TCConnection
 * 
 * @author teck
 */
final class TCConnectionJDK14 implements TCConnection, TCJDK14ChannelReader, TCJDK14ChannelWriter {

  private static final long                  NO_CONNECT_TIME             = -1L;
  private static final TCLogger              logger                      = TCLogging.getLogger(TCConnection.class);
  private static final long                  WARN_THRESHOLD              = 0x400000L;                                                    // 4MB

  private volatile CoreNIOServices           commWorker;
  private volatile SocketChannel             channel;

  private final AtomicBoolean                transportEstablished        = new AtomicBoolean(false);
  private final LinkedList<TCNetworkMessage> writeMessages               = new LinkedList<TCNetworkMessage>();
  private final TCConnectionManagerJDK14     parent;
  private final TCConnectionEventCaller      eventCaller                 = new TCConnectionEventCaller(logger);
  private final SynchronizedLong             lastDataWriteTime           = new SynchronizedLong(System
                                                                             .currentTimeMillis());
  private final SynchronizedLong             lastDataReceiveTime         = new SynchronizedLong(System
                                                                             .currentTimeMillis());
  private final SynchronizedLong             connectTime                 = new SynchronizedLong(NO_CONNECT_TIME);
  private final List                         eventListeners              = new CopyOnWriteArrayList();
  private final TCProtocolAdaptor            protocolAdaptor;
  private final SynchronizedBoolean          isSocketEndpoint            = new SynchronizedBoolean(false);
  private final SetOnceFlag                  closed                      = new SetOnceFlag();
  private final SynchronizedBoolean          connected                   = new SynchronizedBoolean(false);
  private final SetOnceRef                   localSocketAddress          = new SetOnceRef();
  private final SetOnceRef                   remoteSocketAddress         = new SetOnceRef();
  private final SocketParams                 socketParams;
  private final SynchronizedLong             totalRead                   = new SynchronizedLong(0);
  private final SynchronizedLong             totalWrite                  = new SynchronizedLong(0);
  private final ArrayList<WriteContext>      writeContexts               = new ArrayList<WriteContext>();

  private static final boolean               MSG_GROUPING_ENABLED        = TCPropertiesImpl
                                                                             .getProperties()
                                                                             .getBoolean(
                                                                                         TCPropertiesConsts.TC_MESSAGE_GROUPING_ENABLED);
  private static final int                   MSG_GROUPING_MAX_SIZE_BYTES = TCPropertiesImpl
                                                                             .getProperties()
                                                                             .getInt(
                                                                                     TCPropertiesConsts.TC_MESSAGE_GROUPING_MAXSIZE_KB,
                                                                                     128) * 1024;

  static {
    logger.info("Comms Message Batching " + (MSG_GROUPING_ENABLED ? "enabled" : "disabled"));
  }

  // having this variable at instance level helps reducing memory pressure at VM;
  private final ArrayList<TCNetworkMessage>  messagesToBatch             = new ArrayList<TCNetworkMessage>();

  // for creating unconnected client connections
  TCConnectionJDK14(TCConnectionEventListener listener, TCProtocolAdaptor adaptor,
                    TCConnectionManagerJDK14 managerJDK14, CoreNIOServices nioServiceThread, SocketParams socketParams) {
    this(listener, adaptor, null, managerJDK14, nioServiceThread, socketParams);
  }

  TCConnectionJDK14(TCConnectionEventListener listener, TCProtocolAdaptor adaptor, SocketChannel ch,
                    TCConnectionManagerJDK14 parent, CoreNIOServices nioServiceThread, SocketParams socketParams) {

    Assert.assertNotNull(parent);
    Assert.assertNotNull(adaptor);

    this.parent = parent;
    this.protocolAdaptor = adaptor;

    if (listener != null) addListener(listener);

    this.channel = ch;

    if (ch != null) {
      socketParams.applySocketParams(ch.socket());
    }

    this.socketParams = socketParams;
    this.commWorker = nioServiceThread;
  }

  public void setCommWorker(CoreNIOServices worker) {
    this.commWorker = worker;
  }

  private void closeImpl(Runnable callback) {
    Assert.assertTrue(closed.isSet());
    this.transportEstablished.set(false);
    try {
      if (channel != null) {
        commWorker.cleanupChannel(channel, callback);
      } else {
        callback.run();
      }
    } finally {
      synchronized (writeMessages) {
        writeMessages.clear();
      }
    }
  }

  protected void finishConnect() throws IOException {
    Assert.assertNotNull("channel", channel);
    recordSocketAddress(channel.socket());
    setConnected(true);
    eventCaller.fireConnectEvent(eventListeners, this);
  }

  private void connectImpl(TCSocketAddress addr, int timeout) throws IOException, TCTimeoutException {
    SocketChannel newSocket = null;
    InetSocketAddress inetAddr = new InetSocketAddress(addr.getAddress(), addr.getPort());
    for (int i = 1; i <= 3; i++) {
      try {
        newSocket = createChannel();
        newSocket.configureBlocking(true);
        newSocket.socket().connect(inetAddr, timeout);
        break;
      } catch (SocketTimeoutException ste) {
        Assert.eval(commWorker != null);
        commWorker.cleanupChannel(newSocket, null);
        throw new TCTimeoutException("Timeout of " + timeout + "ms occured connecting to " + addr, ste);
      } catch (ClosedSelectorException cse) {
        if (NIOWorkarounds.connectWorkaround(cse)) {
          logger.warn("Retrying connect to " + addr + ", attempt " + i);
          ThreadUtil.reallySleep(500);
          continue;
        }
        throw cse;
      }
    }

    channel = newSocket;
    newSocket.configureBlocking(false);
    Assert.eval(commWorker != null);
    commWorker.requestReadInterest(this, newSocket);
  }

  private SocketChannel createChannel() throws IOException, SocketException {
    SocketChannel rv = SocketChannel.open();
    Socket s = rv.socket();
    socketParams.applySocketParams(s);
    return rv;
  }

  private Socket detachImpl() throws IOException {
    commWorker.detach(channel);
    channel.configureBlocking(true);
    return channel.socket();
  }

  private boolean asynchConnectImpl(TCSocketAddress address) throws IOException {
    SocketChannel newSocket = createChannel();
    newSocket.configureBlocking(false);

    InetSocketAddress inetAddr = new InetSocketAddress(address.getAddress(), address.getPort());
    final boolean rv = newSocket.connect(inetAddr);
    setConnected(rv);

    channel = newSocket;

    if (!rv) {
      commWorker.requestConnectInterest(this, newSocket);
    }

    return rv;
  }

  public int doRead(ScatteringByteChannel sbc) {
    int read = doReadInternal(sbc);
    totalRead.add(read);
    return read;
  }

  public int doWrite(GatheringByteChannel gbc) {
    int written = doWriteInternal(gbc);
    totalWrite.add(written);
    return written;
  }

  private void buildWriteContextsFromMessages() {
    TCNetworkMessage messagesToWrite[];
    synchronized (writeMessages) {
      if (closed.isSet()) { return; }
      messagesToWrite = writeMessages.toArray(new TCNetworkMessage[writeMessages.size()]);
      writeMessages.clear();
    }

    int batchSize = 0;
    int batchMsgCount = 0;
    TCNetworkMessage msg = null;
    for (int i = 0; i < messagesToWrite.length; i++) {
      msg = messagesToWrite[i];

      // we don't want to group already constructed Transport Handshake WireProtocolMessages
      if (msg instanceof WireProtocolMessage) {
        TCNetworkMessage ms = finalizeWireProtocolMessage((WireProtocolMessage) msg, 1);
        writeContexts.add(new WriteContext(ms));
        continue;
      }

      // GenericNetwork messages are used for testing
      if (WireProtocolHeader.PROTOCOL_UNKNOWN == WireProtocolHeader.getProtocolForMessageClass(msg)) {
        writeContexts.add(new WriteContext(msg));
        continue;
      }

      if (MSG_GROUPING_ENABLED) {
        if (!canBatch(msg, batchSize, batchMsgCount)) {
          if (batchMsgCount > 0) {
            writeContexts.add(new WriteContext(buildWireProtocolMessageGroup(messagesToBatch)));
            batchSize = 0;
            batchMsgCount = 0;
            messagesToBatch.clear();
          } else {
            // fall thru and add to the existing batch. next message will goto a new batch
          }
        }
        batchSize += getRealMessgeSize(msg.getTotalLength());
        batchMsgCount++;
        messagesToBatch.add(msg);
      } else {
        writeContexts.add(new WriteContext(buildWireProtocolMessage(msg)));
      }
      msg = null;
    }

    if (MSG_GROUPING_ENABLED && batchMsgCount > 0) {
      TCNetworkMessage ms = buildWireProtocolMessageGroup(messagesToBatch);
      writeContexts.add(new WriteContext(ms));
    }

    messagesToWrite = null;
    messagesToBatch.clear();
  }

  private boolean canBatch(final TCNetworkMessage newMessage, final int currentBatchSize, final int currentBatchMsgCount) {
    if ((currentBatchSize + getRealMessgeSize(newMessage.getTotalLength())) <= MSG_GROUPING_MAX_SIZE_BYTES
        && (currentBatchMsgCount + 1 <= WireProtocolHeader.MAX_MESSAGE_COUNT)) { return true; }
    return false;
  }

  private int getRealMessgeSize(final int length) {
    return TCByteBufferFactory.getTotalBufferSizeNeededForMessageSize(length);
  }

  private int doReadInternal(ScatteringByteChannel sbc) {
    final boolean debug = logger.isDebugEnabled();
    final TCByteBuffer[] readBuffers = getReadBuffers();

    int bytesRead = 0;
    boolean readEOF = false;
    try {
      // Do the read in a loop, instead of calling read(ByteBuffer[]).
      // This seems to avoid memory leaks on sun's 1.4.2 JDK
      for (TCByteBuffer readBuffer : readBuffers) {
        ByteBuffer buf = extractNioBuffer(readBuffer);

        if (buf.hasRemaining()) {
          final int read = sbc.read(buf);

          if (-1 == read) {
            // Normal EOF
            readEOF = true;
            break;
          }

          if (0 == read) {
            break;
          }

          bytesRead += read;

          if (buf.hasRemaining()) {
            // don't move on to the next buffer if we didn't fill the current one
            break;
          }
        }
      }
    } catch (IOException ioe) {
      if (logger.isInfoEnabled()) {
        logger.info("error reading from channel " + channel.toString() + ": " + ioe.getMessage());
      }

      eventCaller.fireErrorEvent(eventListeners, this, ioe, null);
      return bytesRead;
    }

    if (readEOF) {
      if (bytesRead > 0) {
        addNetworkData(readBuffers, bytesRead);
      }

      if (debug) logger.debug("EOF read on connection " + channel.toString());

      eventCaller.fireEndOfFileEvent(eventListeners, this);
      return bytesRead;
    }

    Assert.eval(bytesRead >= 0);

    if (debug) logger.debug("Read " + bytesRead + " bytes on connection " + channel.toString());

    addNetworkData(readBuffers, bytesRead);

    return bytesRead;
  }

  public int doWriteInternal(GatheringByteChannel gbc) {
    final boolean debug = logger.isDebugEnabled();
    int totalBytesWritten = 0;

    // get a copy of the current write contexts. Since we call out to event/error handlers in the write
    // loop below, we don't want to be holding the lock on the writeContexts queue
    if (writeContexts.size() <= 0) buildWriteContextsFromMessages();
    WriteContext context;
    while (writeContexts.size() > 0) {
      context = writeContexts.get(0);
      final ByteBuffer[] buffers = context.clonedData;

      long bytesWritten = 0;
      try {
        // Do the write in a loop, instead of calling write(ByteBuffer[]).
        // This seems to avoid memory leaks on sun's 1.4.2 JDK
        for (int i = context.index, nn = buffers.length; i < nn; i++) {
          final ByteBuffer buf = buffers[i];
          final int written = gbc.write(buf);

          if (written == 0) {
            break;
          }

          bytesWritten += written;

          if (buf.hasRemaining()) {
            break;
          } else {
            context.incrementIndexAndCleanOld();
          }
        }
      } catch (IOException ioe) {
        if (NIOWorkarounds.windowsWritevWorkaround(ioe)) {
          break;
        }

        eventCaller.fireErrorEvent(eventListeners, this, ioe, context.message);
      }

      if (debug) logger.debug("Wrote " + bytesWritten + " bytes on connection " + channel.toString());
      totalBytesWritten += bytesWritten;

      if (context.done()) {
        if (debug) logger.debug("Complete message sent on connection " + channel.toString());
        context.writeComplete();
        writeContexts.remove(context);
      } else {
        if (debug) logger.debug("Message not yet completely sent on connection " + channel.toString());
        break;
      }
    }

    synchronized (writeMessages) {
      if (closed.isSet()) { return totalBytesWritten; }

      if (writeMessages.isEmpty() && writeContexts.isEmpty()) {
        commWorker.removeWriteInterest(this, channel);
      }
    }
    return totalBytesWritten;
  }

  static private ByteBuffer[] extractNioBuffers(TCByteBuffer[] src) {
    ByteBuffer[] rv = new ByteBuffer[src.length];
    for (int i = 0, n = src.length; i < n; i++) {
      rv[i] = src[i].getNioBuffer();
    }

    return rv;
  }

  static private ByteBuffer extractNioBuffer(TCByteBuffer buffer) {
    return buffer.getNioBuffer();
  }

  private void putMessageImpl(TCNetworkMessage message) {
    // ??? Does the message queue and the WriteContext belong in the base connection class?
    final boolean debug = logger.isDebugEnabled();

    long bytesToWrite = 0;
    bytesToWrite = message.getTotalLength();
    if (bytesToWrite >= TCConnectionJDK14.WARN_THRESHOLD) {
      logger.warn("Warning: Attempting to send a messaage of size " + bytesToWrite + " bytes");
    }

    // TODO: outgoing queue should not be unbounded size!
    final boolean newData;
    final int msgCount;

    synchronized (writeMessages) {
      if (closed.isSet()) { return; }
      writeMessages.addLast(message);
      msgCount = writeMessages.size();
      newData = (msgCount == 1);
    }

    if (debug) {
      logger.debug("Connection (" + channel.toString() + ") has " + msgCount + " messages queued");
    }

    if (newData) {
      if (debug) {
        logger.debug("New message on connection, registering for write interest");
      }

      // NOTE: this might be the very first message on the socket and
      // given the current implementation, it isn't necessarily
      // safe to assume one can write to the channel. Long story
      // short, always enqueue the message and wait until it is selected
      // for write interest.

      // If you're trying to optimize for performance by letting the calling thread do the
      // write, we need to add more logic to connection setup. Specifically, you need register
      // for, as well as actually be selected for, write interest immediately
      // after finishConnect(). Only after this selection occurs it is always safe to try
      // to write.
      commWorker.requestWriteInterest(this, channel);
    }
  }

  public final void asynchClose() {
    if (closed.attemptSet()) {
      closeImpl(createCloseCallback(null));
    }
  }

  public final boolean close(final long timeout) {
    if (timeout <= 0) { throw new IllegalArgumentException("timeout cannot be less than or equal to zero"); }

    if (closed.attemptSet()) {
      final Latch latch = new Latch();
      closeImpl(createCloseCallback(latch));
      try {
        return latch.attempt(timeout);
      } catch (InterruptedException e) {
        logger.warn("close interrupted");
        return isConnected();
      }
    }

    return isClosed();
  }

  private final Runnable createCloseCallback(final Latch latch) {
    final boolean fireClose = isConnected();

    return new Runnable() {
      public void run() {
        setConnected(false);
        parent.connectionClosed(TCConnectionJDK14.this);

        if (fireClose) {
          eventCaller.fireCloseEvent(eventListeners, TCConnectionJDK14.this);
        }

        if (latch != null) latch.release();
      }
    };
  }

  public final boolean isClosed() {
    return closed.isSet();
  }

  public final boolean isConnected() {
    return connected.get();
  }

  @Override
  public final String toString() {
    StringBuffer buf = new StringBuffer();

    buf.append(getClass().getName()).append('@').append(hashCode()).append(":");

    buf.append(" connected: ").append(isConnected());
    buf.append(", closed: ").append(isClosed());

    if (isSocketEndpoint.get()) {
      buf.append(" local=");
      if (localSocketAddress.isSet()) {
        buf.append(((TCSocketAddress) localSocketAddress.get()).getStringForm());
      } else {
        buf.append("[unknown]");
      }

      buf.append(" remote=");
      if (remoteSocketAddress.isSet()) {
        buf.append(((TCSocketAddress) remoteSocketAddress.get()).getStringForm());
      } else {
        buf.append("[unknown]");
      }
    }

    buf.append(" connect=[");
    final long connect = getConnectTime();

    if (connect != NO_CONNECT_TIME) {
      buf.append(new Date(connect));
    } else {
      buf.append("no connect time");
    }
    buf.append(']');

    buf.append(" idle=").append(getIdleTime()).append("ms");

    buf.append(" [").append(totalRead.get()).append(" read, ").append(totalWrite.get()).append(" write]");

    return buf.toString();
  }

  public final void addListener(TCConnectionEventListener listener) {
    if (listener == null) { return; }
    eventListeners.add(listener); // don't need sync
  }

  public final void removeListener(TCConnectionEventListener listener) {
    if (listener == null) { return; }
    eventListeners.remove(listener); // don't need sync
  }

  public final long getConnectTime() {
    return connectTime.get();
  }

  public final long getIdleTime() {
    return System.currentTimeMillis()
           - (lastDataWriteTime.get() > lastDataReceiveTime.get() ? lastDataWriteTime.get() : lastDataReceiveTime.get());
  }

  public final long getIdleReceiveTime() {
    return System.currentTimeMillis() - lastDataReceiveTime.get();
  }

  public final synchronized void connect(TCSocketAddress addr, int timeout) throws IOException, TCTimeoutException {
    if (closed.isSet() || connected.get()) { throw new IllegalStateException("Connection closed or already connected"); }
    connectImpl(addr, timeout);
    finishConnect();
  }

  public final synchronized boolean asynchConnect(TCSocketAddress addr) throws IOException {
    if (closed.isSet() || connected.get()) { throw new IllegalStateException("Connection closed or already connected"); }

    boolean rv = asynchConnectImpl(addr);

    if (rv) {
      finishConnect();
    }

    return rv;
  }

  public final void putMessage(TCNetworkMessage message) {
    lastDataWriteTime.set(System.currentTimeMillis());

    // if (!isConnected() || isClosed()) {
    // logger.warn("Ignoring message sent to non-connected connection");
    // return;
    // }

    putMessageImpl(message);
  }

  public final TCSocketAddress getLocalAddress() {
    return (TCSocketAddress) localSocketAddress.get();
  }

  public final TCSocketAddress getRemoteAddress() {
    return (TCSocketAddress) remoteSocketAddress.get();
  }

  private final void setConnected(boolean connected) {
    if (connected) {
      this.connectTime.set(System.currentTimeMillis());
    }
    this.connected.set(connected);
  }

  private final void recordSocketAddress(Socket socket) throws IOException {
    if (socket != null) {
      InetAddress localAddress = socket.getLocalAddress();
      InetAddress remoteAddress = socket.getInetAddress();

      if (remoteAddress != null && localAddress != null) {
        isSocketEndpoint.set(true);
        localSocketAddress.set(new TCSocketAddress(cloneInetAddress(localAddress), socket.getLocalPort()));
        remoteSocketAddress.set(new TCSocketAddress(cloneInetAddress(remoteAddress), socket.getPort()));
      } else {
        // abort if socket is not connected
        throw new IOException("socket is not connected");
      }
    }
  }

  /**
   * This madness to workaround a SocketException("protocol family not available"). For whatever reason, the actual
   * InetAddress instances obtained directly from the connected socket has it's "family" field set to IPv6 even though
   * when it is an instance of Inet4Address. Trying to use that instance to connect to throws an exception
   */
  private static InetAddress cloneInetAddress(InetAddress addr) {
    try {
      byte[] address = addr.getAddress();
      return InetAddress.getByAddress(address);
    } catch (UnknownHostException e) {
      throw new AssertionError(e);
    }
  }

  private final void addNetworkData(TCByteBuffer[] data, int length) {
    lastDataReceiveTime.set(System.currentTimeMillis());

    try {
      protocolAdaptor.addReadData(this, data, length);
    } catch (Exception e) {
      logger.error(this.toString() + " " + e.getMessage());
      eventCaller.fireErrorEvent(eventListeners, this, e, null);
      return;
    }
  }

  protected final TCByteBuffer[] getReadBuffers() {
    // TODO: Hook in some form of read throttle. To throttle how much data is read from the network,
    // only return a subset of the buffers that the protocolAdaptor advises to be used.

    // TODO: should also support a way to de-register read interest temporarily

    return protocolAdaptor.getReadBuffers();
  }

  protected final void fireErrorEvent(Exception e, TCNetworkMessage context) {
    eventCaller.fireErrorEvent(eventListeners, this, e, context);
  }

  public final Socket detach() throws IOException {
    this.parent.removeConnection(this);
    return detachImpl();
  }

  private TCNetworkMessage buildWireProtocolMessageGroup(ArrayList<TCNetworkMessage> messages) {
    Assert.assertTrue("Messages count not ok to build WireProtocolMessageGroup : " + messages.size(),
                      (messages.size() > 0) && (messages.size() <= WireProtocolHeader.MAX_MESSAGE_COUNT));
    if (messages.size() == 1) { return buildWireProtocolMessage(messages.get(0)); }

    final TCNetworkMessage message = WireProtocolGroupMessageImpl.wrapMessages(messages, this);
    Assert.eval(message.getSentCallback() == null);

    final Runnable[] callbacks = new Runnable[messages.size()];
    for (int i = 0; i < messages.size(); i++) {
      Assert.eval(!(messages.get(i) instanceof WireProtocolMessage));
      callbacks[i] = messages.get(i).getSentCallback();
    }

    message.setSentCallback(new Runnable() {
      public void run() {
        for (int i = 0; i < callbacks.length; i++) {
          if (callbacks[i] != null) callbacks[i].run();
        }
      }
    });
    return finalizeWireProtocolMessage((WireProtocolMessage) message, messages.size());
  }

  private TCNetworkMessage buildWireProtocolMessage(TCNetworkMessage message) {
    Assert.eval(!(message instanceof WireProtocolMessage));
    final TCNetworkMessage payload = message;

    message = WireProtocolMessageImpl.wrapMessage(message, this);
    Assert.eval(message.getSentCallback() == null);

    final Runnable callback = payload.getSentCallback();
    if (callback != null) {
      message.setSentCallback(new Runnable() {
        public void run() {
          callback.run();
        }
      });
    }
    return finalizeWireProtocolMessage((WireProtocolMessage) message, 1);
  }

  private TCNetworkMessage finalizeWireProtocolMessage(final WireProtocolMessage message, final int messageCount) {
    WireProtocolHeader hdr = (WireProtocolHeader) message.getHeader();
    hdr.setSourceAddress(getLocalAddress().getAddressBytes());
    hdr.setSourcePort(getLocalAddress().getPort());
    hdr.setDestinationAddress(getRemoteAddress().getAddressBytes());
    hdr.setDestinationPort(getRemoteAddress().getPort());
    hdr.setMessageCount(messageCount);
    hdr.computeChecksum();
    return message;
  }

  private static class WriteContext {
    private final TCNetworkMessage message;
    private final ByteBuffer[]     clonedData;
    private int                    index = 0;

    WriteContext(TCNetworkMessage message) {
      // either WireProtocolMessage or WireProtocolMessageGroup
      this.message = message;

      final ByteBuffer[] msgData = extractNioBuffers(message.getEntireMessageData());
      this.clonedData = new ByteBuffer[msgData.length];

      for (int i = 0; i < msgData.length; i++) {
        clonedData[i] = msgData[i].duplicate().asReadOnlyBuffer();
      }
    }

    boolean done() {
      for (int i = index, n = clonedData.length; i < n; i++) {
        if (clonedData[i].hasRemaining()) { return false; }
      }

      return true;
    }

    void incrementIndexAndCleanOld() {
      clonedData[index] = null;
      index++;
    }

    void writeComplete() {
      this.message.wasSent();
    }
  }

  public void addWeight(int addWeightBy) {
    this.commWorker.addWeight(this, addWeightBy, channel);
  }

  public void setTransportEstablished() {
    this.transportEstablished.set(true);
  }

  public boolean isTransportEstablished() {
    return this.transportEstablished.get();
  }

}
