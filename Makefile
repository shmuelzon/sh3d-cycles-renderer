VERSION=$(shell git describe --always --tags 2>/dev/null || echo dev)
export VERSION
include versions.mk

# Target platform, detected from the host, or e.g. "make TARGET=linux-x64"
TARGETS=linux-x64 windows-x64 macos-arm64
HOST_SYSTEM:=$(shell uname -s)
ifneq ($(filter MINGW% MSYS% CYGWIN%,$(HOST_SYSTEM)),)
  HOST_OS:=windows
else ifeq ($(HOST_SYSTEM),Darwin)
  HOST_OS:=macos
else
  HOST_OS:=linux
endif
HOST_ARCH:=$(if $(filter arm64 aarch64,$(shell uname -m)),arm64,x64)
TARGET?=$(HOST_OS)-$(HOST_ARCH)
TARGET_OS=$(firstword $(subst -, ,$(TARGET)))

ifeq ($(HOST_OS),windows)
  EXE:=.exe
  CPSEP:=;
  # Paths given to native Windows programs (CMake, Java)
  native_path=$(shell cygpath -m '$1')
else
  EXE:=
  CPSEP:=:
  native_path=$1
endif
JOBS?=$(shell nproc 2>/dev/null || sysctl -n hw.ncpu)

PLUGIN_NAME=CyclesRendererPlugin
PLUGIN_BASENAME=$(PLUGIN_NAME)-$(VERSION)-$(TARGET)
PLUGIN=$(PLUGIN_BASENAME).sh3p
UNIVERSAL_PLUGIN_BASENAME=$(PLUGIN_NAME)-$(VERSION)
UNIVERSAL_PLUGIN=$(UNIVERSAL_PLUGIN_BASENAME).sh3p
SRC_DIR=src/com/shmuelzon/CyclesRenderer
CORE_SRC_DIR=src/com/eteks/sweethome3d/j3d

# Java dependencies: SH3D's jar and the Java 3D and Sunflow jars it bundles (Sunflow for SH3D's own renderer)
SWEET_HOME_JAR=dl/SweetHome3D-$(SWEET_HOME_VERSION).jar
BUNDLED_JARS=dl/j3dcore.jar dl/j3dutils.jar dl/vecmath.jar dl/sunflow-0.07.3i.jar
JAVA_DEPENDENCIES=$(SWEET_HOME_JAR) $(BUNDLED_JARS)
empty:=
space:=$(empty) $(empty)
CLASSPATH=$(subst $(space),$(CPSEP),$(JAVA_DEPENDENCIES))

# Native dependencies
CYCLES_DIR=dl/cycles-$(CYCLES_VERSION)
CYCLES_LIBS_PLATFORM=$(subst -,_,$(TARGET))
# Stamps named after a hash of what they depend on, not their timestamps, which a fresh checkout or a restored CI
# cache doesn't keep consistent
CYCLES_SOURCES=$(CYCLES_DIR)/.downloaded-$(call dependency_hash,,$(CYCLES_PATCHES))
CYCLES_LIBS=$(CYCLES_DIR)/lib/$(CYCLES_LIBS_PLATFORM)/.downloaded-$(call dependency_hash,$(CYCLES_LIBS_INCLUDE),)
BLENDER_DIR=dl/blender-$(BLENDER_VERSION)
ifeq ($(shell git lfs version >/dev/null 2>&1 && echo yes),yes)
  GIT_LFS=
  LFS_PATH=
else
  GIT_LFS=dl/git-lfs-$(GIT_LFS_VERSION)/git-lfs
  LFS_PATH=PATH="$(abspath dl/git-lfs-$(GIT_LFS_VERSION)):$$PATH"
endif

