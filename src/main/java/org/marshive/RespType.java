package org.marshive;

public enum RespType {
    ROOM_CREATED((byte) 0x81),
    ROOM_LIST((byte) 0x82),
    JOIN_RESULT((byte) 0x83),
    GUEST_JOINED((byte) 0x84),
    RELAY_BEGIN((byte) 0x85),

    ROOM_EXITED((byte) 0x86),
    GUEST_LEFT((byte) 0x87),

    P2P_INFO((byte) 0x88),
    P2P_READY((byte) 0x89),
    P2P_DONE((byte) 0x8A),
    RELAY_GO((byte) 0x8B),
    ROOM_PROBE_STATE((byte) 0x8C),
    CLIENT_WANT_START((byte) 0x8D),
    SPECTATE_STATE((byte) 0x8E),
    SPECTATOR_LIST((byte) 0x8F),
    RESERVE_SPECTATE_ACK((byte) 0x90),

    ERROR((byte) 0xFF);

    public final byte code;

    RespType(byte code) {
        this.code = code;
    }
}
