package me.cortex.voxy.client.core.rendering.util;

import me.cortex.voxy.client.core.gpu.*;
import me.cortex.voxy.client.core.metal.MetalTexture;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL12C.GL_PACK_IMAGE_HEIGHT;
import static org.lwjgl.opengl.GL12C.GL_PACK_SKIP_IMAGES;
import static org.lwjgl.opengl.GL13C.GL_ACTIVE_TEXTURE;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13C.glActiveTexture;

/** Copies Minecraft's OpenGL block atlas into a CPU-uploadable Metal texture. */
public final class AtlasMirror {
    private static final int WARMUP_MAX_SYNCS = 50;

    private final RenderBackend backend = RenderBackendFactory.get();
    private IGpuTexture mirror;
    private IGpuSampler sampler;
    private long stagingAddress;
    private long stagingSize;
    private int width;
    private int height;
    private int lastSyncedGlId = -1;
    private int warmupSyncCount;

    public IGpuTexture syncMetal(int mcAtlasGlId) {
        if (this.backend.getType() == BackendType.OPENGL) {
            throw new IllegalStateException("AtlasMirror is only valid for a non-GL backend");
        }
        if (mcAtlasGlId == 0) return this.mirror;
        if (mcAtlasGlId == this.lastSyncedGlId && this.mirror != null
                && this.warmupSyncCount >= WARMUP_MAX_SYNCS) {
            return this.mirror;
        }

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
            glBindTexture(GL_TEXTURE_2D, mcAtlasGlId);
            int w = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH);
            int h = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT);
            if (w <= 0 || h <= 0) return this.mirror;
            ensureResources(w, h);
            glPixelStorei(GL_PACK_ROW_LENGTH, 0);
            glPixelStorei(GL_PACK_SKIP_PIXELS, 0);
            glPixelStorei(GL_PACK_SKIP_ROWS, 0);
            glPixelStorei(GL_PACK_SKIP_IMAGES, 0);
            glPixelStorei(GL_PACK_IMAGE_HEIGHT, 0);
            glPixelStorei(GL_PACK_ALIGNMENT, 4);
            org.lwjgl.opengl.GL11C.nglGetTexImage(
                    GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, this.stagingAddress);
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

        this.mirror.uploadSubImage2D(0, 0, 0, this.width, this.height,
                GL_RGBA, GL_UNSIGNED_BYTE, this.stagingAddress);
        this.lastSyncedGlId = mcAtlasGlId;
        this.warmupSyncCount++;
        return this.mirror;
    }

    public IGpuSampler sampler() {
        return this.sampler;
    }

    private void ensureResources(int w, int h) {
        if (this.mirror == null || this.width != w || this.height != h) {
            if (this.mirror != null) this.mirror.free();
            this.width = w;
            this.height = h;
            MetalTexture texture = (MetalTexture) this.backend.createTexture(GL_TEXTURE_2D);
            texture.storeUploadable(GL_RGBA8, 1, w, h);
            texture.name("Voxy.MCBlockAtlasMirror");
            this.mirror = texture;

            long required = (long) w * h * 4;
            if (this.stagingAddress != 0 && required != this.stagingSize) {
                MemoryUtil.nmemFree(this.stagingAddress);
                this.stagingAddress = 0;
            }
            if (this.stagingAddress == 0) {
                this.stagingAddress = MemoryUtil.nmemAllocChecked(required);
                this.stagingSize = required;
            }
            this.lastSyncedGlId = -1;
        }
        if (this.sampler == null) {
            this.sampler = this.backend.createSampler(SamplerDesc.builder()
                    .filter(SamplerDesc.Filter.LINEAR, SamplerDesc.Filter.LINEAR)
                    .mipFilter(SamplerDesc.MipFilter.NEAREST)
                    .wrap(SamplerDesc.Wrap.CLAMP_TO_EDGE, SamplerDesc.Wrap.CLAMP_TO_EDGE)
                    .label("Voxy.MCBlockAtlasSampler")
                    .build());
        }
    }

    public void free() {
        if (this.mirror != null) this.mirror.free();
        if (this.sampler != null) this.sampler.close();
        if (this.stagingAddress != 0) MemoryUtil.nmemFree(this.stagingAddress);
        this.mirror = null;
        this.sampler = null;
        this.stagingAddress = 0;
        this.stagingSize = 0;
    }
}
