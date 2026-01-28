package com.supermobtracker.network;

import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

import com.supermobtracker.SuperMobTracker;


/**
 * Handles network packet registration for client-server communication.
 * Used primarily for server-side drop simulation in multiplayer.
 */
public class NetworkHandler {

    public static final SimpleNetworkWrapper INSTANCE = NetworkRegistry.INSTANCE.newSimpleChannel(SuperMobTracker.MODID);

    private static int packetId = 0;

    /**
     * Register all network packets. Must be called during preInit.
     */
    public static void registerPackets() {
        // Client -> Server: Request drop simulation for an entity
        INSTANCE.registerMessage(
            PacketRequestDropSimulation.Handler.class,
            PacketRequestDropSimulation.class,
            packetId++,
            Side.SERVER
        );

        // Server -> Client: Send simulation results back
        INSTANCE.registerMessage(
            PacketDropSimulationResult.Handler.class,
            PacketDropSimulationResult.class,
            packetId++,
            Side.CLIENT
        );

        // Server -> Client: Send simulation progress updates
        INSTANCE.registerMessage(
            PacketDropSimulationProgress.Handler.class,
            PacketDropSimulationProgress.class,
            packetId++,
            Side.CLIENT
        );

        // Client -> Server: Request loot analysis (batch)
        INSTANCE.registerMessage(
            PacketRequestLootAnalysis.Handler.class,
            PacketRequestLootAnalysis.class,
            packetId++,
            Side.SERVER
        );

        // Server -> Client: Send loot analysis results (batch)
        INSTANCE.registerMessage(
            PacketLootAnalysisResult.Handler.class,
            PacketLootAnalysisResult.class,
            packetId++,
            Side.CLIENT
        );

        // Server -> Client: Send loot analysis progress updates
        INSTANCE.registerMessage(
            PacketLootAnalysisProgress.Handler.class,
            PacketLootAnalysisProgress.class,
            packetId++,
            Side.CLIENT
        );
    }
}
