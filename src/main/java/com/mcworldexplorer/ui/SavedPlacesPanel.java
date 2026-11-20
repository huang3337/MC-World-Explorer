package com.mcworldexplorer.ui;

import com.mcworldexplorer.storage.SavedPlaces.Position;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public final class SavedPlacesPanel extends TitledPane {
    record Row(Position position, String text, boolean available) {}

    private final TextField search = new TextField();
    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final FilteredList<Row> matches = new FilteredList<>(rows);
    private final ListView<Row> list = new ListView<>(matches);
    private final Label placeholder = new Label();
    private Consumer<Position> navigate;
    private String readError;

    public SavedPlacesPanel() {
        setText("自定义标记点");
        setTooltip(new Tooltip("从地点、出生点、玩家或传送门图标按住左键拖出连线。拖到空白处可命名新地点。"));
        setAnimated(false);
        setExpanded(false);
        setMinWidth(0);
        search.setId("placeSearch");
        search.setPromptText("搜索名称、维度或坐标");
        search.setAccessibleText(search.getPromptText());
        list.setId("placeResults");
        list.setFixedCellSize(28);
        list.setPrefHeight(114);
        list.setMinWidth(0);
        list.setPlaceholder(placeholder);
        list.setCellFactory(view -> new ListCell<>() {
            private final Tooltip details = new Tooltip();
            {
                setOnMouseClicked(event -> {
                    if (event.getButton() == MouseButton.PRIMARY && !isEmpty()) {
                        visit(getItem());
                        event.consume();
                    }
                });
            }
            @Override protected void updateItem(Row row, boolean empty) {
                super.updateItem(row, empty);
                boolean present = !empty && row != null;
                setText(present ? row.text() : null);
                details.setText(getText());
                setTooltip(present ? details : null);
                setDisable(present && !row.available());
            }
        });
        list.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                visit(list.getSelectionModel().getSelectedItem());
                event.consume();
            }
        });
        search.textProperty().addListener((o, previous, value) -> {
            String query = value.strip().toLowerCase(Locale.ROOT);
            matches.setPredicate(row -> row.text().toLowerCase(Locale.ROOT).contains(query));
            updatePlaceholder();
        });
        setContent(new VBox(5, search, list));
    }

    private void visit(Row row) {
        if (row != null && row.available() && navigate != null) navigate.accept(row.position());
    }

    void setOnVisit(Consumer<Position> navigate) {
        this.navigate = navigate;
    }

    void reset() {
        search.clear();
        setExpanded(false);
    }

    void setPlaces(List<Row> places, String error) {
        readError = error;
        rows.setAll(places);
        setText("自定义标记点（" + rows.size() + "）");
        updatePlaceholder();
    }

    private void updatePlaceholder() {
        placeholder.setText(readError != null ? "地点读取失败" :
                rows.isEmpty() ? "还没有自定义标记点" : "没有匹配的标记点");
    }
}
