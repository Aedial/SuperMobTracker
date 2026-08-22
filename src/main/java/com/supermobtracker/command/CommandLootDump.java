package com.supermobtracker.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.EntityLiving;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.client.IClientCommand;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.registry.EntityEntry;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import com.supermobtracker.SuperMobTracker;
import com.supermobtracker.config.ModConfig;
import com.supermobtracker.drops.DropSimulator;
import com.supermobtracker.drops.DropSimulator.ProfileResult;
import com.supermobtracker.drops.LootDump;
import com.supermobtracker.drops.LootDump.DumpWriteResult;
import com.supermobtracker.integration.jei.JEIIntegration;


/**
 * Creates the local loot data set consumed by the Super Mob Tracker JEI category.
 */
public class CommandLootDump extends CommandBase implements IClientCommand {
    @Override
    @Nonnull
    public String getName() {
        return "smtlootdump";
    }

    @Override
    @Nonnull
    public String getUsage(@Nonnull ICommandSender sender) {
        return "/smtlootdump [simulationCount]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean allowUsageWithoutPrefix(ICommandSender sender, String message) {
        return false;
    }

    @Override
    @Nonnull
    public List<String> getTabCompletions(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender,
            String[] args, BlockPos targetPos) {
        return Collections.emptyList();
    }

    @Override
    public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender, String[] args)
            throws CommandException {
        if (args.length > 1) throw new CommandException(getUsage(sender));

        int simulationCount = args.length == 1
            ? parseInt(args[0], 100, 100000)
            : ModConfig.clientDropSimulationCount;

        if (DropSimulator.isMultiplayer()) {
            sendMessage(sender, TextFormatting.RED,
                "Loot dumping requires an integrated server because the JEI category reads the local dump file.");
            return;
        }

        sendMessage(sender, TextFormatting.YELLOW,
            "Dumping loot for all available mobs (" + simulationCount + " simulated kills each)...");
        new Thread(() -> runDump(sender, simulationCount), "SMT-LootDump").start();
    }

    private void runDump(ICommandSender sender, int simulationCount) {
        List<ResourceLocation> entityIds = new ArrayList<>();
        for (EntityEntry entry : ForgeRegistries.ENTITIES.getValuesCollection()) {
            if (entry.getRegistryName() != null && EntityLiving.class.isAssignableFrom(entry.getEntityClass())) {
                entityIds.add(entry.getRegistryName());
            }
        }
        entityIds.sort(Comparator.comparing(ResourceLocation::toString));

        Map<ResourceLocation, DropSimulator.DropSimulationResult> results = new LinkedHashMap<>();
        int failedCount = 0;

        try {
            for (int index = 0; index < entityIds.size(); index++) {
                ResourceLocation entityId = entityIds.get(index);
                if ((index + 1) % 50 == 0) {
                    sendMessage(sender, TextFormatting.YELLOW,
                        "Loot dump progress: " + (index + 1) + "/" + entityIds.size() + " mobs simulated...");
                }

                try {
                    ProfileResult result = DropSimulator.profileEntity(entityId, simulationCount);
                    if (result.status == ProfileResult.Status.SUCCESS && result.result != null && result.hasDrops()) {
                        results.put(entityId, result.result);
                    } else if (result.status != ProfileResult.Status.NO_DROPS) {
                        failedCount++;
                    }
                } catch (Throwable error) {
                    failedCount++;
                    SuperMobTracker.LOGGER.warn("Could not dump loot for {}", entityId, error);
                }
            }

            DumpWriteResult writeResult = LootDump.write(results, simulationCount);
            if (Loader.isModLoaded("jei")) JEIIntegration.refreshMobLootRecipes();

            sendMessage(sender, TextFormatting.GREEN,
                "Loot dump complete: " + writeResult.mobCount + " mobs, " + writeResult.uniqueItemCount
                    + " unique items, " + writeResult.dropTypeCount + " drop variants.");
            if (failedCount > 0) {
                sendMessage(sender, TextFormatting.YELLOW,
                    "Skipped " + failedCount + " mobs whose loot could not be simulated.");
            }
            sendMessage(sender, TextFormatting.AQUA,
                "JEI loot data saved to: " + writeResult.file.getAbsolutePath());
        } catch (Exception error) {
            SuperMobTracker.LOGGER.error("Failed to write mob loot dump", error);
            sendMessage(sender, TextFormatting.RED, "Failed to write loot dump: " + error.getMessage());
        } finally {
            DropSimulator.clearProfileCache();
        }
    }

    private static void sendMessage(ICommandSender sender, TextFormatting color, String message) {
        sender.sendMessage(new TextComponentString(color + "[SMT] " + message));
    }
}
