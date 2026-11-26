package com.armsx3.core;

/** A game the engine found (it constructs these and hands them to GameRepository.add). */
public final class GameInfo {
    public final String path;
    public String name;
    public String iconPath;
    public int gameFlags;

    public GameInfo(String path, String name, String iconPath, int gameFlags) {
        this.path = path;
        this.name = name;
        this.iconPath = iconPath;
        this.gameFlags = gameFlags;
    }

    public String getPath() {
        return this.path;
    }

    public String getName() {
        return this.name;
    }

    public String getIconPath() {
        return this.iconPath;
    }

    public int getGameFlags() {
        return this.gameFlags;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setIconPath(String iconPath) {
        this.iconPath = iconPath;
    }

    public void setGameFlags(int gameFlags) {
        this.gameFlags = gameFlags;
    }
}
