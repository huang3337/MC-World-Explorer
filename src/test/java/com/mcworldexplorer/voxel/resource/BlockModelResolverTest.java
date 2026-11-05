package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.voxel.data.BlockState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.mcworldexplorer.voxel.resource.BlockModelResolver.Reason.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Core protocol regression; no deferred adapters on the test classpath. */
class BlockModelResolverTest {
    @TempDir Path home;
    private int sequence;
    private static final String STATE = "assets/minecraft/blockstates/stone.json";
    private static final String MODEL = "assets/minecraft/models/block/stone.json";
    private static final String PARENT = "assets/minecraft/models/block/base.json";
    private static final String SIMPLE_STATE = "{\"variants\":{\"\":{\"model\":\"block/stone\"}}}";

    // Deliberately synthetic geometry; no game assets are distributed with tests.
    private static String cube() {
        List<String> faces = new ArrayList<>();
        for (String face : List.of("down", "up", "north", "south", "west", "east"))
            faces.add("\"" + face + "\":{\"texture\":\"#all\",\"cullface\":\"" + face + "\"}");
        return "[{\"from\":[0,0,0],\"to\":[16,16,16],\"faces\":{" + String.join(",", faces) + "}}]";
    }

    @Test void combinesNestedMultipartConditionsAndKeepsPerPartTransforms() throws Exception {
        String multipart="{\"multipart\":[{\"apply\":{\"model\":\"block/stone\"}},{\"when\":{\"OR\":[{\"axis\":\"x|z\"},{\"AND\":[{\"axis\":\"y\"},{\"open\":\"true\"}]}]},\"apply\":[{\"model\":\"block/stone\",\"y\":90,\"uvlock\":true}]}]}";
        try(var archive=open(Map.of(STATE,multipart))) {
            var resolver=new BlockModelResolver(archive,ModelRuleRegistry.defaults());
            var connected=resolver.resolve(state(Map.of("axis","z"))).model().orElseThrow();
            assertEquals(2,connected.parts().size());assertEquals(12,connected.faces().size());
            assertEquals(90,connected.parts().getLast().yRotation());assertTrue(connected.parts().getLast().uvLock());
            assertTrue(connected.fixedFirstAlternative());
            assertEquals(1,resolver.resolve(state(Map.of("axis","y","open","false"))).model().orElseThrow().parts().size());
            assertEquals(2,resolver.resolve(state(Map.of("axis","y","open","true"))).model().orElseThrow().parts().size());
        }
        assertFallback(INVALID_MODEL,STATE,"{\"multipart\":[{\"when\":{\"OR\":[]},\"apply\":{\"model\":\"block/stone\"}}]}",Map.of());
    }

    @Test void inheritsChildAliasesOverParentAndKeepsTraceAndUv() throws Exception {
        String base = "{\"textures\":{\"all\":\"#surface\",\"surface\":\"block/old\"},\"elements\":" + cube() + "}";
        String child = "{\"parent\":\"block/base\",\"textures\":{\"surface\":\"block/new\"}}";
        try (var archive = open(Map.of(MODEL, child, PARENT, base))) {
            var result = new BlockModelResolver(archive,ModelRuleRegistry.defaults()).resolve(state(Map.of()));
            assertTrue(result.resolved(), result.detail());
            var model = result.model().orElseThrow();
            assertEquals(6, model.faces().size());
            assertTrue(model.faces().stream().allMatch(f -> f.texture().toString().equals("minecraft:block/new")));
            assertEquals(new BlockModelResolver.Uv(0, 0, 16, 16), model.faces().getFirst().uv());
            assertEquals(-1, model.faces().getFirst().tintIndex());
            assertEquals(archive.fingerprint(), result.fingerprint());
            assertEquals(4, result.sources().size());
            assertThrows(UnsupportedOperationException.class, () -> model.faces().clear());
        }
    }

