package me.cortex.voxy.client.core.rendering.util;

import me.cortex.voxy.client.core.gl.GLCompat;
import me.cortex.voxy.client.core.gpu.BackendType;
import me.cortex.voxy.client.core.gpu.IGpuSampler;
import me.cortex.voxy.client.core.gpu.IGpuTexture;
import me.cortex.voxy.client.core.gpu.RenderBackend;
import me.cortex.voxy.client.core.gpu.RenderBackendFactory;
import me.cortex.voxy.client.core.gpu.RenderEncoder;
import me.cortex.voxy.client.core.gpu.SamplerDesc;
import me.cortex.voxy.client.core.metal.MetalTexture;
import me.cortex.voxy.client.mixin.minecraft.AccessorLightTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL12C.GL_PACK_IMAGE_HEIGHT;
import static org.lwjgl.opengl.GL12C.GL_PACK_SKIP_IMAGES;
import static org.lwjgl.opengl.GL13C.GL_ACTIVE_TEXTURE;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13C.glActiveTexture;
import static org.lwjgl.opengl.GL33C.glBindSampler;

/** Routes Minecraft's 16x16 lightmap to the active Voxy backend. */
public final class LightMapHelper {
    private static final int WIDTH = 16;
    private static final int HEIGHT = 16;
    private static final int BYTES = WIDTH * HEIGHT * 4;

    private static IGpuTexture metalLightmap;
    private static IGpuSampler metalSampler;
    private static long stagingAddress;
    private static int lastSyncedFrame = -1;
    private static boolean sizeWarned;

    private LightMapHelper() {
    }

    public static void bind(int lightingIndex) {
        glBindSampler(lightingIndex, 0);
        GLCompat.bindTextureUnit(lightingIndex, minecraftLightmapId());
    }

    /** Mirrors the 1.21.1 OpenGL lightmap into Metal once per Voxy frame. */
    public static void bindMetal(RenderEncoder encoder, int slot, int frameId) {
        ensureMetalResources();
        syncFromMinecraft(frameId);
        encoder.setTexture(slot, metalLightmap);
        encoder.setSampler(slot, metalSampler);
    }

    private static int minecraftLightmapId() {
        LightTexture lightTexture = Minecraft.getInstance().gameRenderer.lightTexture();
        return ((AccessorLightTexture) lightTexture).voxy$getLightTexture().getId();
    }

    private static void ensureMetalResources() {
        RenderBackend backend = RenderBackendFactory.get();
        if (backend.getType() != BackendType.METAL) {
            throw new IllegalStateException("Metal lightmap requested on " + backend.getType());
        }
        if (metalLightmap == null) {
            MetalTexture texture = (MetalTexture) backend.createTexture(GL_TEXTURE_2D);
            texture.storeUploadable(GL_RGBA8, 1, WIDTH, HEIGHT);
            texture.name("Voxy.MCLightmapMirror");
            metalLightmap = texture;
        }
        if (metalSampler == null) {
            metalSampler = backend.createSampler(SamplerDesc.builder()
                    .filter(SamplerDesc.Filter.LINEAR, SamplerDesc.Filter.LINEAR)
                    .mipFilter(SamplerDesc.MipFilter.NOT_MIPMAPPED)
                    .wrap(SamplerDesc.Wrap.CLAMP_TO_EDGE, SamplerDesc.Wrap.CLAMP_TO_EDGE)
                    .label("Voxy.MCLightmapSampler")
                    .build());
        }
        if (stagingAddress == 0) stagingAddress = MemoryUtil.nmemAllocChecked(BYTES);
    }

    private static void syncFromMinecraft(int frameId) {
        if (frameId == lastSyncedFrame) return;
        lastSyncedFrame = frameId;

        int previousActive = glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE0);
        int previousBinding = glGetInteger(GL_TEXTURE_BINDING_2D);
        int previousRowLength = glGetInteger(GL_PACK_ROW_LENGTH);
        int previousSkipPixels = glGetInteger(GL_PACK_SKIP_PIXELS);
        int previousSkipRows = glGetInteger(GL_PACK_SKIP_ROWS);
        int previousSkipImages = glGetInteger(GL_PACK_SKIP_IMAGES);
        int previousImageHeight = glGetInteger(GL_PACK_IMAGE_HEIGHT);
        int previousAlignment = glGetInteger(GL_PACK_ALIGNMENT);
        try {
            glBindTexture(GL_TEXTURE_2D, minecraftLightmapId());
            glPixelStorei(GL_PACK_ROW_LENGTH, 0);
            glPixelStorei(GL_PACK_SKIP_PIXELS, 0);
            glPixelStorei(GL_PACK_SKIP_ROWS, 0);
            glPixelStorei(GL_PACK_SKIP_IMAGES, 0);
            glPixelStorei(GL_PACK_IMAGE_HEIGHT, 0);
            glPixelStorei(GL_PACK_ALIGNMENT, 4);

            int width = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH);
            int height = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT);
            if (width == WIDTH && height == HEIGHT) {
                nglGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, stagingAddress);
                metalLightmap.uploadSubImage2D(0, 0, 0, WIDTH, HEIGHT,
                        GL_RGBA, GL_UNSIGNED_BYTE, stagingAddress);
            } else if (!sizeWarned) {
                sizeWarned = true;
                me.cortex.voxy.common.Logger.warn("Minecraft lightmap is " + width + "x" + height
                        + ", expected 16x16; keeping the last Metal mirror");
            }
        } finally {
            glPixelStorei(GL_PACK_ROW_LENGTH, previousRowLength);
            glPixelStorei(GL_PACK_SKIP_PIXELS, previousSkipPixels);
            glPixelStorei(GL_PACK_SKIP_ROWS, previousSkipRows);
            glPixelStorei(GL_PACK_SKIP_IMAGES, previousSkipImages);
            glPixelStorei(GL_PACK_IMAGE_HEIGHT, previousImageHeight);
            glPixelStorei(GL_PACK_ALIGNMENT, previousAlignment);
            glBindTexture(GL_TEXTURE_2D, previousBinding);
            glActiveTexture(previousActive);
        }
    }
}
