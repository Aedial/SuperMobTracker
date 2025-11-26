package com.supermobtracker.tracking;

import com.supermobtracker.tracking.SpawnTrackerManager;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingSpawnEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class SpawnEventHandler {
    @SubscribeEvent
    public void onMobJoinWorld(EntityJoinWorldEvent event) {
        SpawnTrackerManager.maybeTrackJoin(event.getEntity());
    }

    @SubscribeEvent
    public void onAllowDespawn(LivingSpawnEvent.AllowDespawn event) {
        SpawnTrackerManager.maybeTrackDespawn(event.getEntityLiving());
    }

    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        SpawnTrackerManager.maybeTrackDeath(event.getEntityLiving());
    }
}
