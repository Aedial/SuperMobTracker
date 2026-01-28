package com.supermobtracker.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;


/**
 * Packet sent from server to client with loot analysis progress updates.
 * Displayed in the player's action bar.
 */
public class PacketLootAnalysisProgress implements IMessage {

    private int current;
    private int total;
    private String currentEntity;

    public PacketLootAnalysisProgress() {
    }

    public PacketLootAnalysisProgress(int current, int total, String currentEntity) {
        this.current = current;
        this.total = total;
        this.currentEntity = currentEntity;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.current = buf.readInt();
        this.total = buf.readInt();
        this.currentEntity = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(current);
        buf.writeInt(total);
        ByteBufUtils.writeUTF8String(buf, currentEntity != null ? currentEntity : "");
    }

    public static class Handler implements IMessageHandler<PacketLootAnalysisProgress, IMessage> {

        @Override
        public IMessage onMessage(PacketLootAnalysisProgress message, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                if (Minecraft.getMinecraft().player != null) {
                    TextComponentString text = new TextComponentString(
                        "[SMT] Loot progress: " + message.current + "/" + message.total + " mobs analyzed...");
                    text.getStyle().setColor(TextFormatting.DARK_GREEN);
                    Minecraft.getMinecraft().player.sendStatusMessage(text, true);
                }
            });

            return null;
        }
    }
}