    @Test void selectsPropertiesAndPreservesRotationUvlockAndFixedCandidatePolicy() throws Exception {
        String json = "{\"variants\":{\"axis=x\":[{\"model\":\"block/stone\",\"x\":90,\"y\":270,\"uvlock\":true,\"weight\":2},"
                + "{\"model\":\"block/not_selected\"}],\"axis=y\":{\"model\":\"block/missing\"}}}";
        try (var archive = open(Map.of(STATE, json))) {
            var resolver = new BlockModelResolver(archive,ModelRuleRegistry.defaults());
            var model = resolver.resolve(state(Map.of("axis", "x"))).model().orElseThrow();
            assertEquals(90, model.xRotation()); assertEquals(270, model.yRotation());
            assertTrue(model.uvLock()); assertTrue(model.fixedFirstAlternative());
            assertEquals(NO_VARIANT, resolver.resolve(state(Map.of("axis", "z"))).reason());
            assertEquals(MISSING_RESOURCE, resolver.resolve(state(Map.of("axis", "y"))).reason());
        }
        assertFallback(AMBIGUOUS_VARIANT, STATE,
                "{\"variants\":{\"\":{\"model\":\"block/stone\"},\"axis=x\":{\"model\":\"block/stone\"}}}", Map.of("axis", "x"));
    }

    @Test void retainsExplicitUvFaceRotationTintAndOverlayLayers() throws Exception {
        String base = cube().replace("\"texture\":\"#all\",\"cullface\":\"up\"",
                "\"texture\":\"#all\",\"uv\":[16,0,0,16],\"rotation\":180,\"tintindex\":0,\"cullface\":\"up\"");
        List<String> overlay = new ArrayList<>();
        for (String d : List.of("north", "south", "west", "east"))
            overlay.add("\"" + d + "\":{\"texture\":\"#all\",\"tintindex\":0,\"cullface\":\"" + d + "\"}");
        String elements = base.substring(0, base.length() - 1) + ",{\"from\":[0,0,0],\"to\":[16,16,16],\"faces\":{" + String.join(",", overlay) + "}}]";
        try (var archive = open(Map.of(MODEL, model(elements)))) {
            var resolved = new BlockModelResolver(archive,ModelRuleRegistry.defaults()).resolve(state(Map.of())).model().orElseThrow();
            assertEquals(10, resolved.faces().size());
            var top = resolved.faces().stream().filter(f -> f.direction() == BlockModelResolver.Direction.UP).findFirst().orElseThrow();
            assertEquals(180, top.rotation()); assertEquals(0, top.tintIndex());
            assertEquals(new BlockModelResolver.Uv(16, 0, 0, 16), top.uv());
            assertEquals(4, resolved.faces().stream().filter(f -> f.layer() == 1).count());
        }
    }

    @Test void detectsParentAndTextureCyclesAndMissingReferences() throws Exception {
        assertFallback(REFERENCE_CYCLE, MODEL, "{\"parent\":\"block/stone\"}", Map.of());
        try (var archive = open(Map.of(MODEL, "{\"parent\":\"block/base\"}", PARENT, "{\"parent\":\"block/stone\"}"))) {
            assertEquals(REFERENCE_CYCLE, new BlockModelResolver(archive,ModelRuleRegistry.defaults()).resolve(state(Map.of())).reason());
        }
        assertFallback(REFERENCE_CYCLE, MODEL, model(cube()).replace("\"block/synthetic\"", "\"#all\""), Map.of());
        assertFallback(MISSING_RESOURCE, MODEL, model(cube()).replace("\"block/synthetic\"", "\"#absent\""), Map.of());
        assertFallback(MISSING_RESOURCE, MODEL, "{\"parent\":\"block/absent\"}", Map.of());
        assertFallback(INVALID_MODEL, MODEL, model(cube()).replace("block/synthetic", "../escape"), Map.of());
    }

