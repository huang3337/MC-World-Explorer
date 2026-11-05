package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.voxel.io.CancellationSignal;
import net.kyori.adventure.nbt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorldDataPacksTest {
    @TempDir Path root;
    private static void data(Path world,CompoundBinaryTag data)throws Exception {
        try(var out=Files.newOutputStream(world.resolve("level.dat"))){BinaryTagIO.writer().write(CompoundBinaryTag.builder().put("Data",data).build(),out,BinaryTagIO.Compression.GZIP);}
    }
    @Test void savedSeedAndBlendSettingAreExplicitFingerprintedAndRevalidated()throws Exception {
        Path world=setup(),options=root.resolve("instance/options.txt");
        String first;
        try(var source=open(world)){assertTrue(source.worldSeed().isEmpty());assertEquals(Optional.of(2),source.biomeBlendRadius());first=source.fingerprint().sha256();}
        data(world,CompoundBinaryTag.builder().put("WorldGenSettings",CompoundBinaryTag.builder().putLong("seed",0).build()).build());
        Files.writeString(options,"biomeBlendRadius:7\nresourcePacks:[]\n");String second;
        try(var source=open(world)){assertEquals(Optional.of(0L),source.worldSeed());assertEquals(Optional.of(7),source.biomeBlendRadius());second=source.fingerprint().sha256();assertNotEquals(first,second);Files.writeString(options,"biomeBlendRadius:0\n");assertThrows(ResourceAccessException.class,source::biomeBlendRadius);}
        try(var source=open(world)){assertNotEquals(second,source.fingerprint().sha256());data(world,CompoundBinaryTag.builder().putLong("RandomSeed",-1L).build());assertThrows(ResourceAccessException.class,source::worldSeed);}
        try(var source=open(world)){assertEquals(Optional.of(-1L),source.worldSeed());source.validateAll();}
    }
    @Test void malformedSeedAndBlendInputsDoNotSilentlyChooseZeroOrDefaultRadius()throws Exception {
        Path world=setup(),options=root.resolve("instance/options.txt");
        for(var bad:List.of(CompoundBinaryTag.builder().putInt("RandomSeed",0).build(),CompoundBinaryTag.builder().putLong("RandomSeed",1).put("WorldGenSettings",CompoundBinaryTag.builder().putLong("seed",2).build()).build(),CompoundBinaryTag.builder().putString("WorldGenSettings","bad").build())){
            data(world,bad);try(var source=open(world)){assertThrows(ResourceAccessException.class,source::worldSeed);assertTrue(source.readJson(ResourceLocation.Kind.RAW_DATA,ID).isEmpty(),"种子损坏不抹去其他资源读取能力");}
        }
        data(world,CompoundBinaryTag.empty());
        for(String text:List.of("biomeBlendRadius:-1","biomeBlendRadius:8","biomeBlendRadius:bad","biomeBlendRadius:1\nbiomeBlendRadius:2")){
            Files.writeString(options,text);try(var source=open(world)){assertThrows(ResourceAccessException.class,source::biomeBlendRadius);assertTrue(source.worldSeed().isEmpty());}
        }
    }
    private static final String ENTRY="data/foreign/supplementaries/placeable_books/custom.json";
    private static final ResourceLocation ID=ResourceLocation.parse("foreign:supplementaries/placeable_books/custom.json");
    private static CompoundBinaryTag dimensionData(BinaryTag type){return CompoundBinaryTag.builder().put("WorldGenSettings",CompoundBinaryTag.builder().putLong("seed",0).put("dimensions",CompoundBinaryTag.builder().put("foreign:dimension",CompoundBinaryTag.builder().put("type",type).build()).build()).build()).build();}
    @Test void savedDimensionReferencesEffectiveDataPackTypeAndInlineTypeRemainsAuthoritative()throws Exception {
        Path world=setup();var dimension=ResourceLocation.parse("foreign:dimension");
        String entry="data/foreign/dimension_type/custom.json";Path pack=world.resolve("datapacks/height.zip");
        InstanceResourceSourceTest.zip(root.resolve("instance/mods/type.jar"),Map.of(entry,"{\"min_y\":0,\"height\":256}"));
        InstanceResourceSourceTest.zip(pack,Map.of(entry,"{\"min_y\":-32,\"height\":80}","pack.mcmeta","{\"pack\":{\"pack_format\":48}}"));
        var data=dimensionData(StringBinaryTag.stringBinaryTag("foreign:custom"));var selected=CompoundBinaryTag.builder().put(data).put("DataPacks",CompoundBinaryTag.builder().put("Enabled",ListBinaryTag.from(List.of(StringBinaryTag.stringBinaryTag("vanilla"),StringBinaryTag.stringBinaryTag("file/height.zip")))).build()).build();data(world,selected);String fingerprint;
        try(var source=open(world)){fingerprint=source.fingerprint().sha256();assertEquals(Optional.of(new BlockResourceSource.DimensionHeight(-32,80)),source.dimensionHeight(dimension));assertTrue(source.dimensionHeight(ResourceLocation.parse("minecraft:overworld")).isEmpty());
            data(world,dimensionData(CompoundBinaryTag.builder().putInt("min_y",16).putInt("height",32).build()));assertThrows(ResourceAccessException.class,()->source.dimensionHeight(dimension));}
        try(var source=open(world)){assertNotEquals(fingerprint,source.fingerprint().sha256());assertEquals(Optional.of(new BlockResourceSource.DimensionHeight(16,32)),source.dimensionHeight(dimension));source.validateAll();}
        data(world,dimensionData(StringBinaryTag.stringBinaryTag("foreign:custom")));
        try(var source=open(world)){assertEquals(Optional.of(new BlockResourceSource.DimensionHeight(0,256)),source.dimensionHeight(dimension));}
    }
    @Test void missingAndMalformedDimensionTypesCannotGuessTheOverworldOrSuppressOtherMetadata()throws Exception {
        Path world=setup();var dimension=ResourceLocation.parse("foreign:dimension");
        for(var type:List.of(IntBinaryTag.intBinaryTag(3),StringBinaryTag.stringBinaryTag("../bad"),CompoundBinaryTag.builder().putLong("min_y",0).putInt("height",32).build(),CompoundBinaryTag.builder().putInt("min_y",1).putInt("height",32).build())){
            data(world,dimensionData(type));try(var source=open(world)){assertEquals(Optional.of(0L),source.worldSeed());assertThrows(ResourceAccessException.class,()->source.dimensionHeight(dimension));assertTrue(source.readJson(ResourceLocation.Kind.RAW_DATA,ID).isEmpty());}
        }
        data(world,dimensionData(StringBinaryTag.stringBinaryTag("foreign:missing")));try(var source=open(world)){assertTrue(source.dimensionHeight(dimension).isEmpty());}
        for(String raw:List.of("{\"min_y\":0,\"height\":1}","{\"min_y\":0,\"height\":32.5}","{\"min_y\":0,\"height\":\"32\"}","{\"min_y\":2032,\"height\":16}")){
            InstanceResourceSourceTest.zip(root.resolve("instance/mods/type.jar"),Map.of("data/foreign/dimension_type/missing.json",raw));
            try(var source=open(world)){assertThrows(ResourceAccessException.class,()->source.dimensionHeight(dimension));}
        }
        for(int[] bad:List.of(new int[]{0,0},new int[]{-2048,16},new int[]{0,4096},new int[]{1,32},new int[]{0,17}))assertThrows(IllegalArgumentException.class,()->new BlockResourceSource.DimensionHeight(bad[0],bad[1]));
        var height=new BlockResourceSource.DimensionHeight(-32,80);assertEquals(-8,height.clampQuart(Integer.MIN_VALUE));assertEquals(11,height.clampQuart(Integer.MAX_VALUE));assertTrue(height.contains(-32));assertTrue(height.contains(47));assertFalse(height.contains(48));
    }
    Path setup()throws Exception {
        var fixture=new InstanceResourceSourceTest();fixture.root=root;fixture.client();
        Path world=Files.createDirectories(root.resolve("instance/saves/world"));Files.createDirectories(root.resolve("instance/mods"));return world;
    }
    void selection(Path world,List<String> enabled,List<String> disabled)throws Exception {
        var packs=CompoundBinaryTag.builder().put("Enabled",ListBinaryTag.from(enabled.stream().map(StringBinaryTag::stringBinaryTag).toList())).put("Disabled",ListBinaryTag.from(disabled.stream().map(StringBinaryTag::stringBinaryTag).toList())).build();
        try(var out=Files.newOutputStream(world.resolve("level.dat"))){BinaryTagIO.writer().write(CompoundBinaryTag.builder().put("Data",CompoundBinaryTag.builder().put("DataPacks",packs).build()).build(),out,BinaryTagIO.Compression.GZIP);}
    }
    InstanceResourceSource open(Path world)throws Exception{return InstanceResourceSource.open(world,root.resolve("client.jar"),"1.21.1",root.resolve("instance"),CancellationSignal.NONE,m->{});}
    @Test void enabledDataOverridesModWhileAssetResourcePacksCannotSupplyServerRegistrations()throws Exception {
        Path world=setup();InstanceResourceSourceTest.zip(root.resolve("instance/mods/a.jar"),Map.of(ENTRY,"mod","fabric.mod.json","{\"id\":\"owner\"}"));
        InstanceResourceSourceTest.zip(root.resolve("instance/resourcepacks/assets.zip"),Map.of(ENTRY,"wrong-side"));Files.writeString(root.resolve("instance/options.txt"),"resourcePacks:[\"file/assets.zip\"]");
        InstanceResourceSourceTest.zip(world.resolve("datapacks/a.zip"),Map.of(ENTRY,"first","pack.mcmeta","{\"pack\":{\"pack_format\":48}}"));
        Path directory=world.resolve("datapacks/b");Files.createDirectories(directory.resolve(ENTRY).getParent());Files.writeString(directory.resolve(ENTRY),"last");Files.writeString(directory.resolve("pack.mcmeta"),"{\"pack\":{\"pack_format\":48}}");
        selection(world,List.of("vanilla","file/a.zip","file/b"),List.of());
        try(var source=open(world)){assertEquals("last",source.readJson(ResourceLocation.Kind.RAW_DATA,ID).orElseThrow());assertEquals(Set.of(ID),source.jsonFiles(ResourceLocation.Kind.RAW_DATA,"supplementaries/placeable_books"));assertEquals(Optional.of(true),source.hasMod("owner"));assertEquals(Optional.of("fabric"),source.platform());
            Files.writeString(directory.resolve("pack.mcmeta"),"{\"pack\":{\"pack_format\":49}}");assertThrows(ResourceAccessException.class,source::validateAll);}
        selection(world,List.of("vanilla"),List.of("mod:owner"));try(var source=open(world)){assertTrue(source.readJson(ResourceLocation.Kind.RAW_DATA,ID).isEmpty());}
    }
    @Test void modOwnedPlatformMetadataIsNotOverriddenByUnrelatedCompatibilityArchives()throws Exception {
        Path world=setup();
        InstanceResourceSourceTest.zip(root.resolve("instance/mods/fabric.jar"),Map.of("fabric.mod.json","{\"id\":\"fabric_owner\",\"provides\":[\"alias_owner\"]}"));
        InstanceResourceSourceTest.zip(root.resolve("instance/mods/forge.jar"),Map.of("META-INF/mods.toml","[[mods]]\nmodId=\"forge_owner\""));
        InstanceResourceSourceTest.zip(root.resolve("instance/mods/neo.jar"),Map.of("META-INF/neoforge.mods.toml","[[mods]]\nmodId=\"neo_owner\""));
        try(var source=open(world)){
            assertEquals(Optional.of("neoforge"),source.platform(),"保留旧全局提示兼容，不用于已知Mod配置选择");
            assertEquals(Optional.of("fabric"),source.platform("fabric_owner"));assertEquals(Optional.of("fabric"),source.platform("alias_owner"));assertEquals(Optional.of("forge"),source.platform("forge_owner"));assertEquals(Optional.of("neoforge"),source.platform("neo_owner"));assertTrue(source.platform("unknown_owner").isEmpty());
            assertThrows(IllegalArgumentException.class,()->source.platform("../escape"));
        }
    }
    @Test void ambiguousModOwnedPlatformsFailRatherThanChooseOneMetadataFormat()throws Exception {
        Path world=setup();InstanceResourceSourceTest.zip(root.resolve("instance/mods/universal.jar"),Map.of("fabric.mod.json","{\"id\":\"owner\"}","META-INF/mods.toml","[[mods]]\nmodId=\"owner\""));
        var source=open(world);try{assertEquals(ResourceAccessException.Reason.INVALID_METADATA,assertThrows(ResourceAccessException.class,()->source.platform("owner")).reason());}finally{source.close();}
        assertThrows(ResourceAccessException.class,()->source.platform("owner"));
    }

    @Test void invalidDataPathsAndUnsupportedPackSemanticsFailExplicitly()throws Exception {
        Path world=setup();selection(world,List.of("file/../../escape"),List.of());try(var source=open(world)){assertThrows(ResourceAccessException.class,()->source.jsonFiles(ResourceLocation.Kind.RAW_DATA,"supplementaries/placeable_books"));}
        InstanceResourceSourceTest.zip(world.resolve("datapacks/a.zip"),Map.of("pack.mcmeta","{\"pack\":{},\"filter\":{}}",ENTRY,"not-supported"));selection(world,List.of("file/a.zip"),List.of());try(var source=open(world)){assertThrows(ResourceAccessException.class,()->source.readJson(ResourceLocation.Kind.RAW_DATA,ID));}
    }



    @Test void dataSelectionChangesAndInvalidEnumerationPathsRemainProtected()throws Exception {
        Path world=setup();selection(world,List.of("vanilla"),List.of());
        var source=open(world);
        try {
            assertThrows(IllegalArgumentException.class,()->source.jsonFiles(ResourceLocation.Kind.RAW_DATA,"../escape"));
            selection(world,List.of("vanilla","runtime:generated"),List.of());
            assertThrows(ResourceAccessException.class,source::validateAll);
        } finally {source.close();}
        assertThrows(ResourceAccessException.class,()->source.jsonFiles(ResourceLocation.Kind.RAW_DATA,"x"));
    }
}
