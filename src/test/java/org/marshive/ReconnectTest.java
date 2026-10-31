package org.marshive;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.channels.spi.SelectorProvider;
import java.util.*;
import static org.junit.Assert.*;
import static org.marshive.RelayReconnect.*;

public class ReconnectTest {
    private final RoomManager rooms = new RoomManager(0);
    private final List<ClientHandler> handlers = new ArrayList<>();
    private final Map<ClientHandler, TestChannel> wires = new HashMap<>();
    private ClientHandler host, guest, viewer;
    private Room room;
    private static void set(ClientHandler h, String name, Object value) throws Exception {
        var f = ClientHandler.class.getDeclaredField(name); f.setAccessible(true); f.set(h, value);
    }
    private ClientHandler client() throws Exception {
        var wire = new TestChannel();
        var h = new ClientHandler(wire, rooms, 0, 0, 0, "reconnect-test");
        handlers.add(h); wires.put(h, wire); return h;
    }
    @Before public void setup() throws Exception {
        host = client(); guest = client(); viewer = client();
        room = rooms.createRoom("[VS]reconnect", host, VERSION);
        room.setGuest(guest); room.setGaming(true); room.setRelayMode(true); room.setRelayDataOpen(true); room.setRelayEpoch(1);
        for (ClientHandler h : List.of(host, guest, viewer)) set(h, "currentRoom", room);
        set(host, "isHost", true);
        set(host, "relayDataMode", true); set(guest, "relayDataMode", true);
        set(host, "relayPeer", guest); set(guest, "relayPeer", host);
        set(guest, "playerName", "original guest");
        set(viewer, "isSpectator", true); set(viewer, "spectatorStreamRequested", true); set(viewer, "spectatorRelayOpen", true);
        room.addSpectator(viewer);
    }
    @After public void cleanup() {
        handlers.forEach(ClientHandler::close);
        assertEquals(0, ClientHandler.queuedWriteBytes());
    }
    private void send(ClientHandler h, byte... data) throws Exception {
        wires.get(h).inbound = ByteBuffer.wrap(data); h.onReadable();
    }
    private byte[] drain(ClientHandler h) throws Exception {
        h.onWritable(); var out = wires.get(h).output.toByteArray(); wires.get(h).output.reset(); return out;
    }
    private byte[] ctl(int op, int value) { return control(op, room.reconnect.state, room.reconnect.epoch, room.reconnect.transfer, value); }
    private void waitForReconnect() throws Exception {
        guest.close();
        assertEquals(FREEZING, room.reconnect.state);
        assertFalse(host.isClosed()); assertSame(room, rooms.getRoom(room.getId()));
        byte[] lost = drain(host); assertEquals(CONTROL, lost[0] & 255);
        send(host, ctl(FROZEN, 1));
        assertEquals(WAITING, room.reconnect.state);
        byte[] state = drain(host);
        assertEquals(room.reconnect.code, intAt(state, 12));
        assertEquals(-1, intAt(drain(viewer), 12)); // Never disclose the code to viewers.
    }
    private byte[] joinRequest(int code) {
        return ByteBuffer.allocate(10).put((byte) 0x12).putInt(room.getId()).putInt(VERSION).put((byte) code).array();
    }
    private ClientHandler attach() throws Exception {
        ClientHandler next = client();
        for (byte b : joinRequest(room.reconnect.code)) send(next, b); // fragmented lobby command
        assertEquals(ATTACHING, room.reconnect.state);
        byte[] result = drain(next); assertEquals(0x91, result[0] & 255); assertEquals(0, result[3]);
        drain(host);
        send(next, ByteBuffer.allocate(9).put((byte) 0x13).putInt(room.reconnect.epoch).putInt(room.reconnect.transfer).array());
        assertEquals(SYNCING, room.reconnect.state);
        assertEquals(0x92, drain(next)[0] & 255);
        assertEquals(SYNC, drain(host)[2]);
        return next;
    }
    private byte[] begin(byte[] data) {
        int hash = 0x811c9dc5; for (byte b : data) hash = (hash ^ (b & 255)) * 16777619;
        return ByteBuffer.allocate(26).put((byte) BEGIN).put((byte) 26).putInt(room.reconnect.epoch).putInt(room.reconnect.transfer)
                .putShort((short) 1).putShort((short) 76).putInt(data.length).putInt(hash).putInt(1234).array();
    }
    private byte[] chunk(byte[] data) {
        return ByteBuffer.allocate(16 + data.length).put((byte) CHUNK).put((byte) (16 + data.length))
                .putInt(room.reconnect.epoch).putInt(room.reconnect.transfer).putInt(0).putShort((short) data.length).put(data).array();
    }
    private byte[] end() { return ByteBuffer.allocate(10).put((byte) END).put((byte) 10).putInt(room.reconnect.epoch).putInt(room.reconnect.transfer).array(); }
    private byte[] snapshot() throws Exception {
        var out = new ByteArrayOutputStream(); byte[] data = {3, 2, 1, 0};
        for (byte[] event : new byte[][]{begin(data), chunk(data), end()}) {
            for (byte b : event) send(host, b); // fragmented raw events
            out.write(event);
        }
        return out.toByteArray();
    }
    @Test public void reconnectKeepsRoomAndRequiresSnapshotAndCommitBeforeGameplay() throws Exception {
        waitForReconnect(); room.reconnect.code = 7;
        ClientHandler next = attach();
        send(next, (byte) 27, (byte) 2); assertArrayEquals(new byte[0], drain(host));
        send(next, ctl(LOADED, 1)); assertEquals(SYNCING, room.reconnect.state); // too early
        assertArrayEquals(snapshot(), drain(next));
        assertArrayEquals(new byte[0], drain(viewer));
        send(next, ctl(LOADED, 1)); assertEquals(LOADED, drain(host)[2]);
        assertEquals(RESUMING, room.reconnect.state);
        send(host, ctl(COMMIT, 0));
        for (ClientHandler h : List.of(host, next, viewer)) assertEquals(RESUME, drain(h)[2]);
        assertEquals(NONE, room.reconnect.state); assertSame(next, room.getGuest()); assertEquals(-1, room.reconnect.code);
        byte[] action = {27, 5, 1, 0, 0}; send(next, action);
        assertArrayEquals(action, drain(host)); assertArrayEquals(action, drain(viewer));
        send(host, action); assertArrayEquals(action, drain(next));
        guest.close(); assertSame(next, room.getGuest());
    }
    @Test public void freezeMarkerFollowsCompleteHostEventsEvenWithPartialWrites() throws Exception {
        send(host, (byte) 27, (byte) 5, (byte) 1);
        guest.close(); drain(host);
        send(host, (byte) 0, (byte) 0);
        send(host, ctl(FROZEN, 1));
        byte[] bytes = drain(viewer);
        assertArrayEquals(new byte[]{27, 5, 1, 0, 0}, Arrays.copyOf(bytes, 5));
        assertEquals(CONTROL, bytes[5] & 255); assertEquals(WAITING, bytes[8]);
    }
    @Test public void wrongCodeAndCompetingJoinCannotReplaceCandidate() throws Exception {
        waitForReconnect(); var bad = client();
        send(bad, joinRequest((room.reconnect.code + 1) % 100)); assertEquals(3, drain(bad)[3]);
        assertNull(room.reconnect.candidate); assertEquals(WAITING, room.reconnect.state);
        var first = attach(); var second = client();
        send(second, joinRequest(room.reconnect.code)); assertEquals(2, drain(second)[3]);
        assertSame(first, room.reconnect.candidate);
    }
    @Test public void secondDropRetainsCodeAndIgnoresOldTransfer() throws Exception {
        waitForReconnect(); int code = room.reconnect.code; var first = attach();
        byte[] oldBegin = begin(new byte[]{1}); int oldTransfer = room.reconnect.transfer;
        first.close(); assertEquals(WAITING, room.reconnect.state); assertEquals(code, room.reconnect.code); drain(host);
        var second = attach(); assertNotEquals(oldTransfer, room.reconnect.transfer);
        send(host, oldBegin); assertArrayEquals(new byte[0], drain(second));
        assertArrayEquals(snapshot(), drain(second));
    }
    @Test public void corruptSnapshotOnlyClosesCandidateAndAllowsRetry() throws Exception {
        waitForReconnect(); var next = attach();
        send(host, begin(new byte[]{1,2})); send(host, chunk(new byte[]{2,1})); send(host, end());
        assertTrue(next.isClosed()); assertFalse(host.isClosed()); assertFalse(viewer.isClosed());
        assertEquals(WAITING, room.reconnect.state); assertSame(room, rooms.getRoom(room.getId()));
    }
    @Test public void cancelDuringTransferClosesRoomAndAllParticipants() throws Exception {
        waitForReconnect(); var next = attach(); send(host, begin(new byte[]{1}));
        send(host, ctl(CANCEL, 0));
        assertTrue(host.isClosed()); assertTrue(next.isClosed()); assertTrue(viewer.isClosed());
        assertNull(rooms.getRoom(room.getId()));
        var late = client(); send(late, joinRequest(0)); assertEquals(1, drain(late)[3]);
    }
    @Test public void candidateTimeoutReturnsToWaitingWithoutClosingHost() throws Exception {
        waitForReconnect(); var next = attach();
        room.reconnect.deadline = 0;
        host.onTick(System.currentTimeMillis());
        assertTrue(next.isClosed()); assertFalse(host.isClosed()); assertEquals(WAITING, room.reconnect.state);
    }
    @Test public void totalAttemptDeadlineAlsoLimitsCommitWait() throws Exception {
        waitForReconnect(); var next = attach();
        snapshot(); drain(next); send(next, ctl(LOADED, 1));
        assertEquals(RESUMING, room.reconnect.state);
        room.reconnect.deadline = System.currentTimeMillis() + TIMEOUT_MS;
        room.reconnect.attemptDeadline = 0;
        host.onTick(System.currentTimeMillis());
        assertTrue(next.isClosed()); assertFalse(host.isClosed()); assertEquals(WAITING, room.reconnect.state);
    }
    @Test public void waitingHeartbeatsAreHandledWithoutAnOpponent() throws Exception {
        waitForReconnect(); send(host, ctl(PING, 0));
        assertEquals(PONG, drain(host)[2]); host.onTick(System.currentTimeMillis());
        assertFalse(host.isClosed()); assertEquals(WAITING, room.reconnect.state);
    }
    @Test public void waitingRoomIsListedEvenWhenSpectatingIsDisabled() throws Exception {
        waitForReconnect(); var lobby = client(); send(lobby, (byte) 2);
        byte[] list = drain(lobby); assertEquals(0x82, list[0] & 255); assertEquals(1, list[3]);
        assertTrue((list[8] & 64) != 0); assertTrue((list[8] & 2) != 0);
    }
    @Test public void guestCannotForgeHostControlOrSnapshot() throws Exception {
        waitForReconnect(); var next = attach();
        send(next, ctl(CANCEL, 0)); send(next, begin(new byte[]{1})); send(next, ctl(COMMIT, 0));
        assertEquals(SYNCING, room.reconnect.state); assertFalse(host.isClosed()); assertEquals(0, drain(host).length);
    }

