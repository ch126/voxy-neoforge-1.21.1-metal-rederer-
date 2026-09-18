package me.cortex.voxy.client.core.rendering.hierachical;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gpu.ComputeEncoder;
import me.cortex.voxy.client.core.gpu.ComputePipelineDesc;
import me.cortex.voxy.client.core.gpu.IGpuBuffer;
import me.cortex.voxy.client.core.gpu.IGpuPipeline;
import me.cortex.voxy.client.core.gpu.RenderBackend;
import me.cortex.voxy.client.core.gpu.RenderBackendFactory;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import org.lwjgl.opengl.ARBDirectStateAccess;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.util.LinkedHashMap;
import java.util.Map;

/** GPU node-visibility maintenance shared by OpenGL and Metal. */
public class NodeCleaner {
    private static final int SORTING_WORKER_SIZE = 64;
    private static final int WORK_PER_THREAD = 8;
    static final int OUTPUT_COUNT = 256;
    private static final int PUSH_BINDING = 14;

    private static final int SORT_VISIBILITY_BINDING = 1;
    private static final int SORT_OUTPUT_BINDING = 2;
    private static final int SORT_NODE_DATA_BINDING = 3;
    private static final int TRANSFORM_MIN_ID_BINDING = 0;
    private static final int TRANSFORM_NODE_BUFFER_BINDING = 1;
    private static final int TRANSFORM_OUTPUT_BINDING = 2;
    private static final int TRANSFORM_VISIBILITY_BINDING = 3;
    private static final int CLEAR_VISIBILITY_BINDING = 0;
    private static final int CLEAR_LIST_BINDING = 1;

    private final RenderBackend backend = RenderBackendFactory.get();
    private final IGpuPipeline sorter;
    private final IGpuPipeline resultTransformer;
    private final IGpuPipeline batchClear;
    final IGpuBuffer visibilityBuffer;
    private final IGpuBuffer outputBuffer = RenderBackendFactory.get()
            .createBuffer(OUTPUT_COUNT * 4 + OUTPUT_COUNT * 8);
    private final AsyncNodeManager nodeManager;
    int visibilityId;

    public NodeCleaner(AsyncNodeManager nodeManager) {
        this.nodeManager = nodeManager;
        this.visibilityBuffer = this.backend.createBuffer(nodeManager.maxNodeCount * 4L).zero();
        this.visibilityBuffer.fill(-1);
        this.sorter = this.backend.createComputePipeline(new ComputePipelineDesc(
                ShaderLoader.parse("voxy:lod/hierarchical/cleaner/sort_visibility.comp"),
                sorterDefines(), null, null, SORTING_WORKER_SIZE, 1, 1,
                "NodeCleaner.sort_visibility"));
        this.resultTransformer = this.backend.createComputePipeline(new ComputePipelineDesc(
                ShaderLoader.parse("voxy:lod/hierarchical/cleaner/result_transformer.comp"),
                resultTransformerDefines(), null, null, OUTPUT_COUNT, 1, 1,
                "NodeCleaner.result_transformer"));
        this.batchClear = this.backend.createComputePipeline(new ComputePipelineDesc(
                ShaderLoader.parse("voxy:lod/hierarchical/cleaner/batch_visibility_set.comp"),
                batchClearDefines(), null, null, 128, 1, 1,
                "NodeCleaner.batch_visibility_set"));
    }

    private static Map<String, String> sorterDefines() {
        var defines = new LinkedHashMap<String, String>();
        defines.put("WORK_SIZE", Integer.toString(SORTING_WORKER_SIZE));
        defines.put("ELEMS_PER_THREAD", Integer.toString(WORK_PER_THREAD));
        defines.put("OUTPUT_SIZE", Integer.toString(OUTPUT_COUNT));
        defines.put("VISIBILITY_BUFFER_BINDING", Integer.toString(SORT_VISIBILITY_BINDING));
        defines.put("OUTPUT_BUFFER_BINDING", Integer.toString(SORT_OUTPUT_BINDING));
        defines.put("NODE_DATA_BINDING", Integer.toString(SORT_NODE_DATA_BINDING));
        defines.put("PUSH_BINDING", Integer.toString(PUSH_BINDING));
        return defines;
    }

    private static Map<String, String> resultTransformerDefines() {
        var defines = new LinkedHashMap<String, String>();
        defines.put("OUTPUT_SIZE", Integer.toString(OUTPUT_COUNT));
        defines.put("MIN_ID_BUFFER_BINDING", Integer.toString(TRANSFORM_MIN_ID_BINDING));
        defines.put("NODE_BUFFER_BINDING", Integer.toString(TRANSFORM_NODE_BUFFER_BINDING));
        defines.put("OUTPUT_BUFFER_BINDING", Integer.toString(TRANSFORM_OUTPUT_BINDING));
        defines.put("VISIBILITY_BUFFER_BINDING", Integer.toString(TRANSFORM_VISIBILITY_BINDING));
        defines.put("PUSH_BINDING", Integer.toString(PUSH_BINDING));
        return defines;
    }

