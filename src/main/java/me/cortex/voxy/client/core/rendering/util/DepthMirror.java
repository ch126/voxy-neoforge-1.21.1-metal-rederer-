package me.cortex.voxy.client.core.rendering.util;

import me.cortex.voxy.client.core.gpu.BackendType;
import me.cortex.voxy.client.core.gpu.IGpuTexture;
import me.cortex.voxy.client.core.gpu.RenderBackend;
import me.cortex.voxy.client.core.gpu.RenderBackendFactory;
import me.cortex.voxy.client.core.metal.MetalTexture;
import me.cortex.voxy.common.Logger;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.GL11C.GL_DEPTH_COMPONENT;
import static org.lwjgl.opengl.GL11C.GL_FLOAT;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_BINDING_2D;
import static org.lwjgl.opengl.GL11C.glBindTexture;
import static org.lwjgl.opengl.GL11C.glGetInteger;
import static org.lwjgl.opengl.GL13C.GL_ACTIVE_TEXTURE;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13C.glActiveTexture;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL21C.GL_PIXEL_PACK_BUFFER;
import static org.lwjgl.opengl.GL21C.GL_PIXEL_PACK_BUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.GL_DEPTH_ATTACHMENT;
import static org.lwjgl.opengl.GL30C.GL_DEPTH_COMPONENT32F;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE;
import static org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;
import static org.lwjgl.opengl.GL30C.glGetFramebufferAttachmentParameteri;

/**
 * CPU mirror of Minecraft's OpenGL depth attachment for Metal HiZ input.
 * Apple exposes no direct GL-depth/Metal-texture sharing path, so the
 * experimental real-HiZ path reads normalized floats from GL 4.1 and uploads
 * them into a Shared-storage Depth32Float texture.
 */
public final class DepthMirror {
    private final RenderBackend backend = RenderBackendFactory.get();
    private IGpuTexture mirror;
    private long stagingAddress;
    private long stagingSize;
    private int width;
    private int height;
    private int lastSyncedFrame = -1;
    private boolean loggedDepthStats;
    private static final boolean DIAGNOSTICS =
            "1".equals(System.getenv("VOXY_METAL_DIAGNOSTICS"));

    public IGpuTexture syncFromMC(int sourceFramebuffer, int width, int height, int frameId) {
        if (this.backend.getType() == BackendType.OPENGL) {
            throw new IllegalStateException("DepthMirror is only used by non-OpenGL backends");
        }
        this.ensureResources(width, height);
        if (frameId == this.lastSyncedFrame) return this.mirror;
        this.lastSyncedFrame = frameId;

        int previousReadFramebuffer = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int depthObject;
        int depthObjectType;
        try {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, sourceFramebuffer);
            depthObject = glGetFramebufferAttachmentParameteri(
                    GL_READ_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
                    GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            depthObjectType = glGetFramebufferAttachmentParameteri(
                    GL_READ_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
                    GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        } finally {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, previousReadFramebuffer);
        }
        if (depthObject == 0 || depthObjectType != GL_TEXTURE) return this.mirror;

        int previousActiveTexture = glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE0);
        int previousTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
        int previousPackBuffer = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        try {
            glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
            glBindTexture(GL_TEXTURE_2D, depthObject);
            org.lwjgl.opengl.GL11C.nglGetTexImage(
                    GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT, GL_FLOAT,
                    this.stagingAddress);
        } finally {
            glBindTexture(GL_TEXTURE_2D, previousTexture);
            glBindBuffer(GL_PIXEL_PACK_BUFFER, previousPackBuffer);
            glActiveTexture(previousActiveTexture);
        }

        if (DIAGNOSTICS && (!this.loggedDepthStats || frameId % 600 == 300)) {
            int samples = 0;
            int geometrySamples = 0;
            float minimum = 1.0f;
            float maximum = 0.0f;
            int pixels = width * height;
            for (int i = 0; i < pixels; i += 64) {
                float depth = MemoryUtil.memGetFloat(this.stagingAddress + (long) i * Float.BYTES);
                if (!Float.isFinite(depth)) continue;
                samples++;
                if (depth > 0.0f && depth < 0.999999f) geometrySamples++;
                minimum = Math.min(minimum, depth);
                maximum = Math.max(maximum, depth);
            }
            Logger.info("Metal MC depth mirror: samples=" + samples
                    + ", geometry=" + geometrySamples
                    + ", range=" + minimum + ".." + maximum
                    + ", sourceFbo=" + sourceFramebuffer);
            this.loggedDepthStats = true;
        }

        this.mirror.uploadSubImage2D(0, 0, 0, width, height,
                GL_DEPTH_COMPONENT, GL_FLOAT, this.stagingAddress);
        return this.mirror;
    }

    public IGpuTexture texture() {
        return this.mirror;
    }

    private void ensureResources(int width, int height) {
        long requiredSize = (long) width * height * Float.BYTES;
        if (this.stagingAddress == 0 || this.stagingSize != requiredSize) {
            if (this.stagingAddress != 0) MemoryUtil.nmemFree(this.stagingAddress);
            this.stagingAddress = MemoryUtil.nmemAllocChecked(requiredSize);
            this.stagingSize = requiredSize;
        }

        if (this.mirror == null || this.width != width || this.height != height) {
            if (this.mirror != null) this.mirror.free();
            this.width = width;
            this.height = height;
            MetalTexture texture = (MetalTexture) this.backend.createTexture(GL_TEXTURE_2D);
            texture.storeUploadable(GL_DEPTH_COMPONENT32F, 1, width, height);
            texture.name("Voxy.MCDepthMirror");
            this.mirror = texture;

            // A missing/non-texture depth attachment must mean "no occluder",
            // never undefined memory from the first frame or after a resize.
            MemoryUtil.memSet(this.stagingAddress, 0, requiredSize);
            this.mirror.uploadSubImage2D(0, 0, 0, width, height,
                    GL_DEPTH_COMPONENT, GL_FLOAT, this.stagingAddress);
        }
    }

    public void free() {
        if (this.mirror != null) {
            this.mirror.free();
            this.mirror = null;
        }
        if (this.stagingAddress != 0) {
            MemoryUtil.nmemFree(this.stagingAddress);
            this.stagingAddress = 0;
            this.stagingSize = 0;
        }
    }
}
