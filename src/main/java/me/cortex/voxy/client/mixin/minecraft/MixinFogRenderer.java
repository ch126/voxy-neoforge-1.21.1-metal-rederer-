package me.cortex.voxy.client.mixin.minecraft;

import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * MC 1.21.1 compatible fog mixin.
 *
 * This remains a placeholder because NeoForge exposes the required 1.21.1
 * state through {@code ViewportEvent}. VoxyClientEvents captures terrain fog
 * before moving ordinary air fog beyond the vanilla render distance; the
 * Metal terrain shader consumes the captured submersion/effect parameters.
 *
 * Render order:
 * 1. setupFog(FOG_TERRAIN) called - fog set to vanilla render distance
 * No 1.21.11 Sodium {@code FogParameters} dependency is required.
 */
@Mixin(FogRenderer.class)
public class MixinFogRenderer {
    // No injections needed; see VoxyClientEvents and VoxyFogState.
}
