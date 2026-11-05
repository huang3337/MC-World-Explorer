package com.mcworldexplorer.voxel.resource;

import org.junit.jupiter.api.Test;
import org.joml.Matrix4d;
import java.util.*;
import static com.mcworldexplorer.voxel.resource.BlockModelResolver.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelPartsTest {
    private static Part sample(){
        var face=new Face(Direction.NORTH,ResourceLocation.parse("foreign:plant"),new Uv(2,3,12,14),90,2,Direction.NORTH,0,
            new Vec3(1,2,3),new Vec3(13,15,14),new ElementRotation(new Vec3(8,8,8),"y",22.5,true),true);
        return new Part(ResourceLocation.parse("foreign:model"),90,270,true,List.of(face),"cutout",new Vec3(.125,-.25,.375));
    }
    @Test void placesAlreadyResolvedTransformsAndUvExactlyOnce()throws Exception {
        var part=sample();var original=ModelGeometry.bake(part.faces().getFirst(),part);
        var placed=ModelParts.place(List.of(part),new Matrix4d().translate(.3,.2,.1).scale(.4,.5,.6)).getFirst();
        var actual=ModelGeometry.bake(placed.faces().getFirst(),placed);
        assertEquals(original.uv(),actual.uv());assertNull(actual.cull());assertEquals("cutout",placed.renderType());
        for(int i=0;i<12;i++)assertEquals(original.positions().get(i)*new double[]{.4,.5,.6}[i%3]+new double[]{.3,.2,.1}[i%3],actual.positions().get(i),1e-6);
        assertEquals(2,placed.faces().getFirst().tintIndex());assertEquals(new Vec3(0,0,0),placed.offset());
        assertEquals(List.of(part),List.of(sample()),"Input records must remain unchanged");
        var preserved=ModelParts.place(List.of(part),new Matrix4d().rotateY(Math.PI/2),true).getFirst();
        assertEquals(original.cull(),preserved.faces().getFirst().cullFace(),"Source query bucket is retained independently of new placement");
    }
    @Test void mirrorPreservesWindingAndRejectsInvalidPlacementAndBudgets()throws Exception {
        var part=sample();var source=ModelGeometry.bake(part.faces().getFirst(),part);
        var mirrored=ModelParts.place(List.of(part),new Matrix4d().scale(-1,1,1)).getFirst();var result=ModelGeometry.bake(mirrored.faces().getFirst(),mirrored);
        assertEquals(-source.normal().get(0),result.normal().get(0),1e-6);assertEquals(source.normal().get(1),result.normal().get(1),1e-6);
        for(var invalid:List.of(new Matrix4d().scale(0),new Matrix4d().m03(.2),new Matrix4d().m00(Double.NaN)))assertThrows(ModelRule.Failure.class,()->ModelParts.place(List.of(part),invalid));
        assertEquals(257,ModelParts.place(Collections.nCopies(257,part),new Matrix4d()).size(),"Public placement must not lower the resolver's part budget");
        assertEquals(Reason.LIMIT_EXCEEDED,assertThrows(ModelRule.Failure.class,()->ModelParts.place(Collections.nCopies(4097,part),new Matrix4d())).reason());
        var excessive=new Part(part.id(),0,0,false,Collections.nCopies(32769,part.faces().getFirst()),null);
        assertEquals(Reason.LIMIT_EXCEEDED,assertThrows(ModelRule.Failure.class,()->ModelParts.place(List.of(excessive),new Matrix4d())).reason());
    }
}
