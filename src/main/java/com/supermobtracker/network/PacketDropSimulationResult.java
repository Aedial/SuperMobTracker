package com.supermobtracker.network;

import java.util.ArrayList;
import java.util.List;

import io.netty.buffer.ByteBuf;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import com.supermobtracker.drops.DropSimulator;


/**
 * Packet sent from server to client with drop simulation results.
 * Contains the entity ID, simulation count, and list of drops with their statistics.
 */
public class PacketDropSimulationResult implements IMessage {

    private String entityId;
    private int simulationCount;
    private List<DropData> drops;
    private String errorMessage;
    private boolean hasError;

    public PacketDropSimulationResult() {
        this.drops = new ArrayList<>();
    }

    /**
     * Create a result packet from a successful simulation.
     */
    public PacketDropSimulationResult(DropSimulator.DropSimulationResult result) {
        this.entityId = result.entityId.toString();
        this.simulationCount = result.simulationCount;
        this.drops = new ArrayList<>();
        this.hasError = false;
        this.errorMessage = null;

        for (DropSimulator.DropEntry entry : result.drops) {
            drops.add(new DropData(entry.stack, entry.totalCount));
        }
    }

    /**
     * Create an error result packet.
     */
    public PacketDropSimulationResult(ResourceLocation entityId, String errorMessage) {
        this.entityId = entityId.toString();
        this.simulationCount = 0;
        this.drops = new ArrayList<>();
        this.hasError = true;
        this.errorMessage = errorMessage;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.entityId = ByteBufUtils.readUTF8String(buf);
        this.simulationCount = buf.readInt();
        this.hasError = buf.readBoolean();

        if (hasError) {
            this.errorMessage = ByteBufUtils.readUTF8String(buf);
            this.drops = new ArrayList<>();
        } else {
            int count = buf.readInt();
            this.drops = new ArrayList<>(count);

            for (int i = 0; i < count; i++) {
                ItemStack stack = ByteBufUtils.readItemStack(buf);
                int totalCount = buf.readInt();
                drops.add(new DropData(stack, totalCount));
            }
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, entityId);
        buf.writeInt(simulationCount);
        buf.writeBoolean(hasError);

        if (hasError) {
            ByteBufUtils.writeUTF8String(buf, errorMessage != null ? errorMessage : "Unknown error");
        } else {
            buf.writeInt(drops.size());

            for (DropData drop : drops) {
                ByteBufUtils.writeItemStack(buf, drop.stack);
                buf.writeInt(drop.totalCount);
            }
        }
    }

    /**
     * Get the entity ID this result is for.
     */
    public ResourceLocation getEntityId() {
        return new ResourceLocation(entityId);
    }

    /**
     * Check if this result contains an error.
     */
    public boolean hasError() {
        return hasError;
    }

    /**
     * Get the error message, or null if no error.
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Convert this packet to a DropSimulationResult.
     */
    public DropSimulator.DropSimulationResult toResult() {
        if (hasError) return null;

        List<DropSimulator.DropEntry> entries = new ArrayList<>();
        for (DropData drop : drops) {
            entries.add(new DropSimulator.DropEntry(drop.stack, drop.totalCount, simulationCount));
        }

        return new DropSimulator.DropSimulationResult(new ResourceLocation(entityId), entries, simulationCount);
    }

    /**
     * Simple data class for drop information during serialization.
     */
    private static class DropData {
        final ItemStack stack;
        final int totalCount;

        DropData(ItemStack stack, int totalCount) {
            this.stack = stack;
            this.totalCount = totalCount;
        }
    }

    public static class Handler implements IMessageHandler<PacketDropSimulationResult, IMessage> {

        @Override
        public IMessage onMessage(PacketDropSimulationResult message, MessageContext ctx) {
            // Handle on client main thread
            net.minecraft.client.Minecraft.getMinecraft().addScheduledTask(() -> {
                DropSimulator.handleServerResult(message);
            });

            return null;
        }
    }
}
