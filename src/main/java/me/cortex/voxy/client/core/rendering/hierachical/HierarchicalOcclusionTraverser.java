package me.cortex.voxy.client.core.rendering.hierachical;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import me.cortex.voxy.client.RenderStatistics;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gpu.BackendType;
import me.cortex.voxy.client.core.gpu.ComputeEncoder;
import me.cortex.voxy.client.core.gpu.ComputePipelineDesc;
import me.cortex.voxy.client.core.gpu.IGpuBuffer;
import me.cortex.voxy.client.core.gpu.IGpuPipeline;
import me.cortex.voxy.client.core.gpu.IGpuSampler;
import me.cortex.voxy.client.core.gpu.RenderBackend;
import me.cortex.voxy.client.core.gpu.RenderBackendFactory;
import me.cortex.voxy.client.core.gpu.SamplerDesc;
import me.cortex.voxy.client.core.metal.MetalBuffer;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.PrintfDebugUtil;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.JomlMemory;
import me.cortex.voxy.common.world.WorldEngine;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL11.GL_UNPACK_ROW_LENGTH;
import static org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_PIXELS;
import static org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_ROWS;
import static org.lwjgl.opengl.GL11.glPixelStorei;
import static org.lwjgl.opengl.GL12.GL_UNPACK_IMAGE_HEIGHT;
import static org.lwjgl.opengl.GL12.GL_UNPACK_SKIP_IMAGES;

// TODO: swap to persistent gpu threads instead of dispatching MAX_ITERATIONS of compute layers
public class HierarchicalOcclusionTraverser {
    public static final boolean HIERARCHICAL_SHADER_DEBUG = System.getProperty("voxy.hierarchicalShaderDebug", "false").equals("true");

    public static final int MAX_REQUEST_QUEUE_SIZE = 50;
    public static final int MAX_QUEUE_SIZE = 200_000;


    private static final int MAX_ITERATIONS = WorldEngine.MAX_LOD_LAYER+1;
    private static final int LOCAL_WORK_SIZE_BITS = 5;
    private static final int LOCAL_WORK_SIZE = 1 << LOCAL_WORK_SIZE_BITS;
    private static final int PUSH_BINDING = 14;
    private static final int REQUEST_FLOOR = parseRequestFloor();

    private final AsyncNodeManager nodeManager;
    private final NodeCleaner nodeCleaner;
    private final RenderGenerationService meshGen;

    private final RenderBackend backend = RenderBackendFactory.get();
    private final IGpuBuffer requestBuffer;

    private final IGpuBuffer nodeBuffer;
    private final IGpuBuffer uniformBuffer = this.backend.createBuffer(1024).zero();
    private final IGpuBuffer statisticsBuffer = this.backend.createBuffer(1024).zero();


    private int topNodeCount;

    public int getTopNodeCount() {
        return this.topNodeCount;
    }
    private final Int2IntOpenHashMap topNode2idxMapping = new Int2IntOpenHashMap();//Used to store mapping from TLN to array index
    private final int[] idx2topNodeMapping = new int[MAX_QUEUE_SIZE];//Used to map idx to TLN id
    private final IGpuBuffer topNodeIds = this.backend.createBuffer(MAX_QUEUE_SIZE*4L).zero();
    private final IGpuBuffer queueMetaBuffer = this.backend.createBuffer(4L*4*MAX_ITERATIONS).zero();
    private final IGpuBuffer scratchQueueA = this.backend.createBuffer(MAX_QUEUE_SIZE*4L).zero();
    private final IGpuBuffer scratchQueueB = this.backend.createBuffer(MAX_QUEUE_SIZE*4L).zero();

