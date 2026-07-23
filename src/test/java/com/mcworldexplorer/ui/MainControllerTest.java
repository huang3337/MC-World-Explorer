package com.mcworldexplorer.ui;

import com.mcworldexplorer.preview.PreviewCenter;
import com.mcworldexplorer.preview.PreviewCenterSource;
import com.mcworldexplorer.preview.DimensionHeightRange;
import com.mcworldexplorer.preview.PreviewGenerationResult;
import com.mcworldexplorer.preview.PreviewLayer;
import com.mcworldexplorer.preview.PreviewRequest;
import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.world.WorldInfo;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainControllerTest {
    @Test
    void formatsMinecraftTicksAsReadableRealTime() {
        assertEquals("0m", MainController.formatGameTime(0));
        assertEquals("1m", MainController.formatGameTime(1_200));
        assertEquals("1h 1m", MainController.formatGameTime(73_200));
        assertEquals("1d 1h 1m", MainController.formatGameTime(1_801_200));
    }

    @Test
    void clampsNegativeGameTimeToZero() {
        assertEquals("0m", MainController.formatGameTime(-1));
    }

    @Test
    void countsWorldsAcrossDisplayedGroups() {
        WorldInfo first = new WorldInfo(Path.of("first"));
        WorldInfo second = new WorldInfo(Path.of("second"));

        assertEquals(2, MainController.countWorlds(Map.of(
                "Default", List.of(first),
                "Modpack", List.of(second))));
        assertEquals(0, MainController.countWorlds(Map.of()));
    }

    @Test
    void formatsPreviewCompletionStatus() {
        PreviewGenerationResult result = new PreviewGenerationResult(
                new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB),
                new PreviewCenter(12, -34, PreviewCenterSource.WORLD_SPAWN),
                4096,
                3000,
                1094,
                2,
                500_000,
                100);

        assertEquals(
                "已生成，2 个区块失败 · 中心 12, -34 · 3000 个区块",
                MainController.formatPreviewStatus(result));
    }

    @Test
    void hidesPreviewPlaceholderWhenImageIsPresent() {
        assertFalse(MainController.shouldShowPreviewPlaceholder(true));
        assertTrue(MainController.shouldShowPreviewPlaceholder(false));
    }

    @Test
    void formatsDimensionAndLayerInPreviewStatus() {
        PreviewGenerationResult result = new PreviewGenerationResult(
                new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB),
                new PreviewCenter(0, 0, PreviewCenterSource.DIMENSION_ORIGIN),
                4096,
                2000,
                2096,
                0,
                100_000,
                0);
        PreviewRequest request = new PreviewRequest(
                WorldDimension.nether(Path.of("world")),
                result.center(),
                PreviewLayer.heightBand(64, 95));

        assertEquals(
                "已生成 · 下界 · Y 64 - 95 · 中心 0, 0 · 2000 个区块",
                MainController.formatPreviewStatus(result, request));
    }

    @Test
    void mapsSmoothSliderCoordinatesToThirtyTwoBlockBands() {
        DimensionHeightRange range = new DimensionHeightRange(-64, 319);

        assertEquals(PreviewLayer.heightBand(-64, -33),
                MainController.layerForSlider(range, -63.2));
        assertEquals(PreviewLayer.heightBand(64, 95),
                MainController.layerForSlider(range, 70.9));
        assertEquals(PreviewLayer.heightBand(288, 319),
                MainController.layerForSlider(range, 500));
    }

    @Test
    void formatsAndClampsSliderCoordinateLabel() {
        DimensionHeightRange range = new DimensionHeightRange(0, 80);

        assertEquals("Y 70 · 区间 Y 64 - 80",
                MainController.formatLayerSliderLabel(range, 70.8));
        assertEquals("Y 0 · 区间 Y 0 - 31",
                MainController.formatLayerSliderLabel(range, Double.NaN));
    }

    @Test
    void keepsAndRestoresIndependentSliderStateForEachDimension() {
        Path worldPath = Path.of("world");
        WorldDimension overworld = WorldDimension.overworld(worldPath);
        WorldDimension nether = WorldDimension.nether(worldPath);
        MainController.DimensionPreviewStateStore states =
                new MainController.DimensionPreviewStateStore();
        MainController.DimensionPreviewState overworldState =
                new MainController.DimensionPreviewState(
                        new DimensionHeightRange(-64, 319),
                        42,
                        PreviewLayer.heightBand(32, 63));
        MainController.DimensionPreviewState netherState =
                new MainController.DimensionPreviewState(
                        new DimensionHeightRange(0, 255),
                        86,
                        PreviewLayer.heightBand(64, 95));

        states.put(overworld, overworldState);
        states.put(nether, netherState);

        assertEquals(overworldState, states.get(overworld));
        assertEquals(netherState, states.get(nether));
    }

    @Test
    void keepsLastSuccessfulLayerWhileUpdatingPendingSliderCoordinate() {
        PreviewLayer successfulLayer = PreviewLayer.surfaceOverview();
        MainController.DimensionPreviewState state = new MainController.DimensionPreviewState(
                new DimensionHeightRange(-64, 319),
                64,
                successfulLayer);

        MainController.DimensionPreviewState pending = state.withSliderY(-20);

        assertEquals(-20, pending.sliderY());
        assertEquals(successfulLayer, pending.selectedLayer());
    }

    @Test
    void retriesSelectedLayerWhenFailureClearedTheImage() {
        PreviewLayer layer = PreviewLayer.heightBand(64, 95);

        assertFalse(MainController.shouldSkipLayerRequest(layer, layer, false));
        assertTrue(MainController.shouldSkipLayerRequest(layer, layer, true));
    }
}
