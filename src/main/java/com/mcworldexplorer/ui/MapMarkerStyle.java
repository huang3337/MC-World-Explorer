package com.mcworldexplorer.ui;

import com.mcworldexplorer.map.MapMarkerType;
import javafx.scene.paint.Color;

import java.util.Objects;

final class MapMarkerStyle {
    static final String PLAYER_HEX = "#44c767";
    static final String SPAWN_HEX = "#f2c94c";
    static final String PORTAL_HEX = "#bd5cff";

    private MapMarkerStyle() {
    }

    static String hex(MapMarkerType type) {
        Objects.requireNonNull(type, "type");
        return switch (type) {
            case PLAYER -> PLAYER_HEX;
            case WORLD_SPAWN -> SPAWN_HEX;
            case NETHER_PORTAL -> PORTAL_HEX;
        };
    }

    static Color color(MapMarkerType type) {
        return Color.web(hex(type));
    }

    static String toggleStyleClass(MapMarkerType type) {
        Objects.requireNonNull(type, "type");
        return switch (type) {
            case PLAYER -> "marker-toggle-player";
            case WORLD_SPAWN -> "marker-toggle-spawn";
            case NETHER_PORTAL -> "marker-toggle-portal";
        };
    }
}