    private void finishReconnect(ClientHandler next) throws Exception {
        assertArrayEquals(snapshot(), drain(next));
        send(next, ctl(LOADED, 1));
        assertEquals(LOADED, drain(host)[2]);
        send(host, ctl(COMMIT, 0));
        for (ClientHandler h : List.of(host, next, viewer)) assertEquals(RESUME, drain(h)[2]);
        assertEquals(NONE, room.reconnect.state);
    }

    private void assertRoomClosed(ClientHandler pending) {
        assertTrue(host.isClosed());
        assertTrue(viewer.isClosed());
        if (pending != null) assertTrue(pending.isClosed());
        assertNull(rooms.getRoom(room.getId()));
        assertNull(room.reconnect.candidate);
    }

    @Test public void clientLoadFailureCanRetryAndResumeExistingViewer() throws Exception {
        waitForReconnect(); int originalCode = room.reconnect.code;
        var failed = attach(); snapshot(); drain(failed);
        send(failed, ctl(LOADED, 0));
        assertTrue(failed.isClosed()); assertFalse(host.isClosed()); assertFalse(viewer.isClosed());
        assertEquals(WAITING, room.reconnect.state); assertEquals(originalCode, room.reconnect.code);
        drain(host);
        finishReconnect(attach());
    }

    @Test public void hostCaptureFailureCanRetryWithoutDroppingViewer() throws Exception {
        waitForReconnect(); var failed = attach();
        send(host, ctl(LOADED, 0));
        assertTrue(failed.isClosed()); assertEquals(WAITING, room.reconnect.state);
        assertFalse(host.isClosed()); assertFalse(viewer.isClosed());
        drain(host);
        finishReconnect(attach());
    }

