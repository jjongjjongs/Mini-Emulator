package xendroid.hardware;
// From XenDroid (https://github.com/rfandango/XenDroid, tag XenDroid-0b11201): the JNI class its libe.so binds to.

// Created by xendroid on 2025/7/21.
// SPDX-License-Identifier: WTFPL
public class ProcessorInfo {
    static {
        java.lang.System.loadLibrary("hardware_ProcessorInfo");
    }
    public static native String gpu_get_physical_device_name_vk();
}
