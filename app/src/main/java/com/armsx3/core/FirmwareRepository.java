package com.armsx3.core;

/** Called back by the engine when the PS3 system software is installed or compiled. */
public final class FirmwareRepository {
    public static volatile String installedVersion;

    private FirmwareRepository() {
    }

    public static void onFirmwareInstalled(String version) {
        installedVersion = version;
    }

    public static void onFirmwareCompiled(String version) {
        installedVersion = version;
    }
}
