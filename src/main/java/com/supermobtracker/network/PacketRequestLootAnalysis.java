package com.supermobtracker.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import com.supermobtracker.drops.LootAnalysisRunner;


/**
 * Packet sent from client to server requesting a full loot analysis.
 * The server will run the analysis and send results back via PacketLootAnalysisResult.
 */
public class PacketRequestLootAnalysis implements IMessage {

    private int samples;
    private int simulationCount;

    public PacketRequestLootAnalysis() {
    }

    public PacketRequestLootAnalysis(int samples, int simulationCount) {
        this.samples = samples;
        this.simulationCount = simulationCount;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.samples = buf.readInt();
        this.simulationCount = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(samples);
        buf.writeInt(simulationCount);
    }

    public static class Handler implements IMessageHandler<PacketRequestLootAnalysis, IMessage> {

        @Override
        public IMessage onMessage(PacketRequestLootAnalysis message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;

            // Run on server thread
            player.getServerWorld().addScheduledTask(() -> {
                WorldServer world = player.getServerWorld();

                // Start the analysis on a separate thread to avoid blocking the server
                LootAnalysisRunner.runServerAnalysis(player, world, message.samples, message.simulationCount);
            });

            return null;
        }
    }
}
