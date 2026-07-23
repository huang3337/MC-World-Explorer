package com.mcworldexplorer.world;

import java.nio.file.Path;

public class WorldInfo {
    private final Path folderPath;
    private Path iconPath;
    private String levelName;
    private String versionName;
    private GameType gameType = GameType.SURVIVAL;
    private boolean hardcore;
    private long folderCreationTime;
    private boolean folderCreationTimeAvailable;
    private long lastPlayed;
    private long gameTime;
    private boolean parsed;
    private long randomSeed;
    private boolean seedAvailable;
    private int spawnX;
    private int spawnY;
    private int spawnZ;
    private boolean spawnPositionAvailable;
    private double playerX;
    private double playerY;
    private double playerZ;
    private String playerDimension;
    private boolean playerPositionAvailable;
    private int playerRespawnX;
    private int playerRespawnY;
    private int playerRespawnZ;
    private String playerRespawnDimension;
    private boolean playerRespawnPositionAvailable;

    public WorldInfo(Path folderPath) {
        if (folderPath == null) {
            throw new IllegalArgumentException("folderPath must not be null");
        }
        this.folderPath = folderPath;
        this.levelName = folderPath.getFileName() != null
                ? folderPath.getFileName().toString()
                : folderPath.toString();
        this.versionName = "Unknown";
    }

    // Getters and Setters
    public Path getFolderPath() { return folderPath; }

    public Path getIconPath() { return iconPath; }
    public void setIconPath(Path iconPath) { this.iconPath = iconPath; }

    public String getLevelName() { return levelName; }
    public void setLevelName(String levelName) {
        if (levelName != null && !levelName.isBlank()) {
            this.levelName = levelName;
        }
    }

    public String getVersionName() { return versionName; }
    public void setVersionName(String versionName) {
        this.versionName = versionName == null || versionName.isBlank() ? "Unknown" : versionName;
    }

    public GameType getGameType() { return gameType; }
    public void setGameType(GameType gameType) {
        this.gameType = gameType == null ? GameType.SURVIVAL : gameType;
    }

    public boolean isHardcore() { return hardcore; }
    public void setHardcore(boolean hardcore) { this.hardcore = hardcore; }

    public long getFolderCreationTime() { return folderCreationTime; }
    public void setFolderCreationTime(long folderCreationTime) {
        if (folderCreationTime > 0) {
            this.folderCreationTime = folderCreationTime;
            this.folderCreationTimeAvailable = true;
        }
    }
    public boolean isFolderCreationTimeAvailable() { return folderCreationTimeAvailable; }

    public long getLastPlayed() { return lastPlayed; }
    public void setLastPlayed(long lastPlayed) { this.lastPlayed = Math.max(0, lastPlayed); }

    public long getGameTime() { return gameTime; }
    public void setGameTime(long gameTime) { this.gameTime = Math.max(0, gameTime); }

    public boolean isParsed() { return parsed; }
    public void setParsed(boolean parsed) { this.parsed = parsed; }

    public long getRandomSeed() { return randomSeed; }
    public void setRandomSeed(long randomSeed) {
        this.randomSeed = randomSeed;
        this.seedAvailable = true;
    }
    public boolean isSeedAvailable() { return seedAvailable; }

    public int getSpawnX() { return spawnX; }
    public void setSpawnX(int spawnX) { this.spawnX = spawnX; }

    public int getSpawnY() { return spawnY; }
    public void setSpawnY(int spawnY) { this.spawnY = spawnY; }

    public int getSpawnZ() { return spawnZ; }
    public void setSpawnZ(int spawnZ) { this.spawnZ = spawnZ; }

    public void setSpawnPosition(int spawnX, int spawnY, int spawnZ) {
        this.spawnX = spawnX;
        this.spawnY = spawnY;
        this.spawnZ = spawnZ;
        this.spawnPositionAvailable = true;
    }

    public boolean isSpawnPositionAvailable() { return spawnPositionAvailable; }

    public double getPlayerX() { return playerX; }
    public void setPlayerX(double playerX) { this.playerX = playerX; }

    public double getPlayerY() { return playerY; }
    public void setPlayerY(double playerY) { this.playerY = playerY; }

    public double getPlayerZ() { return playerZ; }
    public void setPlayerZ(double playerZ) { this.playerZ = playerZ; }
    public String getPlayerDimension() { return playerDimension; }

    public void setPlayerPosition(double playerX, double playerY, double playerZ) {
        setPlayerPosition(playerX, playerY, playerZ, null);
    }

    public void setPlayerPosition(
            double playerX,
            double playerY,
            double playerZ,
            String playerDimension) {
        this.playerX = playerX;
        this.playerY = playerY;
        this.playerZ = playerZ;
        this.playerDimension = playerDimension;
        this.playerPositionAvailable = true;
    }

    public boolean isPlayerPositionAvailable() { return playerPositionAvailable; }

    public int getPlayerRespawnX() { return playerRespawnX; }
    public int getPlayerRespawnY() { return playerRespawnY; }
    public int getPlayerRespawnZ() { return playerRespawnZ; }
    public String getPlayerRespawnDimension() { return playerRespawnDimension; }

    public void setPlayerRespawnPosition(
            int playerRespawnX,
            int playerRespawnY,
            int playerRespawnZ,
            String playerRespawnDimension) {
        this.playerRespawnX = playerRespawnX;
        this.playerRespawnY = playerRespawnY;
        this.playerRespawnZ = playerRespawnZ;
        this.playerRespawnDimension = playerRespawnDimension;
        this.playerRespawnPositionAvailable = true;
    }

    public boolean isPlayerRespawnPositionAvailable() { return playerRespawnPositionAvailable; }

    @Override
    public String toString() {
        return "WorldInfo{" +
                "folderPath=" + folderPath +
                ", levelName='" + levelName + '\'' +
                ", versionName='" + versionName + '\'' +
                ", gameType=" + gameType +
                ", hardcore=" + hardcore +
                ", parsed=" + parsed +
                ", folderCreationTime=" + folderCreationTime +
                ", lastPlayed=" + lastPlayed +
                ", gameTime=" + gameTime +
                ", randomSeed=" + randomSeed +
                ", spawnPos=(" + spawnX + ", " + spawnY + ", " + spawnZ + ")" +
                ", playerRespawnPos=(" + playerRespawnX + ", " + playerRespawnY + ", " + playerRespawnZ + ")" +
                ", playerRespawnDimension='" + playerRespawnDimension + '\'' +
                ", playerDimension='" + playerDimension + '\'' +
                ", playerPos=(" + playerX + ", " + playerY + ", " + playerZ + ")" +
                '}';
    }
}
