package org.marshive;

import java.util.ArrayList;
import java.util.List;

public class Room {
    private final int id;
    private final String name;
    private final ClientHandler host;
    private final int protocolVersion;
    private final long createdAtMillis;
    private volatile ClientHandler guest;
    private volatile boolean gaming = false;
    private volatile boolean p2pNegotiating = false;
    private volatile boolean p2pEstablished = false;
    private volatile boolean relayMode = false;
    private volatile int relayEpoch = 0;
    private volatile boolean hostRelayReady = false;
    private volatile boolean guestRelayReady = false;
    private volatile boolean relayDataOpen = false;
    private volatile int p2pAttempt = 0;
    private volatile boolean p2pReprobePending = false;
    private volatile boolean spectateAllowed = false;
    private volatile boolean forceRelay = false;
    private final List<ClientHandler> spectators = new ArrayList<>();

    public Room(int id, String name, ClientHandler host, int protocolVersion) {
        this.id = id;
        this.name = name;
        this.host = host;
        this.protocolVersion = protocolVersion;
        this.createdAtMillis = System.currentTimeMillis();
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public ClientHandler getHost() {
        return host;
    }

    public int getProtocolVersion() {
        return protocolVersion;
    }

    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    public ClientHandler getGuest() {
        return guest;
    }

    public void setGuest(ClientHandler g) {
        this.guest = g;
    }

    public boolean isGaming() {
        return gaming;
    }

    public void setGaming(boolean v) {
        this.gaming = v;
    }

    public boolean isFull() {
        return host != null && guest != null;
    }

    public boolean isP2pNegotiating() {
        return p2pNegotiating;
    }

    public void setP2pNegotiating(boolean v) {
        this.p2pNegotiating = v;
    }

    public boolean isP2pEstablished() {
        return p2pEstablished;
    }

    public void setP2pEstablished(boolean v) {
        this.p2pEstablished = v;
    }

    public boolean isRelayMode() {
        return relayMode;
    }

    public void setRelayMode(boolean v) {
        this.relayMode = v;
    }

    public int getRelayEpoch() {
        return relayEpoch;
    }

    public void setRelayEpoch(int relayEpoch) {
        this.relayEpoch = relayEpoch;
    }

    public boolean isHostRelayReady() {
        return hostRelayReady;
    }

    public void setHostRelayReady(boolean hostRelayReady) {
        this.hostRelayReady = hostRelayReady;
    }

    public boolean isGuestRelayReady() {
        return guestRelayReady;
    }

    public void setGuestRelayReady(boolean guestRelayReady) {
        this.guestRelayReady = guestRelayReady;
    }

    public boolean isRelayDataOpen() {
        return relayDataOpen;
    }

    public void setRelayDataOpen(boolean relayDataOpen) {
        this.relayDataOpen = relayDataOpen;
    }

    public int getP2pAttempt() {
        return p2pAttempt;
    }

    public void setP2pAttempt(int p2pAttempt) {
        this.p2pAttempt = p2pAttempt;
    }

    public boolean isP2pReprobePending() {
        return p2pReprobePending;
    }

    public void setP2pReprobePending(boolean p2pReprobePending) {
        this.p2pReprobePending = p2pReprobePending;
    }

    public boolean isSpectateAllowed() {
        return spectateAllowed;
    }

    public void setSpectateAllowed(boolean spectateAllowed) {
        this.spectateAllowed = spectateAllowed;
    }

    public boolean isForceRelay() {
        return forceRelay;
    }

    public void setForceRelay(boolean forceRelay) {
        this.forceRelay = forceRelay;
    }

    public synchronized void addSpectator(ClientHandler c) {
        if (c == null || spectators.contains(c)) return;
        spectators.add(c);
    }

    public synchronized int spectatorCount() {
        return spectators.size();
    }

    public synchronized void removeSpectator(ClientHandler c) {
        spectators.remove(c);
    }

    public synchronized List<ClientHandler> snapshotSpectators() {
        return new ArrayList<>(spectators);
    }
}