    private static int BINDING_COUNTER = 1;
    private static final int SCENE_UNIFORM_BINDING = BINDING_COUNTER++;
    private static final int REQUEST_QUEUE_BINDING = BINDING_COUNTER++;
    private static final int RENDER_QUEUE_BINDING = BINDING_COUNTER++;
    private static final int NODE_DATA_BINDING = BINDING_COUNTER++;
    private static final int NODE_QUEUE_INDEX_BINDING_RESERVED = BINDING_COUNTER++;
    private static final int NODE_QUEUE_META_BINDING = BINDING_COUNTER++;
    private static final int NODE_QUEUE_SOURCE_BINDING = BINDING_COUNTER++;
    private static final int NODE_QUEUE_SINK_BINDING = BINDING_COUNTER++;
    private static final int RENDER_TRACKER_BINDING = BINDING_COUNTER++;
    private static final int STATISTICS_BUFFER_BINDING = BINDING_COUNTER++;

    private static final int HIZ_BINDING = 0;

    private final IGpuSampler hizSampler = this.backend.createSampler(SamplerDesc.builder()
            .filter(SamplerDesc.Filter.NEAREST, SamplerDesc.Filter.NEAREST)
            .mipFilter(SamplerDesc.MipFilter.NEAREST)
            .wrap(SamplerDesc.Wrap.CLAMP_TO_EDGE, SamplerDesc.Wrap.CLAMP_TO_EDGE)
            .label("hizSampler")
            .build());

    private final IGpuPipeline traversal;


    public HierarchicalOcclusionTraverser(AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, RenderGenerationService meshGen) {
        this.nodeCleaner = nodeCleaner;
        this.nodeManager = nodeManager;
        this.meshGen = meshGen;
        this.requestBuffer = this.backend.createBuffer(MAX_REQUEST_QUEUE_SIZE*8L+8).zero();
        this.nodeBuffer = this.backend.createBuffer(nodeManager.maxNodeCount*16L).fill(-1);
        this.traversal = this.backend.createComputePipeline(new ComputePipelineDesc(
                ShaderLoader.parse("voxy:lod/hierarchical/traversal_dev.comp"),
                traversalDefines(), null, null,
                LOCAL_WORK_SIZE, 1, 1,
                "HierarchicalOcclusionTraverser.traversal"));

        this.topNode2idxMapping.defaultReturnValue(-1);
        this.nodeManager.setTLNAddRemoveCallbacks(this::addTLN, this::remTLN);
    }

    private static Map<String, String> traversalDefines() {
        var defines = new LinkedHashMap<String, String>();
        if (HIERARCHICAL_SHADER_DEBUG) defines.put("DEBUG", "");
        defines.put("MAX_ITERATIONS", Integer.toString(MAX_ITERATIONS));
        defines.put("LOCAL_SIZE_BITS", Integer.toString(LOCAL_WORK_SIZE_BITS));
        defines.put("LOCAL_SIZE", Integer.toString(LOCAL_WORK_SIZE));
        defines.put("MAX_REQUEST_QUEUE_SIZE", Integer.toString(MAX_REQUEST_QUEUE_SIZE));
        defines.put("HIZ_BINDING", Integer.toString(HIZ_BINDING));
        defines.put("SCENE_UNIFORM_BINDING", Integer.toString(SCENE_UNIFORM_BINDING));
        defines.put("REQUEST_QUEUE_BINDING", Integer.toString(REQUEST_QUEUE_BINDING));
        defines.put("RENDER_QUEUE_BINDING", Integer.toString(RENDER_QUEUE_BINDING));
        defines.put("NODE_DATA_BINDING", Integer.toString(NODE_DATA_BINDING));
        defines.put("NODE_QUEUE_META_BINDING", Integer.toString(NODE_QUEUE_META_BINDING));
        defines.put("NODE_QUEUE_SOURCE_BINDING", Integer.toString(NODE_QUEUE_SOURCE_BINDING));
        defines.put("NODE_QUEUE_SINK_BINDING", Integer.toString(NODE_QUEUE_SINK_BINDING));
        defines.put("RENDER_TRACKER_BINDING", Integer.toString(RENDER_TRACKER_BINDING));
        defines.put("PUSH_BINDING", Integer.toString(PUSH_BINDING));
        if (RenderStatistics.enabled) {
            defines.put("HAS_STATISTICS", "");
            defines.put("STATISTICS_BUFFER_BINDING", Integer.toString(STATISTICS_BUFFER_BINDING));
        }
        return defines;
    }

