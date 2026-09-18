package me.cortex.voxy.client;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.rendering.util.VoxyFogState;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Client event handlers for Voxy on NeoForge.
 *
 * Keeps the ordinary render-distance fog wall out of Voxy's far field while
 * preserving gameplay-significant submersion and vision-effect fog.
 */
@EventBusSubscriber(modid = "voxy", value = Dist.CLIENT)
public class VoxyClientEvents {

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onComputeFogColor(ViewportEvent.ComputeFogColor event) {
        VoxyFogState.captureColor(event.getRed(), event.getGreen(), event.getBlue());
    }

    /**
     * Push terrain fog to infinity when Voxy is enabled.
     *
     * This event fires AFTER setupFog() completes but BEFORE terrain renders.
     * By setting fog distances to very large values and cancelling the event,
     * we prevent the fog wall from appearing at vanilla render distance.
     *
     * Both vanilla terrain and Voxy LODs will render without fog-based distance fading.
     * This is the same approach used by Distant Horizons.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        // Only modify terrain fog when Voxy is enabled and rendering
        if (event.getMode() == FogRenderer.FogMode.FOG_TERRAIN
                && VoxyConfig.CONFIG.enabled
                && VoxyConfig.CONFIG.enableRendering) {

            boolean effectFog = event.getCamera().getEntity() instanceof LivingEntity living
                    && (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS));
            VoxyFogState.captureTerrain(event.getType(), event.getNearPlaneDistance(),
                    event.getFarPlaneDistance(), effectFog);

            // Submersion and vision-effect fog are gameplay cues, not the
            // vanilla render-distance wall. Keep them on Sodium's near terrain
            // while the Metal shader applies the captured values to far LODs.
            if (event.getType() != FogType.NONE || effectFog) {
                return;
            }

            // Push fog to very large values (not MAX_VALUE to avoid shader math issues)
            // This removes the fog wall at vanilla render distance
            event.setNearPlaneDistance(999999.0f);
            event.setFarPlaneDistance(9999999.0f);

            // MUST cancel for changes to take effect (per NeoForge docs)
            event.setCanceled(true);
        }
    }
}