    @Test void rejectsCustomLoadersAndInvalidRotationsRatherThanPretendingSupport() throws Exception {
        assertFallback(UNSUPPORTED_MODEL, MODEL, model(cube()).replace("\"textures\":", "\"loader\":\"custom\",\"textures\":"), Map.of());
        assertFallback(INVALID_MODEL, MODEL, model(cube()).replace("\"from\":", "\"rotation\":{},\"from\":"), Map.of());
        try(var archive=open(Map.of(STATE,"{\"multipart\":[]}"))) {
            var empty=new BlockModelResolver(archive,ModelRuleRegistry.defaults()).resolve(state(Map.of()));
            assertTrue(empty.resolved());assertTrue(empty.model().orElseThrow().faces().isEmpty());
        }
        assertFallback(UNSUPPORTED_MODEL, STATE, SIMPLE_STATE.replace("\"model\":", "\"x\":45,\"model\":"), Map.of());
        try (var archive = open(Map.of())) {
            var resolver = new BlockModelResolver(archive,ModelRuleRegistry.defaults());
            assertEquals(MISSING_RESOURCE, resolver.resolve(new BlockState("mod:stone", Map.of())).reason());
            assertEquals(MISSING_RESOURCE, resolver.resolve(new BlockState("minecraft:oak_stairs", Map.of())).reason());
        }
    }

    @Test void parsesStrictJsonAndRejectsDuplicateKeysTypesAndTrailingData() throws Exception {
        for (String json : List.of("// comment\n" + SIMPLE_STATE, SIMPLE_STATE + " true", "{broken",
                SIMPLE_STATE.replace("\"variants\":", "\"variants\":{},\"variants\":"),
                SIMPLE_STATE.replace("\"model\":", "\"x\":0,\"x\":90,\"model\":")))
            assertFallback(INVALID_JSON, STATE, json, Map.of());
        for (String json : List.of(SIMPLE_STATE.replace("\"block/stone\"", "4"),
                SIMPLE_STATE.replace("\"model\":", "\"x\":\"90\",\"model\":"),
                SIMPLE_STATE.replace("\"model\":", "\"uvlock\":1,\"model\":"),
                SIMPLE_STATE.replace("\"model\":", "\"x\":1.5,\"model\":")))
            assertFallback(INVALID_MODEL, STATE, json, Map.of());
    }

    @Test void enforcesNestingNodeAndParentBudgets() throws Exception {
        assertFallback(INVALID_JSON, STATE, "[".repeat(40) + "0" + "]".repeat(40), Map.of());
        assertFallback(LIMIT_EXCEEDED, STATE, "[" + "0,".repeat(BlockModelResolver.MAX_JSON_NODES) + "0]", Map.of());
        Map<String, String> extras = new HashMap<>();
        extras.put(MODEL, "{\"parent\":\"block/p0\"}");
        for (int i = 0; i < 33; i++) extras.put("assets/minecraft/models/block/p" + i + ".json", "{\"parent\":\"block/p" + (i + 1) + "\"}");
        try (var archive = open(extras)) {
            assertEquals(LIMIT_EXCEEDED, new BlockModelResolver(archive,ModelRuleRegistry.defaults()).resolve(state(Map.of())).reason());
        }
        String oversized = "{\"variants\":{\"\":[" + ("{\"model\":\"block/stone\"},").repeat(256) + "{\"model\":\"block/stone\"}]}}";
        assertFallback(LIMIT_EXCEEDED, STATE, oversized, Map.of());
    }

    @Test void boundsTextureVariablesAndRejectsInvalidSelectorsAndFaceValues() throws Exception {
        List<String> textures = new ArrayList<>();
        for (int i = 0; i < 129; i++) textures.add("\"v" + i + "\":\"block/synthetic\"");
        assertFallback(LIMIT_EXCEEDED, MODEL, "{\"textures\":{" + String.join(",", textures) + "}}", Map.of());
        for (String selector : List.of("axis=x,axis=y", "axis=", "axis"))
            assertFallback(INVALID_MODEL, STATE, SIMPLE_STATE.replace("\"\":", "\"" + selector + "\":"), Map.of());
        assertFallback(NO_VARIANT, STATE, SIMPLE_STATE.replace("\"\":", "\"axis=x|z\":"), Map.of());
        assertFallback(INVALID_MODEL, MODEL, model(cube()).replace("\"cullface\":\"up\"", "\"uv\":[0,0,16],\"cullface\":\"up\""), Map.of());
        assertFallback(LIMIT_EXCEEDED, MODEL, model(cube()).replace("\"cullface\":\"up\"", "\"uv\":[0,0,257,16],\"cullface\":\"up\""), Map.of());
        assertFallback(INVALID_MODEL, MODEL, model(cube()).replace("\"up\":", "\"unknown\":"), Map.of());
        try(var archive=open(Map.of(MODEL,model("[]")))) {
            assertTrue(new BlockModelResolver(archive,ModelRuleRegistry.defaults()).resolve(state(Map.of())).model().orElseThrow().faces().isEmpty());
        }
    }

