package org.marshive;

import java.util.HashMap;
import java.util.Map;

public enum MsgType {
    CREATE((byte) 0x01),
    QUERY((byte) 0x02),
    JOIN((byte) 0x03),
    LEAVE((byte) 0x04),
    START((byte) 0x05),

    EXIT_ROOM((byte) 0x06),
    LEAVE_ROOM((byte) 0x07),

    NAT_PORT((byte) 0x08),
    P2P_OK((byte) 0x09),
    P2P_FAIL((byte) 0x0A),
    RELAY_READY((byte) 0x0B),
    KICK_GUEST((byte) 0x0C),
    ASK_START((byte) 0x0D),
    SET_SPECTATE((byte) 0x0E),
    JOIN_SPECTATE((byte) 0x0F),
    SWITCH_ROLE((byte) 0x10),
    RESERVE_SPECTATE((byte) 0x11);

    public final byte code;

    private static final Map<Byte, MsgType> MAP = new HashMap<>();

    static {
        for (MsgType t : values()) MAP.put(t.code, t);
    }

    MsgType(byte code) {
        this.code = code;
    }

    public static MsgType fromByte(byte code) {
        return MAP.get(code);
    }
}
