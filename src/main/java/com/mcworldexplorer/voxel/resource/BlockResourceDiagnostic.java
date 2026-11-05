package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.voxel.data.BlockState;
import java.util.*;

/** Immutable evidence captured at the failing operation, never inferred from a display sentence. */
public record BlockResourceDiagnostic(BlockState state, Severity severity, String reasonCode, String detail,
                                      List<String> loaders, List<Asset> assets, boolean truncated) {
    public static final int MAX_ASSETS=24,MAX_DETAIL=4096;
    public enum Severity {
        FAILURE("解析失败"), DEGRADED("显示降级"), INFO("解析说明");
        private final String label;
        Severity(String label){this.label=label;}
        public String label(){return label;}
    }
    public record Asset(String kind,String id,String origin) {
        public Asset {Objects.requireNonNull(kind);Objects.requireNonNull(id);Objects.requireNonNull(origin);}
    }
    public BlockResourceDiagnostic {
        Objects.requireNonNull(state);Objects.requireNonNull(severity);Objects.requireNonNull(reasonCode);Objects.requireNonNull(detail);
        loaders=List.copyOf(loaders);assets=List.copyOf(assets);
        if(assets.size()>MAX_ASSETS)throw new IllegalArgumentException("diagnostic asset budget exceeded");
        if(detail.length()>MAX_DETAIL){detail=detail.substring(0,MAX_DETAIL)+"…（详情已截断）";truncated=true;}
    }
    public BlockResourceDiagnostic originalState(BlockState original){
        return state.equals(original)?this:new BlockResourceDiagnostic(original,severity,reasonCode,detail,loaders,assets,truncated);
    }
    public static BlockResourceDiagnostic capture(BlockState state,Severity severity,String code,String detail,
                                                   BlockResourceSource source,BlockModelResolver.Result result) {
        var references=new LinkedHashSet<BlockModelResolver.Source>(result.sources());
        result.model().ifPresent(model->model.faces().forEach(face->references.add(new BlockModelResolver.Source(ResourceLocation.Kind.TEXTURE,face.texture()))));
        List<Asset> assets=references.stream().limit(MAX_ASSETS).map(ref->new Asset(ref.kind().name(),ref.id().toString(),source.origin(ref.kind(),ref.id()).orElse("来源未记录或资源缺失"))).toList();
        return new BlockResourceDiagnostic(state,severity,code,detail,result.loaders().stream().map(Object::toString).sorted().toList(),assets,references.size()>MAX_ASSETS);
    }
}
