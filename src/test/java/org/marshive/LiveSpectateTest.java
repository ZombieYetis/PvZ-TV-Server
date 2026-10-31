package org.marshive;

import org.junit.After;
import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.channels.spi.SelectorProvider;
import java.util.*;
import static org.junit.Assert.*;

public class LiveSpectateTest {
    private final List<ClientHandler> handlers = new ArrayList<>();
    private final TestChannel hostWire = new TestChannel(), guestWire = new TestChannel();
    private ClientHandler host, guest;
    private Room room;

    private static void set(ClientHandler h, String name, Object value) throws Exception {
        var f = ClientHandler.class.getDeclaredField(name); f.setAccessible(true); f.set(h, value);
    }
    private ClientHandler handler(TestChannel wire) throws Exception {
        var h = new ClientHandler(wire, new RoomManager(0), 0, 0, 0, "test");
        handlers.add(h); return h;
    }
    private void setup(int version) throws Exception {
        host = handler(hostWire); guest = handler(guestWire);
        room = new Room(1, "[VS]test", host, version); room.setGuest(guest);
        room.setGaming(true); room.setRelayMode(true); room.setRelayEpoch(1); room.setRelayDataOpen(true);
        set(host, "currentRoom", room); set(guest, "currentRoom", room);
        set(host, "isHost", true);
        set(host, "relayDataMode", true); set(guest, "relayDataMode", true);
        set(host, "relayPeer", guest); set(guest, "relayPeer", host);
    }
    private ClientHandler join(TestChannel wire) throws Exception {
        var s = handler(wire); set(s, "currentRoom", room); set(s, "isSpectator", true);
        room.addSpectator(s);
        var m = ClientHandler.class.getDeclaredMethod("handleReserveSpectate", boolean.class);
        m.setAccessible(true); m.invoke(s, true);
        s.onWritable(); wire.output.reset(); // control ACK/BEGIN/GO precede the raw stream
        return s;
    }
    private void send(ClientHandler h, TestChannel wire, byte... bytes) throws Exception {
        wire.inbound = ByteBuffer.wrap(bytes); h.onReadable();
    }
    private byte[] drain(ClientHandler h, TestChannel wire) throws Exception {
        h.onWritable(); byte[] bytes = wire.output.toByteArray(); wire.output.reset(); return bytes;
    }
    @After public void cleanup() {
        handlers.forEach(ClientHandler::close);
        assertEquals(0, ClientHandler.queuedWriteBytes());
    }
    @Test public void joiningDuringPartialGuestEventCannotSplitTheHostStream() throws Exception {
        setup(3214);
        send(guest, guestWire, (byte) 10, (byte) 4, (byte) 42);
        assertArrayEquals(new byte[0], drain(host, hostWire));
        join(new TestChannel());
        assertArrayEquals(new byte[]{(byte) 240, 2}, drain(host, hostWire));
        send(guest, guestWire, (byte) 43);
        assertArrayEquals(new byte[]{10, 4, 42, 43}, drain(host, hostWire));
    }
    @Test public void snapshotsAreViewerOnlyAndFollowCompleteEventOrdering() throws Exception {
        setup(3214);
        var wire = new TestChannel(); var s = join(wire); drain(host, hostWire);
        send(host, hostWire, (byte) 241, (byte) 4, (byte) 1);
        assertArrayEquals(new byte[0], drain(s, wire));
        send(host, hostWire, (byte) 2, (byte) 12, (byte) 2, (byte) 243, (byte) 2);
        assertArrayEquals(new byte[]{(byte) 241, 4, 1, 2, 12, 2, (byte) 243, 2}, drain(s, wire));
        assertArrayEquals(new byte[]{12, 2}, drain(guest, guestWire));
        send(guest, guestWire, (byte) 241, (byte) 2, (byte) 240, (byte) 2);
        assertArrayEquals(new byte[0], drain(host, hostWire));
        assertArrayEquals(new byte[0], drain(s, wire));
    }
    @Test public void aSecondJoinRequestsAnotherSnapshotAndOldRoomsKeepQueueing() throws Exception {
        setup(3214); join(new TestChannel()); join(new TestChannel());
        assertArrayEquals(new byte[]{(byte) 240, 2, (byte) 240, 2}, drain(host, hostWire));
        room = new Room(2, "[VS]old", host, 3213); room.setGuest(guest);
        room.setGaming(true); room.setRelayMode(true); room.setRelayEpoch(1); room.setRelayDataOpen(true);
        join(new TestChannel());
        assertArrayEquals(new byte[0], drain(host, hostWire));
    }
    @Test public void draftSnapshotsCanRestartAndCarryFinalCountdowns() throws Exception {
        setup(3214);
        var wire = new TestChannel(); var spectator = join(wire); drain(host, hostWire);
        // Snapshot format v2: BEGIN, partial data, a live pick, then a fresh
        // BEGIN when drafting ends. The relay keeps the framing/order opaque.
        byte[] begin = {(byte) 241, 12, 2, 0, 16, 0, 0, 0, 1, 2, 3, 4};
        byte[] chunk = new byte[244]; chunk[0] = (byte) 242; chunk[1] = (byte) 244; chunk[2] = 16;
        byte[] pick = {27, 5, 1, 0, 0}; // EVENT_SERVER_SEEDCHOOSER_SELECT_SEED
        byte[] done = {(byte) 243, 12, 1, 0, (byte) 0xb0, 4, 0, 0, 0, 0, 0, 0};
        var stream = new ByteArrayOutputStream();
        for (byte[] event : new byte[][]{begin, chunk, pick, begin, chunk, done}) stream.write(event);
        byte[] bytes = stream.toByteArray();
        // Split every event, including the 12-byte END with authoritative timers.
        for (byte value : bytes) send(host, hostWire, value);
        assertArrayEquals(bytes, drain(spectator, wire));
        assertArrayEquals(pick, drain(guest, guestWire));
    }
    @Test public void joiningMidSnapshotKeepsExistingViewerAndGuestStreamsIntact() throws Exception {
        setup(3214);
        var firstWire = new TestChannel(); var first = join(firstWire); drain(host, hostWire);
        byte[] begin = {(byte) 241, 12, 3, 0, 16, 0, 0, 0, 1, 2, 3, 4};
        send(host, hostWire, begin);
        assertArrayEquals(begin, drain(first, firstWire));
        var secondWire = new TestChannel(); var second = join(secondWire);
        assertArrayEquals(new byte[]{(byte) 240, 2}, drain(host, hostWire));
        // The new viewer sees the first transfer's tail, then a complete fresh
        // transfer. Neither reservation may reset the existing viewer's stream.
        byte[] chunk = new byte[244]; chunk[0] = (byte) 242; chunk[1] = (byte) 244; chunk[2] = 16;
        byte[] done = {(byte) 243, 12, 1, 0, 20, 0, 0, 0, 0, 0, 0, 0};
        byte[] page = {31, 4, 107, 2}; // host chooser-button acknowledgement, absolute plant page 1
        var expected = new ByteArrayOutputStream();
        for (byte[] event : new byte[][]{chunk, done, begin, page, chunk, done}) {
            send(host, hostWire, event); expected.write(event);
        }
        assertArrayEquals(expected.toByteArray(), drain(first, firstWire));
        assertArrayEquals(expected.toByteArray(), drain(second, secondWire));
        assertArrayEquals(page, drain(guest, guestWire));
    }