    private static int parseRequestFloor() {
        String value = System.getenv("VOXY_HOT_REQUEST_FLOOR");
        if (value == null || value.isBlank()) return 8;
        try {
            return Math.max(0, Math.min(MAX_REQUEST_QUEUE_SIZE, Integer.parseInt(value.trim())));
        } catch (NumberFormatException ignored) {
            return 8;
        }
    }

    private void addTLN(int id) {
        int aid = this.topNodeCount++;//Increment buffer
        if (this.topNodeCount > this.topNodeIds.size()/4) {
            throw new IllegalStateException("Top level node count greater than capacity");
        }

        long ptr = UploadStream.INSTANCE.upload(this.topNodeIds, aid * 4L, 4);
        MemoryUtil.memPutInt(ptr, id);
        UploadStream.INSTANCE.commit();

        if (this.topNode2idxMapping.put(id, aid) != -1) {
            throw new IllegalStateException();
        }
        this.idx2topNodeMapping[aid] = id;
    }

    private void remTLN(int id) {
        //Remove id
        int idx = this.topNode2idxMapping.remove(id);
        //Decrement count
        this.topNodeCount--;
        if (idx == -1) {
            throw new IllegalStateException();
        }

        //Count has already been decremented so is an exact match
        //If we are at the end of the array we dont need to do anything
        if (idx == this.topNodeCount) {
            return;
        }

        //Move the entry at the end to the current index
        int endTLNId = this.idx2topNodeMapping[this.topNodeCount];
        this.idx2topNodeMapping[idx] = endTLNId;//Set the old to the new
        if (this.topNode2idxMapping.put(endTLNId, idx) == -1)
            throw new IllegalStateException();

        long ptr = UploadStream.INSTANCE.upload(this.topNodeIds, idx*4L, 4);
        MemoryUtil.memPutInt(ptr, endTLNId);
        UploadStream.INSTANCE.commit();
    }

    private static void setFrustum(Viewport<?> viewport, long ptr) {
        boolean metal = RenderBackendFactory.get().getType() == BackendType.METAL;
        boolean invalid = metal && (Float.isNaN(viewport.projection.m00())
                || Float.isNaN(viewport.frustumPlanes[0].x));
        for (int i = 0; i < 6; i++) {
            var plane = viewport.frustumPlanes[i];
            if (invalid) {
                MemoryUtil.memPutFloat(ptr, 0.0f);
                MemoryUtil.memPutFloat(ptr + 4, 0.0f);
                MemoryUtil.memPutFloat(ptr + 8, 0.0f);
                MemoryUtil.memPutFloat(ptr + 12, 1.0e30f);
            } else if (metal) {
                float length = (float) Math.sqrt(plane.x * plane.x + plane.y * plane.y + plane.z * plane.z);
                MemoryUtil.memPutFloat(ptr, plane.x);
                MemoryUtil.memPutFloat(ptr + 4, plane.y);
                MemoryUtil.memPutFloat(ptr + 8, plane.z);
                MemoryUtil.memPutFloat(ptr + 12, plane.w + 96.0f * length);
            } else {
                JomlMemory.put(plane, ptr);
            }
            ptr += 4*4;
        }
    }

