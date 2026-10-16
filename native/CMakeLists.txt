# sh3d-cycles, built inside the Cycles source tree: Cycles' src/app/CMakeLists.txt adds this directory when
# SH3D_CYCLES_DIR is set (see build.sh), so ${LIB} holds the Cycles libraries and their dependencies.

add_executable(sh3d-cycles sh3d_cycles.cpp json.h)
target_include_directories(sh3d-cycles PRIVATE ${CMAKE_SOURCE_DIR}/src)
target_link_libraries(sh3d-cycles PRIVATE ${LIB})
install(TARGETS sh3d-cycles DESTINATION ${CMAKE_INSTALL_PREFIX})
