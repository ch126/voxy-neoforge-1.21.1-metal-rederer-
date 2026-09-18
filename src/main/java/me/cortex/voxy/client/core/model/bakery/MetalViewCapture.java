package me.cortex.voxy.client.core.model.bakery;

import me.cortex.voxy.client.core.gpu.BackendType;
import me.cortex.voxy.client.core.gpu.IGpuTexture;
import me.cortex.voxy.client.core.gpu.RenderBackend;
import me.cortex.voxy.client.core.gpu.RenderBackendFactory;
import me.cortex.voxy.client.core.gpu.RenderPassDesc;
import me.cortex.voxy.client.core.metal.MetalTexture;
import me.cortex.voxy.client.core.rendering.util.AtlasMirror;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.GL11C.GL_RGBA8;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_2D;

/** Metal counterpart to the GL model-bakery framebuffer and readback path. */
public final class MetalViewCapture {
    private final int width;
    private final int height;
    private final int totalWidth;
    private final int totalHeight;
    private final RenderBackend backend;
    private final MetalTexture bakeTarget;
    private final AtlasMirror atlasMirror;
    private final MetalBudgetBufferRenderer renderer;
    private final long readbackBuffer;
    private boolean activeBake;

    public MetalViewCapture(int width, int height) {
        this.width = width;
        this.height = height;
        this.totalWidth = width * 3;
        this.totalHeight = height * 2;
        this.backend = RenderBackendFactory.get();
        if (this.backend.getType() != BackendType.METAL) {
            throw new IllegalStateException("MetalViewCapture requires the Metal backend");
        }

        this.bakeTarget = (MetalTexture) this.backend.createTexture(GL_TEXTURE_2D);
        this.bakeTarget.storeRenderTargetUploadable(GL_RGBA8, 1, this.totalWidth, this.totalHeight);
        this.bakeTarget.name("Voxy.MetalBakeTarget");
        this.atlasMirror = new AtlasMirror();
        this.renderer = new MetalBudgetBufferRenderer();
        this.readbackBuffer = MemoryUtil.nmemAllocChecked((long) this.totalWidth * this.totalHeight * 4L);
    }

    public void clear() {
        if (this.activeBake) throw new IllegalStateException("clear during active bake");
        try (var encoder = this.backend.beginRenderPass(
                RenderPassDesc.builder(this.totalWidth, this.totalHeight)
                        .clearColor(this.bakeTarget, 0, 0, 0, 0)
                        .build())) {
            encoder.setViewport(0, 0, this.totalWidth, this.totalHeight, 0, 1);
        }
        this.backend.submit();
    }

    public void beginBake(int minecraftAtlasId, long meshAddress, int quadCount) {
        if (this.activeBake) throw new IllegalStateException("nested Metal model bake");
        IGpuTexture atlas = this.atlasMirror.syncMetal(minecraftAtlasId);
        if (atlas == null) return;
        this.renderer.beginPass(this.bakeTarget, this.totalWidth, this.totalHeight, false);
        this.renderer.setup(meshAddress, quadCount, atlas, this.atlasMirror.sampler());
        this.activeBake = true;
    }

    public void renderFace(int faceX, int faceY, Matrix4f matrix) {
        if (!this.activeBake) return;
        this.renderer.setViewport(faceX * this.width, faceY * this.height, this.width, this.height);
        this.renderer.render(matrix);
    }

    public void endBake() {
        if (!this.activeBake) return;
        this.renderer.endPass();
        this.activeBake = false;
    }

    /**
     * Converts the 3x2 bake raster to the legacy face-major uvec2 layout.
     * Empty texels are filled with the average written colour of their face,
     * preventing sparse models from disappearing in distant mip levels.
     */
    public void emitToStream(long destination) {
        this.bakeTarget.getBytes(0, 0, 0, this.totalWidth, this.totalHeight, this.readbackBuffer);
        fillTransparentTexelsPerFace();

        int sourceStride = this.totalWidth * 4;
        for (int face = 0; face < 6; face++) {
            int faceX = face % 3;
            int faceY = face / 3;
            long sourceBase = this.readbackBuffer
                    + (long) faceY * this.height * sourceStride
                    + (long) faceX * this.width * 4L;
            long faceDestination = destination + (long) face * this.width * this.height * 8L;
            for (int y = 0; y < this.height; y++) {
                long sourceRow = sourceBase + (long) y * sourceStride;
                long destinationRow = faceDestination + (long) y * this.width * 8L;
                for (int x = 0; x < this.width; x++) {
                    int rgba = MemoryUtil.memGetInt(sourceRow + x * 4L);
                    long pixelDestination = destinationRow + x * 8L;
                    MemoryUtil.memPutInt(pixelDestination, rgba);
                    MemoryUtil.memPutInt(pixelDestination + 4,
                            (rgba & 0xFF000000) != 0 ? 0x80 : 0);
                }
            }
        }
    }

    private void fillTransparentTexelsPerFace() {
        for (int faceY = 0; faceY < 2; faceY++) {
            for (int faceX = 0; faceX < 3; faceX++) {
                int x0 = faceX * this.width;
                int y0 = faceY * this.height;
                long red = 0, green = 0, blue = 0, alpha = 0;
                int written = 0;
                for (int y = y0; y < y0 + this.height; y++) {
                    for (int x = x0; x < x0 + this.width; x++) {
                        int pixel = pixelAt(x, y);
                        if ((pixel & 0xFF000000) == 0) continue;
                        red += pixel & 0xFF;
                        green += (pixel >>> 8) & 0xFF;
                        blue += (pixel >>> 16) & 0xFF;
                        alpha += pixel >>> 24;
                        written++;
                    }
                }
                if (written == 0) continue;
                int fill = ((int) Math.max(1, alpha / written) << 24)
                        | ((int) (blue / written) << 16)
                        | ((int) (green / written) << 8)
                        | (int) (red / written);
                for (int y = y0; y < y0 + this.height; y++) {
                    for (int x = x0; x < x0 + this.width; x++) {
                        if ((pixelAt(x, y) & 0xFF000000) == 0) setPixel(x, y, fill);
                    }
                }
            }
        }
    }

    private int pixelAt(int x, int y) {
        return MemoryUtil.memGetInt(this.readbackBuffer + ((long) y * this.totalWidth + x) * 4L);
    }

    private void setPixel(int x, int y, int value) {
        MemoryUtil.memPutInt(this.readbackBuffer + ((long) y * this.totalWidth + x) * 4L, value);
    }

    public void free() {
        if (this.activeBake) endBake();
        this.renderer.shutdown();
        this.atlasMirror.free();
        this.bakeTarget.free();
        MemoryUtil.nmemFree(this.readbackBuffer);
    }
}