    private void uploadUniform(Viewport<?> viewport) {
        long ptr = UploadStream.INSTANCE.upload(this.uniformBuffer, 0, 1024);

        JomlMemory.put(viewport.MVP, ptr); ptr += 4*4*4;

        JomlMemory.put(viewport.section, ptr); ptr += 4*3;

        //MemoryUtil.memPutFloat(ptr, viewport.width); ptr += 4;
        MemoryUtil.memPutInt(ptr, viewport.hiZBuffer.getPackedLevels()); ptr += 4;

        JomlMemory.put(viewport.innerTranslation, ptr); ptr += 4*3;

        //MemoryUtil.memPutFloat(ptr, viewport.height); ptr += 4;

        final float screenspaceAreaDecreasingSize = VoxyConfig.CONFIG.subDivisionSize*VoxyConfig.CONFIG.subDivisionSize;
        //Screen space size for descending
        MemoryUtil.memPutFloat(ptr, (float) (screenspaceAreaDecreasingSize) /(viewport.width*viewport.height)); ptr += 4;

        setFrustum(viewport, ptr); ptr += 4*4*6;

        MemoryUtil.memPutInt(ptr, (int) (viewport.getRenderList().size()/4-1)); ptr += 4;

        //VisibilityId
        MemoryUtil.memPutInt(ptr, this.nodeCleaner.visibilityId); ptr += 4;

        {
            final double TARGET_COUNT = 4000;//TODO: make this configurable, or at least dynamically computed based on throughput rate of mesh gen
            double iFillness = Math.max(0, (TARGET_COUNT - this.meshGen.getTaskCount()) / TARGET_COUNT);
            iFillness = Math.pow(iFillness, 2);
            int requestSize = (int) Math.ceil(iFillness * MAX_REQUEST_QUEUE_SIZE);
            if (this.backend.getType() != BackendType.OPENGL) {
                requestSize = Math.max(REQUEST_FLOOR, requestSize);
            }
            MemoryUtil.memPutInt(ptr, Math.max(0, Math.min(MAX_REQUEST_QUEUE_SIZE, requestSize)));ptr += 4;
        }
    }

    public void doTraversal(Viewport<?> viewport) {
        this.uploadUniform(viewport);
        PrintfDebugUtil.bind();

        if (RenderStatistics.enabled) {
            this.statisticsBuffer.zero();
        }

        //Clear the render output counter
        viewport.getRenderList().zeroRange(0, 4);

        //Traverse
        this.traverseInternal(viewport);

        this.downloadResetRequestQueue();

        if (RenderStatistics.enabled) {
            DownloadStream.INSTANCE.download(this.statisticsBuffer, down->{
                for (int i = 0; i < MAX_ITERATIONS; i++) {
                    RenderStatistics.hierarchicalTraversalCounts[i] = MemoryUtil.memGetInt(down.address+i*4L);
                }

                for (int i = 0; i < MAX_ITERATIONS; i++) {
                    RenderStatistics.hierarchicalRenderSections[i] = MemoryUtil.memGetInt(down.address+MAX_ITERATIONS*4L+i*4L);
                }
            });
        }

    }

