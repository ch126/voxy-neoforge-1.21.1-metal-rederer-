package me.cortex.voxy.client.core.model.bakery;

import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gpu.*;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import org.joml.Matrix4f;
import me.cortex.voxy.common.util.JomlMemory;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.util.Map;

import static org.lwjgl.opengl.GL11C.GL_RGBA8;

/** Metal implementation of the small quad renderer used by the model bakery. */
public final class MetalBudgetBufferRenderer {
    private static final int PUSH_BINDING = 14;
    private static final int TEX_BINDING = 0;
    private static final int STRIDE = 24;
    private static final int INDEX_BUFFER_BYTES = 3 * 2 * 2 * 4096;

    private final RenderBackend backend;
    private IGpuPipeline pipeline;
    private IGpuBuffer vertexBuffer;
    private long vertexBufferCapacity;
    private IGpuBuffer indexBuffer;
    private RenderEncoder activeEncoder;
    private int activeQuadCount;

    public MetalBudgetBufferRenderer() {
        this.backend = RenderBackendFactory.get();
        if (this.backend.getType() == BackendType.OPENGL) {
            throw new IllegalStateException("Metal bakery renderer used with OpenGL backend");
        }
    }

    private void ensureInit() {
        if (this.pipeline != null) return;

        VertexLayout layout = VertexLayout.builder()
                .buffer(0, STRIDE, VertexLayout.StepRate.PER_VERTEX)
                .attribute(0, VertexLayout.VertexFormat.FLOAT4, 0, 0)
                .attribute(1, VertexLayout.VertexFormat.FLOAT2, 16, 0)
                .build();
        PipelineState state = new PipelineState(
                PipelineState.DepthState.DISABLED,
                PipelineState.BlendState.OPAQUE,
                PipelineState.RasterState.NO_CULL);
        this.pipeline = this.backend.createGraphicsPipeline(new GraphicsPipelineDesc(
                ShaderLoader.parse("voxy:bakery/position_tex.vsh"),
                ShaderLoader.parse("voxy:bakery/position_tex.fsh"),
                Map.of("BAKERY_SINGLE_ATTACHMENT", ""),
                null, null, null, null, GL_RGBA8,
                layout, state, "MetalBudgetBufferRenderer"));

        this.indexBuffer = this.backend.createBuffer(INDEX_BUFFER_BYTES);
        long dst = UploadStream.INSTANCE.upload(this.indexBuffer, 0, INDEX_BUFFER_BYTES);
        for (int q = 0; q < 4096; q++) {
            int base = q * 4;
            long off = dst + (long) q * 12;
            MemoryUtil.memPutShort(off,      (short) base);
            MemoryUtil.memPutShort(off + 2,  (short) (base + 1));
            MemoryUtil.memPutShort(off + 4,  (short) (base + 2));
            MemoryUtil.memPutShort(off + 6,  (short) (base + 2));
            MemoryUtil.memPutShort(off + 8,  (short) (base + 3));
            MemoryUtil.memPutShort(off + 10, (short) base);
        }
        UploadStream.INSTANCE.commit();
    }

    public void beginPass(IGpuTexture target, int width, int height, boolean clear) {
        ensureInit();
        if (this.activeEncoder != null) throw new IllegalStateException("Nested bakery pass");
        RenderPassDesc.Builder pass = RenderPassDesc.builder(width, height);
        if (clear) {
            pass.clearColor(target, 0, 0, 0, 0);
        } else {
            pass.addColorAttachment(target, 0, RenderPassDesc.LoadAction.LOAD,
                    RenderPassDesc.StoreAction.STORE, 0, 0, 0, 0);
        }
        this.activeEncoder = this.backend.beginRenderPass(pass.build());
        this.activeEncoder.setPipeline(this.pipeline);
        this.activeEncoder.setViewport(0, 0, width, height, 0, 1);
        this.activeEncoder.bindIndexBuffer(this.indexBuffer, RenderEncoder.INDEX_TYPE_UINT16, 0);
    }

    public void setup(long dataPtr, int quads, IGpuTexture source, IGpuSampler sampler) {
        if (this.activeEncoder == null) throw new IllegalStateException("setup outside bakery pass");
        if (quads <= 0 || quads > 4096) throw new IllegalArgumentException("Invalid quad count " + quads);
        this.activeQuadCount = quads;
        long bytes = (long) quads * 4 * STRIDE;
        if (this.vertexBuffer == null || this.vertexBufferCapacity < bytes) {
            if (this.vertexBuffer != null) this.vertexBuffer.free();
            this.vertexBufferCapacity = Math.max(bytes * 2, 64 * 1024);
            this.vertexBuffer = this.backend.createBuffer(this.vertexBufferCapacity);
        }
        long dst = UploadStream.INSTANCE.upload(this.vertexBuffer, 0, bytes);
        MemoryUtil.memCopy(dataPtr, dst, bytes);
        UploadStream.INSTANCE.commit();
        this.activeEncoder.bindVertexBuffer(0, this.vertexBuffer, 0);
        this.activeEncoder.setTexture(TEX_BINDING, source);
        this.activeEncoder.setSampler(TEX_BINDING, sampler);
    }

    public void setViewport(int x, int y, int width, int height) {
        if (this.activeEncoder == null) throw new IllegalStateException("viewport outside bakery pass");
        this.activeEncoder.setViewport(x, y, width, height, 0, 1);
    }

    public void render(Matrix4f matrix) {
        if (this.activeEncoder == null) throw new IllegalStateException("render outside bakery pass");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long address = stack.nmalloc(64);
            JomlMemory.put(matrix, address);
            this.activeEncoder.setBytes(PUSH_BINDING, address, 64);
        }
        this.activeEncoder.drawIndexed(RenderEncoder.PRIMITIVE_TRIANGLES,
                this.activeQuadCount * 6, 1, 0, 0, 0);
    }

    public void endPass() {
        if (this.activeEncoder == null) return;
        this.activeEncoder.close();
        this.activeEncoder = null;
        this.backend.submit();
        this.activeQuadCount = 0;
    }

    public void shutdown() {
        if (this.activeEncoder != null) this.activeEncoder.close();
        if (this.pipeline != null) this.pipeline.close();
        if (this.vertexBuffer != null) this.vertexBuffer.free();
        if (this.indexBuffer != null) this.indexBuffer.free();
        this.activeEncoder = null;
        this.pipeline = null;
        this.vertexBuffer = null;
        this.indexBuffer = null;
    }
}
