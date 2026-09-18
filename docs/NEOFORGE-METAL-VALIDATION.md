# NeoForge 1.21.1 Metal validation

Validated on 2026-09-18 with:

- Apple M5 Pro / macOS arm64
- Temurin Java 21.0.12.1
- Minecraft 1.21.1
- NeoForge 21.1.248
- Sodium 0.6.13

## Build and native library

- `./gradlew build` succeeds and produces `build/libs/voxy-0.2.9-alpha.jar`.
- `native/metal/build.sh Release` builds the arm64 JNI dylib using the Xcode
  Command Line Tools fallback when CMake is unavailable. Full Xcode is not
  required by the current runtime-MSL design.
- The rebuilt bundled dylib passes the render-pass, vertex/indirect, ICB,
  IOSurface, triangle, and compute smoke tests. Compute output is 64/64.
- Shader smoke tests pass 34/34 SPIR-V and 33/34 MSL cases. The sole expected
  MSL gap is the old subgroup HiZ shader, which the active Metal path does not
  use.

## Full client validation

Run command:

```bash
VOXY_FORCE_METAL=1 VOXY_METAL_DIAGNOSTICS=1 \
  ./gradlew runClient -PquickPlayWorld='New WorldVox用Metal' --console=plain
```

Observed in a real integrated-server world:

- The bundled JNI library loads and selects the Apple M5 Pro Metal device.
- The IOSurface bridge composites into Minecraft's main render target.
- Voxy streams real persisted sections and issues non-zero opaque and
  translucent LOD draws (representative stable counts: 292/30).
- The loaded-chunk depth mask contains non-zero depth data and removes the
  near-terrain/LOD boundary overlap.
- Normal air rendering remained stable for thousands of frames without black
  output or major corruption.
- Walking from land into the ocean switched the captured NeoForge fog state
  from `air/off` to water fog (`start=-8`, end adapting toward 96 blocks),
  while translucent LOD draws remained active. The underwater image retained
  the water surface and seabed without far-terrain X-ray or missing regions.
- Respawning switched the fog state back to `air/off`. The complete movement,
  submersion, death, and respawn run exceeded 29,000 frames without a crash.

## Known non-blocking limitations

- Metal MDIC currently uses the reference branch's force-visible fallback for
  fine section occlusion. Frustum/HOT selection and chunk-bound masking remain
  active, so this is primarily a performance limitation.
- Temporal real HiZ is opt-in with `VOXY_METAL_REAL_HIZ=1`; the default avoids
  a full-frame GL depth readback.
- Metal SSAO and Iris/Oculus shader-pack integration are not part of the
  validated default pipeline.
- Apple OpenGL prints a one-time `gldCopyBufferSubData: NEEDS IMPLEMENTATION`
  diagnostic. It did not prevent streaming, drawing, or long-running stability.

No Sodium 0.8 renderer backport was required. The Sodium 0.6.13 render hook,
buffer model, and frame timing are sufficient for the backend-neutral Voxy
renderer used here.
