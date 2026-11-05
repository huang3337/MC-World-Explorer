package com.mcworldexplorer.ui;

import com.mcworldexplorer.viewer.ResourceDiagnosticReport;
import com.mcworldexplorer.voxel.resource.*;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.util.*;

/** Task-oriented resource, diagnostics and plugin pages; draft activation is explicit. */
final class ResourceSettingsPane extends VBox {
    interface Host {
        String world(); String version(); String client(); String instance(); String status(); String diagnostics();
        default ResourceDiagnosticReport diagnosticReport(){return ResourceDiagnosticReport.empty();}
        default void exportDiagnostics() { }
        default void openPluginGuide() { }
        default String pluginNotice(){return "";}
        boolean resourceActionsAvailable();
        default boolean canApply(){return false;}
        void automatic(); void chooseClient(); void chooseInstance(); void simpleColors();
        default void apply(Set<ResourceLocation> enabled)throws Exception{throw new IllegalStateException("插件库尚未准备好");}
        default void applyAsync(Set<ResourceLocation> enabled,java.util.function.Consumer<Exception> completed){
            Exception problem=null;try{apply(enabled);}catch(Exception failure){problem=failure;}completed.accept(problem);
        }
        default boolean externalReady(){return false;}
        default boolean canReloadPlugins(){return externalReady();}
        default boolean pluginOperationRunning(){return false;}
        default Set<ResourceLocation> externalPlugins(){return Set.of();}
        default void installPlugin() { }
        default void updatePlugin(ResourceLocation id) { }
        default void uninstallPlugin(ResourceLocation id) { }
        default void reloadPlugins() { }
    }

    private final Host host;
    private ModelPluginCatalog catalog;
    private Set<ResourceLocation> applied;
    private final List<PluginRow> rows=new ArrayList<>();
    private final ListView<PluginRow> pluginList=new ListView<>();
    private final TextField pluginSearch=new TextField();
    private final Label summary=value(),pluginTitle=value();
    private final TextArea feedback=textArea(),pluginDetails=textArea();
    private final Button apply=new Button("应用启停更改"),disableAll=new Button("全部停用");
    private final Button install=new Button("安装插件…"),reload=new Button("重新检查");
    private final Button update=new Button("更新所选…"),uninstall=new Button("卸载所选…");
    private final Label worldValue=value(),versionValue=value(),clientValue=value(),instanceValue=value(),resourceStatus=value();
    private final Label clientSummary=value(),instanceSummary=value();
    private final List<Button> resourceActions=new ArrayList<>();
    private final TabPane tabs=new TabPane();
    private final ResourceDiagnosticsPane diagnosticPane;
    private boolean applying;

    private final class PluginRow {
        final ModelPluginCatalog.Entry entry;
        final BooleanProperty enabled=new SimpleBooleanProperty();
        PluginRow(ModelPluginCatalog.Entry entry){
            this.entry=entry;enabled.set(applied.contains(id()));
            enabled.addListener((o,a,b)->{updateDraft();if(pluginList.getSelectionModel().getSelectedItem()==this)showPluginDetails();});
        }
        ResourceLocation id(){return entry.descriptor().id();}
        String searchText(){var d=entry.descriptor();return (d.name()+" "+d.id()+" "+String.join(" ",d.targetMods())).toLowerCase(Locale.ROOT);}
        String stateText(){return enabled.get()==applied.contains(id())?(enabled.get()?"已启用":"已停用"):(enabled.get()?"待启用":"待停用");}
    }

