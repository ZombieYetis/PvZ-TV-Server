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
    private static final UUID GUEST_ID = UUID.fromString("ba1a2c90-c077-4a84-8809-6e23c5834265");
    private static final UUID OTHER_ID = UUID.fromString("d0e1d739-e7c4-425a-a2d4-cdcd6b8bf6e5");
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
        room = rooms.createRoom("[VS]reconnect", host, PLAYER_ID_VERSION);
        room.setGuest(guest); room.setGaming(true); room.setRelayMode(true); room.setRelayDataOpen(true); room.setRelayEpoch(1);
        for (ClientHandler h : List.of(host, guest, viewer)) set(h, "currentRoom", room);
        set(host, "isHost", true);
        set(host, "relayDataMode", true); set(guest, "relayDataMode", true);
        set(host, "relayPeer", guest); set(guest, "relayPeer", host);
        set(guest, "playerName", "original guest");
        set(guest, "playerId", GUEST_ID);
        set(viewer, "playerId", OTHER_ID);
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
        assertEquals(GUEST_ID, room.reconnect.guestId);
        assertEquals(0, intAt(state, 12));
        assertEquals(0, intAt(drain(viewer), 12));
    }
    private byte[] joinRequest(UUID identity) {
        return ByteBuffer.allocate(25).put((byte) 0x12).putInt(room.getId()).putInt(PLAYER_ID_VERSION)
                .putLong(identity.getMostSignificantBits()).putLong(identity.getLeastSignificantBits()).array();
    }

    @Test public void fragmentedIdentityRegistrationAndRoleSwitchKeepTheCorrectPlayer() throws Exception {
        for (String mode : List.of("[VS]", "[COOP]")) {
            var owner = client(); var player = client(); var spectator = client();
            byte[] name = (mode + "host").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] create = ByteBuffer.allocate(6 + name.length + 16).put((byte) 1).put((byte) name.length).put(name)
                    .putInt(PLAYER_ID_VERSION).putLong(1).putLong(2).array();
            for (int i = 0; i < create.length - 1; ++i) send(owner, create[i]);
            assertEquals(1, rooms.roomCount()); // Incomplete identity must not create a room yet.
            send(owner, create[create.length - 1]);
            Room added = rooms.getRoom(intAt(drain(owner), 3));
            added.setSpectateAllowed(true);
            for (ClientHandler client : List.of(player, spectator)) {
                boolean watching = client == spectator;
                UUID id = watching ? OTHER_ID : GUEST_ID;
                byte[] join = ByteBuffer.allocate(27).put((byte) (watching ? 0x0f : 3)).putInt(added.getId())
                        .putInt(PLAYER_ID_VERSION).put((byte) 1).put((byte) 'G')
                        .putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
                for (int i = 0; i < join.length - 1; ++i) send(client, join[i]);
                assertEquals(0, drain(client).length);
                send(client, join[join.length - 1]);
                assertEquals(0x83, drain(client)[0] & 255);
                // A rejected second JOIN must not replace an already bound identity.
                join[join.length - 1] ^= 1;
                send(client, join); assertEquals(0xff, drain(client)[0] & 255);
            }
            send(player, (byte) 7); drain(player);
            send(spectator, (byte) 0x10, (byte) 0); drain(spectator);
            assertSame(spectator, added.getGuest());
            added.setGaming(true); added.setRelayMode(true);
            spectator.close();
            assertEquals(FREEZING, added.reconnect.state);
            assertEquals(OTHER_ID, added.reconnect.guestId);
            owner.close();
        }
    }

    private ClientHandler attach() throws Exception {
        ClientHandler next = client();
        for (byte b : joinRequest(GUEST_ID)) send(next, b); // fragmented lobby command
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
        waitForReconnect();
        ClientHandler next = attach();
        send(next, (byte) 27, (byte) 2); assertArrayEquals(new byte[0], drain(host));
        send(next, ctl(LOADED, 1)); assertEquals(SYNCING, room.reconnect.state); // too early
        assertArrayEquals(snapshot(), drain(next));
        assertArrayEquals(new byte[0], drain(viewer));
        send(next, ctl(LOADED, 1)); assertEquals(LOADED, drain(host)[2]);
        assertEquals(RESUMING, room.reconnect.state);
        send(host, ctl(COMMIT, 0));
        for (ClientHandler h : List.of(host, next, viewer)) assertEquals(RESUME, drain(h)[2]);
        assertEquals(NONE, room.reconnect.state); assertSame(next, room.getGuest()); assertEquals(GUEST_ID, room.reconnect.guestId);
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
    @Test public void wrongIdentityAndCompetingJoinCannotReplaceCandidate() throws Exception {
        waitForReconnect(); var bad = client();
        set(bad, "playerName", "original guest"); // Matching names do not grant access.
        send(bad, joinRequest(OTHER_ID)); assertEquals(3, drain(bad)[3]);
        assertNull(room.reconnect.candidate); assertEquals(WAITING, room.reconnect.state);
        var first = attach(); var second = client();
        send(second, joinRequest(GUEST_ID)); assertEquals(2, drain(second)[3]);
        assertSame(first, room.reconnect.candidate);
    }
    @Test public void secondDropRetainsIdentityAndIgnoresOldTransfer() throws Exception {
        waitForReconnect(); var first = attach();
        byte[] oldBegin = begin(new byte[]{1}); int oldTransfer = room.reconnect.transfer;
        first.close(); assertEquals(WAITING, room.reconnect.state); assertEquals(GUEST_ID, room.reconnect.guestId); drain(host);
        var second = attach(); assertNotEquals(oldTransfer, room.reconnect.transfer);
        send(host, oldBegin); assertArrayEquals(new byte[0], drain(second));
        assertArrayEquals(snapshot(), drain(second));
    }
    @Test public void identitySurvivesSuccessfulReconnectAndAnotherDisconnect() throws Exception {
        waitForReconnect(); var next = attach(); finishReconnect(next);
        set(next, "playerName", "renamed");
        next.close(); drain(host); send(host, ctl(FROZEN, 1)); drain(host); drain(viewer);
        assertEquals(GUEST_ID, room.reconnect.guestId);
        assertEquals("renamed", room.reconnect.guestName);
        finishReconnect(attach());
    }

    @Test public void legacyReconnectRequestIsRejectedWithoutConsumingFollowingQuery() throws Exception {
        waitForReconnect(); var old = client();
        send(old, ByteBuffer.allocate(11).put((byte) 0x12).putInt(room.getId()).putInt(VERSION).put((byte) 7).put((byte) 2).array());
        byte[] response = drain(old);
        assertEquals(0x91, response[0] & 255); assertEquals(5, response[3]);
        assertEquals(0x82, response[16] & 255);
        assertEquals(WAITING, room.reconnect.state);
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
        var late = client(); send(late, joinRequest(GUEST_ID)); assertEquals(1, drain(late)[3]);
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
        waitForReconnect();
        var failed = attach(); snapshot(); drain(failed);
        send(failed, ctl(LOADED, 0));
        assertTrue(failed.isClosed()); assertFalse(host.isClosed()); assertFalse(viewer.isClosed());
        assertEquals(WAITING, room.reconnect.state); assertEquals(GUEST_ID, room.reconnect.guestId);
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
        send(stalled, joinRequest(GUEST_ID)); drain(stalled); drain(host);
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
        send(pending, joinRequest(GUEST_ID)); drain(pending); drain(host);
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

    private void fillWriteQueue(ClientHandler target) throws Exception {
        var enqueue = ClientHandler.class.getDeclaredMethod("enqueueRaw", byte[].class);
        enqueue.setAccessible(true);
        enqueue.invoke(target, (Object) new byte[1024 * 1024]);
    }

    private void checkInitialRelayHandoff(boolean hostReadyLast) throws Exception {
        room.setRelayDataOpen(false);
        set(host, "relayDataMode", false); set(guest, "relayDataMode", false);
        set(viewer, "spectatorRelayOpen", false);
        ClientHandler first = hostReadyLast ? guest : host;
        ClientHandler last = hostReadyLast ? host : guest;
        byte[] ready = ByteBuffer.allocate(5).put((byte) 0x0b).putInt(room.getRelayEpoch()).array();
        send(first, Arrays.copyOfRange(ready, 0, 3));
        send(first, Arrays.copyOfRange(ready, 3, ready.length));
        assertFalse(room.isRelayDataOpen());
        for (ClientHandler h : List.of(host, guest, viewer)) assertArrayEquals(new byte[0], drain(h));
        send(last, ByteBuffer.allocate(6).put(ready).put((byte) 2).array());
        byte[] go = ByteBuffer.allocate(7).put((byte) 0x8b).putShort((short) 4).putInt(room.getRelayEpoch()).array();
        for (ClientHandler h : List.of(host, guest, viewer)) assertArrayEquals(go, drain(h));
        assertTrue(room.isRelayDataOpen()); assertEquals(NONE, room.reconnect.state);

        byte[] actions = {27, 5, 1, 0, 0, 27, 5, 2, 0, 0};
        send(host, Arrays.copyOfRange(actions, 0, 3));
        assertArrayEquals(new byte[0], drain(guest)); assertArrayEquals(new byte[0], drain(viewer));
        send(host, Arrays.copyOfRange(actions, 3, actions.length));
        assertArrayEquals(actions, drain(guest)); assertArrayEquals(actions, drain(viewer));
        byte[] reply = {27, 5, 3, 0, 0};
        for (byte value : reply) send(guest, value);
        assertArrayEquals(reply, drain(host)); assertArrayEquals(reply, drain(viewer));
        for (ClientHandler h : List.of(host, guest, viewer)) assertFalse(h.isClosed());
    }

    @Test public void normalGameStartsWhenHostIsReadyLastWithoutMixingLobbyReplies() throws Exception {
        checkInitialRelayHandoff(true);
    }

    @Test public void normalGameStartsWhenGuestIsReadyLastWithoutMixingLobbyReplies() throws Exception {
        checkInitialRelayHandoff(false);
    }

    private void receiveLikeSelector(ClientHandler source, byte[] events) throws Exception {
        try { send(source, events); }
        catch (IOException error) { source.close(); }
    }

    @Test public void fullGuestWriteQueueMustNotCloseHealthyHostOrLoseViewerTail() throws Exception {
        fillWriteQueue(guest);
        byte[] tail = {27, 5, 1, 0, 0, 27, 5, 2, 0, 0};
        receiveLikeSelector(host, tail);
        assertFalse("recipient overflow must not close the sender", host.isClosed());
        assertTrue(guest.isClosed()); assertFalse(viewer.isClosed());
        assertSame(room, rooms.getRoom(room.getId()));
        assertEquals(FREEZING, room.reconnect.state);
        assertArrayEquals(tail, drain(viewer));
        assertEquals(STATE, drain(host)[2]);
        send(host, ctl(FROZEN, 1)); drain(host); drain(viewer);
        finishReconnect(attach());
    }

    @Test public void fullHostWriteQueueClosesRoomWithoutLeakingItsGuest() throws Exception {
        fillWriteQueue(host);
        receiveLikeSelector(guest, new byte[]{27, 5, 1, 0, 0});
        assertRoomClosed(null); assertTrue(guest.isClosed());
    }

    @Test public void fullViewerWriteQueueDoesNotDisconnectEitherPlayer() throws Exception {
        fillWriteQueue(viewer);
        byte[] event = {27, 5, 1, 0, 0};
        receiveLikeSelector(host, event);
        assertFalse(host.isClosed()); assertFalse(guest.isClosed());
        assertTrue(viewer.isClosed()); assertEquals(NONE, room.reconnect.state);
        assertArrayEquals(event, drain(guest));
    }

    @Test public void fullCandidateWriteQueueReturnsToWaitingAndAllowsRetry() throws Exception {
        waitForReconnect(); var next = attach(); fillWriteQueue(next);
        receiveLikeSelector(host, begin(new byte[]{1}));
        assertFalse(host.isClosed()); assertFalse(viewer.isClosed());
        assertTrue(next.isClosed()); assertEquals(WAITING, room.reconnect.state);
        drain(host); finishReconnect(attach());
    }

    @Test public void readyAndCoalescedQueryCannotAppendLobbyReplyAfterReconnectGo() throws Exception {
        waitForReconnect(); var next = client();
        send(next, joinRequest(GUEST_ID)); drain(next); drain(host);
        byte[] readyAndQuery = ByteBuffer.allocate(10).put((byte) 0x13)
                .putInt(room.reconnect.epoch).putInt(room.reconnect.transfer).put((byte) 2).array();
        send(next, readyAndQuery);
        byte[] response = drain(next);
        assertEquals(11, response.length); assertEquals(0x92, response[0] & 255);
        assertEquals(SYNC, drain(host)[2]);
        finishReconnect(next);
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
