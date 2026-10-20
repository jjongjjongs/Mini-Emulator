package net.rpcsx;

// Told by the engine when a PS3 firmware (PS3UPDAT.PUP) has been installed and compiled.
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
