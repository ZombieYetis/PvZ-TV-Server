package org.marshive;

import org.junit.After;
import org.junit.Test;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

public class ClientHandlerMemoryTest {
    private final List<ClientHandler> handlers = new ArrayList<>();

    private ClientHandler handler(TestChannel channel) throws IOException {
        ClientHandler handler = new ClientHandler(channel, new RoomManager(0), 0, 0, 0, "test");
        handlers.add(handler);
        return handler;
    }

    private void enqueue(ClientHandler handler, byte[] data) throws Exception {
        Method method = ClientHandler.class.getDeclaredMethod("enqueueRaw", byte[].class);
        method.setAccessible(true);
        try {
            method.invoke(handler, (Object) data);
        } catch (InvocationTargetException error) {
            throw (Exception) error.getCause();
        }
    }

    @After public void cleanup() {
        handlers.forEach(ClientHandler::close);
        assertEquals(0, ClientHandler.queuedWriteBytes());
        assertEquals(0, ClientHandler.queuedWriteBuffers());
    }

    @Test public void partiallyWrittenBufferKeepsItsFullReservationUntilCompletion() throws Exception {
        TestChannel channel = new TestChannel();
        ClientHandler handler = handler(channel);
        enqueue(handler, new byte[8]);
        channel.writeQuota = 3;
        handler.onWritable();
        assertEquals(8, ClientHandler.queuedWriteBytes());
        assertEquals(1, ClientHandler.queuedWriteBuffers());
        channel.writeQuota = 5;
        handler.onWritable();
        assertEquals(0, ClientHandler.queuedWriteBytes());
    }

    @Test public void closingPartiallyWrittenConnectionReleasesBudgetExactlyOnce() throws Exception {
        TestChannel channel = new TestChannel();
        ClientHandler handler = handler(channel);
        enqueue(handler, new byte[1000]);
        channel.writeQuota = 50;
        handler.onWritable();
        handler.close();
        handler.close();
        assertEquals(0, ClientHandler.queuedWriteBytes());
        assertThrows(IOException.class, () -> enqueue(handler, new byte[1]));
    }

    @Test public void tinyMessagesCannotBypassQueueObjectLimit() throws Exception {
        ClientHandler handler = handler(new TestChannel());
        for (int i = 0; i < 4096; i++) enqueue(handler, new byte[1]);
        assertThrows(IOException.class, () -> enqueue(handler, new byte[1]));
        // Callers may swallow a send failure; the next tick must still disconnect this client.
        assertThrows(IOException.class, () -> handler.onTick(System.currentTimeMillis()));
    }

    @Test public void globalBudgetIsSharedAcrossClientsAndRecoveredOnClose() throws Exception {
        byte[] block = new byte[1024 * 1024];
        int count = ResourceLimits.MAX_QUEUED_BYTES / block.length;
        for (int i = 0; i < count; i++) enqueue(handler(new TestChannel()), block);
        ClientHandler excess = handler(new TestChannel());
        assertThrows(IOException.class, () -> enqueue(excess, block));
        handlers.get(0).close();
        enqueue(handler(new TestChannel()), block);
        assertEquals((long) count * block.length, ClientHandler.queuedWriteBytes());
    }

    @Test public void outboundNotificationsDoNotKeepIdleConnectionsAlive() throws Exception {
        ClientHandler handler = handler(new TestChannel());
        var activity = ClientHandler.class.getDeclaredField("lastActiveAtMillis");
        activity.setAccessible(true);
        long old = System.currentTimeMillis() - ClientHandler.IDLE_TIMEOUT_MS - 1000;
        activity.setLong(handler, old);
        Method send = ClientHandler.class.getDeclaredMethod("sendFrame", byte.class, byte[].class);
        send.setAccessible(true);
        send.invoke(handler, (byte) 0x82, new byte[0]);
        assertEquals(old, activity.getLong(handler));
        assertThrows(IOException.class, () -> handler.onTick(System.currentTimeMillis()));
    }

    @Test public void probeUpdatesAreAppliedByTheGameLoopAndStaleTokensAreIgnored() throws Exception {
        ClientHandler handler = handler(new TestChannel());
        Method nat = ClientHandler.class.getDeclaredMethod("handleNatPort", int.class);
        nat.setAccessible(true);
        var tokenField = ClientHandler.class.getDeclaredField("probeToken");
        tokenField.setAccessible(true);
        nat.invoke(handler, 12345);
        int token = tokenField.getInt(handler);
        try (Socket probe = new Socket() {
            @Override public InetAddress getInetAddress() { return InetAddress.getLoopbackAddress(); }
            @Override public int getPort() { return 12345; }
        }) {
            ClientHandler.acceptP2PProbe(token, probe, 1);
            ClientHandler.acceptP2PProbe(token, probe, 2);
            assertFalse(handler.isProbeReady());
            handler.onTick(System.currentTimeMillis());
            assertTrue(handler.isProbeReady());
            ClientHandler.acceptP2PProbe(token, probe, 1);
            ClientHandler.acceptP2PProbe(token, probe, 2);
            nat.invoke(handler, 12345);
            handler.onTick(System.currentTimeMillis());
            assertFalse(handler.isProbeReady());
        }
    }

    /** A controllable nonblocking socket makes partial-write tests deterministic. */
    private static final class TestChannel extends SocketChannel {
        private final Socket socket = new Socket();
        int writeQuota;
        TestChannel() { super(SelectorProvider.provider()); }
        @Override public int write(ByteBuffer src) {
            int count = Math.min(writeQuota, src.remaining());
            src.position(src.position() + count);
            writeQuota -= count;
            return count;
        }
        @Override public int read(ByteBuffer dst) { return 0; }
        @Override public long read(ByteBuffer[] dsts, int offset, int length) { return 0; }
        @Override public long write(ByteBuffer[] srcs, int offset, int length) {
            long count = 0;
            for (int i = offset; i < offset + length; i++) count += write(srcs[i]);
            return count;
        }
        @Override public Socket socket() { return socket; }
        @Override public boolean isConnected() { return true; }
        @Override public boolean isConnectionPending() { return false; }
        @Override public boolean connect(SocketAddress remote) { return true; }
        @Override public boolean finishConnect() { return true; }
        @Override public SocketAddress getRemoteAddress() { return new InetSocketAddress(0); }
        @Override public SocketAddress getLocalAddress() { return new InetSocketAddress(0); }
        @Override public SocketChannel bind(SocketAddress local) { return this; }
        @Override public <T> SocketChannel setOption(SocketOption<T> name, T value) { return this; }
        @Override public <T> T getOption(SocketOption<T> name) { return null; }
        @Override public Set<SocketOption<?>> supportedOptions() { return Set.of(); }
        @Override public SocketChannel shutdownInput() { return this; }
        @Override public SocketChannel shutdownOutput() { return this; }
        @Override protected void implCloseSelectableChannel() throws IOException { socket.close(); }
        @Override protected void implConfigureBlocking(boolean block) {}
    }
}
