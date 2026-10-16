# sh3d-cycles: native Cycles helper

A small C++ program, linked with the Cycles standalone libraries (Apache-2.0). It renders the scene exported by
the plugin's `CyclesSceneExporter` (`scene.json`, `meshes.bin`, `textures/`, `camera.json`) and writes the resulting
image, with progressive previews while rendering.

- [`sh3d_cycles.cpp`](sh3d_cycles.cpp): scene building, device selection, exposure, tone mapping and progressive
  previews.
  - Materials become Principled BSDF, thin glass, mirrors or emission.
  - SH3D's lights become point and mesh lights with the same flux.
  - The sun and sky are SunFlow's and YafaRay's: a sun lamp of their irradiance, from SunFlow's sun model, and a
    Preetham sky of turbidity 6, or the sky texture of a virtual visit, or night.
  - Images use a fixed exposure and the Standard view (sRGB clipped at 1), as SH3D's other renderers. Automatic
    exposure, AgX (as Blender's), looks and a local compression of the highlights (e.g. the outdoors seen through
    windows) are options.
- [`json.h`](json.h): minimal JSON reader.
- [`CMakeLists.txt`](CMakeLists.txt): the executable's target. The Makefile hooks this directory into Cycles'
  `src/app/CMakeLists.txt`, so the target links with Cycles' libraries and their dependencies.
- [`package.sh`](package.sh): collects the executable and what it needs at runtime into the directory that the plugin
  packages as `native/<target>/`. That covers the shared libraries, Blender's `colormanagement/`, Cycles' kernel
  sources (`source/`, for GPU kernels compiled on first use) and the licenses.

## Building

The [Makefile](../Makefile) builds it, as part of the plugin:

1. It checks out Cycles (`CYCLES_VERSION` in [versions.mk](../versions.mk)) to `dl/`, and applies
   [patches/](patches/):
   - one hooks this directory into Cycles' build;
   - the other updates the display more often in background renders, for the photo dialog's progressive preview.
2. It fetches only the needed parts of the Blender precompiled libraries that Cycles pins
   (`CYCLES_LIBS_INCLUDE`, about 260 MB of the 2.2 GB of Git LFS content).
3. It configures Cycles with `CYCLES_CMAKE_OPTIONS` and builds it with Ninja to `build/native/<target>/`.

The first build takes about 10 minutes on 12 cores; later ones only rebuild `sh3d-cycles`.

## Running

```
sh3d-cycles <export dir> <output.png> [--samples 256] [--device auto|cpu|hip|hip+cpu] [--width W --height H]
            [--auto-exposure off|on] [--exposure EV] [--noise-threshold 0.01] [--highlight-compression 0]
            [--view Standard|AgX] [--look None|Medium_High_Contrast...]
            [--sky-strength auto|S] [--sky-saturation 1] [--night-sky 0.02] [--progress] [--log-level info]
```

- **Output on stdout:**
  - `STATUS <status>` lines when Cycles' status changes, e.g. `Loading render kernels...` or
    `12 / 80 render kernels loaded...` while GPU kernels are compiled (the plugin then shows an image saying so, with the
    progress, until the first preview);
  - `PROGRESS <0..1> <sample>` lines;
  - `PREVIEW <file>` with `--progress` (tone-mapped PNG, rewritten a few times a second, on its own thread);
  - a final `RESULT {json}` line.
- **Devices:** `sh3d-cycles --list-devices` prints a `DEVICE <type> <description>` line for each device Cycles can
  render with. When rendering with a GPU fails (e.g. no kernels for it, or no compiler to build them), the image is
  rendered with the CPU.
- **Session reuse:** the same Cycles session renders the auto-exposure metering pass and then the final image,
  so the scene, BVH and kernels are loaded once.

## Notes on Cycles' API

Things that differ from Blender's defaults or aren't obvious, handled by `sh3d-cycles`:

| Behavior | How it's handled |
|---|---|
| Cycles' `default_light` shader has an emission strength of 0 | Lights get their own white emission shader of strength 1, as in Blender |
| `EmissionNode`'s default strength is 10 | Strengths are always set explicitly |
| The `__builtin_srgb` color space shows 8-bit textures too bright | Textures use the `auto` color space |
| Cycles reads its OCIO config only from `$OCIO` | It's set to the bundled config |
| Output driver updates are skipped in headless sessions | Sessions with `--progress` aren't headless |
| Enabling the denoiser for a later render of a session doesn't take effect | The auto-exposure metering pass is denoised too |
| Blender's precompiled Embree is built with SYCL support | `libsycl` and `libur_loader` are shipped with it |
| Adaptive sampling measures the noise before the color management exposure | The automatic exposure is the film's exposure |
| Objects spanning the whole scene slow rendering down | Meshes are merged by item and material, not by material only |
