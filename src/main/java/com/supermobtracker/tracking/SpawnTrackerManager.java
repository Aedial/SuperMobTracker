package com.supermobtracker.tracking;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.SPacketEntityMetadata;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.registry.EntityEntry;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;


public class SpawnTrackerManager {
    private static class Tracker {
        final Class<? extends Entity> clazz;
        final Set<UUID> uuids = new HashSet<>();
        int liveCount;

        Tracker(Class<? extends Entity> clazz) {
            this.clazz = clazz;
        }
    }

    private static final Map<ResourceLocation, Tracker> tracked = new LinkedHashMap<>();

    public static void setTracked(ResourceLocation id) {
        addTracked(id);
    }

    public static void addTracked(ResourceLocation id) {
        if (id == null) return;
        if (tracked.containsKey(id)) return;

        EntityEntry entry = ForgeRegistries.ENTITIES.getValue(id);
        if (entry == null) return;

        tracked.put(id, new Tracker(entry.getEntityClass()));
    }

    public static void removeTracked(ResourceLocation id) {
        Tracker t = tracked.remove(id);
        if (t != null) {
            t.uuids.clear();
        }
    }

    public static void toggleTracked(ResourceLocation id) {
        if (isTracked(id)) removeTracked(id); else addTracked(id);
    }

    public static boolean isTracked(ResourceLocation id) {
        return tracked.containsKey(id);
    }

    public static boolean isTracked(Entity entity) {
        for (Tracker t : tracked.values()) {
            if (t.clazz.isInstance(entity)) return true;
        }
        return false;
    }

    public static Set<ResourceLocation> getTrackedIds() {
        return Collections.unmodifiableSet(tracked.keySet());
    }

    public static List<String> getTrackedIdStrings() {
        List<String> list = new ArrayList<>();
        for (ResourceLocation id : tracked.keySet()) list.add(id.toString());
        return list;
    }

    public static void restoreTrackedIds(java.util.List<String> ids) {
        tracked.clear();
        for (String s : ids) {
            try {
                addTracked(new ResourceLocation(s));
            } catch (Exception ignored) {}
        }
    }

    public static void clearAll() {
        tracked.clear();
    }

    public static int getLiveCount(ResourceLocation id) {
        Tracker t = tracked.get(id);
        return t == null ? 0 : t.liveCount;
    }

    public static int getLiveCountInRange(ResourceLocation id, net.minecraft.entity.player.EntityPlayer player, double rangeSq) {
        Tracker t = tracked.get(id);
        if (t == null || player == null || player.world == null) return 0;

        int count = 0;
        for (Entity entity : player.world.loadedEntityList) {
            if (t.clazz.isInstance(entity) && player.getDistanceSq(entity) <= rangeSq) count++;
        }

        return count;
    }

    public static void maybeTrackJoin(Entity entity) {
        if (!(entity instanceof EntityLiving)) return;

        for (Map.Entry<ResourceLocation, Tracker> e : tracked.entrySet()) {
            Tracker t = e.getValue();
            if (t.clazz.isInstance(entity)) {
                if (t.uuids.add(entity.getUniqueID())) t.liveCount++;
            }
        }
    }

    public static void maybeTrackDespawn(Entity entity) {
        for (Tracker t : tracked.values()) {
            if (t.uuids.remove(entity.getUniqueID())) {
                t.liveCount = Math.max(0, t.liveCount - 1);
            }
        }
    }

    public static void maybeTrackDeath(Entity entity) {
        for (Tracker t : tracked.values()) {
            if (t.uuids.remove(entity.getUniqueID())) {
                t.liveCount = Math.max(0, t.liveCount - 1);
            }
        }
    }

    public static void makeGlowForPlayer(Entity target, EntityPlayerMP viewer, boolean glow) {
        target.setGlowing(glow);
        viewer.connection.sendPacket(new SPacketEntityMetadata(target.getEntityId(), target.getDataManager(), true));
    }

    public static void scanWorld(World world) {
        if (world == null) return;

        for (Entity entity : world.loadedEntityList) {
            maybeTrackJoin(entity);
        }
    }
}