CYCLES_PATCHES=$(sort $(wildcard native/patches/*.patch))

# CI cache keys, which change only when what they cache does (see .github/workflows/main.yml)
SHA1SUM:=$(if $(shell command -v sha1sum),sha1sum,shasum -a 1)
dependency_hash=$(shell { echo '$1'; cat $2 /dev/null; } | $(SHA1SUM) | cut -c1-12)
SWEET_HOME_CACHE_KEY=sweethome3d-$(SWEET_HOME_VERSION)-$(TARGET)
BLENDER_CACHE_KEY=blender-$(BLENDER_VERSION)-$(TARGET)
CYCLES_CACHE_KEY=cycles-$(CYCLES_VERSION)-$(TARGET)-$(call dependency_hash,$(CYCLES_LIBS_INCLUDE),$(CYCLES_PATCHES))
CYCLES_BUILD_CACHE_KEY=cycles-build-$(CYCLES_VERSION)-$(TARGET)-$(call dependency_hash,$(CYCLES_LIBS_INCLUDE) $(CYCLES_CMAKE_OPTIONS),$(CYCLES_PATCHES))

# Native build
NATIVE_DIR=build/native/$(TARGET)
CYCLES_BUILD=$(NATIVE_DIR)/cmake
CYCLES_CONFIGURED=$(CYCLES_BUILD)/.configured-$(call dependency_hash,$(CYCLES_CMAKE_OPTIONS),)
NATIVE_INSTALL=$(NATIVE_DIR)/install
NATIVE_DIST=$(NATIVE_DIR)/dist
NATIVE_SRCS=$(wildcard native/*.cpp native/*.h native/CMakeLists.txt)
ifeq ($(HOST_OS),windows)
  CMAKE_COMPILERS=-DCMAKE_C_COMPILER=cl -DCMAKE_CXX_COMPILER=cl
endif

DOCKER_CMD :=
ifeq ($(wildcard /.dockerenv)$(GITHUB_ACTIONS),)
ifneq ($(shell which docker 2>/dev/null),)
  DOCKER_CMD := docker run $(if $(TERM),-it )--rm --user $(shell id -u):$(shell id -g) --volume $(PWD):$(PWD) --workdir $(PWD) eclipse-temurin:8-noble
endif
endif
JAVAC=$(DOCKER_CMD) javac -encoding ISO-8859-1 -source 1.8 -target 1.8 -Xlint:-options -nowarn

ifneq ($(V)$(RUNNER_DEBUG),)
  Q :=
define exec
	$3
endef
else
  Q := @
define exec
	@echo "$1\\t$2"
	@output=`$3 2>&1` || (echo "$$output"; false)
endef
endif

define download
	$(Q)mkdir -p dl/
	$(call exec,DL,$2,curl -fsSL --retry 3 -o $1 $2)
endef

# Downloads

$(SWEET_HOME_JAR):
	$(call download,$@,https://sourceforge.net/projects/sweethome3d/files/SweetHome3D/SweetHome3D-$(SWEET_HOME_VERSION)/SweetHome3D-$(SWEET_HOME_VERSION).jar/download)

dl/j3dcore.jar dl/j3dutils.jar dl/vecmath.jar: $(SWEET_HOME_JAR)
	$(call exec,UNZIP,$@,unzip -qoj $< java3d-1.6/$(notdir $@) -d dl/)
	$(Q)touch $@

dl/sunflow-0.07.3i.jar: $(SWEET_HOME_JAR)
	$(call exec,UNZIP,$@,unzip -qoj $< $(notdir $@) -d dl/)
	$(Q)touch $@

dl/git-lfs-$(GIT_LFS_VERSION)/git-lfs:
	$(Q)mkdir -p $(dir $@)
ifeq ($(HOST_OS),macos)
	$(call download,dl/git-lfs.zip,https://github.com/git-lfs/git-lfs/releases/download/v$(GIT_LFS_VERSION)/git-lfs-darwin-$(subst x64,amd64,$(HOST_ARCH))-v$(GIT_LFS_VERSION).zip)
	$(call exec,UNZIP,$@,unzip -qoj dl/git-lfs.zip '*/git-lfs' -d $(dir $@))
	$(Q)rm dl/git-lfs.zip
else
	$(call download,dl/git-lfs.tar.gz,https://github.com/git-lfs/git-lfs/releases/download/v$(GIT_LFS_VERSION)/git-lfs-linux-$(subst x64,amd64,$(HOST_ARCH))-v$(GIT_LFS_VERSION).tar.gz)
	$(call exec,UNTAR,$@,tar -xzf dl/git-lfs.tar.gz -C $(dir $@) --strip-components=1)
	$(Q)rm dl/git-lfs.tar.gz
endif

# Cycles' sources, patched to build sh3d-cycles (native/) with its libraries
$(CYCLES_SOURCES):
	$(Q)rm -rf $(CYCLES_DIR)
	$(call exec,GIT,$(CYCLES_DIR),git -c core.autocrlf=false clone --quiet --depth 1 --branch $(CYCLES_VERSION) https://projects.blender.org/blender/cycles.git $(CYCLES_DIR))
	$(Q)for patch in $(CYCLES_PATCHES); do echo "PATCH\t$$patch"; patch --quiet -p1 -d $(CYCLES_DIR) < $$patch || exit 1; done
	$(Q)touch $@

# Blender's precompiled libraries for the target, as pinned by Cycles, only the needed parts
$(CYCLES_LIBS): $(CYCLES_SOURCES) $(GIT_LFS)
	$(Q)git -C $(CYCLES_DIR) config --local submodule.lib/$(CYCLES_LIBS_PLATFORM).update checkout
	$(call exec,GIT,$(dir $@),env GIT_LFS_SKIP_SMUDGE=1 $(LFS_PATH) git -C $(CYCLES_DIR) submodule update --init lib/$(CYCLES_LIBS_PLATFORM))
	$(call exec,LFS,$(dir $@),env $(LFS_PATH) git -C $(dir $@) lfs install --local)
	$(call exec,LFS,$(dir $@),env $(LFS_PATH) git -C $(dir $@) lfs pull --include="$(CYCLES_LIBS_INCLUDE)")
	$(Q)touch $@

# Blender's OpenColorIO configuration, without the ICC display profiles (Git LFS objects, not on GitHub's mirror)
$(BLENDER_DIR)/.downloaded:
	$(Q)rm -rf $(BLENDER_DIR)
	$(call exec,GIT,$(BLENDER_DIR),env GIT_LFS_SKIP_SMUDGE=1 git clone --quiet --depth 1 --filter=blob:none --sparse --branch $(BLENDER_VERSION) https://github.com/blender/blender.git $(BLENDER_DIR))
	$(call exec,GIT,$(BLENDER_DIR),env GIT_LFS_SKIP_SMUDGE=1 MSYS_NO_PATHCONV=1 git -C $(BLENDER_DIR) sparse-checkout set --no-cone release/datafiles/colormanagement/ '!release/datafiles/colormanagement/icc/' release/license/license.md)
	$(Q)touch $@

# Native helper (sh3d-cycles), built with Cycles

$(CYCLES_CONFIGURED): $(CYCLES_LIBS)
	$(call exec,CMAKE,$(CYCLES_BUILD),cmake -S $(call native_path,$(CYCLES_DIR)) -B $(call native_path,$(CYCLES_BUILD)) -G Ninja \
		-DCMAKE_INSTALL_PREFIX=$(call native_path,$(abspath $(NATIVE_INSTALL))) -DSH3D_CYCLES_DIR=$(call native_path,$(abspath native)) \
		$(CMAKE_COMPILERS) $(CYCLES_CMAKE_OPTIONS))
	$(Q)rm -f $(CYCLES_BUILD)/.configured-* && touch $@

$(NATIVE_INSTALL)/sh3d-cycles$(EXE): $(CYCLES_CONFIGURED) $(NATIVE_SRCS)
	$(call exec,BUILD,$(CYCLES_BUILD),cmake --build $(call native_path,$(CYCLES_BUILD)) -j $(JOBS))
	$(Q)rm -rf $(NATIVE_INSTALL)
	$(call exec,INSTALL,$(NATIVE_INSTALL),cmake --install $(call native_path,$(CYCLES_BUILD)))

$(NATIVE_DIST)/files.txt: $(NATIVE_INSTALL)/sh3d-cycles$(EXE) $(BLENDER_DIR)/.downloaded native/package.sh
	$(call exec,PACKAGE,$(NATIVE_DIST),native/package.sh $(TARGET) $(NATIVE_INSTALL) $(BLENDER_DIR) $(NATIVE_DIST) $(dir $(CYCLES_LIBS)))

# Java: the core classes (defined at runtime in SH3D's class loader) and the plugin

CORE_SRCS=$(wildcard $(CORE_SRC_DIR)/*.java)
build/core/.compiled: $(CORE_SRCS) $(JAVA_DEPENDENCIES)
	$(Q)rm -rf build/core && mkdir -p build/core
	$(call exec,JAVA,build/core,$(JAVAC) -classpath "$(CLASSPATH)" -d build/core $(CORE_SRCS))
	$(Q)touch $@

# Stored as .class.bin so that the plugin's class loader never loads them
build/plugin/core/classes.txt: build/core/.compiled
	$(Q)rm -rf build/plugin/core && mkdir -p build/plugin/core
	$(call exec,GEN,build/plugin/core,cd build/core && for f in $$(find . -name '*.class' | LC_ALL=C sort); do \
		mkdir -p ../plugin/core/$$(dirname $$f) && cp $$f ../plugin/core/$$f.bin && echo $$f | sed 's|^\./||; s|\.class$$||; s|/|.|g' >> ../plugin/core/classes.txt; done)

PLUGIN_SRCS=$(wildcard $(SRC_DIR)/*.java)
# Compiled against the core classes, which the plugin defines in SH3D's class loader at runtime
build/.plugin-compiled: $(PLUGIN_SRCS) $(JAVA_DEPENDENCIES) build/core/.compiled
	$(Q)mkdir -p build/plugin
	$(call exec,JAVA,build/plugin,$(JAVAC) -classpath "$(CLASSPATH)$(CPSEP)build/core" -d build/plugin $(PLUGIN_SRCS))
	$(Q)touch $@

build/plugin/%.properties: src/%.properties
	$(Q)mkdir -p $(dir $@)
	$(call exec,GEN,$@,sed 's/$${VERSION}/$(VERSION)/' $< > $@)

build/plugin/native/$(TARGET)/files.txt: $(NATIVE_DIST)/files.txt
	$(Q)rm -rf build/plugin/native && mkdir -p build/plugin/native
	$(call exec,COPY,$(dir $@),cp -R $(NATIVE_DIST) build/plugin/native/$(TARGET))

PLUGIN_FILES=build/.plugin-compiled build/plugin/com/shmuelzon/CyclesRenderer/ApplicationPlugin.properties \
	build/plugin/core/classes.txt build/plugin/native/$(TARGET)/files.txt
$(PLUGIN): check-target $(PLUGIN_FILES)
	$(call exec,JAR,$@,$(DOCKER_CMD) jar -cf $@ -C build/plugin .)

# Universal plugin: the per-target plugins of this version found in the current directory, merged (not rebuilt)
TARGET_PLUGINS=$(wildcard $(PLUGIN_NAME)-$(VERSION)-*.sh3p)
universal:
	$(if $(TARGET_PLUGINS),,$(error No $(PLUGIN_NAME)-$(VERSION)-<target>.sh3p to merge))
	$(Q)rm -rf build/universal && mkdir -p build/universal
	$(Q)for plugin in $(TARGET_PLUGINS); do echo "MERGE\t$$plugin"; (cd build/universal && unzip -qo ../../$$plugin); done
	$(call exec,JAR,$(UNIVERSAL_PLUGIN),$(DOCKER_CMD) jar -cf $(UNIVERSAL_PLUGIN) -C build/universal .)

check-target:
	$(if $(filter $(TARGET),$(TARGETS)),,$(error Unsupported TARGET=$(TARGET), use one of: $(TARGETS)))
	$(if $(filter $(TARGET_OS),$(HOST_OS)),,$(error Can't build TARGET=$(TARGET) on $(HOST_OS), cross-compiling isn't supported))

build: $(PLUGIN)

# Tests: load the plugin with SH3D's plugin manager and render through SH3D's renderer API

build/.test-compiled: $(wildcard test/*.java) $(JAVA_DEPENDENCIES)
	$(Q)mkdir -p build/test
	$(call exec,JAVA,build/test,$(JAVAC) -classpath "$(CLASSPATH)" -d build/test $(wildcard test/*.java))
	$(Q)touch $@

TEST_CLASSPATH=$(call native_path,$(abspath build/test))$(CPSEP)$(subst $(space),$(CPSEP),$(foreach jar,$(JAVA_DEPENDENCIES),$(call native_path,$(abspath $(jar)))))
check: $(PLUGIN) build/.test-compiled
	$(Q)rm -rf build/check && mkdir -p build/check/plugins && cp $(PLUGIN) build/check/plugins/
	$(Q)java -Djava.awt.headless=true -Dj3d.rend=noop -classpath "$(TEST_CLASSPATH)" PluginTest \
		$(call native_path,$(abspath build/check/plugins)) $(call native_path,$(abspath build/check)) $(PROJECT) $(if $(POV),"$(POV)")

ifeq ($(HOST_OS),windows)
  PLUGINS_DIR?=$(APPDATA)/eTeks/Sweet Home 3D/plugins
else ifeq ($(HOST_OS),macos)
  PLUGINS_DIR?=$(HOME)/Library/Application Support/eTeks/Sweet Home 3D/plugins
else
  PLUGINS_DIR?=$(HOME)/.eteks/sweethome3d/plugins
endif

install: $(PLUGIN)
	$(Q)mkdir -p "$(PLUGINS_DIR)"
	$(Q)rm -f "$(PLUGINS_DIR)"/$(PLUGIN_NAME)*.sh3p
	$(call exec,INSTALL,$(PLUGIN),cp $(PLUGIN) "$(PLUGINS_DIR)/")

test: install
	$(Q)java -jar $(SWEET_HOME_JAR) $(PROJECT)

clean:
	$(Q)rm -rf build/plugin build/core build/test build/check build/universal build/.*-compiled *.sh3p

# Also removes the native build, which takes a while to rebuild
mrproper: clean
	$(Q)rm -rf build

distclean: mrproper
	$(Q)rm -rf dl

# Prints a variable, e.g. "make -s print-PLUGIN"
print-%:
	@echo '$($*)'

.DEFAULT_GOAL:=build
.PHONY: build universal check-target check install test clean mrproper distclean
