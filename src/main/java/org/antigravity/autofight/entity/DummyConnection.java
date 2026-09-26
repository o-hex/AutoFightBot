package org.antigravity.autofight.entity;

import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

public class DummyConnection extends Connection {
    public DummyConnection() {
        super(PacketFlow.SERVERBOUND);
        this.channel = new EmbeddedChannel();
        this.address = new InetSocketAddress("127.0.0.1", 25565);
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return this.address;
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public boolean isMemoryConnection() {
        return true;
    }

    @Override
    public void send(Packet<?> packet, PacketSendListener callbacks) {
        // Discard outgoing packets to the fake player to avoid network serialization overhead
    }

    @Override
    public void send(Packet<?> packet) {
        // Discard outgoing packets
    }
}
