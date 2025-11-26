package com.supermobtracker.client;

import com.supermobtracker.config.ModConfig;

public class ClientSettings {
    public static boolean i18nNames = ModConfig.clientI18nNames;

    public static void toggleI18n() {
        i18nNames = !i18nNames;
        ModConfig.setClientI18nNames(i18nNames);
    }
}
