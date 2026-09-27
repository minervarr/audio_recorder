cmake_minimum_required(VERSION 3.22.1)
project(recorder_core)

# app/src/main/cpp is four levels under the repo root.
set(REPO ${CMAKE_CURRENT_SOURCE_DIR}/../../../..)
set(AE_ROOT ${REPO}/audioengine)
set(VK_CANVAS_DIR ${REPO}/framework/vk_canvas CACHE PATH "" FORCE)
set(APP_SHELL_DIR ${REPO}/framework/app_shell)

set(APP_SHELL_APP_NAME "audio_recorder" CACHE STRING "" FORCE)
set(APP_SHELL_WINDOW_TITLE "Audio Recorder" CACHE STRING "" FORCE)
set(APP_SHELL_APP_ID "audio_recorder" CACHE STRING "" FORCE)

set(NATIVE_APP_GLUE ${ANDROID_NDK}/sources/android/native_app_glue)
set(APP_SHELL_NATIVE_APP_GLUE_DIR ${NATIVE_APP_GLUE} CACHE PATH "" FORCE)

set(AUDIO_ENGINE_BUILD_DECODERS ON CACHE BOOL "" FORCE)
add_subdirectory(${AE_ROOT} ${CMAKE_BINARY_DIR}/audio_engine)

add_subdirectory(${VK_CANVAS_DIR}/core ${CMAKE_BINARY_DIR}/vk_canvas_core)
add_subdirectory(${APP_SHELL_DIR} ${CMAKE_BINARY_DIR}/app_shell)

set(VCE_SLANGC "/opt/shader-slang/bin/slangc" CACHE FILEPATH "" FORCE)
set(SHADER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../assets/shaders)
include(${VK_CANVAS_DIR}/cmake/VceShaders.cmake)
set(FONT_SHADERS ${VK_CANVAS_DIR}/first_party/vulkan_font_engine/shaders_src)
vce_compile_slang(recorder_shaders_font ${SHADER_DIR} ${FONT_SHADERS}
    composite_vert composite_frag tiling coverage
    msdf_vert msdf_frag glyph_area glyph_resolve)
vce_compile_slang(recorder_shaders_canvas ${SHADER_DIR} ${VK_CANVAS_DIR}/shaders_src
    overlay_vert overlay_frag image_vert image_frag shape_vert shape_frag)

# ui_metrics.hh includes this from the build tree. The number is font geometry
# measured on the host; the NDK build copies a checked-in result.
file(MAKE_DIRECTORY ${CMAKE_BINARY_DIR}/generated)
configure_file(${REPO}/platform/android/ui_min_text_size.gen.h
               ${CMAKE_BINARY_DIR}/generated/ui_min_text_size.gen.h COPYONLY)

add_library(recorder_core SHARED
    session.cc
    ${AE_ROOT}/backends/aaudio/aaudio_source.cpp
    ${AE_ROOT}/backends/aaudio/aaudio_sink.cpp
    ${REPO}/gui/recorder_view.cc
    ${REPO}/platform/android/bridge.cc
    ${REPO}/platform/android/android_main.cc
    ${NATIVE_APP_GLUE}/android_native_app_glue.c
    ${VK_CANVAS_DIR}/platform/android/android_platform.cc)

target_include_directories(recorder_core PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${REPO}/gui
    ${AE_ROOT}/backends/aaudio
    ${AE_ROOT}/backends/flac
    ${AE_ROOT}/core/include
    ${NATIVE_APP_GLUE}
    ${VK_CANVAS_DIR}/platform/android)

target_compile_features(recorder_core PRIVATE cxx_std_17)
target_link_libraries(recorder_core PRIVATE
    ae_usb aaudio log android vulkan
    app_shell_android vk_canvas_core)

if(TARGET ae_flac)
    target_link_libraries(recorder_core PRIVATE ae_flac)
    target_compile_definitions(recorder_core PRIVATE RECORDER_HAVE_FLAC=1)
endif()

add_dependencies(recorder_core recorder_shaders_font recorder_shaders_canvas)

# NativeActivity looks this symbol up by name. Nothing in the app calls it,
# so the linker would otherwise drop the glue.
target_link_options(recorder_core PRIVATE -u ANativeActivity_onCreate)
