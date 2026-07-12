package com.mcworldexplorer.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GameTypeTest {
    @Test
    void mapsKnownIdsAndFallsBackToSurvival() {
        assertEquals(GameType.CREATIVE, GameType.fromId(1));
        assertEquals(GameType.SURVIVAL, GameType.fromId(999));
    }
}
