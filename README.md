# Voxy NeoForge Metal

> **Experimental Apple Silicon Metal backend port** of Voxy for Minecraft 1.21.1 + NeoForge 21.1.x

This repository is an experimental port of Voxy's Apple Silicon **Metal** rendering backend to Minecraft 1.21.1 and NeoForge 21.1.x. It combines the community NeoForge 1.21.1 port of Voxy with the GPU abstraction layer, Metal renderer, JNI bindings and IOSurface interoperability originally developed by the Voxy M-series support project.

The Java integration, Metal native library loading, JNI initialization, IOSurface compositing and Metal LOD draw submission have been validated in a real Minecraft instance. **This remains an experimental development build and is not an official Voxy release.**

## 中文说明

将 Voxy 的 Apple Silicon Metal 渲染后端移植到 Minecraft 1.21.1 + NeoForge 21.1.x。项目包含 GPU 抽象层、Metal 后端、JNI 原生桥接以及 IOSurface OpenGL/Metal 互操作，已在 Apple M5 Pro 上完成实机验证。本项目属于实验性移植，并非 Voxy 官方版本。

---

## Current Target

| Component | Version |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.x |
| Java | 21 |
| Sodium | 0.8.13 (NeoForge) |
| Platform | Apple Silicon (tested on Apple M5 Pro) |

**Rendering path:** Metal is used to render Voxy's distant LOD terrain, with OpenGL/Metal interoperability handled through IOSurface compositing.

## Status

**Experimental / Alpha** — functional on real hardware, but still under active development.

### Validated
- Metal native library loading and JNI bridge initialization
- IOSurface-based OpenGL ↔ Metal compositing
- Metal LOD draw submission and frustum/HiZ culling
- LOD terrain rendering beyond vanilla render distance on Apple Silicon
- Sodium 0.8.13 runtime compatibility

### Known Limitations
- macOS / Apple Silicon only — no Windows/Linux Metal path (those platforms should use upstream Voxy's normal OpenGL renderer)
- Some optional integrations not yet ported (Iris, Nvidium, Vivecraft)
- Debug screen integration disabled (MC 1.21.1 API changes)
- Expect rough edges typical of an experimental native-backend port

See [`docs/STATUS.md`](docs/STATUS.md), [`docs/NEOFORGE-METAL-VALIDATION.md`](docs/NEOFORGE-METAL-VALIDATION.md) and [`docs/M-SERIES-PORT-STATE.md`](docs/M-SERIES-PORT-STATE.md) for detailed, up-to-date progress notes.

## Requirements

| Dependency | Version | Link |
|---|---|---|
| Minecraft | 1.21.1 | - |
| NeoForge | 21.1.x | [neoforged.net](https://neoforged.net/) |
| Sodium | 0.8.13 (NeoForge) | [Modrinth](https://modrinth.com/mod/sodium) |
| Forgified Fabric API | latest compatible with 1.21.1 | [Modrinth](https://modrinth.com/mod/forgified-fabric-api) |

### Recommended

| Dependency | Purpose |
|---|---|
| Reese's Sodium Options | Better settings UI, Sodium + Voxy config access |
| Lithium | General performance improvements |

## Building from Source

> Due to Voxy's ARR (All Rights Reserved) upstream license, compiled JARs are not distributed. You must build from source.

```bash
git clone https://github.com/ch126/voxy-neoforge-1.21.1-metal-rederer-.git
cd voxy-neoforge-1.21.1-metal-rederer-
./gradlew build
```

The built JAR will be in `build/libs/`. Building/loading the Metal native library additionally requires Xcode Command Line Tools on macOS — see [`docs/METAL-MIGRATION.md`](docs/METAL-MIGRATION.md).

## Testing

See [`TESTING.md`](TESTING.md) for a full setup walkthrough (Prism Launcher instance creation, Java/JVM configuration, mod installation).

## Documentation

| Doc | Contents |
|---|---|
| [`docs/M-SERIES-PORT-OVERVIEW.md`](docs/M-SERIES-PORT-OVERVIEW.md) | High-level overview of the Apple Silicon / Metal port |
| [`docs/M-SERIES-PORT-STATE.md`](docs/M-SERIES-PORT-STATE.md) | Current implementation state |
| [`docs/METAL-MIGRATION.md`](docs/METAL-MIGRATION.md) | Migration notes from OpenGL to Metal |
| [`docs/VX-CONTRACT-METAL-DESIGN.md`](docs/VX-CONTRACT-METAL-DESIGN.md) | GPU abstraction / contract design |
| [`docs/NEOFORGE-METAL-VALIDATION.md`](docs/NEOFORGE-METAL-VALIDATION.md) | Real-instance validation results |
| [`docs/LOD-FLICKER-INVESTIGATION.md`](docs/LOD-FLICKER-INVESTIGATION.md) | LOD flicker root-cause investigation |
| [`docs/WATER-LOD-FIX-HANDOFF.md`](docs/WATER-LOD-FIX-HANDOFF.md) | Water LOD fix handoff notes |
| [`docs/MIGRATION-HISTORY.md`](docs/MIGRATION-HISTORY.md) | Full migration/change history |
| [`docs/STATUS.md`](docs/STATUS.md) | Overall project status |
| [`CLAUDE.md`](CLAUDE.md) | Development guidelines for AI-assisted contributions |

## Why a Native NeoForge Port?

The base NeoForge port (before the Metal backend) exists because upstream Voxy targets Fabric and the original author has indicated no plans to backport to NeoForge. Rather than relying on a Fabric-to-Forge translation layer like [Sinytra Connector](https://github.com/Sinytra/Connector), this project ports Voxy natively:

| Aspect | Native NeoForge Port (this repo) | Sinytra Connector |
|---|---|---|
| Performance | No translation overhead | Runtime translation layer |
| Mod Integration | Native NeoForge API calls | Fabric API emulation via FFAPI |
| Maintenance | Must track upstream Voxy changes | Just drop in Fabric jar |
| Stability | Tested directly against NeoForge | May have translation edge cases |

## Special Thanks

**All credit for Voxy goes to [MCRcortex](https://github.com/MCRcortex)**, the original author and creator of this LOD rendering mod, and to the Voxy M-series support project for the original Metal/JNI/IOSurface backend work this port builds on.

- **Original Voxy repository:** [github.com/MCRcortex/voxy](https://github.com/MCRcortex/voxy)

## License Notice

The original Voxy mod is licensed under **All Rights Reserved** by MCRcortex. This port is provided for personal, non-commercial use. Please respect the original author's licensing terms. No compiled binaries are distributed from this repository.
