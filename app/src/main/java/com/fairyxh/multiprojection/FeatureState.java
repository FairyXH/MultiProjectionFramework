package com.fairyxh.multiprojection;

import java.util.Locale;

/**
 * Status/statistics accessor. Actual feature toggles live in {@link FeatureRegistry} so the
 * overlay, hooks, and persistence share a single source of truth. Keep this class for counters
 * and the last status line shown in the overlay.
 */
public final class FeatureState {
    private static volatile String lastMessage = "Ready";

    private FeatureState() {}

    public static boolean isEnabled() {
        return FeatureRegistry.getBool(FeatureRegistry.KEY_ENABLED);
    }

    public static void setEnabled(boolean value) {
        FeatureRegistry.setBool(FeatureRegistry.KEY_ENABLED, value);
        lastMessage = value ? "Enabled" : "Disabled";
    }

    public static void setLastMessage(String message) {
        if (message != null && !message.isEmpty()) lastMessage = message;
    }

    public static String summary() {
        return String.format(Locale.US,
                "Enabled: %s\nDiagnostics: %s\nRangeChoice: %s\nMultiSession: %s\nStatus: %s",
                isEnabled() ? "yes" : "no",
                FeatureRegistry.getBool(FeatureRegistry.KEY_DIAGNOSTICS) ? "on" : "off",
                FeatureRegistry.getBool(FeatureRegistry.KEY_RANGE_CHOICE) ? "on" : "off",
                FeatureRegistry.getBool(FeatureRegistry.KEY_MULTI_SESSION) ? "on" : "off",
                lastMessage);
    }
}