    @Test void childElementsReplaceParentsAndOnlyReferencedTexturesAreResolved() throws Exception {
        try (var archive = open(Map.of(PARENT, model("[]"), MODEL,
                model(cube()).replace("\"textures\":", "\"parent\":\"block/base\",\"textures\":")))) {
            assertTrue(new BlockModelResolver(archive,ModelRuleRegistry.defaults()).resolve(state(Map.of())).resolved());
        }
        try(var archive=open(Map.of(MODEL,model(cube()).replace("\"all\":", "\"particle\":\"#particle\",\"unused\":\"#missing\",\"all\":")))) {
            assertTrue(new BlockModelResolver(archive,ModelRuleRegistry.defaults()).resolve(state(Map.of())).resolved(),"未引用的编辑器变量不影响实际几何");
        }
    }

    @Test void propagatesClosedAndChangedArchiveInsteadOfCachingStaleModels() throws Exception {
        var archive = open(Map.of());
        var resolver = new BlockModelResolver(archive,ModelRuleRegistry.defaults());
        assertTrue(resolver.resolve(state(Map.of())).resolved());
        archive.close();
        var failure = assertThrows(ResourceAccessException.class, () -> resolver.resolve(state(Map.of())));
        assertEquals(ResourceAccessException.Reason.CLOSED, failure.reason());
        try (var changed = open(Map.of())) {
            var second = new BlockModelResolver(changed,ModelRuleRegistry.defaults());
            assertTrue(second.resolve(state(Map.of())).resolved());
            Path path = changed.fingerprint().path();
            Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(changed.fingerprint().modifiedMillis() + 10_000));
            assertEquals(ResourceAccessException.Reason.SOURCE_CHANGED,
                    assertThrows(ResourceAccessException.class, () -> second.resolve(state(Map.of()))).reason());
        }
    }

    @Test void resolvesRealClientAllTargetsAndAxesWithoutWritingSource() throws Exception {
        String configured = System.getenv("MCWORLD_CLIENT_RESOURCE_JAR");
        assumeTrue(configured != null && !configured.isBlank(), "真实客户端仅显式启用");
        Path jar = Path.of(configured);
        String before = stamp(jar);
        try (var archive = ClientResourceArchive.open(jar, "1.21.1")) {
            var resolver = new BlockModelResolver(archive,ModelRuleRegistry.defaults());
            for (String name : List.of("stone", "cobblestone", "dirt", "oak_planks", "bricks", "sand")) {
                var result = resolver.resolve(new BlockState("minecraft:" + name, Map.of()));
                assertTrue(result.resolved(), name + ": " + result.detail());
                assertEquals(6, result.model().orElseThrow().faces().size());
            }
            for (boolean snowy : List.of(false, true)) {
                var result = resolver.resolve(new BlockState("minecraft:grass_block", Map.of("snowy", "" + snowy)));
                assertTrue(result.resolved(), result.detail());
                assertEquals(snowy ? 6 : 10, result.model().orElseThrow().faces().size());
                var top = result.model().orElseThrow().faces().stream().filter(f -> f.direction() == BlockModelResolver.Direction.UP).findFirst().orElseThrow();
                assertEquals("minecraft:block/grass_block_top", top.texture().toString());
                assertEquals(snowy ? -1 : 0, top.tintIndex());
            }
            for (String axis : List.of("x", "y", "z")) {
                var result = resolver.resolve(new BlockState("minecraft:oak_log", Map.of("axis", axis)));
                assertTrue(result.resolved(), result.detail());
                var model = result.model().orElseThrow();
                assertEquals(axis.equals("y") ? 0 : 90, model.xRotation());
                assertEquals(axis.equals("x") ? 90 : 0, model.yRotation());
                assertEquals(2, model.faces().stream().filter(f -> f.texture().path().equals("block/oak_log_top")).count());
                assertEquals(axis.equals("y") ? 0 : 180, model.faces().stream().filter(f -> f.direction() == BlockModelResolver.Direction.UP).findFirst().orElseThrow().rotation());
            }
        }
        assertEquals(before, stamp(jar));
    }

    @Test void cachedModelsStillCheckSourceLifecycleAndEmptyGeometryIsValid() throws Exception {
        var archive=open(Map.of());var resolver=new BlockModelResolver(archive,ModelRuleRegistry.defaults());
        assertTrue(resolver.resolve(state(Map.of())).resolved());archive.close();
        var failure=assertThrows(ResourceAccessException.class,()->resolver.resolve(state(Map.of())));
        assertEquals(ResourceAccessException.Reason.CLOSED,failure.reason());
        try(var empty=open(Map.of(MODEL,"{\"textures\":{\"particle\":\"block/synthetic\"}}"))) {
            var result=new BlockModelResolver(empty,ModelRuleRegistry.defaults()).resolve(state(Map.of()));
            assertTrue(result.resolved());assertTrue(result.model().orElseThrow().faces().isEmpty());
        }
        try(var optional=open(Map.of(MODEL,model(cube()).replace("\"cullface\":\"down\"","\"cullface\":\"bottom\"")))) {
            var result=new BlockModelResolver(optional,ModelRuleRegistry.defaults()).resolve(state(Map.of()));assertTrue(result.resolved());
            assertNull(result.model().orElseThrow().faces().stream().filter(f->f.direction()==BlockModelResolver.Direction.DOWN).findFirst().orElseThrow().cullFace());
        }
    }

    @Test void lowNodeLargeStringsCannotBypassParsedCacheMemoryBound() throws Exception {
        Map<String,Integer> reads=new java.util.HashMap<>();String comment="x".repeat(900_000);
        BlockResourceSource source=new BlockResourceSource(){
            public ClientResourceArchive.Fingerprint fingerprint(){return new ClientResourceArchive.Fingerprint(home,1,1,"synthetic");}
            public java.util.Optional<byte[]> read(ResourceLocation.Kind kind,ResourceLocation id){return java.util.Optional.empty();}
            public java.util.Optional<String> readJson(ResourceLocation.Kind kind,ResourceLocation id){
                if(kind==ResourceLocation.Kind.BLOCKSTATE)return java.util.Optional.of("{\"variants\":{\"\":{\"model\":\"example:block/"+id.path()+"\"}}}");
                reads.merge(id.path(),1,Integer::sum);return java.util.Optional.of("{\"__comment\":\""+comment+"\",\"elements\":[]}");
            }
        };
        var resolver=new BlockModelResolver(source,ModelRuleRegistry.defaults());
        for(int i=0;i<12;i++)assertTrue(resolver.resolve(new BlockState("example:shape"+i,Map.of())).resolved());
        assertTrue(resolver.resolve(new BlockState("example:shape0",Map.of())).resolved());
        assertEquals(2,reads.get("block/shape0"),"大字符串模型应按字符预算逐出，而非只计算节点数");
    }

    private static String stamp(Path path) throws Exception {
        return Files.size(path) + ":" + Files.getLastModifiedTime(path) + ":"
                + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
    @Test void editorMarkerAndUnrestrictedAliasKeepActualTextureAndTint() throws Exception {
        String json=model(cube()).replace("{\"textures\":","{\"comment\":\"author note\",\"textures\":").replace("\"from\":", "\"comment\":\"part note\",\"Comment\":\"editor description\",\"color\":3,\"from\":").replace("all", "Surface.Diffuse-0");
        try(var archive=open(Map.of(MODEL,json))) {
            var result=new BlockModelResolver(archive,ModelRuleRegistry.defaults()).resolve(state(Map.of()));assertTrue(result.resolved(),result.detail());
            assertEquals(6,result.model().orElseThrow().faces().size());
            for(var face:result.model().orElseThrow().faces()){assertEquals(-1,face.tintIndex());assertEquals("minecraft:block/synthetic",face.texture().toString());}
        }
        assertFallback(INVALID_MODEL,MODEL,model(cube()).replace("block/synthetic","../outside"),Map.of());
        assertFallback(INVALID_MODEL,MODEL,model(cube()).replace("all","bad\\u0000key"),Map.of());
    }
    @Test void rotationPivotHasSeparateBoundsAndDefaultWithoutRelaxingElementCorners()throws Exception {
        String prefix="\"rotation\":{\"origin\":[-24,2,5],\"axis\":\"y\",\"angle\":0},\"from\":";
        try(var archive=open(Map.of(MODEL,model(cube()).replace("\"from\":",prefix)))) {
            var result=new BlockModelResolver(archive,ModelRuleRegistry.defaults()).resolve(state(Map.of()));assertTrue(result.resolved(),result.detail());
            assertEquals(new BlockModelResolver.Vec3(-24,2,5),result.model().orElseThrow().faces().getFirst().elementRotation().origin());
        }
        try(var archive=open(Map.of(MODEL,model(cube()).replace("\"from\":","\"rotation\":{\"axis\":\"y\",\"angle\":0},\"from\":")))){assertTrue(new BlockModelResolver(archive,ModelRuleRegistry.defaults()).resolve(state(Map.of())).resolved());}
        assertFallback(LIMIT_EXCEEDED,MODEL,model(cube()).replace("[0,0,0]","[-17,0,0]"),Map.of());
        assertFallback(LIMIT_EXCEEDED,MODEL,model(cube()).replace("\"from\":",prefix.replace("-24","-513")),Map.of());
    }
    @Test void manySelectorsDoNotRelaxMultipartOrRandomAlternativeBudgets()throws Exception {
        var variants=new StringJoiner(",","{\"variants\":{","}}");
        for(int i=0;i<576;i++)variants.add("\"index="+i+"\":{\"model\":\"block/stone\"}");
        try(var archive=open(Map.of(STATE,variants.toString()))){assertTrue(new BlockModelResolver(archive,ModelRuleRegistry.defaults()).resolve(state(Map.of("index","575"))).resolved());}
        String choices="["+String.join(",",Collections.nCopies(257,"{\"model\":\"block/stone\"}"))+"]";
        assertFallback(LIMIT_EXCEEDED,STATE,"{\"variants\":{\"\":"+choices+"}}",Map.of());
        assertFallback(LIMIT_EXCEEDED,STATE,"{\"multipart\":["+String.join(",",Collections.nCopies(257,"{\"apply\":{\"model\":\"block/stone\"}}"))+"]}",Map.of());
        var oversized=new StringJoiner(",","{\"variants\":{","}}");for(int i=0;i<BlockModelResolver.MAX_STATE_BRANCHES+1;i++)oversized.add("\"index="+i+"\":{\"model\":\"block/stone\"}");
        assertFallback(LIMIT_EXCEEDED,STATE,oversized.toString(),Map.of("index","0"));
    }
    @Test void boundedDetailedModelsKeepAllFacesAndRejectOversizedInput()throws Exception {
        String element="{\"from\":[0,0,0],\"to\":[1,1,1],\"faces\":{\"up\":{\"texture\":\"#all\"}}}";
        try(var archive=open(Map.of(MODEL,model("["+String.join(",",Collections.nCopies(180,element))+"]")))){var result=new BlockModelResolver(archive,ModelRuleRegistry.defaults()).resolve(state(Map.of()));assertTrue(result.resolved(),result.detail());assertEquals(180,result.model().orElseThrow().faces().size());}
        assertFallback(LIMIT_EXCEEDED,MODEL,model("["+String.join(",",Collections.nCopies(257,element))+"]"),Map.of());
    }


    @Test void requestedGeometricNormalsAreBakedFromRotatedVerticesAndUnknownMaterialDataIsNotIgnored()throws Exception {
        String json=model(cube()).replace("\"from\":","\"neoforge_data\":{\"calculate_normals\":true},\"rotation\":{\"angle\":22.5,\"axis\":\"x\",\"origin\":[8,8,8]},\"from\":");
        try(var archive=open(Map.of(MODEL,json))) {
            var result=new BlockModelResolver(archive,ModelRuleRegistry.defaults()).resolve(state(Map.of()));assertTrue(result.resolved(),result.detail());var part=result.model().orElseThrow().parts().getFirst();var top=part.faces().stream().filter(face->face.direction()==BlockModelResolver.Direction.UP).findFirst().orElseThrow();var normal=ModelGeometry.bake(top,part).normal();
            assertEquals(Math.cos(Math.toRadians(22.5)),normal.get(1),1e-6);assertEquals(Math.sin(Math.toRadians(22.5)),normal.get(2),1e-6);
        }
        assertFallback(UNSUPPORTED_MODEL,MODEL,json.replace("calculate_normals\":true","calculate_normals\":false"),Map.of());
        assertFallback(INVALID_MODEL,MODEL,json.replace("calculate_normals\":true","calculate_normals\":\"true\""),Map.of());
        try(var archive=open(Map.of(MODEL,json.replace("calculate_normals\":true","color\":4294901760")))){assertEquals(0xFFFF0000,new BlockModelResolver(archive,ModelRuleRegistry.defaults()).resolve(state(Map.of())).model().orElseThrow().faces().getFirst().material().argb());}
    }
    @Test void vanillaAmbientOcclusionAndRescaleAcceptCanonicalBooleanStringsWithoutRelaxingShade()throws Exception {
        String json=model(cube()).replace("{\"textures\":","{\"ambientocclusion\":\"false\",\"textures\":").replace("\"from\":","\"rotation\":{\"axis\":\"y\",\"angle\":22.5,\"origin\":[8,8,8],\"rescale\":\"true\"},\"from\":");
        try(var archive=open(Map.of(MODEL,json))){var result=new BlockModelResolver(archive,ModelRuleRegistry.defaults()).resolve(state(Map.of()));assertTrue(result.resolved(),result.detail());assertTrue(result.model().orElseThrow().faces().getFirst().elementRotation().rescale());}
        assertFallback(INVALID_MODEL,MODEL,json.replace("ambientocclusion\":\"false\"","ambientocclusion\":\"unrecognized\""),Map.of());
        assertFallback(INVALID_MODEL,MODEL,json.replace("\"from\":","\"shade\":\"true\",\"from\":"),Map.of());
    }
    @Test void blockstateQuarterTurnsNormalizeSignedAndWrappedAnglesButFaceUvStaysStrict()throws Exception {
        for(int angle:List.of(-450,-360,-90,0,90,360,450,720)) {
            String json="{\"variants\":{\"\":{\"model\":\"block/stone\",\"x\":"+angle+",\"y\":"+(-angle)+",\"uvlock\":\"true\"}}}";
            try(var archive=open(Map.of(STATE,json))){var result=new BlockModelResolver(archive,ModelRuleRegistry.defaults()).resolve(state(Map.of()));assertTrue(result.resolved(),result.detail());var part=result.model().orElseThrow().parts().getFirst();assertEquals(Math.floorMod(angle,360),part.xRotation());assertEquals(Math.floorMod(-angle,360),part.yRotation());assertTrue(part.uvLock());}
        }
        assertFallback(UNSUPPORTED_MODEL,STATE,"{\"variants\":{\"\":{\"model\":\"block/stone\",\"x\":45}}}",Map.of());
        assertFallback(UNSUPPORTED_MODEL,MODEL,model(cube()).replace("\"texture\":", "\"rotation\":-90,\"texture\":"),Map.of());
    }
    @Test void fancyMaterialDoesNotUseOrFallBackToTheFastGraphicsHint()throws Exception {
        String parent=model(cube()).replace("{\"textures\":", "{\"render_type\":\"minecraft:cutout\",\"render_type_fast\":\"minecraft:solid\",\"textures\":");
        try(var archive=open(Map.of(MODEL,"{\"parent\":\"block/base\"}",PARENT,parent))) {
            var result=new BlockModelResolver(archive,ModelRuleRegistry.defaults()).resolve(state(Map.of()));assertTrue(result.resolved(),result.detail());
            assertEquals("minecraft:cutout",result.model().orElseThrow().parts().getFirst().renderType());
            assertEquals(6,result.model().orElseThrow().faces().size());
        }
        String fastOnly=model(cube()).replace("{\"textures\":", "{\"render_type_fast\":\"third_party:fast_only\",\"textures\":");
        try(var archive=open(Map.of(MODEL,fastOnly))) {
            var result=new BlockModelResolver(archive,ModelRuleRegistry.defaults()).resolve(state(Map.of()));assertTrue(result.resolved(),result.detail());
            assertNull(result.model().orElseThrow().parts().getFirst().renderType());
        }
        assertFallback(INVALID_MODEL,MODEL,fastOnly.replace("\"third_party:fast_only\"","{}"),Map.of());
        assertFallback(INVALID_MODEL,MODEL,fastOnly.replace("third_party:fast_only","../escape"),Map.of());
        assertFallback(UNSUPPORTED_MODEL,MODEL,parent.replace("minecraft:cutout","third_party:custom_fancy"),Map.of());
    }
    @Test void largeCartesianStateTablesSelectTheirLastBranchWithinIndependentBudgets()throws Exception {
        var variants=new StringJoiner(",","{\"variants\":{","}}");
        for(int i=0;i<4096;i++)variants.add("\"index="+i+"\":{\"model\":\"block/"+(i==4095?"stone":"absent")+"\"}");
        try(var archive=open(Map.of(STATE,variants.toString()))) {
            var result=new BlockModelResolver(archive,ModelRuleRegistry.defaults()).resolve(state(Map.of("index","4095")));assertTrue(result.resolved(),result.detail());
            assertEquals(6,result.model().orElseThrow().faces().size());
            assertTrue(result.sources().stream().noneMatch(item->item.id().path().contains("absent")));
        }
        String choices="{\"variants\":{\"\":["+String.join(",",Collections.nCopies(257,"{\"model\":\"block/stone\"}"))+"]}}";
        assertFallback(LIMIT_EXCEEDED,STATE,choices,Map.of());
    }
    @Test void multipartCommentMetadataDoesNotHideAnyRenderingDirective()throws Exception {
        String json="{\"multipart\":[{\"__comment\":{\"note\":\"author metadata\"},\"apply\":{\"model\":\"block/stone\"}}]}";
        try(var archive=open(Map.of(STATE,json))){var result=new BlockModelResolver(archive,ModelRuleRegistry.defaults()).resolve(state(Map.of()));assertTrue(result.resolved(),result.detail());assertEquals(6,result.model().orElseThrow().faces().size());}
        assertFallback(UNSUPPORTED_MODEL,STATE,json.replace("__comment","unknown_render_condition"),Map.of());
    }
    private static String model(String elements) { return "{\"textures\":{\"all\":\"block/synthetic\"},\"elements\":" + elements + "}"; }
    private static BlockState state(Map<String, String> properties) { return new BlockState("minecraft:stone", properties); }
    private void assertFallback(BlockModelResolver.Reason reason, String path, String json, Map<String, String> properties) throws Exception {
        try (var archive = open(Map.of(path, json))) {
            var result = new BlockModelResolver(archive,ModelRuleRegistry.defaults()).resolve(state(properties));
            assertEquals(reason, result.reason(), result.detail());
            assertTrue(result.model().isEmpty()); assertFalse(result.detail().isBlank());
            assertFalse(result.sources().isEmpty());
        }
    }
    private ClientResourceArchive open(Map<String, String> replacements) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("version.json", "{\"id\":\"1.21.1\",\"world_version\":3955,\"pack_version\":{\"resource\":34}}");
        entries.put(STATE, SIMPLE_STATE); entries.put(MODEL, model(cube()));
        entries.put("assets/minecraft/textures/block/stone.png", "synthetic placeholder");
        entries.putAll(replacements);
        Path path = home.resolve("fixture-" + sequence++ + ".jar");
        try (var out = new ZipOutputStream(Files.newOutputStream(path))) {
            for (var entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8)); out.closeEntry();
            }
        }
        return ClientResourceArchive.open(path, "1.21.1");
    }
}
