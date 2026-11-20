package com.mcworldexplorer.ui;

import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.storage.SavedPlaces;
import com.mcworldexplorer.storage.SavedPlaces.Position;
import com.mcworldexplorer.storage.SavedPlaces.Link;
import com.mcworldexplorer.storage.SavedPlaces.Endpoint;
import com.mcworldexplorer.storage.SavedPlaces.LineData;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Dialog;
import javafx.scene.paint.Color;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

final class SavedPlacesMenu {
    private final MapViewport viewport;
    private final Menu destinations;
    private final SavedPlacesPanel panel;
    private final Function<String, WorldDimension> findDimension;
    private final Consumer<Position> navigate;
    private final Consumer<String> status;
    private final ContextMenu popup = new ContextMenu();
    private SavedPlaces store;
    private Path folder;
    private WorldDimension dimension;
    private Map<Position, String> places = Map.of();
    private Map<Link, LineData> lines = Map.of();
    private String readError;

    SavedPlacesMenu(MapViewport viewport, Menu destinations, SavedPlacesPanel panel, Function<String, WorldDimension> findDimension,
                    Consumer<Position> navigate, Consumer<String> status) {
        this.viewport = viewport;
        this.destinations = destinations;
        this.panel = panel;
        this.findDimension = findDimension;
        this.navigate = navigate;
        this.status = status;
        panel.setOnVisit(navigate);
        destinations.setOnShowing(event -> refresh());
        popup.setAutoHide(true);
        popup.setConsumeAutoHidingEvents(true);
        viewport.setOnBlockMenuRequested(this::show);
        viewport.placeLines.setOnComplete(this::connect);
    }

    void refresh() {
        Map<Position, String> previous = places;
        String previousError = readError;
        reload();
        if (!places.equals(previous) || !java.util.Objects.equals(previousError, readError)) updateDestinations();
    }

    void setWorld(Path path) {
        panel.reset();
        setDimension(null);
        folder = path;
        store = null;
        places = Map.of();
        lines = Map.of();
        readError = null;
        if (path != null) reload();
        updateDestinations();
    }

    void setDimension(WorldDimension current) {
        popup.hide();
        viewport.placeLines.cancel();
        dimension = current;
        drawPlaces();
    }

    private void reload() {
        if (folder == null) return;
        try {
            if (store == null) store = new SavedPlaces(folder);
            var snapshot = store.snapshot();
            places = snapshot.places();
            lines = snapshot.lines();
            readError = null;
            drawPlaces();
        } catch (IOException | SecurityException failure) {
            readError = "地点读取失败：" + failure.getMessage();
        }
    }

