cmake_minimum_required(VERSION 3.22.1)
project(gba_emulator_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Locate pinned mGBA submodule (ADR-0001).
get_filename_component(REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
set(MGBA_SOURCE_DIR "${REPO_ROOT}/third_party/mgba")

if(NOT EXISTS "${MGBA_SOURCE_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "Pinned mGBA submodule not found at '${MGBA_SOURCE_DIR}'.\n"
        "Run: git submodule update --init --recursive"
    )
endif()

# Configure unmodified upstream mGBA for Libretro-only build (zero desktop deps).
set(BUILD_LIBRETRO ON CACHE BOOL "" FORCE)
set(SKIP_LIBRARY ON CACHE BOOL "" FORCE)
set(BUILD_QT OFF CACHE BOOL "" FORCE)
set(BUILD_SDL OFF CACHE BOOL "" FORCE)
set(BUILD_PERF OFF CACHE BOOL "" FORCE)
set(BUILD_SUITE OFF CACHE BOOL "" FORCE)
set(BUILD_EXAMPLE OFF CACHE BOOL "" FORCE)
set(BUILD_PYTHON OFF CACHE BOOL "" FORCE)
set(BUILD_CINEMA OFF CACHE BOOL "" FORCE)
set(BUILD_GL OFF CACHE BOOL "" FORCE)
set(BUILD_GLES2 OFF CACHE BOOL "" FORCE)
set(BUILD_GLES3 OFF CACHE BOOL "" FORCE)
set(DISABLE_DEPS ON CACHE BOOL "" FORCE)
set(USE_SQLITE3 OFF CACHE BOOL "" FORCE)
set(USE_ZLIB OFF CACHE BOOL "" FORCE)
set(USE_PNG OFF CACHE BOOL "" FORCE)
set(USE_LIBZIP OFF CACHE BOOL "" FORCE)
set(USE_FFMPEG OFF CACHE BOOL "" FORCE)
set(USE_ELF OFF CACHE BOOL "" FORCE)
set(USE_LZMA OFF CACHE BOOL "" FORCE)
set(USE_EDITLINE OFF CACHE BOOL "" FORCE)
set(USE_GDB_STUB OFF CACHE BOOL "" FORCE)
set(USE_DISCORD_RPC OFF CACHE BOOL "" FORCE)
set(USE_LUA OFF CACHE BOOL "" FORCE)
set(M_CORE_GBA ON CACHE BOOL "" FORCE)
set(M_CORE_GB ON CACHE BOOL "" FORCE)

add_subdirectory("${MGBA_SOURCE_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/mgba_build" EXCLUDE_FROM_ALL)

# Android's APK packager only packages shared libraries prefixed with "lib".
# Set PREFIX externally so third_party/mgba MPL-2.0 files remain completely unmodified.
if(TARGET mgba_libretro)
    set_target_properties(mgba_libretro PROPERTIES PREFIX "lib")
else()
    message(FATAL_ERROR "Expected CMake target 'mgba_libretro' was not created by third_party/mgba.")
endif()

# Platform-agnostic C++17 Libretro frontend engine.
add_library(gba_core_engine STATIC
    core/LibretroLoader.cpp
    core/LibretroFrontend.cpp
)

target_include_directories(gba_core_engine
    PUBLIC
        "${CMAKE_CURRENT_SOURCE_DIR}/core"
        "${MGBA_SOURCE_DIR}/src/platform/libretro"
)

target_compile_options(gba_core_engine PRIVATE
    -Wall
    -Wextra
    -Wpedantic
)

find_package(Threads REQUIRED)
target_link_libraries(gba_core_engine
    PUBLIC
        Threads::Threads
        ${CMAKE_DL_LIBS}
)

if(ANDROID)
    find_library(LOG_LIB log REQUIRED)
    find_library(ANDROID_LIB android REQUIRED)
    find_library(EGL_LIB EGL REQUIRED)
    find_library(GLES3_LIB GLESv3 REQUIRED)
    find_library(AAUDIO_LIB aaudio REQUIRED)

    add_library(gba_frontend SHARED
        video/GlVideoRenderer.cpp
        audio/AAudioOutput.cpp
        jni/NativeEmulatorJni.cpp
    )

    target_include_directories(gba_frontend PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}"
        "${MGBA_SOURCE_DIR}/src/platform/libretro"
    )

    target_compile_options(gba_frontend PRIVATE
        -Wall
        -Wextra
        -Wpedantic
    )

    # Ensure Gradle always builds libmgba_libretro.so alongside libgba_frontend.so.
    add_dependencies(gba_frontend mgba_libretro)

    target_link_libraries(gba_frontend
        PRIVATE
            gba_core_engine
            ${LOG_LIB}
            ${ANDROID_LIB}
            ${EGL_LIB}
            ${GLES3_LIB}
            ${AAUDIO_LIB}
    )
else()
    option(GBA_BUILD_HOST_TESTS "Build host unit & mGBA core integration tests" ON)
    if(GBA_BUILD_HOST_TESTS)
        enable_testing()
        add_executable(gba_native_tests
            tests/native_tests.cpp
        )
        add_dependencies(gba_native_tests mgba_libretro)
        target_compile_options(gba_native_tests PRIVATE
            -Wall
            -Wextra
            -Wpedantic
        )
        target_compile_definitions(gba_native_tests PRIVATE
            GBA_LIBRETRO_CORE_PATH="$<TARGET_FILE:mgba_libretro>"
        )
        target_link_libraries(gba_native_tests
            PRIVATE
                gba_core_engine
        )
        add_test(NAME gba_native_tests COMMAND gba_native_tests)
    endif()
endif()