    private static Map<String, String> batchClearDefines() {
        var defines = new LinkedHashMap<String, String>();
        defines.put("VISIBILITY_BUFFER_BINDING", Integer.toString(CLEAR_VISIBILITY_BINDING));
        defines.put("LIST_BUFFER_BINDING", Integer.toString(CLEAR_LIST_BINDING));
        defines.put("PUSH_BINDING", Integer.toString(PUSH_BINDING));
        return defines;
    }

    public void tick(IGpuBuffer nodeDataBuffer) {
        this.visibilityId++;
        if (!shouldCleanGeometry()) return;
        this.outputBuffer.fill(this.nodeManager.maxNodeCount - 2);
        try (ComputeEncoder encoder = this.backend.beginComputePass()) {
            encoder.setPipeline(this.sorter);
            encoder.setBuffer(SORT_VISIBILITY_BINDING, this.visibilityBuffer, 0);
            encoder.setBuffer(SORT_OUTPUT_BINDING, this.outputBuffer, 0);
            encoder.setBuffer(SORT_NODE_DATA_BINDING, nodeDataBuffer, 0);
            encoder.barrier(ComputeEncoder.BARRIER_SHADER, ComputeEncoder.BARRIER_SHADER);
            int groups = (this.nodeManager.getCurrentMaxNodeId()
                    + SORTING_WORKER_SIZE * WORK_PER_THREAD - 1)
                    / (SORTING_WORKER_SIZE * WORK_PER_THREAD);
            encoder.dispatch(groups, 1, 1);

            encoder.setPipeline(this.resultTransformer);
            encoder.setBuffer(TRANSFORM_MIN_ID_BINDING, this.outputBuffer, 0);
            encoder.setBuffer(TRANSFORM_NODE_BUFFER_BINDING, nodeDataBuffer, 0);
            encoder.setBuffer(TRANSFORM_OUTPUT_BINDING, this.outputBuffer, 4L * OUTPUT_COUNT);
            encoder.setBuffer(TRANSFORM_VISIBILITY_BINDING, this.visibilityBuffer, 0);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                long address = stack.nmalloc(4);
                MemoryUtil.memPutInt(address, this.visibilityId);
                encoder.setBytes(PUSH_BINDING, address, 4);
            }
            encoder.barrier(ComputeEncoder.BARRIER_SHADER, ComputeEncoder.BARRIER_SHADER);
            encoder.dispatch(1, 1, 1);
            encoder.barrier(ComputeEncoder.BARRIER_SHADER, ComputeEncoder.BARRIER_TRANSFER);
        }
        DownloadStream.INSTANCE.download(this.outputBuffer, 4 * OUTPUT_COUNT, 8 * OUTPUT_COUNT,
                buffer -> this.nodeManager.submitRemoveBatch(buffer.copy()));
    }

    private boolean shouldCleanGeometry() {
        return this.nodeManager.getGeometryCapacity() - this.nodeManager.getUsedGeometryCapacity()
                < 256_000_000;
    }

    public void updateIds(IntOpenHashSet collection) {
        if (collection.isEmpty()) return;
        int count = collection.size();
        long offset = (UploadStream.INSTANCE.rawUploadAddress(count * 4 + 16) + 15) & ~15L;
        long pointer = UploadStream.INSTANCE.getBaseAddress() + offset;
        var iterator = collection.iterator();
        while (iterator.hasNext()) {
            MemoryUtil.memPutInt(pointer, iterator.nextInt());
            pointer += 4;
        }
        UploadStream.INSTANCE.commit();

        try (ComputeEncoder encoder = this.backend.beginComputePass()) {
            encoder.setPipeline(this.batchClear);
            encoder.setBuffer(CLEAR_VISIBILITY_BINDING, this.visibilityBuffer, 0);
            encoder.setBuffer(CLEAR_LIST_BINDING, UploadStream.INSTANCE.getUploadBuffer(), offset, count * 4L);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                long address = stack.nmalloc(8);
                MemoryUtil.memPutInt(address, count);
                MemoryUtil.memPutInt(address + 4, this.visibilityId);
                encoder.setBytes(PUSH_BINDING, address, 8);
            }
            encoder.barrier(ComputeEncoder.BARRIER_SHADER, ComputeEncoder.BARRIER_SHADER);
            encoder.dispatch((count + 127) / 128, 1, 1);
            encoder.barrier(ComputeEncoder.BARRIER_SHADER, ComputeEncoder.BARRIER_SHADER);
        }
    }

    @SuppressWarnings("unused")
    private void dumpDebugData() {
        int[] output = new int[OUTPUT_COUNT * 3];
        ARBDirectStateAccess.glGetNamedBufferSubData(this.outputBuffer.id(), 0, output);
        int[] visibility = new int[(int) (this.visibilityBuffer.size() / 4)];
        ARBDirectStateAccess.glGetNamedBufferSubData(this.visibilityBuffer.id(), 0, visibility);
    }

    public void free() {
        this.sorter.close();
        this.visibilityBuffer.free();
        this.outputBuffer.free();
        this.batchClear.close();
        this.resultTransformer.close();
    }
}
