# Contributing Guidelines

All types of contributions are encouraged and valued. Please make sure to read
relevant sections in this file and in README before making your contribution. It
will make it a lot easier for us maintainers and smooth out the experience for
all involved. This project looks forward to your contributions :tada:

## How to contribute

See [Building](README.md#building) for the requirements of each platform.

* Run `make` to build the plugin for the host platform
* Run `make check` to load the plugin with Sweet Home 3D's plugin manager and
  render test images, saved in `build/check/`
* Run `make test` to install the plugin and start SH3D
* Run `make install` to install the plugin to SH3D's plugins directory
* Run `make clean` to remove the Java build and any `.sh3p` files,
  `make mrproper` to also remove the native build, and `make distclean` to also
  remove the `dl` directory

### Project layout

* `src/com/shmuelzon/CyclesRenderer/` - The plugin, loaded by SH3D's plugin
  manager. It defines the classes below in SH3D's class loader, registers the
  renderer and adds its settings panel (`SettingsPanel`) to the Tools menu
* `src/com/eteks/sweethome3d/j3d/` - The renderer (`CyclesRenderer`), its
  scene exporter (`CyclesSceneExporter`) and its settings, saved in the project
  (`CyclesRendererSettings`), in SH3D's package to use the package-private
  helpers of its renderers
* `native/` - `sh3d-cycles`, the native program rendering exported scenes with
  Cycles, built inside Cycles' source tree. See [native/README.md](native/README.md)
* `test/` - `PluginTest` (`make check`) and `PhotoDialogTest`, which opens SH3D's
  *Create photo* dialog, selects Cycles and renders (needs a display):
  `java -Dj3d.rend=noop -Dcom.eteks.sweethome3d.no3D=true -cp "build/test:dl/*" PhotoDialogTest build/check/plugins build/check [project.sh3d ["level/point of view"]]`.
  `SettingsPanelTest` opens the settings panel, renders a preview and checks the
  settings are saved in the project (needs a display too):
  `java -Dj3d.rend=noop -Dcom.eteks.sweethome3d.no3D=true -cp "build/test:dl/*" SettingsPanelTest build/check/plugins build/check [project.sh3d ["level/point of view" [HH:MM]]]`.
  `RenderScenes` renders points of view of a project with a given renderer, to
  compare renderers:
  `java -Djava.awt.headless=true -cp "build/test:dl/*" RenderScenes build/check/plugins <output dir> <project.sh3d> <width> <height> <renderer class> <name>:<level>/<point of view>:<HH:MM>:<on|off|light name>...`
  (`_` for spaces in names, `on` to turn the lights on, or a light's name to
  turn only it on, as the floor plan plugin does)
* `native/patches/` - Patches applied to Cycles' sources: building `sh3d-cycles`
  with its libraries, and more frequent display updates for the progressive
  preview
* `doc/` - Images used by the README

### Continuous integration

The [CI](.github/workflows/main.yml) builds and tests each target on its own
runner, then merges the per-target plugins into the universal one. Its
artifacts are named after the plugin files they contain.

It caches the external dependencies, each keyed on its own version only (from
[versions.mk](versions.mk), see `make print-<NAME>_CACHE_KEY`), so a cache only
changes when its dependency is updated. `sh3d-cycles` and the Java code are
always built.

| Cache | Contents | Key |
|---|---|---|
| Sweet Home 3D | `dl/SweetHome3D-*`, the jars it bundles | `SWEET_HOME_VERSION` |
| Blender | `dl/blender-*`, its color management configuration | `BLENDER_VERSION` |
| Cycles | `dl/cycles-*`, Cycles' sources and the parts of its precompiled libraries used | `CYCLES_VERSION`, `CYCLES_LIBS_INCLUDE`, [native/patches](native/patches/) |
| Cycles build | `build/native/<target>/cmake`, the compiled Cycles libraries, without `sh3d-cycles` | The above and `CYCLES_CMAKE_OPTIONS` |

### On Windows Systems

The Windows plugin has to be built on Windows, from Git Bash, with Visual
Studio's C++ compiler. WSL builds the Linux plugin.

## Submitting  Pull Request

* Ensure any install or build dependencies are working and the code is
  generating the right output before submitting a PR
* Update the README.md with details of changes to the interface, this includes
  new fields, features, etc.
* Promptly address any CI failures. If your pull request fails to build or pass
  tests, please push another commit to fix it
* Resolve any merge conflicts that occur