    private void traverseInternal(Viewport<?> viewport) {
        {
            //Fix mesa bug
            glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
            glPixelStorei(GL_UNPACK_IMAGE_HEIGHT, 0);
            glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
            glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
            glPixelStorei(GL_UNPACK_SKIP_IMAGES, 0);
        }

        int firstDispatchSize = (this.topNodeCount+LOCAL_WORK_SIZE-1)>>LOCAL_WORK_SIZE_BITS;
        {//TODO:FIXME: THIS IS BULLSHIT BY INTEL need to fix the clearing
            long ptr = UploadStream.INSTANCE.upload(this.queueMetaBuffer, 0, 16*MAX_ITERATIONS);
            MemoryUtil.memPutInt(ptr +  0, firstDispatchSize);
            MemoryUtil.memPutInt(ptr +  4, 1);
            MemoryUtil.memPutInt(ptr +  8, 1);
            MemoryUtil.memPutInt(ptr + 12, this.topNodeCount);
            for (int i = 1; i < MAX_ITERATIONS; i++) {
                MemoryUtil.memPutInt(ptr + (i*16)+ 0, 0);
                MemoryUtil.memPutInt(ptr + (i*16)+ 4, 1);
                MemoryUtil.memPutInt(ptr + (i*16)+ 8, 1);
                MemoryUtil.memPutInt(ptr + (i*16)+12, 0);
            }
            UploadStream.INSTANCE.commit();
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            long pushAddress = stack.nmalloc(4);
            for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
                try (ComputeEncoder encoder = this.backend.beginComputePass()) {
                    encoder.setPipeline(this.traversal);
                    encoder.setBuffer(SCENE_UNIFORM_BINDING, this.uniformBuffer, 0);
                    encoder.setBuffer(REQUEST_QUEUE_BINDING, this.requestBuffer, 0);
                    encoder.setBuffer(RENDER_QUEUE_BINDING, viewport.getRenderList(), 0);
                    encoder.setBuffer(NODE_DATA_BINDING, this.nodeBuffer, 0);
                    encoder.setBuffer(NODE_QUEUE_META_BINDING, this.queueMetaBuffer, 0);
                    encoder.setBuffer(RENDER_TRACKER_BINDING, this.nodeCleaner.visibilityBuffer, 0);
                    if (RenderStatistics.enabled) {
                        encoder.setBuffer(STATISTICS_BUFFER_BINDING, this.statisticsBuffer, 0);
                    }
                    encoder.setTexture(HIZ_BINDING, viewport.hiZBuffer.getHizTexture());
                    encoder.setSampler(HIZ_BINDING, this.hizSampler);

                    MemoryUtil.memPutInt(pushAddress, iter);
                    encoder.setBytes(PUSH_BINDING, pushAddress, 4);
                    IGpuBuffer source = iter == 0 ? this.topNodeIds
                            : ((iter & 1) == 0 ? this.scratchQueueA : this.scratchQueueB);
                    IGpuBuffer sink = (iter & 1) == 0 ? this.scratchQueueB : this.scratchQueueA;
                    encoder.setBuffer(NODE_QUEUE_SOURCE_BINDING, source, 0);
                    encoder.setBuffer(NODE_QUEUE_SINK_BINDING, sink, 0);

                    if (iter == 0) {
                        encoder.dispatch(firstDispatchSize, 1, 1);
                    } else {
                        encoder.dispatchIndirect(this.queueMetaBuffer, iter * 16L);
                    }
                }
            }
        }
    }


    private void downloadResetRequestQueue() {
        if (this.backend.getType() == BackendType.METAL && this.requestBuffer instanceof MetalBuffer metalBuffer) {
            this.backend.submit();
            this.forwardDownloadResult(metalBuffer.getContentsPtr(), this.requestBuffer.size());
            this.requestBuffer.zeroRange(0, 4);
            return;
        }
        DownloadStream.INSTANCE.download(this.requestBuffer, this::forwardDownloadResult);
        this.requestBuffer.zeroRange(0, 4);
    }

    private void forwardDownloadResult(long ptr, long size) {
        int count = MemoryUtil.memGetInt(ptr);ptr += 8;//its 8 since we need to skip the second value (which is empty)
        if (count < 0 || count > 50000) {
            Logger.error(new IllegalStateException("Count unexpected extreme value: " + count + " things may get weird"));
            return;
        }
        if (count > (this.requestBuffer.size()>>3)-1) {
            //This should not break the synchonization between gpu and cpu as in the traversal shader is
            // `if (atomRes < REQUEST_QUEUE_SIZE) {` which forcefully clamps to the request size

            //Logger.warn("Count over max buffer size, clamping, got count: " + count + ".");

            count = (int) ((this.requestBuffer.size()>>3)-1);

            //Write back the clamped count
            MemoryUtil.memPutInt(ptr-8, count);
        }
        //if (count > REQUEST_QUEUE_SIZE) {
        //    Logger.warn("Count larger than 'maxRequestCount', overflow captured. Overflowed by " + (count-REQUEST_QUEUE_SIZE));
        //}
        if (count != 0) {
            this.nodeManager.submitRequestBatch(new MemoryBuffer(count*8L+8).cpyFrom(ptr-8));// the -8 is because we incremented it by 8
        }
    }

    public IGpuBuffer getNodeBuffer() {
        return this.nodeBuffer;
    }

    public void free() {
        this.traversal.close();
        this.requestBuffer.free();
        this.nodeBuffer.free();
        this.uniformBuffer.free();
        this.statisticsBuffer.free();
        this.queueMetaBuffer.free();
        this.topNodeIds.free();
        this.scratchQueueA.free();
        this.scratchQueueB.free();
        this.hizSampler.close();
    }
}