    @Test public void largeSnapshotBurstPreservesEveryByteAfterRelayHandoff() throws Exception {
        setup(3214);
        var wire = new TestChannel(); var spectator = join(wire); drain(host, hostWire);
        byte[] chunk = new byte[244]; chunk[0] = (byte) 242; chunk[1] = (byte) 244; chunk[2] = (byte) 240;
        var expected = new ByteArrayOutputStream();
        for (int i = 0; i < 80; ++i) {
            chunk[4] = (byte) i;
            send(host, hostWire, chunk); expected.write(chunk);
        }
        assertTrue(expected.size() > 8192);
        assertArrayEquals(expected.toByteArray(), drain(spectator, wire));
        assertArrayEquals(new byte[0], drain(guest, guestWire));
    }
    @Test public void replayHistoryIsHostOnlyAndNeverReachesTheOpponent() throws Exception {
        setup(3214);
        var wire = new TestChannel(); var spectator = join(wire); drain(host, hostWire);
        var expected = new ByteArrayOutputStream();
        for (int type = 245; type <= 248; ++type) {
            byte[] history = {(byte) type, 8, 1, 0, 42, 0, 0, 0};
            for (byte value : history) send(host, hostWire, value);
            expected.write(history);
            send(guest, guestWire, history); // a player cannot inject a forged history
        }
        byte[] live = {27, 5, 1, 0, 0};
        send(host, hostWire, live); expected.write(live);
        assertArrayEquals(expected.toByteArray(), drain(spectator, wire));
        assertArrayEquals(live, drain(guest, guestWire));
        assertArrayEquals(new byte[0], drain(host, hostWire));
    }

