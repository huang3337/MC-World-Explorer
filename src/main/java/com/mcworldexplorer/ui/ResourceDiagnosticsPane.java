package com.mcworldexplorer.ui;

import com.mcworldexplorer.viewer.ResourceDiagnosticReport;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import java.util.*;
import java.util.function.Function;

/** The current selection's problems: Mod summary, filtered blocks, then precise developer details. */
final class ResourceDiagnosticsPane extends VBox {
    private record ModChoice(ResourceDiagnosticReport.Group group){
        String key(){return group==null?"*":group.namespace();}
        String title(){return group==null?"全部问题":group.ownerLabel();}
        String counts(){return group==null?"当前选区":group.failedOccurrences()+" 失败 · "+group.degradedOccurrences()+" 降级"+(group.infoOccurrences()>0?" · "+group.infoOccurrences()+" 诊断":"");}
    }
    private static final String ALL_REASONS="失败与降级";
    private static final String WITH_INFO="全部（含说明）";
    private final Label summary=new Label(),scope=new Label(),filteredCount=new Label(),copyStatus=new Label();
    private final ListView<ModChoice> mods=new ListView<>();
    private final ComboBox<String> reasons=new ComboBox<>();
    private final TextField search=new TextField();
    private final TableView<ResourceDiagnosticReport.Entry> entries=new TableView<>();
    private final TextArea details=new TextArea(),context=new TextArea();
    private final TitledPane detailSection=new TitledPane("方块详情与资产来源",details);
    private final MenuButton copy=new MenuButton("复制完整报告");
    private final Button export=new Button("导出报告…");
    private ResourceDiagnosticReport report=ResourceDiagnosticReport.empty();
    private Task<String> copyTask;
    private boolean replacing;

