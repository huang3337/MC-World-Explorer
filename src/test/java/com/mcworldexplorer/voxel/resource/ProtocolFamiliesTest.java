package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.voxel.data.*;
import com.mcworldexplorer.voxel.io.*;
import com.mcworldexplorer.voxel.mesh.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ProtocolFamiliesTest {
    private static Path path(String key){String value=System.getenv(key);assumeTrue(value!=null,key);return Path.of(value);}
    private static Path client(Path world){Path instance=world.getParent().getParent();return instance.resolve(instance.getFileName()+".jar");}

    @Test void flowTagsMergeEffectiveLayersBeforeResolvingReferences()throws Exception {
        var tag=ResourceLocation.parse("minecraft:blocks_fluid_flow");
        var source=new BlockResourceSource(){
            public Optional<byte[]> read(ResourceLocation.Kind k,ResourceLocation id){return Optional.empty();}
            public Optional<String> readJson(ResourceLocation.Kind k,ResourceLocation id){return Optional.empty();}
            public ClientResourceArchive.Fingerprint fingerprint(){throw new AssertionError();}
            public List<String> dataLayers(ResourceLocation id){
                return switch(id.path()){
                    case "tags/block/blocks_fluid_flow.json" -> List.of(
                        "{\"values\":[\"#old:missing\"]}",
                        "{\"replace\":true,\"values\":[\"minecraft:stone\",\"#custom:extra\"]}",
                        "{\"values\":[{\"id\":\"#optional:missing\",\"required\":false}]}");
                    case "tags/block/extra.json" -> List.of("{\"values\":[\"custom:barrier\"]}");
                    case "tags/block/cycle.json" -> List.of("{\"values\":[\"#custom:cycle\"]}");
                    default -> List.of();
                };
            }
        };
        var tags=new FluidFlowTag(source);
        assertEquals(Set.of("minecraft:stone","custom:barrier"),tags.read(tag).orElseThrow());
        assertThrows(java.io.IOException.class,()->tags.read(ResourceLocation.parse("custom:cycle")));
    }
    @Test void bannerAndPortalPathsFollowTheBaseLayoutWhenAPackContainsBothPaths()throws Exception {
        for(boolean grouped:List.of(false,true)){
            var source=new BlockResourceSource(){
                public boolean hasBaseTexture(ResourceLocation id){return grouped;}
                public Optional<byte[]> read(ResourceLocation.Kind k,ResourceLocation id){return Optional.of(new byte[0]);}
                public Optional<String> readJson(ResourceLocation.Kind k,ResourceLocation id){return Optional.empty();}
                public ClientResourceArchive.Fingerprint fingerprint(){throw new AssertionError();}
            };
            var builtin=new BuiltinBlockModels(source,Map.of());
            for(String name:List.of("white_banner","blue_wall_banner","end_portal","end_gateway")){
                boolean banner=name.endsWith("_banner");
                var state=new BlockState("minecraft:"+name,Map.of());
                var faces=builtin.resolve(state).orElseThrow().getFirst().faces();
                String path=banner?(grouped?"banner/banner_base":"banner_base"):(grouped?"end_portal/end_portal":"end_portal");
                var material=ResourceLocation.parse("entity/"+path);
                assertTrue(faces.stream().skip(banner?6:0).allMatch(f->f.texture().equals(material)));
                if(banner)assertTrue(faces.subList(0,6).stream().allMatch(f->f.texture().equals(ResourceLocation.parse("entity/banner/base"))));
                assertEquals(banner?(name.contains("wall")?12:18):6,faces.size());
            }
        }
    }

    @Test void realClientsBakeAllBannerColorsAndBothEndPortals()throws Exception {
        var clients=Map.of("1.21.5",client(path("MCWE_TINT_1215_WORLD")),"26.1",path("MCWE_COLORS_261_CLIENT"),
                "26.3",client(path("MCWE_TINT_263_WORLD")));
        var states=new ArrayList<BlockState>();
        for(String color:List.of("white","orange","magenta","light_blue","yellow","lime","pink","gray","light_gray","cyan",
                "purple","blue","brown","green","red","black")){
            states.add(new BlockState("minecraft:"+color+"_banner",Map.of("rotation","4")));
            states.add(new BlockState("minecraft:"+color+"_wall_banner",Map.of("facing","east")));
        }
        for(String id:List.of("end_portal","end_gateway"))states.add(new BlockState("minecraft:"+id,Map.of()));
        for(var entry:clients.entrySet())try(var source=ClientResourceArchive.open(entry.getValue(),entry.getKey())){
            var textures=BlockTextureSet.load(source,states,CancellationSignal.NONE);
            for(var state:states){
                var faces=textures.surfaces(state);
                assertNotNull(faces,()->entry.getKey()+" "+state+": "+textures.fallbackReason(state));
                assertEquals(state.name().endsWith("_wall_banner")?12:state.name().endsWith("_banner")?18:6,faces.size());
            }
        }
    }

    @Test void literalBlockKeysBindStemColorsAndVerifiedNeutralLayersKeepTheirTexture()throws Exception {
        try(var source=ClientResourceArchive.open(client(path("MCWE_WEIGHTED_MOD_WORLD")),"1.21.1")){
            var colors=new ClientBlockColors(source);
            assertTrue(colors.unresolvedBindings().isEmpty());
            var states=new ArrayList<BlockState>();
            for(String plant:List.of("pumpkin","melon")){
                String growing="minecraft:"+plant+"_stem";
                String attached="minecraft:attached_"+plant+"_stem";
                assertEquals(ClientBlockColors.Kind.STEM,colors.layer(ResourceLocation.parse(growing),0).orElseThrow());
                assertEquals(ClientBlockColors.Kind.ATTACHED_STEM,colors.layer(ResourceLocation.parse(attached),0).orElseThrow());
                states.add(new BlockState(growing,Map.of("age","4")));
                states.add(new BlockState(attached,Map.of("facing","west")));
            }
            for(String id:List.of("stonecutter","bamboo_sapling","lava_cauldron","powder_snow_cauldron","cherry_leaves")){
                assertEquals(ClientBlockColors.Kind.WHITE,colors.layer(ResourceLocation.parse(id),0).orElseThrow());
                assertTrue(colors.layer(ResourceLocation.parse(id),1).isEmpty());
            }
            assertTrue(colors.layer(ResourceLocation.parse("fixture:cherry_leaves"),0).isEmpty());
            assertTrue(colors.layer(ResourceLocation.parse("minecraft:pale_oak_leaves"),0).isEmpty());
            var textures=BlockTextureSet.load(source,states,CancellationSignal.NONE);
            for(var state:states){
                assertNotNull(textures.surfaces(state),textures.fallbackReason(state));
                assertFalse(textures.diagnostics(state).stream().anyMatch(d->d.reasonCode().equals("UNBOUND_TINT")));
            }
        }
    }

    @Test void twoNamedClientsUseTheSameRegistrationLayers()throws Exception {
        try(var earlier=ClientResourceArchive.open(path("MCWE_COLORS_261_CLIENT"),"26.1");
            var later=ClientResourceArchive.open(client(path("MCWE_TINT_263_WORLD")),"26.3")){
            var a=new ClientBlockColors(earlier);var b=new ClientBlockColors(later);
            for(String name:List.of("grass_block","short_grass","oak_leaves","redstone_wire","pink_petals","water_cauldron","vine","spruce_leaves","birch_leaves")){
                var id=ResourceLocation.parse(name);
                assertTrue(a.layer(id,0).isPresent(),name);
                for(int i=0;i<4;i++)assertEquals(b.layer(id,i),a.layer(id,i),name);
            }
        }
    }
    @Test void resolvesCopperFamiliesAgainstActualTexturesAndKeepsAirEmpty()throws Exception {
        try(var source=ClientResourceArchive.open(client(path("MCWE_TINT_263_WORLD")),"26.3")){
            var states=new ArrayList<BlockState>();
            for(String wax:List.of("","waxed_"))for(String finish:List.of("","exposed_","weathered_","oxidized_")){
                for(String facing:List.of("north","west","east","south")){
                    for(String pose:List.of("standing","running","sitting","star"))
                        states.add(new BlockState("minecraft:"+wax+finish+"copper_golem_statue",Map.of("facing",facing,"copper_golem_pose",pose)));
                    for(String type:List.of("single","left","right"))
                        states.add(new BlockState("minecraft:"+wax+finish+"copper_chest",Map.of("facing",facing,"type",type,"waterlogged","false")));
                }
            }
            states.add(BlockState.AIR);
            var prepared=BlockTextureSet.load(source,states,CancellationSignal.NONE);
            for(var state:states){
                var faces=prepared.surfaces(state);
                assertNotNull(faces,()->state+" "+prepared.fallbackReason(state));
                int count=state==BlockState.AIR?0:state.name().endsWith("chest")?18:"sitting".equals(state.properties().get("copper_golem_pose"))?66:54;
                assertEquals(count,faces.size(),state.toString());
                for(var f:faces)assertTrue(f.positions().stream().allMatch(Float::isFinite));
            }
        }
    }
    @Test void savedOceanBlendsWaterAndKeepsHostMaterialsAndLavaSeparate()throws Exception {
        Path world=path("MCWE_TINT_263_WORLD");
        var area=new VoxelAreaLoader().load(new VoxelAreaRequest(world,"minecraft:overworld",ChunkRectangle.target(1,23,1,23)),CancellationSignal.NONE);
        var chunk=area.result(new ChunkCoordinate(1,23)).chunk();
        try(var source=InstanceResourceSource.open(world,client(world),"26.3",world.getParent().getParent(),CancellationSignal.NONE,s->{})){
            var textures=BlockTextureSet.load(source,area,CancellationSignal.NONE);
            assertTrue(textures.fluidBarriers().orElseThrow().contains("minecraft:stone"));
            var seen=EnumSet.noneOf(SavedFluid.class);var colors=new HashSet<String>();boolean embedded=false;
            for(var section:chunk.sections())for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++){
                var raw=section.blockState(x,y,z);var kind=SavedFluid.of(raw);
                if(kind==SavedFluid.NONE)continue;
                var state=textures.renderState(raw,x+16,section.sectionY()*16+y,z+368);
                var faces=textures.surfaces(state);
                assertNotNull(faces,textures.fallbackReason(state));
                var liquid=faces.stream().filter(BlockTextureSet.Surface::fluid).toList();
                assertEquals(6,liquid.size(),state.toString());seen.add(kind);
                if(kind==SavedFluid.LAVA){assertTrue(liquid.stream().allMatch(f->f.layer()==RenderLayer.OPAQUE));continue;}
                assertTrue(liquid.stream().allMatch(f->f.layer()==RenderLayer.FLUID));
                if(SavedFluid.embeddedWater(raw)){assertTrue(faces.size()>6);embedded=true;}
                assertFalse(textures.diagnostics(state).stream().anyMatch(d->d.reasonCode().equals("UNBOUND_TINT")));
                colors.add(Objects.requireNonNull(state.properties().get("__mcwe_tint")));
            }
            assertTrue(embedded);assertEquals(EnumSet.of(SavedFluid.WATER,SavedFluid.LAVA),seen);assertTrue(colors.size()>1);
            var mesh=new ChunkMesher(textures).build(area,new ChunkCoordinate(1,23),CancellationSignal.NONE);
            int slopes=0;
            for(var batch:mesh.batches())if(batch.layer()==RenderLayer.FLUID){
                float[] p=batch.positions(),n=batch.normals();
                for(int i=0;i<p.length;i+=12)if(n[i+1]>.9 && (p[i+1]!=p[i+4]||p[i+1]!=p[i+7]||p[i+1]!=p[i+10]))slopes++;
            }
            assertTrue(slopes>0);
        }
    }
    @Test void sameCompositeRuleBuildsIndependentModAssets()throws Exception {
        Path world=path("MCWE_WEIGHTED_MOD_WORLD");
        try(var source=InstanceResourceSource.open(world,client(world),"1.21.1",world.getParent().getParent(),CancellationSignal.NONE,s->{})){
            var states=List.of(new BlockState("create:display_link",Map.of("facing","north","powered","false")),
                    new BlockState("create_connected:fan_splashing_catalyst",Map.of()),
                    new BlockState("create:stock_link",Map.of("face","floor","facing","west","powered","false","waterlogged","false")));
            var baked=BlockTextureSet.load(source,states,CancellationSignal.NONE);
            for(var state:states){
                var result=new BlockModelResolver(source).resolve(state);
                assertTrue(result.resolved(),()->state+" "+result.detail());
                assertTrue(result.loaders().contains(ResourceLocation.parse("neoforge:composite")),result.loaders().toString());
                assertNotNull(baked.surfaces(state),baked.fallbackReason(state));assertFalse(baked.surfaces(state).isEmpty());
            }
        }
    }
    @Test void commentedOwnerTablesIgnoreDependenciesAndMultilineExamples() throws Exception {
        String manifest = """
                [[mods]] # primary declaration
                modId = 'first_mod' # primary ID
                description = '''
                [[mods]] # just an example inside a description
                modId = 'not_an_owner'
                '''
                [[dependencies.first_mod]] # dependency
                modId = "also_not_an_owner"
                [[ mods ]]
                modId = "second_mod"
                """;
        assertEquals(Set.of("first_mod", "second_mod"), BuiltinResourcePacks.owners(null, null, manifest));
    }

    @Test void forgeCompositeBakesIndependentModsFromRealInstances() throws Exception {
        var cases = Map.of(
                "MCWE_COMPOSITE_FARMING_WORLD", List.of(
                        new BlockState("create:red_nixie_tube", Map.of("double_face","ceiling","facing","east","waterlogged","false")),
                        new BlockState("create_connected:fan_splashing_catalyst", Map.of()),
                        new BlockState("youkaisfeasts:apple_basket", Map.of("count","7","facing","south"))),
                "MCWE_COMPOSITE_FORGE_WORLD", List.of(new BlockState("goety:chorus_fern", Map.of())));
        for (var entry : cases.entrySet()) {
            Path world = path(entry.getKey());
            try (var source = InstanceResourceSource.open(world,client(world),"1.20.1",world.getParent().getParent(),
                    CancellationSignal.NONE, message -> {})) {
                assertEquals(Optional.of("forge"), source.platform());
                var resolver = new BlockModelResolver(source);
                var baked = BlockTextureSet.load(source,entry.getValue(),CancellationSignal.NONE);
                for (var state : entry.getValue()) {
                    var result = resolver.resolve(state);
                    assertTrue(result.resolved(), () -> state + " " + result.detail());
                    assertTrue(result.loaders().contains(ResourceLocation.parse("forge:composite")), state.toString());
                    assertNotNull(baked.surfaces(state), baked.fallbackReason(state));
                    assertFalse(baked.surfaces(state).isEmpty(), state.toString());
                }
            }
        }
    }

    @Test void colorOnlyPluginBindsSavedModFoliageWithoutReplacingItsMesh()throws Exception {
        Path world=path("MCWE_WEIGHTED_MOD_WORLD");
        var area=new VoxelAreaLoader().load(new VoxelAreaRequest(world,"minecraft:overworld",ChunkRectangle.target(12,0,12,0)),CancellationSignal.NONE);
        var chunk=area.result(new ChunkCoordinate(12,0)).chunk();
        var layer=new ModelPlugin.TintLayer(ResourceLocation.parse("biomesoplenty:jacaranda_leaves"),0,ModelRule.BiomeColor.FOLIAGE,null);
        var plugin=new ModelPlugin(){
            public Descriptor descriptor(){return new Descriptor(ResourceLocation.parse("sample:color"),"Sample","1",1,"Test",Set.of(),Map.of(),Set.of(Capability.BIOME_COLOR));}
            public Collection<TintLayer> tintLayers(){return List.of(layer);}
        };
        var catalog=ModelPluginCatalog.core().with(plugin);
        var off=catalog.select(Set.of()).rules();var on=catalog.select(Set.of(plugin.descriptor().id())).rules();
        assertNotEquals(off.fingerprint(),on.fingerprint());assertEquals(off.rules(),on.rules());
        assertThrows(IllegalArgumentException.class,()->new ModelRuleRegistry(List.of(),"",List.of(layer,layer)));
        try(var source=InstanceResourceSource.open(world,client(world),"1.21.1",world.getParent().getParent(),CancellationSignal.NONE,s->{})){
            var before=BlockTextureSet.load(source,area,CancellationSignal.NONE,off);
            var after=BlockTextureSet.load(source,area,CancellationSignal.NONE,on);
            for(var section:chunk.sections())for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++){
                var raw=section.blockState(x,y,z);
                if(!raw.name().equals(layer.block().toString()))continue;
                var a=before.renderState(raw,192+x,section.sectionY()*16+y,z);
                var b=after.renderState(raw,192+x,section.sectionY()*16+y,z);
                assertTrue(before.diagnostics(a).stream().anyMatch(d->d.reasonCode().equals("UNBOUND_TINT")));
                assertTrue(after.diagnostics(b).stream().noneMatch(d->d.reasonCode().equals("UNBOUND_TINT")),after.notes(b).toString());
                assertEquals(before.surfaces(a).stream().map(BlockTextureSet.Surface::positions).toList(),
                        after.surfaces(b).stream().map(BlockTextureSet.Surface::positions).toList());
                assertTrue(b.properties().containsKey("__mcwe_tint"));
                return;
            }
            fail("选择区中缺少预期保存叶片");
        }
    }
}
