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
 * Packet sent from server to client with loot analysis results summary.
 * The full results are written to a file on the server side.
 */
public class PacketLootAnalysisResult implements IMessage {

    private boolean success;
    private String message;
    private int successfulCount;
    private int noDropsCount;
    private int invalidCount;
    private int constructionFailedCount;
    private int crashedCount;

    public PacketLootAnalysisResult() {
    }

    /**
     * Create a success result packet with counts.
     */
    public PacketLootAnalysisResult(int successfulCount, int noDropsCount, int invalidCount,
                                    int constructionFailedCount, int crashedCount, String outputPath) {
        this.success = true;
        this.message = outputPath;
        this.successfulCount = successfulCount;
        this.noDropsCount = noDropsCount;
        this.invalidCount = invalidCount;
        this.constructionFailedCount = constructionFailedCount;
        this.crashedCount = crashedCount;
    }

    /**
     * Create an error result packet.
     */
    public PacketLootAnalysisResult(String errorMessage) {
        this.success = false;
        this.message = errorMessage;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.success = buf.readBoolean();
        this.message = ByteBufUtils.readUTF8String(buf);

        if (success) {
            this.successfulCount = buf.readInt();
            this.noDropsCount = buf.readInt();
            this.invalidCount = buf.readInt();
            this.constructionFailedCount = buf.readInt();
            this.crashedCount = buf.readInt();
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(success);
        ByteBufUtils.writeUTF8String(buf, message != null ? message : "");

        if (success) {
            buf.writeInt(successfulCount);
            buf.writeInt(noDropsCount);
            buf.writeInt(invalidCount);
            buf.writeInt(constructionFailedCount);
            buf.writeInt(crashedCount);
        }
    }

    public static class Handler implements IMessageHandler<PacketLootAnalysisResult, IMessage> {

        @Override
        public IMessage onMessage(PacketLootAnalysisResult message, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                if (message.success) {
                    sendClientMessage(TextFormatting.GREEN, "Loot analysis complete!");
                    sendClientMessage(TextFormatting.AQUA, "Successful: " + message.successfulCount +
                        ", No drops: " + message.noDropsCount +
                        ", Invalid: " + message.invalidCount +
                        ", Construction failed: " + message.constructionFailedCount +
                        ", Crashed: " + message.crashedCount);
                    sendClientMessage(TextFormatting.AQUA, "Results saved on server: " + message.message);
                } else {
                    sendClientMessage(TextFormatting.RED, "Loot analysis failed: " + message.message);
                }
            });

            return null;
        }

        private void sendClientMessage(TextFormatting color, String text) {
            if (Minecraft.getMinecraft().player != null) {
                TextComponentString component = new TextComponentString("[SMT] " + text);
                component.getStyle().setColor(color);
                Minecraft.getMinecraft().player.sendMessage(component);
            }
        }
    }
}
