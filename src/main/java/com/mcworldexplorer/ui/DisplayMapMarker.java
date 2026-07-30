package com.mcworldexplorer.ui;

import com.mcworldexplorer.map.MapMarker;
import com.mcworldexplorer.map.MapMarkerType;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public record DisplayMapMarker(
        MapMarker marker,
        String playerIdentifier,
        String playerName,
        String dimensionName,
        double exactX,
        double exactY,
        double exactZ) {

    public DisplayMapMarker {
        Objects.requireNonNull(marker, "marker");
        if (playerIdentifier != null) {
            if (playerIdentifier.isBlank()
                    || playerName == null || playerName.isBlank()
                    || dimensionName == null || dimensionName.isBlank()
                    || marker.type() != MapMarkerType.PLAYER) {
                throw new IllegalArgumentException("player marker identity must be complete");
            }
            if (!Double.isFinite(exactX)
                    || !Double.isFinite(exactY)
                    || !Double.isFinite(exactZ)) {
                throw new IllegalArgumentException("player marker coordinates must be finite");
            }
        } else if (playerName != null || dimensionName != null) {
            throw new IllegalArgumentException("standard marker cannot contain player identity");
        }
    }

    public static DisplayMapMarker standard(MapMarker marker) {
        return new DisplayMapMarker(marker, null, null, null, 0, 0, 0);
    }

    public static DisplayMapMarker player(
            MapMarker marker,
            String identifier,
            String name,
            String dimensionName,
            double x,
            double y,
            double z) {
        return new DisplayMapMarker(
                marker,
                identifier,
                name,
                dimensionName,
                x,
                y,
                z);
    }

    public boolean isPlayer() {
        return playerIdentifier != null;
    }

    public Optional<String> identifier() {
        return Optional.ofNullable(playerIdentifier);
    }

    public String tooltipText() {
        if (isPlayer()) {
            return String.format(
                    Locale.ROOT,
                    "%s · %s (%s) · X %.1f · Y %.1f · Z %.1f",
                    playerName,
                    dimensionName,
                    marker.dimensionId(),
                    exactX,
                    exactY,
                    exactZ);
        }
        String type = switch (marker.type()) {
            case PLAYER -> "玩家";
            case WORLD_SPAWN -> "世界出生点";
            case NETHER_PORTAL -> "下界传送门";
        };
        return type + " · X " + marker.x() + " · Z " + marker.z();
    }
}
