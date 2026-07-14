package com.mcworldexplorer.preview;

import java.util.Arrays;
import java.util.Optional;

public final class ChunkSurface {
    public static final int WIDTH = 16;
    private static final int COLUMN_COUNT = WIDTH * WIDTH;

    private final ChunkSurfaceLayout layout;
    private final String[] blockNames = new String[COLUMN_COUNT];
    private final int[] heights = new int[COLUMN_COUNT];

    ChunkSurface(ChunkSurfaceLayout layout) {
        this.layout = layout;
        Arrays.fill(heights, Integer.MIN_VALUE);
    }

    public ChunkSurfaceLayout getLayout() {
        return layout;
    }

    public Optional<SurfaceColumn> getColumn(int localBlockX, int localBlockZ) {
        int index = index(localBlockX, localBlockZ);
        String blockName = blockNames[index];
        return blockName == null
                ? Optional.empty()
                : Optional.of(new SurfaceColumn(blockName, heights[index]));
    }

    public int getPopulatedColumnCount() {
        int count = 0;
        for (String blockName : blockNames) {
            if (blockName != null) {
                count++;
            }
        }
        return count;
    }

    boolean hasColumn(int localBlockX, int localBlockZ) {
        return blockNames[index(localBlockX, localBlockZ)] != null;
    }

    void setColumn(int localBlockX, int localBlockZ, String blockName, int y) {
        int index = index(localBlockX, localBlockZ);
        blockNames[index] = blockName;
        heights[index] = y;
    }

    private static int index(int localBlockX, int localBlockZ) {
        if (localBlockX < 0 || localBlockX >= WIDTH || localBlockZ < 0 || localBlockZ >= WIDTH) {
            throw new IndexOutOfBoundsException(
                    "local block coordinates must be between 0 and 15: "
                            + localBlockX + ", " + localBlockZ);
        }
        return localBlockX + localBlockZ * WIDTH;
    }
}
