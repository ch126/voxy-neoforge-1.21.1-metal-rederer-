# Voxy NeoForge Metal (Experimental)

**Experimental Voxy Metal backend port for Minecraft 1.21.1, NeoForge 21.1.x, Java 21 and Apple Silicon.**

This repository is an experimental port of Voxy's Apple Silicon Metal rendering backend to Minecraft 1.21.1 and NeoForge 21.1.x. It combines the NeoForge 1.21.1 port of Voxy with the GPU abstraction, Metal renderer, JNI bindings and IOSurface interoperability developed by the Voxy M-series support project.

**Current target:**
- Minecraft 1.21.1
- NeoForge 21.1.x
- Java 21
- Sodium 0.8.13
- Apple Silicon, tested on Apple M5 Pro
- Metal rendering for Voxy distant LODs
- OpenGL/Metal interoperability through IOSurface

The Java integration, Metal native library loading, JNI initialization, IOSurface compositing and Metal LOD draw submission have been validated in a real Minecraft instance. This remains an experimental development build and is not an official Voxy release.

## 中文说明

将 Voxy 的 Apple Silicon Metal 渲染后端移植到 Minecraft 1.21.1 + NeoForge 21.1.x。项目包含 GPU 抽象层、Metal 后端、JNI 原生桥接以及 IOSurface OpenGL/Metal 互操作，已在 Apple M5 Pro 上完成实机验证。本项目属于实验性移植，并非 Voxy 官方版本。

---

# Voxy NeoForge 1.21.1

> **Unofficial NeoForge port** of the Voxy mod

## Special Thanks

**All credit for Voxy goes to [MCRcortex](https://github.com/MCRcortex)**, the original author and creator of this incredible LOD rendering mod.

- **Original Repository:** [MCRcortex/voxy](https://github.com/MCRcortex/voxy)
- **Original Author:** [MCRcortex](https://github.com/MCRcortex)

This repository is a community port to NeoForge 1.21.1, created because the original author has indicated they will not be backporting to this version. We are deeply grateful for MCRcortex's work on Voxy.

## License Notice

The original Voxy mod is licensed under **All Rights Reserved** by MCRcortex. This port is provided for personal use. Please respect the original author's licensing terms.

---

## About

**Voxy** is a Level-of-Detail (LOD) rendering mod for Minecraft that extends your view distance far beyond vanilla limits by rendering distant terrain at lower detail levels.

## Why This Port?

You might wonder: "Why not just use the Fabric version with [Sinytra Connector](https://github.com/Sinytra/Connector)?"

| Aspect | Native NeoForge Port (this repo) | Sinytra Connector |
|--------|----------------------------------|-------------------|
| **Performance** | No translation overhead | Runtime translation layer |
| **Mod Integration** | Native NeoForge API calls | Fabric API emulation via FFAPI |
| **Maintenance** | Must track upstream Voxy changes | Just drop in Fabric jar |
| **Stability** | Tested against NeoForge directly | May have edge cases from translation |
| **Dependencies** | Forgified Fabric API | Connector + Forgified Fabric API |

**Bottom line:** For a performance-critical LOD mod like Voxy, eliminating the translation layer overhead is worthwhile. If you prioritize simplicity and don't mind potential overhead, Sinytra Connector is a valid alternative.

## Status

**Alpha** - Functional with known limitations.

### Working Features
- LOD terrain rendering beyond vanilla render distance
- Smooth transitions between LOD and vanilla chunks
- Fog integration (disabled at LOD boundaries)
- Block model baking for all render types (solid, cutout, cutout_mipped, translucent)
- Delayed chunk unloading to prevent pop-out effects

### Current Limitations
- Requires Sodium 0.6.13+ (NeoForge version)
- Some optional integrations not yet ported (Iris, Nvidium, Vivecraft)
- Debug screen integration disabled (MC 1.21.1 API changes)

## Requirements

### Required Dependencies

| Dependency | Version | Link |
|------------|---------|------|
| Minecraft | 1.21.1 | - |
| NeoForge | 21.1.x | [NeoForge](https://neoforged.net/) |
| Sodium | mc1.21.1-0.6.13-neoforge | [Modrinth](https://modrinth.com/mod/sodium/version/mc1.21.1-0.6.13-neoforge) |
| Forgified Fabric API | 0.116.7+2.2.0+1.21.1 | [Modrinth](https://modrinth.com/mod/forgified-fabric-api/version/0.116.7+2.2.0+1.21.1) |

### Recommended Dependencies

| Dependency | Purpose | Link |
|------------|---------|------|
| Reese's Sodium Options | Better settings UI for Sodium + Voxy config access | [Modrinth](https://modrinth.com/mod/reeses-sodium-options) |
| Lithium | General performance improvements | [Modrinth](https://modrinth.com/mod/lithium) |

## Installation

> **Note:** Due to Voxy's ARR (All Rights Reserved) license, compiled JARs are not distributed. You must build from source.

1. Install NeoForge for Minecraft 1.21.1
2. Install required dependencies (see above)
3. Build Voxy from source (see below)
4. Place the built JAR in your `mods` folder

## Building from Source

```bash
git clone https://github.com/j-shelfwood/voxy-neoforge.git
cd voxy-neoforge
./gradlew build
```

The built JAR will be in `build/libs/`.

## Contributing

For development guidelines, see [CLAUDE.md](CLAUDE.md).

### Validation Scripts

The `scripts/` directory contains build validation tools used in CI.

## Links

- **Original Voxy:** [github.com/MCRcortex/voxy](https://github.com/MCRcortex/voxy)
- **This Port:** [github.com/j-shelfwood/voxy-neoforge](https://github.com/j-shelfwood/voxy-neoforge)
- **Sinytra Connector (alternative):** [github.com/Sinytra/Connector](https://github.com/Sinytra/Connector)
