package com.mcworldexplorer.voxel.data;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkRectangleTest {
    @Test
    void createsStableCenteredRangesAndHalo() {
        ChunkRectangle odd = ChunkRectangle.centered(10, -4, 3, 3);
        assertEquals(new ChunkRectangle(9, -5, 11, -3), odd);

        ChunkRectangle even = ChunkRectangle.centered(10, -4, 2, 2);
        assertEquals(new ChunkRectangle(10, -4, 11, -3), even);
        assertEquals(new ChunkRectangle(9, -5, 12, -2), even.expanded(1));

        List<ChunkCoordinate> coordinates = even.coordinates();
        assertEquals(List.of(
                new ChunkCoordinate(10, -4),
                new ChunkCoordinate(11, -4),
                new ChunkCoordinate(10, -3),
                new ChunkCoordinate(11, -3)), coordinates);
    }

    @Test
    void enforcesTargetLimitAndCoordinateOverflow() {
        assertThrows(IllegalArgumentException.class,
                () -> ChunkRectangle.centered(0, 0, 33, 1));
        assertThrows(ArithmeticException.class,
                () -> ChunkRectangle.centered(Integer.MAX_VALUE, 0, 2, 1));
        assertThrows(IllegalArgumentException.class,
                () -> ChunkRectangle.centered(0, 0, 17, 16));
        assertThrows(IllegalArgumentException.class,
                () -> ChunkRectangle.target(Integer.MIN_VALUE, 0, Integer.MIN_VALUE, 0));
        assertEquals(256, ChunkRectangle.centered(0, 0, 16, 16).coordinates().size());
        assertThrows(ArithmeticException.class,
                () -> new ChunkCoordinate(Integer.MAX_VALUE, 0).offset(1, 0));
        assertTrue(ChunkRectangle.target(-2, -3, 5, 4)
                .contains(new ChunkCoordinate(0, 0)));
    }
}
