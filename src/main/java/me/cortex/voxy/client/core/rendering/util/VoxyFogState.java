package me.cortex.voxy.client.core.rendering.util;

import net.minecraft.world.level.material.FogType;

/**
 * Captures the Minecraft 1.21.1/NeoForge terrain-fog state before Voxy moves
 * ordinary distance fog beyond the vanilla render distance.
 */
public final class VoxyFogState {
    public record Snapshot(float start, float end, float red, float green, float blue, boolean enabled) {
        private static final Snapshot DISABLED = new Snapshot(0, 1, 0, 0, 0, false);
    }

    private static float red;
    private static float green;
    private static float blue;
    private static volatile Snapshot terrain = Snapshot.DISABLED;

    private VoxyFogState() {
    }

    public static void captureColor(float red, float green, float blue) {
        VoxyFogState.red = red;
        VoxyFogState.green = green;
        VoxyFogState.blue = blue;
    }

    public static void captureTerrain(FogType type, float start, float end, boolean effectFog) {
        boolean enabled = type != FogType.NONE || effectFog;
        terrain = new Snapshot(start, end, red, green, blue, enabled && end > start);
    }

    public static Snapshot terrain() {
        return terrain;
    }
}
