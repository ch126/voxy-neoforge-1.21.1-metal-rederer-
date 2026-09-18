package me.cortex.voxy.client;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.gpu.BackendType;
import me.cortex.voxy.client.core.gpu.RenderBackend;
import me.cortex.voxy.client.core.gpu.RenderBackendFactory;
import me.cortex.voxy.client.core.model.bakery.BudgetBufferRenderer;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
// TODO: Debug screen API changed in MC 1.21.1 - disabled for now
// import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
// import net.minecraft.client.gui.components.debug.DebugScreenEntries;
// import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import java.util.HashSet;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Client initialization for Voxy on NeoForge.
 * Uses NeoForge event bus for command registration.
 */
@EventBusSubscriber(modid = "voxy", value = Dist.CLIENT)
public class VoxyClient {
    private static final HashSet<String> FREX = new HashSet<>();

    public static void initVoxyClient() {
        Capabilities.init();//Ensure clinit is called

        if (Capabilities.INSTANCE.hasBrokenDepthSampler) {
            Logger.error("AMD broken depth sampler detected, voxy does not work correctly and has been disabled, this will hopefully be fixed in the future");
        }

        RenderBackend backend = RenderBackendFactory.get();
        Logger.info("Render backend: " + backend.getType()
                + " (compute=" + backend.hasCompute()
                + ", indirectParameters=" + backend.hasIndirectParameters() + ")");

        boolean systemSupported = backend.hasCompute() && backend.hasIndirectParameters()
                && !Capabilities.INSTANCE.hasBrokenDepthSampler;
        boolean forceMetal = "1".equals(System.getenv("VOXY_FORCE_METAL"))
                || "true".equalsIgnoreCase(System.getenv("VOXY_FORCE_METAL"))
                || Boolean.getBoolean("voxy.forceMetal");

        if (systemSupported && backend.getType() != BackendType.OPENGL && !forceMetal) {
            Logger.warn("Voxy is disabled on " + backend.getType()
                    + "; set VOXY_FORCE_METAL=1 (or -Dvoxy.forceMetal=true) to enable the Metal path.");
            systemSupported = false;
        } else if (systemSupported && backend.getType() != BackendType.OPENGL) {
            Logger.warn("[VOXY_FORCE_METAL] Enabling Voxy on " + backend.getType()
                    + " via the IOSurface render path.");
        }
        if (systemSupported) {

            SharedIndexBuffer.INSTANCE.id();
            // The model bakery remains GL-only; loading this helper on Metal
            // would run its static GL buffer/shader initializers.
            if (backend.getType() == BackendType.OPENGL) {
                BudgetBufferRenderer.init();
            }

            VoxyCommon.setInstanceFactory(VoxyClientInstance::new);

            if (!Capabilities.INSTANCE.subgroup) {
                Logger.warn("GPU does not support subgroup operations, expect some performance degradation");
            }

        } else {
            Logger.error("Voxy is unsupported on your system.");
        }
    }

    /**
     * NeoForge event handler for client command registration.
     * Replaces Fabric's ClientCommandRegistrationCallback.
     */
    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        if (VoxyCommon.isAvailable()) {
            event.getDispatcher().register(VoxyCommands.register());
        }
    }

    // Note: FREX flawless frames integration disabled on NeoForge
    // (Fabric-specific entrypoint mechanism not available)

    public static boolean isFrexActive() {
        return !FREX.isEmpty();
    }

    public static int getOcclusionDebugState() {
        return 0;
    }

    public static boolean disableSodiumChunkRender() {
        return false;// getOcclusionDebugState() != 0;
    }
}
