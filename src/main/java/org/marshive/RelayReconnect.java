package org.marshive;

import java.nio.ByteBuffer;
import java.security.SecureRandom;

/** Selector-thread-owned state. A disconnected guest's seat is not a new-game slot. */
final class RelayReconnect {
    static final int VERSION = 3215;
    static final int CONTROL = 249, BEGIN = 250, CHUNK = 251, END = 252;
    static final int STATE = 1, FROZEN = 2, SYNC = 3, LOADED = 4, COMMIT = 5,
            RESUME = 6, CANCEL = 7, PING = 8, PONG = 9;
    static final int NONE = 0, FREEZING = 1, WAITING = 2, ATTACHING = 3,
            SYNCING = 4, RESUMING = 5, CLOSING = 6;
    static final long TIMEOUT_MS = 60_000, HEARTBEAT_TIMEOUT_MS = 20_000;
    static final int MAX_SNAPSHOT = 64 * 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    int state, epoch, transfer, code, received, expected, hash, expectedHash;
    long codeWindow;
    int codeAttempts;
    long deadline, attemptDeadline;
    boolean snapshotEnded;
    ClientHandler candidate;
    String guestName = "";

    void begin() {
        epoch = epoch == Integer.MAX_VALUE ? 1 : epoch + 1;
        code = RANDOM.nextInt(100);
        state = FREEZING;
        deadline = System.currentTimeMillis() + TIMEOUT_MS;
    }

    boolean allowCodeAttempt(long now) {
        if (now - codeWindow >= 10_000) { codeWindow = now; codeAttempts = 0; }
        return ++codeAttempts <= 5; // Room-scoped: opening another socket cannot bypass this limit.
    }

    void newAttempt(ClientHandler client) {
        transfer = transfer == Integer.MAX_VALUE ? 1 : transfer + 1;
        candidate = client;
        state = ATTACHING;
        expected = received = 0;
        hash = 0x811c9dc5;
        snapshotEnded = false;
        deadline = System.currentTimeMillis() + TIMEOUT_MS;
        attemptDeadline = System.currentTimeMillis() + 120_000;
    }

    static byte[] control(int op, int state, int epoch, int transfer, int value) {
        return ByteBuffer.allocate(16).put((byte) CONTROL).put((byte) 16)
                .put((byte) op).put((byte) state).putInt(epoch).putInt(transfer).putInt(value).array();
    }

    static int intAt(byte[] data, int offset) {
        return ByteBuffer.wrap(data, offset, 4).getInt();
    }

    static int u16At(byte[] data, int offset) {
        return Short.toUnsignedInt(ByteBuffer.wrap(data, offset, 2).getShort());
    }
}
