# Versions of everything downloaded to dl/ and of the native build options. CI caches dl/ and the native (Cycles)
# build directory per target and per content of this file, so changing anything here rebuilds them from scratch.

SWEET_HOME_VERSION=7.5
# Cycles standalone (https://projects.blender.org/blender/cycles), with Blender's precompiled libraries it pins
CYCLES_VERSION=v5.2.0
# Blender, only for its OpenColorIO configuration (AgX view transform and looks)
BLENDER_VERSION=v5.2.2
# Used when git-lfs isn't installed (Linux and macOS)
GIT_LFS_VERSION=3.6.1

# Parts of Blender's precompiled libraries (Git LFS) needed for this build, out of about 2.2 GB. Patterns are case
# sensitive, and directory names differ between platforms (e.g. OpenImageIO on Windows)
CYCLES_LIBS_INCLUDE=abseil/**,aom/**,brotli/**,deflate/**,eigen/**,embree/**,epoxy/**,fmt/**,freetype/**,hiprt/**,\
imath/**,jpeg/**,level-zero/**,openimagedenoise/**,openimageio/**,OpenImageIO/**,opencolorio/**,openexr/**,openjpeg/**,\
openjph/**,openpgl/**,png/**,pthreads/**,pugixml/**,pystring/**,sse2neon/**,tbb/**,tiff/**,webp/**,xml2/**,zlib/**,zstd/**,\
dpcpp/include/**,dpcpp/lib/libsycl.so*,dpcpp/lib/libur_*.so*,dpcpp/lib/sycl*.lib,dpcpp/bin/sycl*.dll,dpcpp/bin/ur_*.dll

# Cycles build options: CPU rendering, HIP/Metal host support (kernels aren't built yet), no OSL/USD/volumes
CYCLES_CMAKE_OPTIONS=-DCMAKE_BUILD_TYPE=Release \
	-DWITH_CYCLES_OSL=OFF -DWITH_CYCLES_USD=OFF -DWITH_CYCLES_HYDRA_RENDER_DELEGATE=OFF \
	-DWITH_CYCLES_ALEMBIC=OFF -DWITH_CYCLES_OPENVDB=OFF -DWITH_CYCLES_NANOVDB=OFF -DWITH_CYCLES_OPENSUBDIV=OFF \
	-DWITH_CYCLES_DEVICE_CUDA=OFF -DWITH_CYCLES_DEVICE_OPTIX=OFF -DWITH_CYCLES_DEVICE_ONEAPI=OFF \
	-DWITH_CYCLES_STANDALONE_GUI=OFF
