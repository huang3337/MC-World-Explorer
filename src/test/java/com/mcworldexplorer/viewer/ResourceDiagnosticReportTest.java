package com.mcworldexplorer.viewer;

import com.google.gson.JsonParser;
import com.mcworldexplorer.preview.PreviewLayer;
import com.mcworldexplorer.voxel.data.*;
import com.mcworldexplorer.voxel.mesh.*;
import com.mcworldexplorer.voxel.resource.BlockResourceDiagnostic;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ResourceDiagnosticReportTest {
    private static MeshWarning issue(String id,Map<String,String> state,int x,int occurrences,BlockResourceDiagnostic.Severity severity,String code,String text){
        var data=new BlockResourceDiagnostic(new BlockState(id,state),severity,code,text,List.of("example:loader"),
            List.of(new BlockResourceDiagnostic.Asset("MODEL","other:shared","Pack.zip")),false);
        return new MeshWarning(MeshWarning.Code.TEXTURE_FALLBACK,new ChunkCoordinate(x,0),"display text intentionally identical",occurrences,Optional.of(data));
    }
    private static ViewerTaskResult result(List<MeshWarning> warnings,Set<String> confirmed){
        var scene=new VoxelSceneSnapshot(0,0,List.of(),new MeshBounds(0,0,0,0,0,0),0,0,0,0,warnings,Optional.empty(),confirmed);
        var request=new ViewerRequest(Path.of("saved-world"),"测试世界","minecraft:overworld","主世界",ChunkRectangle.target(0,0,2,2),ViewerPerformanceProfile.ENHANCED,new ViewerMapReturnState(0,0,1,PreviewLayer.surfaceOverview()));
        return new ViewerTaskResult(request,scene,false,0);
    }
    @Test void groupsOnlyConfirmedMetadataAndKeepsUnconfirmedNamespaceSeparateFromAssetOwner(){
        var warnings=List.of(issue("alpha:machine",Map.of(),0,2,BlockResourceDiagnostic.Severity.FAILURE,"UNSUPPORTED_MODEL","bad"),
            issue("beta:machine",Map.of(),0,3,BlockResourceDiagnostic.Severity.DEGRADED,"UNBOUND_TINT","color"));
        var report=ResourceDiagnosticReport.from(result(warnings,Set.of("alpha")));
        assertEquals(2,report.groups().size());
        var alpha=report.groups().stream().filter(g->g.namespace().equals("alpha")).findFirst().orElseThrow();assertTrue(alpha.ownerConfirmed());assertEquals(2,alpha.failedOccurrences());
        var beta=report.groups().stream().filter(g->g.namespace().equals("beta")).findFirst().orElseThrow();assertFalse(beta.ownerConfirmed());assertTrue(beta.ownerLabel().contains("未确认"));assertEquals(3,beta.degradedOccurrences());
        assertTrue(report.entries().stream().allMatch(e->e.assets().getFirst().id().equals("other:shared")));
    }
    @Test void identicalDisplayTextCannotMergeDistinctStatesAndCoordinatesRemainActual(){
        var warnings=List.of(issue("alpha:machine",Map.of("facing","east"),0,2,BlockResourceDiagnostic.Severity.FAILURE,"UNSUPPORTED_MODEL","same"),
            issue("alpha:machine",Map.of("facing","west"),0,1,BlockResourceDiagnostic.Severity.FAILURE,"UNSUPPORTED_MODEL","same"),
            issue("alpha:machine",Map.of("facing","east"),1,4,BlockResourceDiagnostic.Severity.FAILURE,"UNSUPPORTED_MODEL","same"));
        var report=ResourceDiagnosticReport.from(result(warnings,Set.of("alpha")));assertEquals(2,report.entries().size());
        var east=report.entries().stream().filter(e->e.state().get("facing").equals("east")).findFirst().orElseThrow();
        assertEquals(6,east.occurrences());assertEquals(List.of(new ChunkCoordinate(0,0),new ChunkCoordinate(1,0)),east.chunks());
    }
    @Test void unstructuredWarningsAreNotParsedIntoGuessedModFailures(){
        var legacy=new MeshWarning(MeshWarning.Code.TEXTURE_FALLBACK,new ChunkCoordinate(0,0),"invented:mod: UNSUPPORTED_MODEL text",4);
        var report=ResourceDiagnosticReport.from(result(List.of(legacy),Set.of("invented")));
        assertTrue(report.entries().isEmpty());assertTrue(report.groups().isEmpty());assertTrue(report.contextDetails().stream().anyMatch(s->s.contains("invented:mod")));
        assertFalse(report.summary().contains("全部通过"));
    }
    @Test void exportContainsStableScopeTypedFieldsAndExplainsUnavailableState(){
        assertFalse(ResourceDiagnosticReport.empty("资源已改变，请重新生成").generated());
        assertTrue(ResourceDiagnosticReport.empty("资源已改变，请重新生成").summary().contains("重新生成"));
        var report=ResourceDiagnosticReport.from(result(List.of(issue("alpha:machine",Map.of("facing","east"),0,2,BlockResourceDiagnostic.Severity.FAILURE,"UNSUPPORTED_MODEL","bad")),Set.of("alpha")));
        var json=JsonParser.parseString(report.toJson()).getAsJsonObject();assertEquals(1,json.get("schemaVersion").getAsInt());
        assertTrue(json.get("scope").getAsString().contains("minecraft:overworld"));assertTrue(json.get("scope").getAsString().contains("0..2"));
        var entry=json.getAsJsonArray("entries").get(0).getAsJsonObject();assertEquals("alpha:machine",entry.get("blockId").getAsString());assertEquals("FAILURE",entry.get("severity").getAsString());
        assertTrue(entry.get("ownerConfirmed").getAsBoolean());assertEquals("east",entry.getAsJsonObject("state").get("facing").getAsString());
        assertTrue(report.toText().contains("Pack.zip"));assertTrue(report.toText().contains("不代表整个 Mod"));
    }
    @Test void reportBudgetMarksOmissionsAndRetainsFailuresBeforeInfo(){
        var warnings=new ArrayList<MeshWarning>();
        for(int i=0;i<ResourceDiagnosticReport.MAX_ENTRIES+2;i++)warnings.add(issue("alpha:block_"+i,Map.of(),0,1,BlockResourceDiagnostic.Severity.INFO,"MODEL_NOTE","note"));
        warnings.add(issue("beta:failed",Map.of(),0,3,BlockResourceDiagnostic.Severity.FAILURE,"MISSING_TEXTURE","missing"));
        var report=ResourceDiagnosticReport.from(result(warnings,Set.of("alpha","beta")));
        assertTrue(report.truncated());assertEquals(ResourceDiagnosticReport.MAX_ENTRIES,report.entries().size());assertEquals(3,report.omittedWarningCount());assertEquals(3,report.omittedOccurrences());
        assertTrue(report.entries().stream().anyMatch(e->e.blockId().equals("beta:failed")));assertTrue(report.summary().contains("不是全量"));
    }
    @Test void contextBudgetReportsOmittedRecordsWithoutInventingModResults(){
        var warnings=new ArrayList<MeshWarning>();
        for(int i=0;i<ResourceDiagnosticReport.MAX_CONTEXT_DETAILS+2;i++)warnings.add(new MeshWarning(MeshWarning.Code.RESOURCE_WARNING,new ChunkCoordinate(0,0),"context "+i));
        var report=ResourceDiagnosticReport.from(result(warnings,Set.of()));
        assertTrue(report.truncated());assertEquals(2,report.omittedContextDetails());assertEquals(0,report.omittedWarningCount());
        assertTrue(report.entries().isEmpty());assertTrue(report.contextDetails().stream().anyMatch(s->s.contains("2 条环境说明")));
    }
    @Test void sceneOnlyDiagnosticsParticipateInSceneCacheBudget(){
        var loaded=result(List.of(issue("alpha:machine",Map.of("facing","east"),0,2,BlockResourceDiagnostic.Severity.FAILURE,"UNSUPPORTED_MODEL","failed evidence")),Set.of());
        long bytes=ViewerSceneCache.estimatedBytes(loaded.snapshot());assertTrue(bytes>0);
        var cache=new ViewerSceneCache(2,bytes-1);var key=loaded.request().areaKey("diagnostics");
        assertFalse(cache.put(key,loaded.snapshot(),new RegionSourceSignature(List.of())));assertEquals(0,cache.currentBytes());
    }
}