    private void drawPlaces() {
        viewport.setNamedPlaces(dimension == null ? Map.of() : places.entrySet().stream()
                .filter(entry -> entry.getKey().dimension().equals(dimension.id()))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
        viewport.placeLines.setLines(dimension == null ? Map.of() : lines.entrySet().stream()
                .filter(entry -> entry.getKey().a().dimension().equals(dimension.id()))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
    }

    private void updateDestinations() {
        var rows = new java.util.ArrayList<SavedPlacesPanel.Row>();
        destinations.getItems().clear();
        if (readError != null) destinations.getItems().add(label(readError));
        if (places.isEmpty()) {
            panel.setPlaces(rows, readError);
            if (readError == null) destinations.getItems().add(label("还没有地点，可在地图上右键标记"));
            return;
        }
        SavedPlaces source = store;
        places.entrySet().stream().sorted(Map.Entry.<Position, String>comparingByValue()
                .thenComparing(entry -> entry.getKey().dimension())
                .thenComparingInt(entry -> entry.getKey().x()).thenComparingInt(entry -> entry.getKey().z()))
                .forEach(entry -> {
                    Position place = entry.getKey();
                    WorldDimension target = findDimension.apply(place.dimension());
                    String dimensionName = target == null ? place.dimension() + "（不可用）" : target.displayName();
                    String text = entry.getValue() + " · " + description(dimensionName, place);
                    rows.add(new SavedPlacesPanel.Row(place, text, target != null));
                    MenuItem item = action(text, () -> {
                        if (store == source && places.containsKey(place)) navigate.accept(place);
                    });
                    item.setDisable(target == null);
                    destinations.getItems().add(item);
                });
        panel.setPlaces(rows, readError);
    }

    private void show(MapViewport.BlockClick click) {
        if (dimension == null) return;
        refresh();
        if (click.line() != null && lines.containsKey(click.line()) && readError == null) {
            popup.hide();
            popup.getItems().setAll(lineItems(click.line()));
            popup.show(viewport.getScene().getWindow(), click.screenX(), click.screenY());
            return;
        }
        var hit = click.marker();
        Position place;
        if (hit == null) {
            if (click.x() != (int) click.x() || click.z() != (int) click.z()) {
                status.accept("坐标超出可标记范围");
                return;
            }
            place = new Position(dimension.id(), (int) click.x(), (int) click.z());
        } else {
            place = hit.position();
            if (!place.dimension().equals(dimension.id())) return;
        }
        popup.hide();
        popup.getItems().setAll(itemsFor(place, hit == null ? null : hit.original()));
        // The map itself is outside the menu. A node owner would exclude clicks on that node from auto-hide.
        popup.show(viewport.getScene().getWindow(), click.screenX(), click.screenY());
    }

    private List<MenuItem> itemsFor(Position place, DisplayMapMarker original) {
        String name = places.get(place);
        String detail = description(dimension.displayName(), place);
        String suggested = original == null ? null : original.placeName();
        String title = original == null ? name : suggested;
        var items = new java.util.ArrayList<MenuItem>();
        items.add(label(title == null ? detail : title + " · " + detail));
        items.add(new SeparatorMenuItem());
        SavedPlaces source = store;
        if (readError != null) {
            items.add(label(readError));
        } else {
            List<MenuItem> customActions = items;
            if (original != null && name != null) {
                Menu custom = new Menu("同位置的自定义标记 · " + name);
                items.add(custom);
                customActions = custom.getItems();
            }
            String saveAction = name != null ? "重命名…" : original == null ? "标记此处…" : "在此创建自定义标记…";
            customActions.add(action(saveAction, () -> {
                if (!current(source, place)) return;
                askName(name == null ? "标记此处" : "重命名地点", detail, name == null ? suggested : name)
                        .ifPresent(value -> change(source, place, name, value));
            }));
            if (name != null) customActions.add(action("删除标记…", () -> {
                if (!current(source, place)) return;
                Alert dialog = new Alert(Alert.AlertType.CONFIRMATION, detail, ButtonType.CANCEL, ButtonType.OK);
                dialog.initOwner(viewport.getScene().getWindow());
                dialog.setTitle("删除自定义标记");
                long count = lines.entrySet().stream()
                        .filter(entry -> entry.getValue().usesCustomPlace(entry.getKey(), place)).count();
                dialog.setHeaderText("删除「" + name + "」？" + (count == 0 ? "" : "同时移除 " + count + " 条连线。"));
                if (dialog.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
                    change(source, place, name, null);
                }
            }));
        }
        items.add(action("复制坐标", () -> {
            var content = new ClipboardContent();
            String coordinates = BlockCoordinates.format(place.x(), place.z());
            content.putString(coordinates);
            try {
                status.accept(Clipboard.getSystemClipboard().setContent(content)
                        ? "已复制坐标 " + coordinates : "复制失败，剪贴板不可用");
            } catch (RuntimeException failure) {
                status.accept("复制失败，剪贴板不可用");
            }
        }));
        return items;
    }

    private boolean current(SavedPlaces source, Position place) {
        return source != null && store == source && dimension != null && dimension.id().equals(place.dimension());
    }

    private java.util.Optional<String> askName(String title, String detail, String name) {
        var dialog = new TextInputDialog(name == null ? "" : name);
        dialog.initOwner(viewport.getScene().getWindow());
        dialog.setTitle(title);
        dialog.setHeaderText(detail);
        dialog.setContentText("地点名称");
        dialog.getDialogPane().lookupButton(ButtonType.OK).disableProperty().bind(
                dialog.getEditor().textProperty().map(String::isBlank));
        return dialog.showAndWait();
    }

    private void connect(MapViewport.PlaceHit start, MapViewport.PlaceHit end) {
        Position from = start.position();
        Position to = end.position();
        refresh();
        SavedPlaces source = store;
        if (readError != null) { status.accept(readError); return; }
        if (!current(source, from) || !current(source, to)) return;
        if (start.original() == null && !places.containsKey(from)) { status.accept("起点已发生变化，请重新连线"); return; }
        Link link = new Link(from, to);
        if (lines.containsKey(link)) {
            viewport.placeLines.hover(link);
            status.accept("这两个地点已有连线");
            return;
        }
        String newName = null;
        if (end.original() == null && !places.containsKey(to)) {
            var name = askName("标记终点并连线", description(dimension.displayName(), to), null);
            if (name.isEmpty()) return;
            newName = name.get();
        }
        if (!current(source, from)) return;
        try {
            var first = new Endpoint(from, start.original() == null ? null : start.original().placeName());
            var second = new Endpoint(to, end.original() == null ? null : end.original().placeName());
            apply(source.connect(first, second, newName));
            status.accept("已连接：" + lines.get(link).title(link, places));
        } catch (IOException | SecurityException failure) {
            reportFailure("连线未保存", failure);
        }
    }

    private List<MenuItem> lineItems(Link link) {
        LineData style = lines.get(link);
        String title = style.title(link, places);
        SavedPlaces source = store;
        return List.of(label(title), new SeparatorMenuItem(), action("设置颜色…", () -> {
            var picker = new ColorPicker(Color.web(style.color()));
            var dialog = new Dialog<Color>();
            dialog.initOwner(viewport.getScene().getWindow());
            dialog.setTitle("连线颜色");
            dialog.setHeaderText(title);
            dialog.getDialogPane().setContent(picker);
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            dialog.setResultConverter(button -> button == ButtonType.OK ? picker.getValue() : null);
            dialog.showAndWait().ifPresent(color -> {
                String rgb = String.format("#%02X%02X%02X", Math.round(color.getRed() * 255),
                        Math.round(color.getGreen() * 255), Math.round(color.getBlue() * 255));
                edit(source, link, style, style.withColor(rgb));
            });
        }), action("编辑备注…", () -> {
            var dialog = new TextInputDialog(style.note());
            dialog.initOwner(viewport.getScene().getWindow());
            dialog.setTitle("连线备注");
            dialog.setHeaderText(title);
            dialog.setContentText("备注");
            dialog.showAndWait().ifPresent(note -> edit(source, link, style, style.withNote(note.strip())));
        }), action("删除连线", () -> edit(source, link, style, null)));
    }

    private void edit(SavedPlaces source, Link link, LineData expected, LineData replacement) {
        if (!current(source, link.a())) return;
        try {
            apply(source.editLink(link, expected, replacement));
            status.accept(replacement == null ? "已删除连线" : "已更新连线");
        } catch (IOException | SecurityException failure) {
            reportFailure("连线未更新", failure);
        }
    }

    private void apply(SavedPlaces.Snapshot snapshot) {
        places = snapshot.places();
        lines = snapshot.lines();
        readError = null;
        drawPlaces();
        updateDestinations();
    }

    private void change(SavedPlaces source, Position place, String oldName, String name) {
        if (!current(source, place)) return;
        try {
            apply(source.changePlace(place, oldName, name, lines));
            status.accept(name == null ? "已删除地点" : "已保存地点：" + name.strip());
        } catch (IOException | SecurityException failure) {
            reportFailure("地点未保存", failure);
        }
    }

    private void reportFailure(String header, Exception failure) {
        refresh();
        var dialog = new Alert(Alert.AlertType.ERROR, failure.getMessage(), ButtonType.OK);
        dialog.initOwner(viewport.getScene().getWindow());
        dialog.setHeaderText(header);
        dialog.showAndWait();
    }

    private static String description(String dimension, Position place) {
        return dimension + " · X " + place.x() + " · Z " + place.z();
    }

    private static MenuItem label(String text) {
        MenuItem item = new MenuItem(text);
        item.setMnemonicParsing(false);
        item.setDisable(true);
        return item;
    }

    private static MenuItem action(String text, Runnable run) {
        MenuItem item = new MenuItem(text);
        item.setMnemonicParsing(false);
        item.setOnAction(event -> run.run());
        return item;
    }
}
