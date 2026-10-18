if (NOT PURPLE_QT_ANDROID)
    message(FATAL_ERROR "PURPLE_QT_ANDROID is not set: point it at a Qt 6 Android prefix (see local.properties).")
endif()

add_library(qt6core SHARED IMPORTED)
set_target_properties(qt6core PROPERTIES
        IMPORTED_LOCATION ${PURPLE_QT_ANDROID}/lib/libQt6Core_${ANDROID_ABI}.so
        INTERFACE_INCLUDE_DIRECTORIES "${PURPLE_QT_ANDROID}/include;${PURPLE_QT_ANDROID}/include/QtCore")

add_library(purplecore SHARED
        purple_jni/jni_bridge.cpp
        purple_jni/account_sync_bridge.cpp
        purple_jni/config_sync_bridge.cpp
        purple/purple/purple_passcode.cpp
        purple/purple/purple_settings.cpp
        purple/purple/purple_splice.cpp
        purple/purple/purple_state.cpp
        purple/purple/purple_config_sync.cpp
        purple/purple/purple_config_diff.cpp
        purple/purple/purple_config_payload.cpp
        purple/purple/purple_sync_json.cpp
        purple/purple/purple_sync_envelope.cpp
        purple/purple/purple_sync_directory.cpp
        purple/purple/purple_sync_inventory.cpp
        purple/purple/purple_sync_config_flow.cpp
        purple/purple/purple_sync_config_describe.cpp
        purple/purple/purple_sync_local_state.cpp
        purple/purple/purple_sync_status.cpp
        purple/purple/purple_screentime.cpp
        purple/purple/purple_engine.cpp)

target_compile_options(purplecore PRIVATE -std=c++20 -Oz)

target_include_directories(purplecore PRIVATE
        purple
        purple/tomlplusplus)

target_link_options(purplecore PRIVATE "-Wl,-z,max-page-size=16384")
target_link_options(purplecore PRIVATE "-Wl,-z,common-page-size=16384")
target_link_options(purplecore PRIVATE "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/purple_jni/exports.map")

target_link_libraries(purplecore
        qt6core
        log)

add_custom_command(TARGET purplecore POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
                ${PURPLE_QT_ANDROID}/lib/libQt6Core_${ANDROID_ABI}.so
                $<TARGET_FILE_DIR:purplecore>/libQt6Core_${ANDROID_ABI}.so)
