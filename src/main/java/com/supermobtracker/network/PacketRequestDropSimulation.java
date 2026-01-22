package com.supermobtracker.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import com.supermobtracker.drops.DropSimulator;


/**
 * Packet sent from client to server requesting drop simulation for an entity.
 * The server will run the simulation and send results back via PacketDropSimulationResult.
 */
public class PacketRequestDropSimulation implements IMessage {

    private String entityId;
    private int simulationCount;

    public PacketRequestDropSimulation() {
    }

    public PacketRequestDropSimulation(ResourceLocation entityId, int simulationCount) {
        this.entityId = entityId.toString();
        this.simulationCount = simulationCount;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.entityId = ByteBufUtils.readUTF8String(buf);
        this.simulationCount = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, entityId);
        buf.writeInt(simulationCount);
    }

    public static class Handler implements IMessageHandler<PacketRequestDropSimulation, IMessage> {

        @Override
        public IMessage onMessage(PacketRequestDropSimulation message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;

            // Run simulation on server thread to avoid concurrency issues
            player.getServerWorld().addScheduledTask(() -> {
                ResourceLocation entityId = new ResourceLocation(message.entityId);

                // Run the simulation on the server
                DropSimulator.ServerSimulationTask task = DropSimulator.runServerSimulation(
                    entityId,
                    message.simulationCount,
                    player
                );

                // The task will send the result packet when complete
            });

            return null;
        }
    }
}
