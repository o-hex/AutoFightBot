package org.antigravity.autofight.entity;

import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

public class DummyPacketListener extends ServerGamePacketListenerImpl {
    public DummyPacketListener(MinecraftServer server, Connection connection, ServerPlayer player, CommonListenerCookie cookie) {
        super(server, connection, player, cookie);
    }

    @Override
    public void disconnect(Component reason) {
        // Prevent disconnecting the NPC
    }

    @Override
    public boolean isAcceptingMessages() {
        return true;
    }
}
