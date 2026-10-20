package net.rpcsx;

public final class GameInfo {
    public final String path;
    public final String name;
    public final String iconPath;
    public final int flags;

    public GameInfo(String path, String name, String iconPath, int flags) {
        this.path = path;
        this.name = name;
        this.iconPath = iconPath;
        this.flags = flags;
    }
}
