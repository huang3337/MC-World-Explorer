package com.mcworldexplorer.ui;

import com.mcworldexplorer.storage.PortablePaths;
import com.mcworldexplorer.voxel.resource.*;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** FX owner for asynchronous library transactions; file IO and adapter startup never run on FX. */
final class ModelPluginLibraryController implements AutoCloseable {
    // The owner must finish queued worker cleanup before the host JVM can exit.
    // close() only schedules that cleanup; it never waits on the FX thread.
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r->{var thread=new Thread(r,"mcwe-plugin-library");thread.setDaemon(false);return thread;});
    private final BooleanSupplier idle;
    private final Consumer<ModelPluginStore.Snapshot> applied;
    private final Consumer<String> feedback;
    private final Consumer<Runnable> completionDispatcher;
    private volatile ModelPluginStore store;
    private ModelPluginStore.Snapshot current;
    private boolean busy,closed;
    private Future<?> activeTask;
    private String version;
    ModelPluginLibraryController(BooleanSupplier idle,Consumer<ModelPluginStore.Snapshot> applied,Consumer<String> feedback){this(idle,applied,feedback,Platform::runLater);}
    ModelPluginLibraryController(BooleanSupplier idle,Consumer<ModelPluginStore.Snapshot> applied,Consumer<String> feedback,Consumer<Runnable> completionDispatcher){this.idle=idle;this.applied=applied;this.feedback=feedback;this.completionDispatcher=Objects.requireNonNull(completionDispatcher);}
    boolean busy(){return busy;}
    boolean ready(){return store!=null&&!closed;}
    Set<ResourceLocation> external(){return current==null?Set.of():current.external();}
    boolean needsRestart(){return current!=null&&store!=null&&current.processIds().keySet().stream().anyMatch(id->!store.running(id));}
    void initialize(){
        run("正在读取模型插件库…",()->{
            version=ModelPluginStore.hostVersion();
            store=new ModelPluginStore(PortablePaths.configDirectory().resolve("model-plugins"),version);
            return store.restore();
        },this::accept,failure->{});
    }
    void apply(Set<ResourceLocation> enabled,Consumer<Exception> completion){
        if(!ready()){completion.accept(new IllegalStateException("插件库尚未准备好"));return;}
        run("正在校验并应用解析插件…",()->store.apply(enabled),snapshot->{accept(snapshot);completion.accept(null);},completion);
    }
    void choosePackage(Window owner,ResourceLocation updating){
        if(!ready()||busy||!idle.getAsBoolean())return;
        var chooser=new FileChooser();chooser.setTitle(updating==null?"安装模型解析插件":"更新模型解析插件");chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("模型插件 JAR","*.jar"));
        var file=chooser.showOpenDialog(owner);if(file==null)return;
        inspectPackage(file.toPath(),owner,updating);
    }
    void inspectPackage(Path file,Window owner,ResourceLocation updating){
        if(!ready())return;
        run("正在检查插件包…",()->ExternalModelPluginPackage.inspect(file,version),receipt->{
            if(updating!=null&&!updating.equals(receipt.manifest().plugin().id())){feedback.accept("所选包的插件ID与要更新的插件不一致。");return;}
            var descriptor=receipt.manifest().plugin();var alert=new Alert(Alert.AlertType.CONFIRMATION);if(owner!=null)alert.initOwner(owner);
            alert.setTitle(updating==null?"安装解析插件":"更新解析插件");alert.setHeaderText(descriptor.name()+" · "+descriptor.version());
            alert.setContentText(descriptor.description()+"\n\n仅安装你信任的解析插件。插件在独立进程中运行，仍能以当前用户权限访问文件和网络。"+(updating==null?"\n安装后默认停用，可在列表中启用。":"\n更新成功后沿用当前启用设置。"));
            var details=new TextArea("ID："+descriptor.id()+"\n文件："+receipt.file()+"\nSHA-256："+receipt.sha256()+"\n目标 Mod："+String.join("、",new TreeSet<>(descriptor.targetMods()))+"\n声明使用的解析服务："+capabilityText(descriptor));details.setEditable(false);details.setWrapText(true);alert.getDialogPane().setExpandableContent(details);
            if(alert.showAndWait().filter(ButtonType.OK::equals).isEmpty()){feedback.accept("已取消插件安装或更新，插件库未更改。");return;}
            installApproved(receipt,updating!=null);
        },failure->{});
    }
    void uninstall(Window owner,ResourceLocation id){
        if(!ready()||busy||!idle.getAsBoolean()||!external().contains(id))return;
        String name=current.catalog().entries().stream().filter(e->e.descriptor().id().equals(id)).findFirst().orElseThrow().descriptor().name();
        var alert=new Alert(Alert.AlertType.CONFIRMATION,"将移除此解析插件。下次预览按剩余规则重新解析。",ButtonType.OK,ButtonType.CANCEL);if(owner!=null)alert.initOwner(owner);alert.setTitle("卸载解析插件");alert.setHeaderText(name);
        if(alert.showAndWait().filter(ButtonType.OK::equals).isEmpty())return;
        uninstallApproved(id);
    }
    private static String capabilityText(com.mcworldexplorer.voxel.resource.ModelPlugin.Descriptor descriptor){
        if(descriptor.capabilities().isEmpty())return "无附加声明";
        return descriptor.capabilities().stream().sorted().map(capability->switch(capability){
            case SAVED_DATA->"存档内容";case NEIGHBORS->"相邻方块";case BIOME_COLOR->"群系颜色";
        }).collect(java.util.stream.Collectors.joining("、"));
    }
    void installApproved(ExternalModelPluginPackage receipt,boolean update){run("正在安装模型插件…",()->store.install(receipt,update),this::accept,failure->{});}
    void uninstallApproved(ResourceLocation id){run("正在卸载模型插件…",()->store.uninstall(id),this::accept,failure->{});}
    void reload(){if(ready())run("正在重新检查模型插件…",()->store.restore(),this::accept,failure->{});else if(!closed&&!busy)initialize();}
    void recoverForPreview(Runnable completed){run("正在恢复模型插件进程…",()->store.restore(),snapshot->{accept(snapshot);completed.run();},failure->{});}
    private void accept(ModelPluginStore.Snapshot snapshot){current=snapshot;applied.accept(snapshot);feedback.accept(snapshot.notices().isEmpty()?"模型插件设置已更新。":String.join("\n",snapshot.notices()));}
    private <T> void run(String message,Callable<T> work,Consumer<T> success,Consumer<Exception> failure){
        if(closed||busy||!idle.getAsBoolean()){failure.accept(new IllegalStateException("当前任务结束后再操作模型插件"));return;}
        busy=true;feedback.accept(message);
        activeTask=executor.submit(()->{
            T value=null;Exception problem=null;
            try{value=work.call();}catch(Exception invalid){problem=invalid;}
            T result=value;Exception error=problem;
            completionDispatcher.accept(()->{
                if(closed)return;busy=false;
                if(error==null)success.accept(result);
                else{feedback.accept("模型插件操作失败："+Objects.toString(error.getMessage(),error.getClass().getSimpleName()));failure.accept(error);}
            });
        });
    }
    @Override public void close(){
        if(closed)return;closed=true;
        if(activeTask!=null)activeTask.cancel(true);
        // Cleanup stays behind the owner task: initialization may not have assigned store yet.
        // Never wait for a plugin process or the store monitor on the FX thread.
        executor.execute(()->{var existing=store;if(existing!=null)existing.close();});
        executor.shutdown();
    }
}
