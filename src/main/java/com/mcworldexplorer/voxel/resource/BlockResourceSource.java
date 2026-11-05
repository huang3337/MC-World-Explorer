package com.mcworldexplorer.voxel.resource;

import java.io.IOException;
import java.util.Optional;

/** Read-only asset lookup boundary, independent of client, Mod or pack containers. */
public interface BlockResourceSource {
    Optional<byte[]> read(ResourceLocation.Kind kind,ResourceLocation id) throws IOException;
    Optional<String> readJson(ResourceLocation.Kind kind,ResourceLocation id) throws IOException;
    ClientResourceArchive.Fingerprint fingerprint();
    default Optional<String> origin(ResourceLocation.Kind kind,ResourceLocation id){return Optional.empty();}
    /** Bounded exact-folder JSON IDs, including their .json suffix. No arbitrary host paths. */
    default java.util.Set<ResourceLocation> jsonFiles(ResourceLocation.Kind kind,String folder)throws IOException{throw new IOException("资源源未提供JSON目录枚举");}

    /** Installed Mod IDs and loader metadata, without executing their entry points. */
    default Optional<Boolean> hasMod(String id)throws IOException{return Optional.empty();}
    default Optional<String> platform()throws IOException{return Optional.empty();}
    /** Loader metadata owned by this Mod, rather than unrelated cross-loader compatibility libraries. */
    default Optional<String> platform(String modId)throws IOException{new ResourceLocation(modId,"check");return platform();}
    /** Whether the selected base client declares a data registry; empty means unavailable capability. */
    default Optional<Boolean> baseDataRegistry(String directory)throws IOException{return Optional.empty();}
    /** Explicit selected base-client capability (DECISION-020), kept separate from Mod registration lookup. */
    default Optional<String> baseClientVersion(){return Optional.empty();}
    /** Actual saved world seed; absence never implies zero. Included in source identity. */
    default Optional<Long> worldSeed()throws IOException{return Optional.empty();}
    /** Selected instance's biomeBlendRadius (0..7), including the verified game default when absent. */
    default Optional<Integer> biomeBlendRadius()throws IOException{return Optional.empty();}
    /** Saved dimension's effective type height; never inferred from available chunk sections. */
    record DimensionHeight(int minY,int height) {
        public DimensionHeight {
            if(minY < -2032 || minY > 2016 || height < 16 || height > 4064 || minY+height > 2032 || (minY&15)!=0 || (height&15)!=0)
                throw new IllegalArgumentException("维度高度超出已核对范围或未按16格对齐");
        }
        public int clampQuart(int y){return Math.clamp(y,minY>>2,((minY+height)>>2)-1);}
        public boolean contains(int y){return y>=minY&&y<minY+height;}
    }
    default Optional<DimensionHeight> dimensionHeight(ResourceLocation dimension)throws IOException{return Optional.empty();}
    /** Inert exact class bytes from the selected client only; no classpath, sibling client or execution. */
    default Optional<RegistrationClass> clientClass(String internalName)throws IOException{RegistrationClass.checkName(internalName);return Optional.empty();}
    /** Recheck mutable backing sources before/after using cached parsed resources. */
    default void validate() throws IOException { }
}
