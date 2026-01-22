package com.supermobtracker.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import com.supermobtracker.drops.DropSimulator;


/**
 * Packet sent from server to client to update simulation progress.
 * Sent periodically during simulation to update the client's progress display.
 */
public class PacketDropSimulationProgress implements IMessage {

    private String entityId;
    private int progress;
    private int total;

    public PacketDropSimulationProgress() {}

    /**
     * Create a progress update packet.
     */
    public PacketDropSimulationProgress(ResourceLocation entityId, int progress, int total) {
        this.entityId = entityId.toString();
        this.progress = progress;
        this.total = total;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.entityId = ByteBufUtils.readUTF8String(buf);
        this.progress = buf.readInt();
        this.total = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, entityId);
        buf.writeInt(progress);
        buf.writeInt(total);
    }

    public ResourceLocation getEntityId() {
        return new ResourceLocation(entityId);
    }

    public int getProgress() {
        return progress;
    }

    public int getTotal() {
        return total;
    }

    public static class Handler implements IMessageHandler<PacketDropSimulationProgress, IMessage> {

        @Override
        public IMessage onMessage(PacketDropSimulationProgress message, MessageContext ctx) {
            // Handle on client main thread
            net.minecraft.client.Minecraft.getMinecraft().addScheduledTask(() -> {
                DropSimulator.handleServerProgress(message);
            });

            return null;
        }
    }
}
