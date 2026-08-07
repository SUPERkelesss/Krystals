cmake_minimum_required(VERSION 3.22.1)
project(spglib_bridge)

# Prebuilt spglib shared library per ABI from jniLibs. IMPORTED_NO_SONAME is
# REQUIRED: without it CMake records the IMPORTED_LOCATION's absolute build
# path (E:/.../jniLibs/<abi>/libspglib.so) as the DT_NEEDED entry, which the
# Android classloader cannot resolve on-device ("dlopen failed: library
# E:/... not found"). With NO_SONAME the link entry becomes plain "libspglib.so"
# and the loader finds it inside the APK's lib/<abi>/ at runtime.
set(SPGLIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})
add_library(spglib SHARED IMPORTED)
set_target_properties(spglib PROPERTIES
    IMPORTED_LOCATION ${SPGLIB_DIR}/libspglib.so
    IMPORTED_NO_SONAME TRUE
)

add_library(spglib_bridge SHARED spglib_bridge.c)
target_include_directories(spglib_bridge PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/spglib/include ${CMAKE_CURRENT_SOURCE_DIR}/spglib/src)
target_link_libraries(spglib_bridge spglib)