    @Test public void historyBackpressureOnOneViewerDoesNotBlockLivePlayersOrOtherViewers() throws Exception {
        setup(3214);
        var slowWire = new TestChannel(); var slow = join(slowWire); drain(host, hostWire);
        var fastWire = new TestChannel(); var fast = join(fastWire); drain(host, hostWire);
        byte[] chunk = new byte[244]; chunk[0] = (byte) 246; chunk[1] = (byte) 244;
        chunk[2] = (byte) 232; chunk[4] = 42;
        var history = new ByteArrayOutputStream();
        for (int i = 0; i < 100; ++i) {
            chunk[12] = (byte) i;
            send(host, hostWire, chunk); history.write(chunk);
        }
        slowWire.writeQuota = 10;
        slow.onWritable(); // leave this viewer's history partially written
        byte[] live = {27, 5, 1, 0, 0};
        send(host, hostWire, live); history.write(live);
        assertArrayEquals(live, drain(guest, guestWire));
        assertArrayEquals(history.toByteArray(), drain(fast, fastWire));
        slowWire.writeQuota = Integer.MAX_VALUE;
        assertArrayEquals(history.toByteArray(), drain(slow, slowWire));
    }
    @Test public void lateViewerReceivesNothingUntilManualSpectateRequest() throws Exception {
        setup(3214);
        var wire = new TestChannel(); var spectator = handler(wire);
        set(spectator, "currentRoom", room); set(spectator, "isSpectator", true);
        room.addSpectator(spectator);
        byte[] live = {27, 5, 1, 0, 0};
        send(host, hostWire, live);
        assertArrayEquals(live, drain(guest, guestWire));
        assertArrayEquals(new byte[0], drain(spectator, wire));
        assertArrayEquals(new byte[0], drain(host, hostWire));

        var request = ClientHandler.class.getDeclaredMethod("handleReserveSpectate", boolean.class);
        request.setAccessible(true); request.invoke(spectator, true);
        byte[] control = drain(spectator, wire);
        assertTrue(control.length > 0);
        assertEquals(0x90, control[0] & 0xff);
        assertArrayEquals(new byte[]{(byte) 240, 2}, drain(host, hostWire));
        send(host, hostWire, live);
        assertArrayEquals(live, drain(spectator, wire));
        assertArrayEquals(live, drain(guest, guestWire));
    }

    @Test public void duplicateManualRequestDoesNotRestartAnActiveViewer() throws Exception {
        setup(3214);
        var wire = new TestChannel(); var spectator = join(wire);
        assertArrayEquals(new byte[]{(byte) 240, 2}, drain(host, hostWire));
        var request = ClientHandler.class.getDeclaredMethod("handleReserveSpectate", boolean.class);
        request.setAccessible(true); request.invoke(spectator, true);
        assertArrayEquals(new byte[0], drain(host, hostWire));
        assertArrayEquals(new byte[0], drain(spectator, wire));
        byte[] live = {27, 5, 1, 0, 0};
        send(host, hostWire, live);
        assertArrayEquals(live, drain(spectator, wire));
        assertArrayEquals(live, drain(guest, guestWire));
    }

    private static final class TestChannel extends SocketChannel {
        private final Socket socket = new Socket();
        int writeQuota = Integer.MAX_VALUE; final ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteBuffer inbound = ByteBuffer.allocate(0);
        TestChannel() { super(SelectorProvider.provider()); }
        @Override public int write(ByteBuffer src) {
            int count = Math.min(writeQuota, src.remaining());
            for (int i = 0; i < count; i++) output.write(src.get());
            writeQuota -= count;
            return count;
        }
        @Override public int read(ByteBuffer dst) {
            int count = Math.min(dst.remaining(), inbound.remaining());
            for (int i = 0; i < count; i++) dst.put(inbound.get());
            return count;
        }
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