    @Test public void timeoutBeforeReadyReleasesCandidateForAnotherAttempt() throws Exception {
        waitForReconnect(); var stalled = client();
        send(stalled, joinRequest(room.reconnect.code)); drain(stalled); drain(host);
        assertEquals(ATTACHING, room.reconnect.state);
        room.reconnect.deadline = 0;
        host.onTick(System.currentTimeMillis());
        assertTrue(stalled.isClosed()); assertEquals(WAITING, room.reconnect.state);
        drain(host);
        finishReconnect(attach());
    }

    @Test public void hostCanCancelWhileWaiting() throws Exception {
        waitForReconnect(); send(host, ctl(CANCEL, 0)); assertRoomClosed(null);
    }

    @Test public void hostCanCancelBeforeCandidateReady() throws Exception {
        waitForReconnect(); var pending = client();
        send(pending, joinRequest(room.reconnect.code)); drain(pending); drain(host);
        send(host, ctl(CANCEL, 0)); assertRoomClosed(pending);
    }

    @Test public void hostCanCancelAfterClientLoadedBeforeCommit() throws Exception {
        waitForReconnect(); var next = attach(); snapshot(); drain(next);
        send(next, ctl(LOADED, 1)); drain(host);
        send(host, ctl(CANCEL, 0)); assertRoomClosed(next);
    }

