package com.supermobtracker;

import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.item.Item;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;


public final class ModItems {
    /** Hidden item used only as a JEI focus anchor for mob loot navigation. */
    public static final Item JEI_ANCHOR = new Item()
        .setRegistryName(new ResourceLocation(Tags.MODID, "jei_anchor"))
        .setTranslationKey(Tags.MODID + ".jei_anchor")
        .setMaxStackSize(1);

    private ModItems() {}

    public static void registerItems() {
        ForgeRegistries.ITEMS.register(JEI_ANCHOR);
    }

    @SideOnly(Side.CLIENT)
    public static void registerModels() {
        ModelLoader.setCustomModelResourceLocation(
            JEI_ANCHOR,
            0,
            new ModelResourceLocation(JEI_ANCHOR.getRegistryName(), "inventory")
        );
    }
}