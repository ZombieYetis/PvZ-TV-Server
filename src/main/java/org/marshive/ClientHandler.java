package org.marshive;

import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class ClientHandler {
    private static final int NETPLAY_VERSION = 3154;

    private static final byte ERR_BAD_REQ = 1;
    private static final byte ERR_NOT_FOUND = 2;
    private static final byte ERR_FULL = 3;
    private static final byte ERR_NOT_HOST = 4;
    private static final byte ERR_NOT_READY = 5;
    private static final byte ERR_NOT_ALLOWED = 6;

    static final long IDLE_TIMEOUT_MS = 10 * 60 * 1000L;
    static final long P2P_FALLBACK_MS = 5000L;

    private static final ConcurrentHashMap<Integer, ClientHandler> PROBE_WAITERS = new ConcurrentHashMap<>();

    final SocketChannel ch;
    final RoomManager rm;
    final int shardId;
    final int probePort;
    final int probePort2;

    SelectionKey key;
    private Room currentRoom;
    private boolean isHost = false;
    private boolean isSpectator = false;
    private boolean closed = false;

    private int natPort = -1;
    private String observedNatIp1 = "";
    private int observedNatPort1 = -1;
    private String observedNatIp2 = "";
    private int observedNatPort2 = -1;
    private int probeToken = 0;
    private int protocolVersion = NETPLAY_VERSION;
    private volatile String playerName = "";

    private long lastActiveAtMillis = System.currentTimeMillis();
    private long p2pFallbackDeadlineAtMillis = 0L;

    private boolean relayDataMode = false;
    private ClientHandler relayPeer;

    private final ByteBuffer readBuf = ByteBuffer.allocate(64 * 1024);
    private final Queue<ByteBuffer> writeQ = new ArrayDeque<>();

    ClientHandler(SocketChannel ch, RoomManager rm, int shardId, int probePort, int probePort2) throws IOException {
        this.ch = ch;
        this.rm = rm;
        this.shardId = shardId;
        this.probePort = probePort;
        this.probePort2 = probePort2;
        ch.configureBlocking(false);
        Socket s = ch.socket();
        s.setTcpNoDelay(true);
        touchActivity();
    }

    static void acceptP2PProbe(int token, Socket probeSocket, int probeIndex) {
        ClientHandler handler = PROBE_WAITERS.get(token);
        if (handler == null) return;
        handler.updateObservedEndpoint(probeSocket, probeIndex);
    }

    void onReadable() throws IOException {
        if (closed) return;
        touchActivity();

        if (relayDataMode) {
            relayReadable();
            return;
        }

        // In relay mode, spectators reuse the same socket for downlink game stream.
        // Their uplink bytes are not protocol commands and must never enter command parser.
        Room room = currentRoom;
        if (isSpectator && room != null && room.isGaming() && room.isRelayDataOpen()) {
            ByteBuffer tmp = ByteBuffer.allocate(4096);
            int n = ch.read(tmp);
            if (n < 0) throw new EOFException("Spectator disconnected");
            if (n > 0) touchActivity();
            return;
        }

        int n = ch.read(readBuf);
        if (n < 0) throw new EOFException("Client disconnected");
        if (n == 0) return;

        touchActivity();
        readBuf.flip();
        try {
            while (tryConsumeOneCommand()) {
                // parse as much as possible
            }
        } finally {
            readBuf.compact();
        }
    }

    void onWritable() throws IOException {
        if (closed) return;
        while (!writeQ.isEmpty()) {
            ByteBuffer b = writeQ.peek();
            ch.write(b);
            if (b.hasRemaining()) {
                refreshInterestOps();
                return;
            }
            writeQ.poll();
        }
        refreshInterestOps();
    }

    void onTick(long nowMillis) throws IOException {
        if (closed) return;
        if (nowMillis - lastActiveAtMillis >= IDLE_TIMEOUT_MS) {
            Room room = currentRoom;
            if (room == null) {
                throw new IOException("Idle timeout (not in room)");
            } else if (!room.isGaming()) {
                kickFromRoomByIdle();
                touchActivity();
            } else {
                throw new IOException("Idle timeout (gaming)");
            }
        }
        if (p2pFallbackDeadlineAtMillis > 0 && nowMillis >= p2pFallbackDeadlineAtMillis) {
            Room room = currentRoom;
            p2pFallbackDeadlineAtMillis = 0;
            if (room != null) {
                onP2PNegotiationFailed(room);
            }
        }
    }

    void close() {
        if (closed) return;
        closed = true;
        try {
            ch.close();
        } catch (IOException ignored) {
        }
        unregisterProbeToken();
        cleanupOnDisconnect();
        if (key != null) key.cancel();
    }

    boolean isClosed() {
        return closed;
    }

    private void relayReadable() throws IOException {
        if (relayPeer == null || relayPeer.closed) throw new IOException("Relay peer closed");
        ByteBuffer tmp = ByteBuffer.allocate(4096);
        int n = ch.read(tmp);
        if (n < 0) throw new EOFException("Relay closed");
        if (n == 0) return;
        tmp.flip();
        byte[] raw = new byte[tmp.remaining()];
        tmp.get(raw);
        Room room = currentRoom;
        if (room != null) {
            AnalyticsCollector.onRelayPayload(room.getId(), raw);
        }
        relayPeer.enqueueRaw(raw);
        mirrorRelayToSpectators(raw);
    }

    private void mirrorRelayToSpectators(byte[] raw) {
        Room room = currentRoom;
        if (room == null || !room.isGaming() || !room.isRelayDataOpen()) return;
        if (isSpectator) return;
        if (room.getHost() == null || room.getGuest() == null) return;

        for (ClientHandler s : room.snapshotSpectators()) {
            if (s == null || s == this || s.closed) continue;
            try {
                s.enqueueRaw(raw);
            } catch (IOException ignored) {
            }
        }
    }

    private boolean tryConsumeOneCommand() throws IOException {
        int startPos = readBuf.position();
        if (readBuf.remaining() < 1) return false;
        byte tb = readBuf.get();
        MsgType type = MsgType.fromByte(tb);
        if (type == null) {
            sendError(ERR_BAD_REQ);
            return true;
        }
        if (!parseAndHandle(type, startPos)) {
            readBuf.position(startPos);
            return false;
        }
        touchActivity();
        return true;
    }

    private boolean parseAndHandle(MsgType type, int cmdStartPos) throws IOException {
        switch (type) {
            case CREATE: {
                if (readBuf.remaining() < 1) return false;
                int nameLen = u8(readBuf.get());
                if (readBuf.remaining() < nameLen + 4) return false;
                byte[] nb = new byte[nameLen];
                readBuf.get(nb);
                String roomName = new String(nb, StandardCharsets.UTF_8);
                int clientVersion = readBuf.getInt();
                handleCreate(roomName, clientVersion);
                return true;
            }
            case QUERY: {
                handleQuery();
                return true;
            }
            case JOIN: {
                if (readBuf.remaining() < 9) return false;
                int roomId = readBuf.getInt();
                int clientVersion = readBuf.getInt();
                int nameLen = u8(readBuf.get());
                if (readBuf.remaining() < nameLen) return false;
                byte[] nb = new byte[nameLen];
                readBuf.get(nb);
                String guestName = new String(nb, StandardCharsets.UTF_8);
                handleJoin(roomId, clientVersion, guestName);
                return true;
            }
            case START:
                handleStart();
                return true;
            case EXIT_ROOM:
                handleExitRoom();
                return true;
            case LEAVE_ROOM:
                handleLeaveRoom();
                return true;
            case NAT_PORT: {
                if (readBuf.remaining() < 2) return false;
                int p = ((u8(readBuf.get()) << 8) | u8(readBuf.get()));
                handleNatPort(p);
                return true;
            }
            case P2P_OK:
                handleP2POk();
                return true;
            case P2P_FAIL:
                handleP2PFail();
                return true;
            case RELAY_READY: {
                if (readBuf.remaining() < 4) return false;
                int relayEpoch = readBuf.getInt();
                handleRelayReady(relayEpoch);
                return true;
            }
            case KICK_GUEST:
                handleKickGuest();
                return true;
            case ASK_START:
                handleAskStart();
                return true;
            case SET_SPECTATE: {
                if (readBuf.remaining() < 1) return false;
                int allow = u8(readBuf.get());
                handleSetSpectate(allow != 0);
                return true;
            }
            case JOIN_SPECTATE: {
                if (readBuf.remaining() < 9) return false;
                int roomId = readBuf.getInt();
                int clientVersion = readBuf.getInt();
                int nameLen = u8(readBuf.get());
                if (readBuf.remaining() < nameLen) return false;
                byte[] nb = new byte[nameLen];
                readBuf.get(nb);
                String watcherName = new String(nb, StandardCharsets.UTF_8);
                handleJoinSpectate(roomId, clientVersion, watcherName);
                return true;
            }
            case SWITCH_ROLE: {
                if (readBuf.remaining() < 1) return false;
                int toSpectator = u8(readBuf.get());
                handleSwitchRole(toSpectator != 0);
                return true;
            }
            case LEAVE:
                throw new IOException("User left (disconnect)");
            default:
                readBuf.position(cmdStartPos);
                return false;
        }
    }

    private boolean isCurrentRoomHost() {
        Room room = currentRoom;
        return room != null && room.getHost() == this;
    }

    private boolean isCurrentRoomGuest() {
        Room room = currentRoom;
        return room != null && room.getGuest() == this;
    }

    private boolean isCurrentRoomPlayer() {
        return isCurrentRoomHost() || isCurrentRoomGuest();
    }

    private void handleCreate(String roomName, int clientVersion) throws IOException {
        if (currentRoom != null) {
            sendError(ERR_BAD_REQ);
            return;
        }
        protocolVersion = clientVersion;
        playerName = roomName;
        currentRoom = rm.createRoom(roomName, this, clientVersion);
        isHost = true;

        byte[] payload = new byte[8];
        writeIntTo(payload, 0, currentRoom.getId());
        writeIntTo(payload, 4, currentRoom.getProtocolVersion());
        sendFrame(RespType.ROOM_CREATED.code, payload);
    }

    private void handleQuery() throws IOException {
        sendFrame(RespType.ROOM_LIST.code, buildRoomListPayload());
    }

    private void handleJoin(int roomId, int clientVersion, String guestName) throws IOException {
        if (currentRoom != null) {
            sendError(ERR_BAD_REQ);
            return;
        }
        Room r = rm.getRoom(roomId);
        if (r == null) {
            sendJoinResult(false, 0, 0, "", (byte) 0);
            return;
        }
        if (r.isGaming()) {
            sendError(ERR_NOT_READY);
            return;
        }
        if (r.isFull()) {
            sendError(ERR_FULL);
            return;
        }
        if (r.getProtocolVersion() != clientVersion) {
            sendJoinResult(false, roomId, r.getProtocolVersion(), "", (byte) 0);
            return;
        }

        boolean ok = rm.joinRoom(roomId, this);
        if (!ok) {
            sendJoinResult(false, 0, r.getProtocolVersion(), "", (byte) 0);
            return;
        }

        currentRoom = rm.getRoom(roomId);
        isHost = false;
        isSpectator = false;
        protocolVersion = clientVersion;
        playerName = guestName;

        sendJoinResult(true, roomId, r.getProtocolVersion(), r.getName(), (byte) 0);

        notifyRoomGuestJoined(currentRoom, guestName);
        logGuestFlow("JOINED", currentRoom, this);
        broadcastSpectateState(currentRoom);
        sendRoomProbeState(currentRoom);
        broadcastSpectatorList(currentRoom);
    }

    private void handleStart() throws IOException {
        if (!isHost || currentRoom == null) {
            sendError(ERR_NOT_HOST);
            return;
        }
        if (!currentRoom.isFull()) {
            sendError(ERR_NOT_READY);
            return;
        }

        synchronized (currentRoom) {
            currentRoom.setGaming(true);
            currentRoom.setP2pNegotiating(false);
            currentRoom.setP2pEstablished(false);
            currentRoom.setRelayMode(false);
            currentRoom.setRelayEpoch(0);
            currentRoom.setHostRelayReady(false);
            currentRoom.setGuestRelayReady(false);
            currentRoom.setRelayDataOpen(false);
            currentRoom.setP2pAttempt(0);
            currentRoom.setP2pReprobePending(false);
        }
        AnalyticsCollector.onMatchStart(currentRoom.getId());
        boolean p2pStarted;
        if (currentRoom.isForceRelay()) {
            p2pStarted = false;
        } else {
            p2pStarted = tryBeginP2PNegotiation(currentRoom);
        }
        if (!p2pStarted) beginRelayFallback(currentRoom);
    }

    private void handleSetSpectate(boolean allow) throws IOException {
        if (!isHost || currentRoom == null) {
            sendError(ERR_NOT_HOST);
            return;
        }
        if (currentRoom.isGaming()) {
            sendError(ERR_NOT_READY);
            return;
        }
        currentRoom.setSpectateAllowed(allow);
        currentRoom.setForceRelay(allow);
        if (!allow) {
            for (ClientHandler s : currentRoom.snapshotSpectators()) {
                if (s == null) continue;
                try {
                    s.sendFrame(RespType.ROOM_EXITED.code, null);
                } catch (IOException ignored) {
                }
                s.currentRoom = null;
                s.isHost = false;
                s.isSpectator = false;
                currentRoom.removeSpectator(s);
            }
        }
        broadcastSpectateState(currentRoom);
        broadcastSpectatorList(currentRoom);
    }

    private void handleJoinSpectate(int roomId, int clientVersion, String watcherName) throws IOException {
        if (currentRoom != null) {
            sendError(ERR_BAD_REQ);
            return;
        }
        Room r = rm.getRoom(roomId);
        if (r == null) {
            sendJoinResult(false, 0, 0, "", (byte) 1);
            return;
        }
        if (r.getProtocolVersion() != clientVersion) {
            sendJoinResult(false, roomId, r.getProtocolVersion(), "", (byte) 1);
            return;
        }
        if (r.isGaming()) {
            sendError(ERR_NOT_READY);
            return;
        }
        if (!r.isSpectateAllowed()) {
            sendError(ERR_NOT_ALLOWED);
            return;
        }
        if (r.spectatorCount() >= Room.MAX_SPECTATORS) {
            sendError(ERR_FULL);
            return;
        }

        currentRoom = r;
        isHost = false;
        isSpectator = true;
        protocolVersion = clientVersion;
        playerName = watcherName;
        r.addSpectator(this);
        sendJoinResult(true, roomId, r.getProtocolVersion(), r.getName(), (byte) 1);
        ClientHandler guest = r.getGuest();
        if (guest != null && guest.playerName != null && !guest.playerName.isEmpty()) {
            byte[] guestNameBytes = guest.playerName.getBytes(StandardCharsets.UTF_8);
            int guestNameLen = Math.min(255, guestNameBytes.length);
            byte[] payload = new byte[4 + 1 + guestNameLen];
            writeIntTo(payload, 0, roomId);
            payload[4] = (byte) guestNameLen;
            if (guestNameLen > 0) System.arraycopy(guestNameBytes, 0, payload, 5, guestNameLen);
            sendFrame(RespType.GUEST_JOINED.code, payload);
        }
        broadcastSpectatorList(r);
    }

    private void handleExitRoom() throws IOException {
        if (!isHost || currentRoom == null) {
            sendError(ERR_NOT_HOST);
            return;
        }
        if (currentRoom.isGaming()) {
            sendError(ERR_NOT_READY);
            return;
        }

        int roomId = currentRoom.getId();
        ClientHandler guest = currentRoom.getGuest();
        if (guest != null) {
            logGuestFlow("HOST_EXITED", currentRoom, guest);
            guest.sendFrame(RespType.ROOM_EXITED.code, null);
            guest.currentRoom = null;
            guest.isHost = false;
        }
        for (ClientHandler s : currentRoom.snapshotSpectators()) {
            if (s == null) continue;
            try {
                s.sendFrame(RespType.ROOM_EXITED.code, null);
            } catch (IOException ignored) {
            }
            s.currentRoom = null;
            s.isHost = false;
            s.isSpectator = false;
        }
        rm.removeRoom(roomId);
        AnalyticsCollector.onRoomClosed(roomId);
        currentRoom = null;
        isHost = false;
        sendFrame(RespType.ROOM_EXITED.code, null);
    }

    private void handleLeaveRoom() throws IOException {
        if (isHost || currentRoom == null) {
            sendError(ERR_BAD_REQ);
            return;
        }
        if (isSpectator) {
            currentRoom.removeSpectator(this);
            broadcastSpectatorList(currentRoom);
            currentRoom = null;
            isHost = false;
            isSpectator = false;
            sendFrame(RespType.ROOM_EXITED.code, null);
            return;
        }
        if (currentRoom.isGaming()) {
            sendError(ERR_NOT_READY);
            return;
        }
        int roomId = currentRoom.getId();
        Room r = rm.getRoom(roomId);
        if (r == null) {
            sendError(ERR_NOT_FOUND);
            return;
        }
        boolean ok = rm.leaveAsGuest(roomId, this);
        if (!ok) {
            sendError(ERR_BAD_REQ);
            return;
        }
        notifyHostGuestLeft(r);
        logGuestFlow("LEFT", r, this);
        sendRoomProbeState(r);
        currentRoom = null;
        isHost = false;
        sendFrame(RespType.ROOM_EXITED.code, null);
    }

    private void handleSwitchRole(boolean toSpectator) throws IOException {
        Room r = currentRoom;
        if (r == null || r.isGaming() || isHost) {
            sendError(ERR_BAD_REQ);
            return;
        }
        if (toSpectator) {
            if (!isCurrentRoomGuest()) {
                sendError(ERR_BAD_REQ);
                return;
            }
            if (!r.isSpectateAllowed()) {
                sendError(ERR_NOT_ALLOWED);
                return;
            }
            if (r.spectatorCount() >= Room.MAX_SPECTATORS) {
                sendError(ERR_FULL);
                return;
            }
            r.setGuest(null);
            isSpectator = true;
            r.addSpectator(this);
            sendJoinResult(true, r.getId(), r.getProtocolVersion(), r.getName(), (byte) 1);
            notifyHostGuestLeft(r);
            sendRoomProbeState(r);
            broadcastSpectateState(r);
            broadcastSpectatorList(r);
            return;
        }

        if (!isSpectator) {
            sendError(ERR_BAD_REQ);
            return;
        }
        if (r.isFull()) {
            sendError(ERR_FULL);
            return;
        }
        r.removeSpectator(this);
        r.setGuest(this);
        isSpectator = false;
        sendJoinResult(true, r.getId(), r.getProtocolVersion(), r.getName(), (byte) 0);

        notifyRoomGuestJoined(r, playerName);
        sendRoomProbeState(r);
        broadcastSpectateState(r);
        broadcastSpectatorList(r);
    }

    private void handleKickGuest() throws IOException {
        if (!isHost || currentRoom == null) {
            sendError(ERR_NOT_HOST);
            return;
        }
        if (currentRoom.isGaming()) {
            sendError(ERR_NOT_READY);
            return;
        }
        ClientHandler guest = currentRoom.getGuest();
        if (guest == null) {
            sendError(ERR_NOT_FOUND);
            return;
        }
        guest.sendFrame(RespType.ROOM_EXITED.code, null);
        guest.currentRoom = null;
        guest.isHost = false;
        currentRoom.setGuest(null);
        logGuestFlow("KICKED", currentRoom, guest);
        notifyHostGuestLeft(currentRoom);
        sendRoomProbeState(currentRoom);
    }

    private void handleAskStart() throws IOException {
        if (currentRoom == null || isHost || isSpectator || !isCurrentRoomGuest()) {
            sendError(ERR_BAD_REQ);
            return;
        }
        if (currentRoom.isGaming()) {
            sendError(ERR_NOT_READY);
            return;
        }
        ClientHandler host = currentRoom.getHost();
        if (host == null) {
            sendError(ERR_NOT_FOUND);
            return;
        }
        host.sendFrame(RespType.CLIENT_WANT_START.code, null);
    }

    private void handleNatPort(int p) throws IOException {
        if (isSpectator) {
            sendError(ERR_NOT_ALLOWED);
            return;
        }
        if (p <= 0) {
            sendError(ERR_BAD_REQ);
            return;
        }
        natPort = p;
        observedNatIp1 = "";
        observedNatPort1 = -1;
        observedNatIp2 = "";
        observedNatPort2 = -1;
        unregisterProbeToken();
        probeToken = registerProbeToken(this);

        byte[] payload = new byte[10];
        writeU16To(payload, 0, p);
        writeU16To(payload, 2, probePort);
        writeU16To(payload, 4, probePort2);
        writeIntTo(payload, 6, probeToken);
        sendFrame(RespType.P2P_READY.code, payload);
        sendRoomProbeState(currentRoom);
    }

    private void handleP2POk() throws IOException {
        if (currentRoom == null || !currentRoom.isGaming()) {
            sendError(ERR_BAD_REQ);
            return;
        }
        if (!isCurrentRoomPlayer()) {
            sendError(ERR_NOT_ALLOWED);
            return;
        }
        finishP2P(currentRoom);
    }

    private void handleP2PFail() throws IOException {
        if (currentRoom == null || !currentRoom.isGaming()) {
            sendError(ERR_BAD_REQ);
            return;
        }
        if (!isCurrentRoomPlayer()) {
            sendError(ERR_NOT_ALLOWED);
            return;
        }
        onP2PNegotiationFailed(currentRoom);
    }

    private void handleRelayReady(int relayEpoch) throws IOException {
        if (currentRoom == null || !currentRoom.isGaming() || !currentRoom.isRelayMode()) {
            sendError(ERR_BAD_REQ);
            return;
        }
        if (!isCurrentRoomPlayer()) {
            sendError(ERR_NOT_ALLOWED);
            return;
        }
        markRelayReady(currentRoom, relayEpoch);
    }

    private boolean tryBeginP2PNegotiation(Room room) {
        ClientHandler host = room.getHost();
        ClientHandler guest = room.getGuest();
        if (host == null || guest == null) return false;
        if (host.natPort <= 0 || guest.natPort <= 0) return false;
        if (!host.hasStableProbe() || !guest.hasStableProbe()) return false;

        byte[] toHost = buildP2pInfoPayload(room.getId(), guest);
        byte[] toGuest = buildP2pInfoPayload(room.getId(), host);
        synchronized (room) {
            room.setP2pNegotiating(true);
            room.setP2pEstablished(false);
            room.setRelayMode(false);
            room.setRelayEpoch(0);
            room.setHostRelayReady(false);
            room.setGuestRelayReady(false);
            room.setRelayDataOpen(false);
            room.setP2pReprobePending(false);
            room.setP2pAttempt(room.getP2pAttempt() + 1);
        }
        try {
            host.sendFrame(RespType.P2P_INFO.code, toHost);
            guest.sendFrame(RespType.P2P_INFO.code, toGuest);
        } catch (IOException e) {
            synchronized (room) {
                room.setP2pNegotiating(false);
            }
            return false;
        }
        long deadline = System.currentTimeMillis() + P2P_FALLBACK_MS;
        host.p2pFallbackDeadlineAtMillis = deadline;
        guest.p2pFallbackDeadlineAtMillis = deadline;
        return true;
    }

    private void onP2PNegotiationFailed(Room room) {
        ClientHandler host = room.getHost();
        ClientHandler guest = room.getGuest();
        if (host == null || guest == null) {
            beginRelayFallback(room);
            return;
        }

        boolean retryByReprobe = false;
        synchronized (room) {
            if (room.isP2pEstablished() || room.isRelayMode()) return;
            if (room.getP2pAttempt() < 2 && !room.isP2pReprobePending()) {
                room.setP2pNegotiating(false);
                room.setP2pReprobePending(true);
                retryByReprobe = true;
            }
        }

        if (!retryByReprobe) {
            beginRelayFallback(room);
            return;
        }

        host.p2pFallbackDeadlineAtMillis = System.currentTimeMillis() + P2P_FALLBACK_MS;
        guest.p2pFallbackDeadlineAtMillis = host.p2pFallbackDeadlineAtMillis;
        host.reissueProbeTicket();
        guest.reissueProbeTicket();
    }

    private void finishP2P(Room room) {
        ClientHandler host = room.getHost();
        ClientHandler guest = room.getGuest();
        if (host == null || guest == null) {
            beginRelayFallback(room);
            return;
        }
        synchronized (room) {
            if (room.isRelayMode() || room.isP2pEstablished()) return;
            room.setP2pNegotiating(false);
            room.setP2pEstablished(true);
            room.setRelayMode(false);
            room.setRelayEpoch(0);
            room.setHostRelayReady(false);
            room.setGuestRelayReady(false);
            room.setRelayDataOpen(false);
        }
        host.p2pFallbackDeadlineAtMillis = 0;
        guest.p2pFallbackDeadlineAtMillis = 0;
        try {
            host.sendFrame(RespType.P2P_DONE.code, null);
            guest.sendFrame(RespType.P2P_DONE.code, null);
        } catch (IOException ignored) {
            beginRelayFallback(room);
        }
        System.out.println("[GAME_MODE][shard=" + shardId + "][room=" + room.getId() + "] P2P (p2p established)");
    }

    private void beginRelayFallback(Room room) {
        ClientHandler host = room.getHost();
        ClientHandler guest = room.getGuest();
        if (host == null || guest == null) return;
        int relayEpoch;
        synchronized (room) {
            if (room.isRelayMode() || room.isP2pEstablished()) return;
            room.setP2pNegotiating(false);
            room.setP2pReprobePending(false);
            room.setRelayMode(true);
            room.setP2pEstablished(false);
            room.setHostRelayReady(false);
            room.setGuestRelayReady(false);
            room.setRelayDataOpen(false);
            relayEpoch = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
            room.setRelayEpoch(relayEpoch);
        }
        host.p2pFallbackDeadlineAtMillis = 0;
        guest.p2pFallbackDeadlineAtMillis = 0;

        byte[] relayPayload = new byte[4];
        writeIntTo(relayPayload, 0, relayEpoch);
        try {
            host.sendFrame(RespType.RELAY_BEGIN.code, relayPayload);
            guest.sendFrame(RespType.RELAY_BEGIN.code, relayPayload);
            for (ClientHandler s : room.snapshotSpectators()) {
                if (s == null || s.closed) continue;
                s.sendFrame(RespType.RELAY_BEGIN.code, relayPayload);
            }
        } catch (IOException ignored) {
        }
        System.out.println("[GAME_MODE][shard=" + shardId + "][room=" + room.getId() + "] RELAY (relay fallback)");
    }

    private void markRelayReady(Room room, int relayEpoch) throws IOException {
        boolean shouldOpenRelay = false;
        int openEpoch = relayEpoch;
        ClientHandler host = null;
        ClientHandler guest = null;

        synchronized (room) {
            if (!room.isRelayMode()) return;
            if (relayEpoch != room.getRelayEpoch()) return;
            if (isHost) room.setHostRelayReady(true);
            else room.setGuestRelayReady(true);
            if (room.isHostRelayReady() && room.isGuestRelayReady() && !room.isRelayDataOpen()) {
                room.setRelayDataOpen(true);
                shouldOpenRelay = true;
                openEpoch = room.getRelayEpoch();
                host = room.getHost();
                guest = room.getGuest();
            }
        }
        if (!shouldOpenRelay || host == null || guest == null) return;

        byte[] payload = new byte[4];
        writeIntTo(payload, 0, openEpoch);
        host.sendFrame(RespType.RELAY_GO.code, payload);
        guest.sendFrame(RespType.RELAY_GO.code, payload);
        for (ClientHandler s : room.snapshotSpectators()) {
            if (s == null || s.closed) continue;
            try {
                s.sendFrame(RespType.RELAY_GO.code, payload);
            } catch (IOException ignored) {
            }
        }
        host.enableRelayWith(guest);
        guest.enableRelayWith(host);
        System.out.println("[GAME_MODE][shard=" + shardId + "][room=" + room.getId() + "] RELAY_GO epoch=" + openEpoch);
    }

    private void enableRelayWith(ClientHandler peer) {
        this.relayPeer = peer;
        this.relayDataMode = true;
    }

    private void sendJoinResult(boolean ok, int roomId, int roomVersion, String hostName, byte role) throws IOException {
        byte[] hostNameBytes = hostName == null ? new byte[0] : hostName.getBytes(StandardCharsets.UTF_8);
        int hostNameLen = Math.min(255, hostNameBytes.length);
        byte[] payload = new byte[1 + 4 + 4 + 1 + hostNameLen + 1];
        payload[0] = (byte) (ok ? 1 : 0);
        writeIntTo(payload, 1, roomId);
        writeIntTo(payload, 5, roomVersion);
        payload[9] = (byte) hostNameLen;
        if (hostNameLen > 0) System.arraycopy(hostNameBytes, 0, payload, 10, hostNameLen);
        payload[10 + hostNameLen] = role;
        sendFrame(RespType.JOIN_RESULT.code, payload);
    }

    private void sendSpectateState(Room room) throws IOException {
        if (room == null) return;
        byte[] payload = new byte[6];
        writeIntTo(payload, 0, room.getId());
        payload[4] = (byte) (room.isSpectateAllowed() ? 1 : 0);
        payload[5] = (byte) (room.isForceRelay() ? 1 : 0);
        sendFrame(RespType.SPECTATE_STATE.code, payload);
    }

    private void broadcastSpectateState(Room room) {
        if (room == null) return;
        byte[] payload = new byte[6];
        writeIntTo(payload, 0, room.getId());
        payload[4] = (byte) (room.isSpectateAllowed() ? 1 : 0);
        payload[5] = (byte) (room.isForceRelay() ? 1 : 0);

        ClientHandler host = room.getHost();
        ClientHandler guest = room.getGuest();
        if (host != null && !host.closed) {
            try {
                host.sendFrame(RespType.SPECTATE_STATE.code, payload);
            } catch (IOException ignored) {
            }
        }
        if (guest != null && !guest.closed) {
            try {
                guest.sendFrame(RespType.SPECTATE_STATE.code, payload);
            } catch (IOException ignored) {
            }
        }
    }

    private void broadcastSpectatorList(Room room) {
        if (room == null) return;
        // During active gameplay, sockets are carrying raw battle stream.
        // Injecting framed control packets here will corrupt the in-game stream.
        if (room.isGaming()) return;
        int roomId = room.getId();
        ArrayList<ClientHandler> specs = new ArrayList<>();
        for (ClientHandler s : room.snapshotSpectators()) {
            if (s == null || s.closed) continue;
            specs.add(s);
        }

        int count = Math.min(255, specs.size());
        ArrayList<byte[]> names = new ArrayList<>(count);
        int payloadLen = 4 + 1;
        for (int i = 0; i < count; i++) {
            String n = specs.get(i).playerName == null ? "" : specs.get(i).playerName;
            byte[] nb = n.getBytes(StandardCharsets.UTF_8);
            int nl = Math.min(255, nb.length);
            byte[] clipped = new byte[nl];
            if (nl > 0) System.arraycopy(nb, 0, clipped, 0, nl);
            names.add(clipped);
            payloadLen += 1 + nl;
        }

        byte[] payload = new byte[payloadLen];
        writeIntTo(payload, 0, roomId);
        payload[4] = (byte) count;
        int off = 5;
        for (byte[] nb : names) {
            payload[off++] = (byte) nb.length;
            if (nb.length > 0) {
                System.arraycopy(nb, 0, payload, off, nb.length);
                off += nb.length;
            }
        }

        ClientHandler host = room.getHost();
        ClientHandler guest = room.getGuest();
        if (host != null && !host.closed) {
            try {
                host.sendFrame(RespType.SPECTATOR_LIST.code, payload);
            } catch (IOException ignored) {
            }
        }
        if (guest != null && !guest.closed) {
            try {
                guest.sendFrame(RespType.SPECTATOR_LIST.code, payload);
            } catch (IOException ignored) {
            }
        }
        for (ClientHandler s : specs) {
            if (s == null || s.closed) continue;
            try {
                s.sendFrame(RespType.SPECTATOR_LIST.code, payload);
            } catch (IOException ignored) {
            }
        }
    }

    private void sendError(byte errCode) throws IOException {
        sendFrame(RespType.ERROR.code, new byte[]{errCode});
    }

    private void sendFrame(byte respType, byte[] payload) throws IOException {
        if (closed) throw new IOException("channel closed");
        int len = payload == null ? 0 : payload.length;
        if (len > 65535) throw new IOException("payload too large: " + len);
        ByteBuffer b = ByteBuffer.allocate(1 + 2 + len);
        b.put(respType);
        b.put((byte) ((len >>> 8) & 0xFF));
        b.put((byte) (len & 0xFF));
        if (len > 0) b.put(payload);
        b.flip();
        writeQ.add(b);
        refreshInterestOps();
        touchActivity();
    }

    private void enqueueRaw(byte[] raw) throws IOException {
        if (closed) throw new IOException("channel closed");
        if (raw.length == 0) return;
        ByteBuffer b = ByteBuffer.wrap(raw);
        writeQ.add(b);
        refreshInterestOps();
    }

    private void refreshInterestOps() {
        if (key == null || !key.isValid()) return;
        int ops = SelectionKey.OP_READ;
        if (!writeQ.isEmpty()) ops |= SelectionKey.OP_WRITE;
        key.interestOps(ops);
    }

    boolean isProbeReady() {
        return natPort > 0 && hasStableProbe();
    }

    private static int registerProbeToken(ClientHandler handler) {
        int token;
        do {
            token = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
        } while (PROBE_WAITERS.putIfAbsent(token, handler) != null);
        return token;
    }

    private void unregisterProbeToken() {
        int token = probeToken;
        if (token != 0) {
            PROBE_WAITERS.remove(token, this);
            probeToken = 0;
        }
    }

    private synchronized void updateObservedEndpoint(Socket probeSocket, int probeIndex) {
        String ip = probeSocket.getInetAddress().getHostAddress();
        int port = probeSocket.getPort();
        if (probeIndex == 2) {
            observedNatIp2 = ip;
            observedNatPort2 = port;
        } else {
            observedNatIp1 = ip;
            observedNatPort1 = port;
        }
        sendRoomProbeState(currentRoom);
        maybeContinueReprobeP2P();
    }

    private void maybeContinueReprobeP2P() {
        Room room = currentRoom;
        if (room == null) return;
        ClientHandler host = room.getHost();
        ClientHandler guest = room.getGuest();
        if (host == null || guest == null) return;
        boolean shouldRetry = false;
        synchronized (room) {
            if (!room.isGaming() || room.isRelayMode() || room.isP2pEstablished()) return;
            if (!room.isP2pReprobePending()) return;
            if (host.hasStableProbe() && guest.hasStableProbe()) {
                room.setP2pReprobePending(false);
                shouldRetry = true;
            }
        }
        if (shouldRetry) {
            tryBeginP2PNegotiation(room);
        }
    }

    private synchronized boolean hasStableProbe() {
        return observedNatPort1 > 0 && observedNatPort2 > 0
                && !observedNatIp1.isEmpty() && !observedNatIp2.isEmpty()
                && observedNatPort1 == observedNatPort2
                && Objects.equals(observedNatIp1, observedNatIp2);
    }

    private synchronized String getStableNatIp() {
        return hasStableProbe() ? observedNatIp1 : "";
    }

    private synchronized int getStableNatPort() {
        return hasStableProbe() ? observedNatPort1 : -1;
    }

    private byte[] buildP2pInfoPayload(int roomId, ClientHandler peer) {
        String ip = peer.getStableNatIp();
        if (ip == null || ip.isEmpty()) ip = "0.0.0.0";
        byte[] ipBytes = ip.getBytes(StandardCharsets.UTF_8);
        int ipLen = Math.min(255, ipBytes.length);
        byte[] p = new byte[4 + 1 + ipLen + 2 + 1];
        int off = 0;
        writeIntTo(p, off, roomId);
        off += 4;
        p[off++] = (byte) ipLen;
        System.arraycopy(ipBytes, 0, p, off, ipLen);
        off += ipLen;
        writeU16To(p, off, peer.getStableNatPort());
        off += 2;
        int timeoutSec = Math.max(1, (int) (P2P_FALLBACK_MS / 1000));
        p[off] = (byte) timeoutSec;
        return p;
    }

    private void sendRoomProbeState(Room room) {
        if (room == null) return;
        ClientHandler host;
        ClientHandler guest;
        int roomId;
        boolean hostReady;
        boolean guestReady;
        synchronized (room) {
            host = room.getHost();
            guest = room.getGuest();
            roomId = room.getId();
            hostReady = host != null && host.isProbeReady();
            guestReady = guest != null && guest.isProbeReady();
        }

        byte[] payload = new byte[6];
        writeIntTo(payload, 0, roomId);
        payload[4] = (byte) (hostReady ? 1 : 0);
        payload[5] = (byte) (guestReady ? 1 : 0);

        if (host != null) {
            try {
                host.sendFrame(RespType.ROOM_PROBE_STATE.code, payload);
            } catch (IOException ignored) {
            }
        }
        if (guest != null && guest != host) {
            try {
                guest.sendFrame(RespType.ROOM_PROBE_STATE.code, payload);
            } catch (IOException ignored) {
            }
        }
    }

    private byte[] buildRoomListPayload() {
        ArrayList<Room> list = new ArrayList<>();
        for (Room r : rm.allRooms()) {
            if (r.isGaming()) continue;
            boolean hasGuestSlot = !r.isFull();
            boolean hasSpectateSlot = r.isSpectateAllowed() && r.spectatorCount() < Room.MAX_SPECTATORS;
            if (!hasGuestSlot && !hasSpectateSlot) continue;
            if (list.size() >= 255) break;
            list.add(r);
        }
        int total = 1;
        for (Room r : list) {
            byte[] nb = r.getName().getBytes(StandardCharsets.UTF_8);
            total += 4 + 1 + 4 + 1 + Math.min(255, nb.length);
        }
        byte[] p = new byte[total];
        p[0] = (byte) list.size();
        int off = 1;
        for (Room r : list) {
            writeIntTo(p, off, r.getId());
            off += 4;
            int flags = 0;
            if (r.isFull()) flags |= 1;
            if (r.isGaming()) flags |= 2;
            ClientHandler host = r.getHost();
            ClientHandler guest = r.getGuest();
            if (host != null && host.isProbeReady()) flags |= 4;
            if (guest != null && guest.isProbeReady()) flags |= 8;
            if (r.isSpectateAllowed()) flags |= 16;
            if (r.isForceRelay()) flags |= 32;
            p[off++] = (byte) flags;
            writeIntTo(p, off, r.getProtocolVersion());
            off += 4;
            byte[] nb = r.getName().getBytes(StandardCharsets.UTF_8);
            int nlen = Math.min(255, nb.length);
            p[off++] = (byte) nlen;
            System.arraycopy(nb, 0, p, off, nlen);
            off += nlen;
        }
        return p;
    }

    private void notifyGuestRoomExited(Room room) {
        ClientHandler guest = room.getGuest();
        if (guest == null) return;
        if (room.isGaming() && room.isRelayMode()) return;
        try {
            guest.sendFrame(RespType.ROOM_EXITED.code, null);
        } catch (IOException ignored) {
        }
        guest.currentRoom = null;
        guest.isHost = false;
    }

    private void notifyHostGuestLeft(Room room) {
        if (room == null) return;
        byte[] p = new byte[4];
        writeIntTo(p, 0, room.getId());
        ClientHandler host = room.getHost();
        if (host != null) {
            try {
                host.sendFrame(RespType.GUEST_LEFT.code, p);
            } catch (IOException ignored) {
            }
        }
        for (ClientHandler s : room.snapshotSpectators()) {
            if (s == null || s.closed) continue;
            try {
                s.sendFrame(RespType.GUEST_LEFT.code, p);
            } catch (IOException ignored) {
            }
        }
    }

    private void notifyRoomGuestJoined(Room room, String guestName) {
        if (room == null) return;
        byte[] guestNameBytes = guestName == null ? new byte[0] : guestName.getBytes(StandardCharsets.UTF_8);
        int guestNameLen = Math.min(255, guestNameBytes.length);
        byte[] p = new byte[4 + 1 + guestNameLen];
        writeIntTo(p, 0, room.getId());
        p[4] = (byte) guestNameLen;
        if (guestNameLen > 0) {
            System.arraycopy(guestNameBytes, 0, p, 5, guestNameLen);
        }
        ClientHandler host = room.getHost();
        try {
            if (host != null) {
                host.sendFrame(RespType.GUEST_JOINED.code, p);
            }
        } catch (IOException ignored) {
        }
        for (ClientHandler s : room.snapshotSpectators()) {
            if (s == null || s.closed) continue;
            try {
                s.sendFrame(RespType.GUEST_JOINED.code, p);
            } catch (IOException ignored) {
            }
        }
    }

    private void cleanupOnDisconnect() {
        Room room = currentRoom;
        currentRoom = null;
        relayDataMode = false;
        relayPeer = null;
        if (room == null) {
            isHost = false;
            isSpectator = false;
            return;
        }
        if (isSpectator) {
            room.removeSpectator(this);
            broadcastSpectatorList(room);
            isSpectator = false;
            isHost = false;
            return;
        }
        if (isHost) {
            notifyGuestRoomExited(room);
            for (ClientHandler s : room.snapshotSpectators()) {
                if (s == null) continue;
                if (room.isGaming()) {
                    // In-game spectator channels are carrying raw battle stream.
                    // Do not inject control frames; force EOF by closing socket.
                    s.close();
                } else {
                    try {
                        s.sendFrame(RespType.ROOM_EXITED.code, null);
                    } catch (IOException ignored) {
                    }
                    s.currentRoom = null;
                    s.isHost = false;
                    s.isSpectator = false;
                }
            }
            rm.removeRoom(room.getId());
            AnalyticsCollector.onRoomClosed(room.getId());
        } else if (!room.isGaming() && room.getGuest() == this) {
            room.setGuest(null);
            logGuestFlow("LEFT_DISCONNECT", room, this);
            notifyHostGuestLeft(room);
            sendRoomProbeState(room);
        }
        isHost = false;
        isSpectator = false;
    }

    private void kickFromRoomByIdle() {
        Room room = currentRoom;
        if (room == null) return;
        if (room.isGaming()) return;

        if (isHost) {
            notifyGuestRoomExited(room);
            for (ClientHandler s : room.snapshotSpectators()) {
                if (s == null) continue;
                try {
                    s.sendFrame(RespType.ROOM_EXITED.code, null);
                } catch (IOException ignored) {
                }
                s.currentRoom = null;
                s.isHost = false;
                s.isSpectator = false;
            }
            rm.removeRoom(room.getId());
            AnalyticsCollector.onRoomClosed(room.getId());
        } else if (!room.isGaming() && room.getGuest() == this) {
            room.setGuest(null);
            logGuestFlow("LEFT_IDLE", room, this);
            notifyHostGuestLeft(room);
            sendRoomProbeState(room);
        } else if (room.isGaming()) {
            // In-game idle is handled by transport activity; keep current behavior here.
            return;
        }

        try {
            sendFrame(RespType.ROOM_EXITED.code, null);
        } catch (IOException ignored) {
        }

        currentRoom = null;
        isHost = false;
        isSpectator = false;
        p2pFallbackDeadlineAtMillis = 0L;
        relayDataMode = false;
        relayPeer = null;
    }

    void onRoomRemovedByServer() {
        Room room = currentRoom;
        if (room == null) return;
        try {
            sendFrame(RespType.ROOM_EXITED.code, null);
        } catch (IOException ignored) {
        }
        currentRoom = null;
        isHost = false;
        isSpectator = false;
        p2pFallbackDeadlineAtMillis = 0L;
        relayDataMode = false;
        relayPeer = null;
    }

    private void touchActivity() {
        lastActiveAtMillis = System.currentTimeMillis();
    }

    private synchronized void reissueProbeTicket() {
        if (closed || natPort <= 0) return;
        observedNatIp1 = "";
        observedNatPort1 = -1;
        observedNatIp2 = "";
        observedNatPort2 = -1;
        unregisterProbeToken();
        probeToken = registerProbeToken(this);

        byte[] payload = new byte[10];
        writeU16To(payload, 0, natPort);
        writeU16To(payload, 2, probePort);
        writeU16To(payload, 4, probePort2);
        writeIntTo(payload, 6, probeToken);
        try {
            sendFrame(RespType.P2P_READY.code, payload);
        } catch (IOException ignored) {
        }
    }

    private void logGuestFlow(String action, Room room, ClientHandler guest) {
        if (room == null || guest == null) return;
        ClientHandler host = room.getHost();
        String hostName = (host == null || host.playerName == null || host.playerName.isEmpty()) ? "unknown" : host.playerName;
        String guestName = (guest.playerName == null || guest.playerName.isEmpty()) ? "unknown" : guest.playerName;
        boolean hostP2P = host != null && host.isProbeReady();
        boolean guestP2P = guest.isProbeReady();
        System.out.println(
                "[ROOM_GUEST_" + action + "][shard=" + shardId + "][room=" + room.getId() + "] host='" + hostName +
                        "' guest='" + guestName + "' hostP2P=" + hostP2P + " guestP2P=" + guestP2P
        );
    }

    private static int u8(byte b) {
        return b & 0xFF;
    }

    private static void writeIntTo(byte[] b, int off, int v) {
        b[off] = (byte) ((v >>> 24) & 0xFF);
        b[off + 1] = (byte) ((v >>> 16) & 0xFF);
        b[off + 2] = (byte) ((v >>> 8) & 0xFF);
        b[off + 3] = (byte) (v & 0xFF);
    }

    private static void writeU16To(byte[] b, int off, int v) {
        b[off] = (byte) ((v >>> 8) & 0xFF);
        b[off + 1] = (byte) (v & 0xFF);
    }
}