    ResourceDiagnosticsPane(Runnable exportAction,Runnable guideAction){
        setId("resourceDiagnosticsPane");setSpacing(9);setPadding(new Insets(14,0,0,0));
        summary.setWrapText(true);summary.getStyleClass().add("diagnostic-summary");summary.setId("resourceDiagnosticsSummary");
        scope.setWrapText(true);scope.getStyleClass().add("muted-text");scope.setMaxHeight(40);scope.setId("resourceDiagnosticsScope");var scopeTip=new Tooltip();scopeTip.textProperty().bind(scope.textProperty());scope.setTooltip(scopeTip);
        mods.setId("diagnosticModList");mods.setMinWidth(140);mods.setPrefWidth(205);mods.setMinHeight(120);mods.setCellFactory(list->new ListCell<>(){
            @Override protected void updateItem(ModChoice value,boolean empty){
                super.updateItem(value,empty);setText(null);setGraphic(null);setTooltip(null);
                if(!empty&&value!=null){
                    var title=new Label(value.title());title.setWrapText(true);title.maxWidthProperty().bind(widthProperty().subtract(20));
                    var count=new Label(value.counts());count.getStyleClass().add("muted-text");count.setWrapText(true);count.maxWidthProperty().bind(widthProperty().subtract(20));
                    setGraphic(new VBox(3,title,count));setTooltip(new Tooltip(value.group()==null?"显示本次所有问题":value.group().namespace()+"\n"+(value.group().ownerConfirmed()?"归属已确认":"所属 Mod 未确认")));
                }
            }
        });
        mods.getSelectionModel().selectedItemProperty().addListener((o,a,b)->filter());
        search.setId("diagnosticBlockSearch");search.setPromptText("搜索方块 ID 或问题");search.setMinWidth(100);search.setPrefWidth(220);search.textProperty().addListener((o,a,b)->filter());
        reasons.setId("diagnosticReasonFilter");reasons.setPrefWidth(170);reasons.setMinWidth(110);reasons.valueProperty().addListener((o,a,b)->filter());
        var filters=new FlowPane(8,8,search,reasons);
        entries.setId("resourceIssueTable");entries.setMinHeight(80);entries.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        entries.getColumns().add(column("原始方块 ID",ResourceDiagnosticReport.Entry::blockId,205));
        entries.getColumns().add(column("状态",entry->entry.severity().label(),68));
        entries.getColumns().add(column("原因码",ResourceDiagnosticReport.Entry::reasonCode,160));
        var count=new TableColumn<ResourceDiagnosticReport.Entry,Long>("数量");count.setCellValueFactory(cell->new ReadOnlyObjectWrapper<>(cell.getValue().occurrences()));count.setPrefWidth(64);entries.getColumns().add(count);
        entries.getSelectionModel().selectedItemProperty().addListener((o,a,b)->showEntry(b));
        entries.setRowFactory(table->{
            var row=new TableRow<ResourceDiagnosticReport.Entry>();var item=new MenuItem("复制原始方块 ID");
            item.setOnAction(e->{if(row.getItem()!=null){cancelCopy();copyText(row.getItem().blockId(),"已复制方块 ID");}});
            var menu=new ContextMenu(item);row.itemProperty().addListener((o,a,b)->row.setContextMenu(b==null?null:menu));return row;
        });
        filteredCount.getStyleClass().add("muted-text");var issueArea=new VBox(8,filters,entries,filteredCount);VBox.setVgrow(entries,Priority.ALWAYS);issueArea.setMinWidth(280);
        var split=new SplitPane(mods,issueArea);split.setDividerPositions(.28);split.setMinHeight(165);VBox.setVgrow(split,Priority.ALWAYS);
        details.setId("resourceIssueDetails");details.setEditable(false);details.setWrapText(true);details.setPrefRowCount(5);details.setPrefHeight(125);details.getStyleClass().add("diagnostic-detail");
        detailSection.setExpanded(false);detailSection.setId("resourceIssueDetailSection");
        context.setId("resourceDiagnosticsText");context.setEditable(false);context.setWrapText(true);context.setPrefRowCount(5);context.setPrefHeight(115);
        var contextSection=new TitledPane("资源来源与运行说明",context);contextSection.setId("resourceDiagnosticsSection");contextSection.setExpanded(false);
        var text=new MenuItem("复制完整文本");text.setOnAction(e->copyReport(false));
        var json=new MenuItem("复制完整 JSON");json.setOnAction(e->copyReport(true));copy.getItems().setAll(text,json);copy.setId("copyResourceReport");
        export.setId("exportResourceReport");export.setOnAction(e->exportAction.run());
        var guide=new Hyperlink("插件开发说明");guide.setId("diagnosticPluginGuide");guide.setOnAction(e->guideAction.run());
        var actions=new FlowPane(8,4,copy,export,guide,copyStatus);
        copy.setTooltip(new Tooltip("筛选只影响列表，复制与导出包含本次完整报告。"));export.setTooltip(new Tooltip("导出本次完整报告，不扫描其他区域或 Mod。"));
        getChildren().setAll(summary,scope,split,detailSection,contextSection,actions);refresh(report,"");
    }
    private static TableColumn<ResourceDiagnosticReport.Entry,String> column(String title,Function<ResourceDiagnosticReport.Entry,String> value,double width){
        var column=new TableColumn<ResourceDiagnosticReport.Entry,String>(title);column.setCellValueFactory(cell->new ReadOnlyStringWrapper(value.apply(cell.getValue())));column.setPrefWidth(width);
        column.setCellFactory(view->new TableCell<>(){
            @Override protected void updateItem(String value,boolean empty){super.updateItem(value,empty);setText(empty?null:value);setTooltip(empty||value==null?null:new Tooltip(value));}
        });return column;
    }
    void refresh(ResourceDiagnosticReport next,String legacyContext){
        next=Objects.requireNonNullElseGet(next,ResourceDiagnosticReport::empty);
        String contextText=String.join("\n",next.contextDetails());
        if(contextText.isBlank())contextText=Objects.requireNonNullElse(legacyContext,"");
        if(!Objects.equals(context.getText(),contextText))context.setText(contextText);
        if(report==next&&!mods.getItems().isEmpty())return;
        cancelCopy();
        String selected=mods.getSelectionModel().getSelectedItem()==null?"*":mods.getSelectionModel().getSelectedItem().key();String reason=reasons.getValue();
        report=next;replacing=true;
        try{
            summary.setText(report.summary()+(report.truncated()?" · 报告有截断":""));scope.setText(report.scope());copy.setDisable(!report.generated());export.setDisable(!report.generated());
            var values=new ArrayList<ModChoice>();values.add(new ModChoice(null));report.groups().forEach(group->values.add(new ModChoice(group)));mods.getItems().setAll(values);
            mods.getSelectionModel().select(values.stream().filter(value->value.key().equals(selected)).findFirst().orElse(values.getFirst()));
            var codes=new TreeSet<String>();report.entries().forEach(entry->codes.add(entry.reasonCode()));var allReasons=new ArrayList<String>();allReasons.add(ALL_REASONS);allReasons.add(WITH_INFO);allReasons.addAll(codes);reasons.getItems().setAll(allReasons);reasons.setValue(allReasons.contains(reason)?reason:ALL_REASONS);
            mods.setDisable(!report.generated()||report.entries().isEmpty());search.setDisable(!report.generated()||report.entries().isEmpty());reasons.setDisable(!report.generated()||report.entries().isEmpty());copyStatus.setText("");
        }finally{replacing=false;}
        filter();
    }
    private void filter(){
        if(replacing)return;
        String reason=reasons.getValue();String selectedKey=mods.getSelectionModel().getSelectedItem()==null?"*":mods.getSelectionModel().getSelectedItem().key();
        var choices=new ArrayList<ModChoice>();choices.add(new ModChoice(null));
        report.groups().stream().filter(group->!ALL_REASONS.equals(reason)||group.failedOccurrences()>0||group.degradedOccurrences()>0).forEach(group->choices.add(new ModChoice(group)));
        if(!mods.getItems().equals(choices)){
            replacing=true;
            try{mods.getItems().setAll(choices);mods.getSelectionModel().select(choices.stream().filter(choice->choice.key().equals(selectedKey)).findFirst().orElse(choices.getFirst()));}
            finally{replacing=false;}
        }
        var selected=mods.getSelectionModel().getSelectedItem();String namespace=selected==null?"*":selected.key();String term=search.getText().strip().toLowerCase(Locale.ROOT);
        var previous=entries.getSelectionModel().getSelectedItem();
        var visible=report.entries().stream().filter(entry->namespace.equals("*")||namespace.equals(entry.namespace()))
            .filter(entry->reason==null||reason.equals(WITH_INFO)||(reason.equals(ALL_REASONS)?entry.severity()!=ResourceDiagnosticReport.Severity.INFO:entry.reasonCode().equals(reason)))
            .filter(entry->term.isEmpty()||(entry.blockId()+" "+entry.ownerLabel()+" "+entry.reasonCode()+" "+entry.detail()).toLowerCase(Locale.ROOT).contains(term)).toList();
        entries.getItems().setAll(visible);entries.sort();
        entries.setPlaceholder(new Label(!report.generated()?"生成一次三维预览后查看解析结果":report.entries().isEmpty()?"本次选区没有记录到解析问题\n不代表整个 Mod 或其他区域均已支持":"当前筛选没有匹配的问题\n仅说明类记录可选择“全部（含说明）”查看"));
        filteredCount.setText(report.generated()?"显示 "+visible.size()+" / "+report.entries().size()+" 项记录；仅代表本次选区":"尚无本次选区的解析报告");
        if(previous!=null&&visible.contains(previous))entries.getSelectionModel().select(previous);
        else if(!visible.isEmpty())entries.getSelectionModel().selectFirst();
        else showEntry(null);
    }
    private void showEntry(ResourceDiagnosticReport.Entry entry){
        if(entry==null){details.setText("选择一项问题查看完整原因、状态和资产来源。");return;}
        StringBuilder text=new StringBuilder("方块：").append(entry.blockId()).append("\nMod / 命名空间：").append(entry.ownerLabel()).append(" · ").append(entry.namespace());
        if(!entry.ownerConfirmed())text.append("（所属 Mod 未确认）");
        text.append("\n状态：").append(entry.severity().label()).append(" · ").append(entry.reasonCode()).append("\n出现次数：").append(entry.occurrences());
        if(!entry.state().isEmpty())text.append("\n保存属性：").append(new TreeMap<>(entry.state()));
        text.append("\n\n原因：").append(entry.detail());
        text.append("\n\nLoader：").append(entry.loaders().isEmpty()?"未记录":String.join("、",entry.loaders()));
        text.append("\n资产来源：");
        if(entry.assets().isEmpty())text.append("未记录可用来源");
        for(var asset:entry.assets())text.append("\n• ").append(asset.kind()).append(" · ").append(asset.id()).append("\n  ").append(asset.origin().isBlank()?"来源未取得":asset.origin());
        if(!entry.chunks().isEmpty())text.append("\n区块：").append(entry.chunks());
        if(entry.truncated())text.append("\n\n本条详情、资源引用或区块列表存在截断，导出报告保留截断标识。");
        details.setText(text.toString());details.positionCaret(0);
    }
    private void copyText(String value,String message){
        try{var content=new ClipboardContent();content.putString(value);if(!Clipboard.getSystemClipboard().setContent(content))throw new IllegalStateException("系统剪贴板暂不可用");copyStatus.setText(message);copyStatus.getStyleClass().remove("field-error");}
        catch(RuntimeException failure){copyStatus.setText("复制失败，请使用导出报告");if(!copyStatus.getStyleClass().contains("field-error"))copyStatus.getStyleClass().add("field-error");}
    }
    private void copyReport(boolean json){
        if(copyTask!=null||!report.generated())return;
        ResourceDiagnosticReport snapshot=report;
        var task=new Task<String>(){
            @Override protected String call(){
                if(isCancelled())return "";
                String text=json?snapshot.toJson():snapshot.toText();
                return isCancelled()?"":text;
            }
        };
        copyTask=task;copy.setDisable(true);copyStatus.setText("正在准备报告…");copyStatus.getStyleClass().remove("field-error");
        task.setOnSucceeded(event->{
            if(!finishCopy(task,snapshot))return;
            copyText(task.getValue(),json?"已复制完整 JSON 报告":"已复制完整文本报告");
        });
        task.setOnFailed(event->{
            if(!finishCopy(task,snapshot))return;
            copyStatus.setText("报告生成失败，请重试或导出");
            if(!copyStatus.getStyleClass().contains("field-error"))copyStatus.getStyleClass().add("field-error");
        });
        task.setOnCancelled(event->{if(finishCopy(task,snapshot))copyStatus.setText("复制已取消");});
        try{Thread.ofPlatform().daemon().name("mcwe-diagnostic-copy").start(task);}
        catch(RuntimeException failure){
            cancelCopy();copyStatus.setText("无法启动复制任务，请重试或导出");
            if(!copyStatus.getStyleClass().contains("field-error"))copyStatus.getStyleClass().add("field-error");
        }
    }
    /** All completion handlers run on FX; a replaced report must never overwrite the clipboard. */
    private boolean finishCopy(Task<String> task,ResourceDiagnosticReport snapshot){
        if(copyTask!=task)return false;
        copyTask=null;copy.setDisable(!report.generated());return report==snapshot;
    }
    private void cancelCopy(){
        var previous=copyTask;copyTask=null;
        if(previous!=null)previous.cancel(true);
        copy.setDisable(!report.generated());
    }
}
