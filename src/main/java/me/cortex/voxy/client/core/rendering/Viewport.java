package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.client.core.gpu.IGpuBuffer;
import me.cortex.voxy.client.core.rendering.util.DepthFramebuffer;
import me.cortex.voxy.client.core.rendering.util.HiZBuffer;
import me.cortex.voxy.client.core.rendering.util.VoxyFogState;
// TODO: FogParameters removed in Sodium 0.6.x - fog rendering disabled for now
// import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.util.Mth;
import org.joml.*;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;

public abstract class Viewport <A extends Viewport<A>> {
    //public final HiZBuffer2 hiZBuffer = new HiZBuffer2();
    public final HiZBuffer hiZBuffer = new HiZBuffer();
    public final DepthFramebuffer depthBoundingBuffer = new DepthFramebuffer();
    /**
     * Metal-only raw-float copy of {@link #depthBoundingBuffer}. Sampling a
     * depth texture through SPIRV-Cross's texture2d&lt;float&gt; declaration reads
     * zero on Apple GPUs, so the terrain fragment shader consumes this SSBO.
     * Layout: uint width + 12 padding bytes + width*height float depths.
     */
    public IGpuBuffer metalBoundReadBuffer;

    private static final Field planesField;
    static {
        try {
            planesField = FrustumIntersection.class.getDeclaredField("planes");
            planesField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    public int width;
    public int height;
    public int frameId;
    public Matrix4f vanillaProjection = new Matrix4f();
    public Matrix4f projection = new Matrix4f();
    public Matrix4f modelView = new Matrix4f();
    public final FrustumIntersection frustum = new FrustumIntersection();
    public final Vector4f[] frustumPlanes;
    public double cameraX;
    public double cameraY;
    public double cameraZ;
    public VoxyFogState.Snapshot fogState = VoxyFogState.terrain();
    // Disabled for Sodium 0.6.x compatibility - FogParameters no longer exists
    // @Nullable public FogParameters fogParameters;

    public final Matrix4f MVP = new Matrix4f();
    public final Vector3i section = new Vector3i();
    public final Vector3f innerTranslation = new Vector3f();

    protected Viewport() {
        Vector4f[] planes = null;
        try {
             planes = (Vector4f[]) planesField.get(this.frustum);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        this.frustumPlanes = planes;
    }

    public final void delete() {
        this.delete0();
    }

    protected void delete0() {
        this.hiZBuffer.free();
        this.depthBoundingBuffer.free();
        if (this.metalBoundReadBuffer != null) {
            this.metalBoundReadBuffer.free();
            this.metalBoundReadBuffer = null;
        }
    }

    public A setVanillaProjection(Matrix4fc projection) {
        this.vanillaProjection.set(projection);
        return (A) this;
    }

    public A setProjection(Matrix4f projection) {
        this.projection = projection;
        return (A) this;
    }

    public A setModelView(Matrix4f modelView) {
        this.modelView = modelView;
        return (A) this;
    }

    public A setCamera(double x, double y, double z) {
        this.cameraX = x;
        this.cameraY = y;
        this.cameraZ = z;
        return (A) this;
    }

    public A setScreenSize(int width, int height) {
        this.width = width;
        this.height = height;
        return (A) this;
    }

    public A setFogState(VoxyFogState.Snapshot fogState) {
        this.fogState = fogState;
        return (A) this;
    }

    // Disabled for Sodium 0.6.x compatibility - FogParameters no longer exists
    /*
    public A setFogParameters(FogParameters fogParameters) {
        this.fogParameters = fogParameters;
        return (A) this;
    }
    */

    public A update() {
        //MVP
        this.projection.mul(this.modelView, this.MVP);

        //Update the frustum
        this.frustum.set(this.MVP, false);

        //Translation vectors
        int sx = Mth.floor(this.cameraX)>>5;
        int sy = Mth.floor(this.cameraY)>>5;
        int sz = Mth.floor(this.cameraZ)>>5;
        this.section.set(sx, sy, sz);

        this.innerTranslation.set(
                (float) (this.cameraX-(sx<<5)),
                (float) (this.cameraY-(sy<<5)),
                (float) (this.cameraZ-(sz<<5)));

        if (this.depthBoundingBuffer.resize(this.width, this.height)) {
            this.depthBoundingBuffer.clear(0.0f);
        }

        return (A) this;
    }

    public abstract IGpuBuffer getRenderList();
}
