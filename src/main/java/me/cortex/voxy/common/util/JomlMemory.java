package me.cortex.voxy.common.util;

import org.joml.Matrix4fc;
import org.joml.Vector3fc;
import org.joml.Vector3ic;
import org.joml.Vector4fc;
import org.lwjgl.system.MemoryUtil;

/**
 * Writes JOML values to native memory without JOML's Unsafe-backed MemUtil.
 * NeoForge's transforming module loader can invalidate JOML 1.10.5's field
 * offset self-test on Java 21; the public component getters remain stable.
 */
public final class JomlMemory {
    private JomlMemory() {}

    public static void put(Matrix4fc matrix, long address) {
        MemoryUtil.memPutFloat(address,      matrix.m00());
        MemoryUtil.memPutFloat(address + 4,  matrix.m01());
        MemoryUtil.memPutFloat(address + 8,  matrix.m02());
        MemoryUtil.memPutFloat(address + 12, matrix.m03());
        MemoryUtil.memPutFloat(address + 16, matrix.m10());
        MemoryUtil.memPutFloat(address + 20, matrix.m11());
        MemoryUtil.memPutFloat(address + 24, matrix.m12());
        MemoryUtil.memPutFloat(address + 28, matrix.m13());
        MemoryUtil.memPutFloat(address + 32, matrix.m20());
        MemoryUtil.memPutFloat(address + 36, matrix.m21());
        MemoryUtil.memPutFloat(address + 40, matrix.m22());
        MemoryUtil.memPutFloat(address + 44, matrix.m23());
        MemoryUtil.memPutFloat(address + 48, matrix.m30());
        MemoryUtil.memPutFloat(address + 52, matrix.m31());
        MemoryUtil.memPutFloat(address + 56, matrix.m32());
        MemoryUtil.memPutFloat(address + 60, matrix.m33());
    }

    public static void put(Vector3fc vector, long address) {
        MemoryUtil.memPutFloat(address, vector.x());
        MemoryUtil.memPutFloat(address + 4, vector.y());
        MemoryUtil.memPutFloat(address + 8, vector.z());
    }

    public static void put(Vector3ic vector, long address) {
        MemoryUtil.memPutInt(address, vector.x());
        MemoryUtil.memPutInt(address + 4, vector.y());
        MemoryUtil.memPutInt(address + 8, vector.z());
    }

    public static void put(Vector4fc vector, long address) {
        MemoryUtil.memPutFloat(address, vector.x());
        MemoryUtil.memPutFloat(address + 4, vector.y());
        MemoryUtil.memPutFloat(address + 8, vector.z());
        MemoryUtil.memPutFloat(address + 12, vector.w());
    }
}
