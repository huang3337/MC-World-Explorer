package com.mcworldexplorer.viewer;

import com.mcworldexplorer.voxel.io.CancellationSignal;
import com.mcworldexplorer.voxel.io.VoxelAreaLoadResult;

/** North-up thumbnail rendered by the 2D map pipeline from raw read-only chunk data. */
public record ViewerAreaThumbnail(int width, int height, int[] argb) {
    public ViewerAreaThumbnail {
        if (width < 1 || height < 1 || width > 160 || height > 100
                || argb == null || argb.length != width * height) {
            throw new IllegalArgumentException("thumbnail must fit within 160 x 100 pixels");
        }
        argb = argb.clone();
    }

    @Override
    public int[] argb() {
        return argb.clone();
    }

    public long estimatedBytes() {
        return (long) argb.length * Integer.BYTES;
    }

    public static ViewerAreaThumbnail fromArea(VoxelAreaLoadResult area, CancellationSignal cancellation) {
        java.awt.image.BufferedImage image = new com.mcworldexplorer.map.MapAreaThumbnailRenderer().render(area, cancellation);
        return new ViewerAreaThumbnail(image.getWidth(), image.getHeight(),
                image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()));
    }
}
