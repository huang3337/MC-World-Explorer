package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.voxel.data.BlockState;
import com.mcworldexplorer.voxel.data.BlockEntityData;
import com.mcworldexplorer.voxel.data.ChunkCoordinate;
import com.mcworldexplorer.voxel.data.ChunkRectangle;
import com.mcworldexplorer.voxel.data.ChunkSection;
import com.mcworldexplorer.voxel.data.VoxelChunk;
import com.mcworldexplorer.voxel.io.CancellationSignal;
import com.mcworldexplorer.voxel.io.ChunkLoadResult;
import com.mcworldexplorer.voxel.io.ChunkLoadStatus;
import com.mcworldexplorer.voxel.io.VoxelAreaLoadResult;
import com.mcworldexplorer.voxel.mesh.VoxelSceneBuilder;
import com.mcworldexplorer.preview.PreviewLayer;
import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.viewer.*;
import org.junit.jupiter.api.Test;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

class BlockResourceDiagnosticTest {
    private static final BlockState STATE=new BlockState("alpha:machine",Map.of("facing","east"));
    private static final String ELEMENTS="""
        {"textures":{"side":"pack:surface"},"elements":[{"from":[0,0,0],"to":[16,16,16],"faces":{"up":{"texture":"#side"}}}]}
        """;
    static BlockResourceSource source(String model,boolean texture)throws Exception {
        var image=new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB);image.setRGB(0,0,0xffeeeeee);
        var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);byte[] png=bytes.toByteArray();
        return new BlockResourceSource(){
            public Optional<byte[]> read(ResourceLocation.Kind kind,ResourceLocation id){
                if(kind==ResourceLocation.Kind.TEXTURE)return texture?Optional.of(png):Optional.empty();
                return readJson(kind,id).map(s->s.getBytes(StandardCharsets.UTF_8));
            }
            public Optional<String> readJson(ResourceLocation.Kind kind,ResourceLocation id){
                return switch(kind){
                    case BLOCKSTATE->Optional.of("{\"variants\":{\"\":{\"model\":\"alpha:block/machine\"}}}");
                    case MODEL->Optional.of(model);
                    default->Optional.empty();
                };
            }
            public Optional<String> origin(ResourceLocation.Kind kind,ResourceLocation id){
                if(kind==ResourceLocation.Kind.TEXTURE&&!texture)return Optional.empty();
                return Optional.of(kind==ResourceLocation.Kind.TEXTURE?"SelectedPack.zip":"AlphaResources.jar");
            }
            public ClientResourceArchive.Fingerprint fingerprint(){return new ClientResourceArchive.Fingerprint(Path.of("diagnostic-fixture"),1,0,"fixture");}
        };
    }
    @Test void unsupportedLoaderKeepsTypedIdentityAndRealModelSource()throws Exception {
        var source=source("{\"loader\":\"alpha:dynamic\"}",false);
        var result=new BlockModelResolver(source).resolve(STATE);
        assertEquals(Set.of(ResourceLocation.parse("alpha:dynamic")),result.loaders());
        var set=BlockTextureSet.load(source,List.of(STATE),CancellationSignal.NONE);
        var diagnostic=set.diagnostics(STATE).getFirst();
        assertEquals(STATE,diagnostic.state());assertEquals(BlockResourceDiagnostic.Severity.FAILURE,diagnostic.severity());
        assertEquals("UNSUPPORTED_MODEL",diagnostic.reasonCode());assertEquals(List.of("alpha:dynamic"),diagnostic.loaders());
        assertTrue(diagnostic.assets().stream().anyMatch(a->a.kind().equals("MODEL")&&a.id().equals("alpha:block/machine")&&a.origin().equals("AlphaResources.jar")));
    }
    @Test void missingTextureIsFailureAndCrossNamespaceAssetDoesNotChangeBlockIdentity()throws Exception {
        var set=BlockTextureSet.load(source(ELEMENTS,false),List.of(STATE),CancellationSignal.NONE);
        var diagnostic=set.diagnostics(STATE).getFirst();assertEquals("MISSING_TEXTURE",diagnostic.reasonCode());
        assertEquals("alpha:machine",diagnostic.state().name());assertNull(set.surfaces(STATE));
        assertTrue(diagnostic.assets().stream().anyMatch(a->a.kind().equals("TEXTURE")&&a.id().equals("pack:surface")&&a.origin().contains("未记录")));
    }
    @Test void unboundTintIsExplicitDegradationWhileGeometryAndTextureRemainAvailable()throws Exception {
        String model=ELEMENTS.replace("\"texture\":\"#side\"","\"texture\":\"#side\",\"tintindex\":2");
        var set=BlockTextureSet.load(source(model,true),List.of(STATE),CancellationSignal.NONE);
        assertNotNull(set.surfaces(STATE));
        var tint=set.diagnostics(STATE).stream().filter(d->d.reasonCode().equals("UNBOUND_TINT")).findFirst().orElseThrow();
        assertEquals(BlockResourceDiagnostic.Severity.DEGRADED,tint.severity());
        assertTrue(tint.assets().stream().anyMatch(a->a.kind().equals("TEXTURE")&&a.origin().equals("SelectedPack.zip")));
    }
    @Test void failedResourcesPreserveRawStatesAndAllChunkSamplesThroughMeshing()throws Exception {
        var west=new BlockState(STATE.name(),Map.of("facing","west","__mcwe_user","saved-value"));
        var textures=BlockTextureSet.load(source("{\"loader\":\"alpha:dynamic\"}",true),List.of(STATE,west),CancellationSignal.NONE);
        var target=ChunkRectangle.target(0,0,1,0);var results=new LinkedHashMap<ChunkCoordinate,ChunkLoadResult>();
        for(var coordinate:target.expanded(1).coordinates())results.put(coordinate,ChunkLoadResult.withoutChunk(coordinate,ChunkLoadStatus.MISSING,"missing",0,0));
        for(int x=0;x<2;x++){
            long[] data=new long[256];data[0]=x==0?0x21:1;
            var coordinate=new ChunkCoordinate(x,0);var chunk=new VoxelChunk(x,0,List.of(new ChunkSection(0,List.of(BlockState.AIR,STATE,west),data)));
            results.put(coordinate,ChunkLoadResult.success(coordinate,chunk,0,0));
        }
        var world=Path.of(".").toAbsolutePath().normalize();
        var area=new VoxelAreaLoadResult(world,WorldDimension.overworld(world),target,results);
        var scene=new VoxelSceneBuilder(textures).build(area,CancellationSignal.NONE).snapshot().orElseThrow();
        var request=new ViewerRequest(world,"fixture","minecraft:overworld","主世界",target,ViewerPerformanceProfile.ENHANCED,new ViewerMapReturnState(0,0,1,PreviewLayer.surfaceOverview()));
        var report=ResourceDiagnosticReport.from(new ViewerTaskResult(request,scene,false,0));
        assertEquals(2,report.entries().size());assertEquals(3,report.entries().stream().mapToLong(ResourceDiagnosticReport.Entry::occurrences).sum());
        var east=report.entries().stream().filter(e->e.state().get("facing").equals("east")).findFirst().orElseThrow();
        assertEquals(2,east.occurrences());assertEquals(List.of(new ChunkCoordinate(0,0),new ChunkCoordinate(1,0)),east.chunks());
        var savedWest=report.entries().stream().filter(e->e.state().get("facing").equals("west")).findFirst().orElseThrow();
        assertEquals("saved-value",savedWest.state().get("__mcwe_user"));
        assertTrue(report.entries().stream().allMatch(e->e.reasonCode().equals("UNSUPPORTED_MODEL")&&e.loaders().equals(List.of("alpha:dynamic"))));
        assertTrue(report.entries().stream().allMatch(e->e.assets().stream().anyMatch(a->a.kind().equals("MODEL")&&a.origin().equals("AlphaResources.jar"))));
    }
    @Test void resultWrapperRetainsEncounteredLoadersAndLongEvidenceIsExplicitlyTruncated()throws Exception {
        var loader=ResourceLocation.parse("alpha:dynamic");
        var result=new BlockModelResolver.Result(BlockModelResolver.Reason.UNSUPPORTED_MODEL,"failure",Optional.empty(),List.of(),new ClientResourceArchive.Fingerprint(Path.of("fixture"),1,0,"fixture"),new BlockModelResolver.Dependencies(Set.of(),false),Set.of(loader));
        assertSame(result,BlockTintResolver.apply(result,new BlockTintResolver.Colors(Map.of(),"")));
        var resolved=new BlockModelResolver(source(ELEMENTS,true)).resolve(STATE);
        var traced=new BlockModelResolver.Result(resolved.reason(),resolved.detail(),resolved.model(),resolved.sources(),resolved.fingerprint(),resolved.dependencies(),Set.of(loader));
        var tinted=BlockTintResolver.apply(traced,new BlockTintResolver.Colors(Map.of(2,0x100FF00),"fixture"));
        assertNotSame(traced,tinted);assertEquals(Set.of(loader),tinted.loaders());
        var evidence=new BlockResourceDiagnostic(STATE,BlockResourceDiagnostic.Severity.FAILURE,"LONG","x".repeat(5000),List.of(),List.of(),false);
        assertTrue(evidence.truncated());assertTrue(evidence.detail().contains("截断"));
    }
    @Test void identicalPreparedIdentityCannotOverwriteTwoDifferentSavedIdentities()throws Exception {
        var first=new BlockState(STATE.name(),Map.of("__mcwe_entity","saved-first"));
        var second=new BlockState(STATE.name(),Map.of("__mcwe_entity","saved-second"));
        var visual=net.kyori.adventure.nbt.CompoundBinaryTag.builder().putString("id","alpha:machine").putString("Color","red").build();
        var entities=List.of(new BlockEntityData(0,0,0,visual,"shared-visual"),new BlockEntityData(1,0,0,visual,"shared-visual"));
        var prepared=BlockTextureSet.visualState(first,entities.getFirst());
        assertEquals(prepared,BlockTextureSet.visualState(second,entities.get(1)));
        var textures=BlockTextureSet.load(source("{\"loader\":\"alpha:dynamic\"}",true),List.of(prepared),CancellationSignal.NONE);
        long[] data=new long[256];data[0]=0x21;var coordinate=new ChunkCoordinate(0,0);
        var chunk=new VoxelChunk(0,0,List.of(new ChunkSection(0,List.of(BlockState.AIR,first,second),data)),entities);
        var target=ChunkRectangle.target(0,0,0,0);var world=Path.of(".").toAbsolutePath().normalize();
        var results=new LinkedHashMap<ChunkCoordinate,ChunkLoadResult>();
        for(var neighbor:target.expanded(1).coordinates())results.put(neighbor,ChunkLoadResult.withoutChunk(neighbor,ChunkLoadStatus.MISSING,"missing",0,0));
        results.put(coordinate,ChunkLoadResult.success(coordinate,chunk,0,0));
        var scene=new VoxelSceneBuilder(textures).build(new VoxelAreaLoadResult(world,WorldDimension.overworld(world),target,results),CancellationSignal.NONE).snapshot().orElseThrow();
        var request=new ViewerRequest(world,"fixture","minecraft:overworld","主世界",target,ViewerPerformanceProfile.ENHANCED,new ViewerMapReturnState(0,0,1,PreviewLayer.surfaceOverview()));
        var report=ResourceDiagnosticReport.from(new ViewerTaskResult(request,scene,false,0));
        assertEquals(2,report.entries().size());assertTrue(report.entries().stream().allMatch(entry->entry.occurrences()==1));
        assertEquals(Set.of("saved-first","saved-second"),report.entries().stream().map(entry->entry.state().get("__mcwe_entity")).collect(java.util.stream.Collectors.toSet()));
    }
}