    ResourceSettingsPane(Host host){this(ModelPluginCatalog.core(),Set.of(),host);}
    ResourceSettingsPane(ModelPluginCatalog catalog,Set<ResourceLocation> enabled,Host host){
        this.catalog=Objects.requireNonNull(catalog);this.host=Objects.requireNonNull(host);applied=Set.copyOf(enabled);
        setId("resourceSettingsPane");getStyleClass().add("resource-settings");setSpacing(10);setPadding(new Insets(14));
        setPrefSize(820,590);setMinSize(560,440);
        var heading=new Label("资源与插件");heading.getStyleClass().add("settings-heading");
        diagnosticPane=new ResourceDiagnosticsPane(host::exportDiagnostics,host::openPluginGuide);
        tabs.getTabs().setAll(tab("资源",resourceTab(),"resourceSettingsTab"),tab("解析诊断",diagnosticPane,"resourceDiagnosticsTab"),tab("插件",pluginTab(),"modelPluginsTab"));
        tabs.setId("resourceSettingsTabs");tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);VBox.setVgrow(tabs,Priority.ALWAYS);
        getChildren().setAll(heading,tabs);showPluginFeedback(host.pluginNotice());refresh();
    }
    private static Tab tab(String title,javafx.scene.Node content,String id){var tab=new Tab(title,content);tab.setId(id);return tab;}

    private VBox resourceTab(){
        worldValue.setId("resourceWorldValue");versionValue.setId("resourceVersionValue");clientValue.setId("resourceClientValue");instanceValue.setId("resourceInstanceValue");
        var context=fields();context.addRow(0,new Label("存档"),worldValue);context.addRow(1,new Label("游戏版本"),versionValue);
        var selected=fields();selected.addRow(0,new Label("客户端"),clientSummary);selected.addRow(1,new Label("游戏目录"),instanceSummary);
        var paths=fields();paths.addRow(0,new Label("客户端路径"),clientValue);paths.addRow(1,new Label("目录路径"),instanceValue);
        var manualActions=new FlowPane(8,8,action("选择客户端 JAR…","resourceClientButton",host::chooseClient),action("选择游戏目录…","resourceInstanceButton",host::chooseInstance),action("使用简化颜色","resourceSimpleButton",host::simpleColors));
        var help=value();help.setText("客户端请选择对应游戏版本的原版 JAR。游戏目录应包含 mods、resourcepacks 或 saves。资源缺失时可使用简化颜色。");help.getStyleClass().add("muted-text");
        var manual=new TitledPane("手动指定与完整路径",new VBox(12,paths,manualActions,help));manual.setExpanded(false);manual.setId("resourceManualSection");
        var automatic=action("自动匹配资源","resourceAutomaticButton",host::automatic);automatic.getStyleClass().add("primary-action");
        var choose=new Button("手动指定…");choose.setOnAction(e->manual.setExpanded(!manual.isExpanded()));
        var actions=new FlowPane(8,8,automatic,choose);
        resourceStatus.setId("resourceSettingsStatus");resourceStatus.getStyleClass().add("settings-notice");resourceStatus.setMaxHeight(74);
        var intro=value();intro.setText("选择用于三维预览的资源。方块解析问题请查看“解析诊断”。");intro.getStyleClass().add("muted-text");
        var content=new VBox(16,intro,context,selected,actions,resourceStatus,manual);content.setPadding(new Insets(14,0,0,0));
        var scroll=new ScrollPane(content);scroll.setFitToWidth(true);scroll.getStyleClass().add("settings-scroll");var result=new VBox(scroll);VBox.setVgrow(scroll,Priority.ALWAYS);return result;
    }
    private static GridPane fields(){
        var grid=new GridPane();grid.setHgap(12);grid.setVgap(8);
        var label=new ColumnConstraints();label.setMinWidth(76);
        var value=new ColumnConstraints();value.setHgrow(Priority.ALWAYS);value.setMinWidth(0);grid.getColumnConstraints().setAll(label,value);return grid;
    }

    private VBox pluginTab(){
        pluginSearch.setPromptText("搜索插件名称、ID 或目标 Mod");pluginSearch.setId("modelPluginSearch");pluginSearch.setMinWidth(140);pluginSearch.setPrefWidth(260);pluginSearch.textProperty().addListener((o,a,b)->filterPlugins());
        install.setId("modelPluginInstall");install.setOnAction(e->host.installPlugin());reload.setId("modelPluginReload");reload.setOnAction(e->host.reloadPlugins());
        var toolbar=new FlowPane(8,8,pluginSearch,install,reload);
        pluginList.setId("modelPluginList");pluginList.getStyleClass().add("plugin-list");pluginList.setMinWidth(210);pluginList.setMinHeight(130);
        pluginList.setPlaceholder(new Label("尚未安装插件\n通用资源解析始终可用"));pluginList.setCellFactory(list->new PluginCell());pluginList.getSelectionModel().selectedItemProperty().addListener((o,a,b)->showPluginDetails());
        pluginTitle.setText("选择插件查看详情");pluginTitle.getStyleClass().add("section-title");
        pluginDetails.setId("modelPluginDetails");pluginDetails.setPromptText("列表中每个勾选框控制该插件的启停草稿。更改后点击下方应用。");pluginDetails.setPrefRowCount(7);pluginDetails.setMinHeight(100);VBox.setVgrow(pluginDetails,Priority.ALWAYS);
        update.setId("modelPluginUpdateSelected");update.setOnAction(e->{var row=pluginList.getSelectionModel().getSelectedItem();if(row!=null)host.updatePlugin(row.id());});
        uninstall.setId("modelPluginUninstallSelected");uninstall.setOnAction(e->{var row=pluginList.getSelectionModel().getSelectedItem();if(row!=null)host.uninstallPlugin(row.id());});
        var details=new VBox(8,pluginTitle,pluginDetails,new FlowPane(8,8,update,uninstall));details.setPadding(new Insets(8));details.setMinWidth(190);details.getStyleClass().add("plugin-detail");
        var split=new SplitPane(pluginList,details);split.setDividerPositions(.54);split.setMinHeight(150);VBox.setVgrow(split,Priority.ALWAYS);
        disableAll.setId("modelPluginDefaults");disableAll.setOnAction(e->{rows.forEach(row->row.enabled.set(false));updateDraft();});
        apply.setId("modelPluginsApply");apply.getStyleClass().add("primary-action");apply.setOnAction(e->applyDraft());
        summary.setMaxHeight(38);summary.setId("modelPluginSummary");
        var bottom=new BorderPane();bottom.setCenter(summary);bottom.setRight(new FlowPane(8,8,disableAll,apply));BorderPane.setMargin(summary,new Insets(0,8,0,0));
        feedback.setId("modelPluginsFeedback");feedback.setPrefRowCount(2);feedback.setPrefHeight(46);feedback.setMaxHeight(64);feedback.setMinHeight(0);feedback.getStyleClass().add("settings-feedback");feedback.managedProperty().bind(feedback.textProperty().isNotEmpty());feedback.visibleProperty().bind(feedback.managedProperty());
        var boundary=value();boundary.setText("安装后默认停用；关闭窗口会放弃未应用的勾选。");boundary.getStyleClass().add("muted-text");
        var guide=new Hyperlink("插件开发说明");guide.setId("pluginDevelopmentGuide");guide.setOnAction(e->host.openPluginGuide());
        var foot=new FlowPane(8,4,boundary,guide);
        var result=new VBox(10,toolbar,split,bottom,feedback,foot);result.setPadding(new Insets(14,0,0,0));rebuildPlugins();return result;
    }
    private final class PluginCell extends ListCell<PluginRow> {
        private PluginRow bound;
        private final CheckBox check=new CheckBox();
        private final Label metadata=value(),state=value();
        private final HBox graphic=new HBox(10,check,new VBox(3,metadata,state));
        PluginCell(){
            graphic.setPadding(new Insets(5,2,5,2));check.setWrapText(true);check.setMinWidth(0);check.setMaxWidth(Double.MAX_VALUE);HBox.setHgrow(check,Priority.ALWAYS);metadata.getStyleClass().add("muted-text");metadata.setWrapText(false);metadata.setMaxWidth(110);metadata.setTextOverrun(OverrunStyle.ELLIPSIS);state.getStyleClass().add("plugin-state");
            check.setOnAction(e->{if(getItem()!=null)pluginList.getSelectionModel().select(getItem());});
        }
        @Override protected void updateItem(PluginRow row,boolean empty){
            if(bound!=null)check.selectedProperty().unbindBidirectional(bound.enabled);bound=null;
            super.updateItem(row,empty);setText(null);setTooltip(null);
            if(empty||row==null){setGraphic(null);return;}
            bound=row;var descriptor=row.entry.descriptor();check.setText(descriptor.name());
            check.setId("pluginToggle-external-"+Base64.getUrlEncoder().withoutPadding().encodeToString(row.id().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            check.setUserData(row.id());check.selectedProperty().bindBidirectional(row.enabled);check.setDisable(applying||host.pluginOperationRunning());
            metadata.setText("v"+descriptor.version());state.setText(row.stateText());setGraphic(graphic);
            setTooltip(new Tooltip(descriptor.name()+"\n"+descriptor.id()));
        }
    }
    private void rebuildPlugins(){
        ResourceLocation selection=pluginList.getSelectionModel().getSelectedItem()==null?null:pluginList.getSelectionModel().getSelectedItem().id();
        rows.clear();catalog.entries().forEach(entry->rows.add(new PluginRow(entry)));filterPlugins();
        if(selection!=null)pluginList.getItems().stream().filter(row->row.id().equals(selection)).findFirst().ifPresent(row->pluginList.getSelectionModel().select(row));
        showPluginDetails();updateDraft();
    }
    private void filterPlugins(){
        ResourceLocation selection=pluginList.getSelectionModel().getSelectedItem()==null?null:pluginList.getSelectionModel().getSelectedItem().id();
        String search=pluginSearch.getText().strip().toLowerCase(Locale.ROOT);pluginList.getItems().setAll(rows.stream().filter(row->row.searchText().contains(search)).toList());
        pluginList.setPlaceholder(new Label(rows.isEmpty()?"尚未安装插件\n通用资源解析始终可用":"没有匹配的插件"));
        if(selection!=null)pluginList.getItems().stream().filter(row->row.id().equals(selection)).findFirst().ifPresent(row->pluginList.getSelectionModel().select(row));
        if(pluginList.getSelectionModel().isEmpty()&&!pluginList.getItems().isEmpty())pluginList.getSelectionModel().selectFirst();
        showPluginDetails();
    }
    private void showPluginDetails(){
        var row=pluginList.getSelectionModel().getSelectedItem();
        if(row==null){pluginTitle.setText("选择插件查看详情");pluginDetails.clear();}
        else{
            var d=row.entry.descriptor();pluginTitle.setText(d.name());
            String dependencies=d.dependencies().isEmpty()?"无":d.dependencies().entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(Object::toString))).map(e->e.getKey()+" · "+e.getValue()).collect(java.util.stream.Collectors.joining("\n"));
            pluginDetails.setText(d.description()+"\n\n状态："+row.stateText()+"\n版本："+d.version()+"\nID："+d.id()+"\n目标 Mod："+(d.targetMods().isEmpty()?"未限定":String.join("、",new TreeSet<>(d.targetMods())))+"\n依赖："+dependencies);
        }
        updateActions();
    }
    private Set<ResourceLocation> selected(){var result=new HashSet<ResourceLocation>();rows.forEach(row->{if(row.enabled.get())result.add(row.id());});return Set.copyOf(result);}
    private void updateDraft(){
        Set<ResourceLocation> selected=selected();boolean dirty=!selected.equals(applied);
        summary.setText("启用 "+selected.size()+" / "+rows.size()+" 项"+(dirty?" · 有未应用更改":""));
        apply.setDisable(!dirty||!host.canApply()||applying);disableAll.setDisable(rows.isEmpty()||selected.isEmpty()||applying||host.pluginOperationRunning());
        pluginList.refresh();updateActions();
    }
    private void updateActions(){
        boolean dirty=!selected().equals(applied);boolean busy=applying||host.pluginOperationRunning();
        install.setDisable(dirty||busy||!host.canApply()||!host.externalReady());reload.setDisable(dirty||busy||!host.canReloadPlugins());
        var row=pluginList.getSelectionModel().getSelectedItem();boolean unavailable=row==null||!host.externalPlugins().contains(row.id())||dirty||busy||!host.canApply()||!host.externalReady();
        update.setDisable(unavailable);uninstall.setDisable(unavailable);
    }
    private void applyDraft(){
        if(!host.canApply()||applying)return;
        var selected=selected();try{catalog.select(selected);}catch(Exception failure){showApplyFailure(failure);return;}
        applying=true;showPluginFeedback("正在应用插件启停更改…");updateDraft();
        host.applyAsync(selected,failure->{
            applying=false;
            if(failure==null){applied=selected;feedback.getStyleClass().remove("field-error");showPluginFeedback(host.pluginNotice().isBlank()?"设置已应用，旧三维缓存已按需清理。":host.pluginNotice());}
            else showApplyFailure(failure);
            showPluginDetails();updateDraft();
        });
    }
    private void showApplyFailure(Exception failure){showPluginFeedback("无法应用："+Objects.toString(failure.getMessage(),failure.getClass().getSimpleName()));if(!feedback.getStyleClass().contains("field-error"))feedback.getStyleClass().add("field-error");}
    void replacePlugins(ModelPluginCatalog catalog,Set<ResourceLocation> enabled){this.catalog=Objects.requireNonNull(catalog);applied=Set.copyOf(enabled);rebuildPlugins();}
    void showPluginFeedback(String text){feedback.setText(Objects.requireNonNullElse(text,""));updateDraft();}
    private Button action(String text,String id,Runnable run){var button=new Button(text);button.setId(id);button.setOnAction(e->{run.run();refresh();});resourceActions.add(button);return button;}
    private static Label value(){var label=new Label();label.setWrapText(true);label.setMinWidth(0);label.setMaxWidth(Double.MAX_VALUE);var tooltip=new Tooltip();tooltip.textProperty().bind(label.textProperty());label.setTooltip(tooltip);return label;}
    private static TextArea textArea(){var area=new TextArea();area.setEditable(false);area.setWrapText(true);return area;}
    private static String shortPath(String text){if(text==null)return "";int end=Math.max(text.lastIndexOf('/'),text.lastIndexOf('\\'));return end>=0&&end<text.length()-1?text.substring(end+1):text;}
    void refresh(){
        worldValue.setText(host.world());versionValue.setText(host.version());clientValue.setText(host.client());instanceValue.setText(host.instance());resourceStatus.setText(host.status());
        clientSummary.setText(shortPath(host.client()));clientSummary.setTooltip(new Tooltip(host.client()));instanceSummary.setText(shortPath(host.instance()));instanceSummary.setTooltip(new Tooltip(host.instance()));
        resourceActions.forEach(button->button.setDisable(!host.resourceActionsAvailable()));diagnosticPane.refresh(host.diagnosticReport(),host.diagnostics());updateDraft();
    }
    void showDiagnostics(){tabs.getSelectionModel().select(1);}
}
