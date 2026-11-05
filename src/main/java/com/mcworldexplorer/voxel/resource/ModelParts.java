package com.mcworldexplorer.voxel.resource;

import java.util.*;
import org.joml.Matrix4dc;
import static com.mcworldexplorer.voxel.resource.BlockModelResolver.*;

/** Pure placement of resolved model parts. Coordinates in the affine matrix are measured in blocks. */
public final class ModelParts {
    private ModelParts() { }

    /** Bakes element and blockstate rotations, UV lock and offsets exactly once, then applies placement.
     * Returned faces have explicit vertices/UV, identity part transforms and no external cull face:
     * a placed miniature no longer occupies the original block's occlusion boundary. */
    public static List<Part> place(List<Part> parts,Matrix4dc placement)throws ModelRule.Failure {
        return place(parts,placement,false);
    }

    /** If preserveSourceCull is true, retains the baked source's directional query buckets unchanged.
     * Use only for adapters whose source renderer keeps querying those same buckets after placement;
     * this does not rotate culling with the placement matrix. */
    public static List<Part> place(List<Part> parts,Matrix4dc placement,boolean preserveSourceCull)throws ModelRule.Failure {
        Objects.requireNonNull(parts);var pose=ModelPlacement.copy(placement);
        if(parts.size()>4096)throw new ModelRule.Failure(Reason.LIMIT_EXCEEDED,"放置模型部件超过4096项");
        long faces=0;for(var part:parts){Objects.requireNonNull(part);faces+=part.faces().size();}
        if(faces>32768)throw new ModelRule.Failure(Reason.LIMIT_EXCEEDED,"放置模型面超过32768项");
        return parts.stream().map(part->GeometryTransform.transform(part,pose,preserveSourceCull)).toList();
    }
}
