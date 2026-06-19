package com.mcworldexplorer.world;

import java.nio.file.Path;

public class WorldInfo {
    private Path folderPath;
    private Path iconPath;
    
    private String levelName;
    private String versionName;
    private int gameType;
    private boolean hardcore;
    private long lastPlayed;
    private long gameTime;
    
    private long randomSeed;
    private int spawnX, spawnY, spawnZ;
    private double playerX, playerY, playerZ;
    
    public WorldInfo(Path folderPath) {
        this.folderPath = folderPath;
    }

    // Getters and Setters
    public Path getFolderPath() { return folderPath; }
    
    public Path getIconPath() { return iconPath; }
    public void setIconPath(Path iconPath) { this.iconPath = iconPath; }

    public String getLevelName() { return levelName; }
    public void setLevelName(String levelName) { this.levelName = levelName; }

    public String getVersionName() { return versionName; }
    public void setVersionName(String versionName) { this.versionName = versionName; }

    public int getGameType() { return gameType; }
    public void setGameType(int gameType) { this.gameType = gameType; }

    public boolean isHardcore() { return hardcore; }
    public void setHardcore(boolean hardcore) { this.hardcore = hardcore; }

    public long getLastPlayed() { return lastPlayed; }
    public void setLastPlayed(long lastPlayed) { this.lastPlayed = lastPlayed; }

    public long getGameTime() { return gameTime; }
    public void setGameTime(long gameTime) { this.gameTime = gameTime; }

    public long getRandomSeed() { return randomSeed; }
    public void setRandomSeed(long randomSeed) { this.randomSeed = randomSeed; }

    public int getSpawnX() { return spawnX; }
    public void setSpawnX(int spawnX) { this.spawnX = spawnX; }

    public int getSpawnY() { return spawnY; }
    public void setSpawnY(int spawnY) { this.spawnY = spawnY; }

    public int getSpawnZ() { return spawnZ; }
    public void setSpawnZ(int spawnZ) { this.spawnZ = spawnZ; }

    public double getPlayerX() { return playerX; }
    public void setPlayerX(double playerX) { this.playerX = playerX; }

    public double getPlayerY() { return playerY; }
    public void setPlayerY(double playerY) { this.playerY = playerY; }

    public double getPlayerZ() { return playerZ; }
    public void setPlayerZ(double playerZ) { this.playerZ = playerZ; }

    @Override
    public String toString() {
        return "WorldInfo{" +
                "levelName='" + levelName + '\'' +
                ", versionName='" + versionName + '\'' +
                ", gameType=" + gameType +
                ", hardcore=" + hardcore +
                '}';
    }
}
