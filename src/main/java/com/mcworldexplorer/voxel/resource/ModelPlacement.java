package com.mcworldexplorer.voxel.resource;

import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import java.util.Objects;

/** Common finite, nondegenerate affine placement boundary for shared resource templates. */
final class ModelPlacement {
    static Matrix4d copy(Matrix4dc placement)throws ModelRule.Failure {
        var pose=new Matrix4d(Objects.requireNonNull(placement));double[] values=new double[16];pose.get(values);
        for(double value:values)if(!Double.isFinite(value)||Math.abs(value)>1_000_000)throw invalid("模型放置矩阵无效或超限");
        if(pose.m03()!=0||pose.m13()!=0||pose.m23()!=0||pose.m33()!=1||Math.abs(pose.determinant3x3())<1e-12)throw invalid("模型放置需要非退化仿射矩阵");
        return pose;
    }
    private static ModelRule.Failure invalid(String message){return new ModelRule.Failure(BlockModelResolver.Reason.INVALID_MODEL,message);}
}