    @Test public void hostSocketCloseAlsoClosesPendingCandidateAndViewers() throws Exception {
        waitForReconnect(); var next = attach(); host.close(); assertRoomClosed(next);
    }

    @Test public void unsupportedHostSceneClosesRoomInsteadOfWaitingForever() throws Exception {
        guest.close(); drain(host); send(host, ctl(FROZEN, 0)); assertRoomClosed(null);
    }

    @Test public void spectatorSnapshotAndHistoryContinueAfterReconnectBarrier() throws Exception {
        // Both streams may already be in flight when the guest disappears.
        byte[] prefix = {(byte) 241, 4, 1, 2, (byte) 242, 3, 42, (byte) 245, 4, 1, 0};
        send(host, prefix); assertArrayEquals(prefix, drain(viewer));
        waitForReconnect(); var next = attach();
        finishReconnect(next);
        byte[] tail = {(byte) 242, 3, 43, (byte) 243, 2, (byte) 246, 3, 44, (byte) 247, 2, 27, 2};
        send(host, tail);
        assertArrayEquals(tail, drain(viewer));
        assertArrayEquals(new byte[]{27, 2}, drain(next)); // Snapshot/history remain viewer-only.
    }

    @Test public void viewerCanLeaveOrSendDataWithoutCancellingReconnect() throws Exception {
        waitForReconnect(); send(viewer, ctl(CANCEL, 0));
        assertEquals(WAITING, room.reconnect.state); assertFalse(host.isClosed());
        viewer.close();
        assertEquals(WAITING, room.reconnect.state); assertFalse(host.isClosed());
        assertEquals(0, room.spectatorCount());
    }

    @Test public void newSpectatorCannotStartCompetingSnapshotButCanRetryAfterResume() throws Exception {
        waitForReconnect(); var late = client();
        set(late, "currentRoom", room); set(late, "isSpectator", true); room.addSpectator(late);
        send(late, (byte) 0x11, (byte) 1);
        assertEquals(0xff, drain(late)[0] & 255);
        assertEquals(0, drain(host).length);
        finishReconnect(attach());
        send(late, (byte) 0x11, (byte) 1);
        assertArrayEquals(new byte[]{(byte) 240, 2}, drain(host));
        assertTrue(drain(late).length > 0);
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
