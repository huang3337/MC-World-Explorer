package com.mcworldexplorer.voxel.resource;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class UvTilingTest {
    private static ModelGeometry.Quad quad(List<Float> uv){return new ModelGeometry.Quad(BlockModelResolver.Direction.SOUTH,null,List.of(0f,0f,0f,1f,0f,0f,1f,1f,0f,0f,1f,0f),List.of(0f,0f,1f),uv);}
    @Test void repeatedUvsPreserveAreaAndStayWithinTheirOwnAtlasTile()throws Exception {
        var faces=UvTiling.split(quad(List.of(-1f,0f,2f,0f,2f,1f,-1f,1f)));assertEquals(3,faces.size());double area=0;
        for(var face:faces){assertTrue(face.uv().stream().allMatch(value->value>=0&&value<=1));var p=face.positions();area+=Math.abs((p.get(3)-p.get(0))*(p.get(7)-p.get(1))-(p.get(4)-p.get(1))*(p.get(6)-p.get(0)));}
        assertEquals(1,area,1e-6);
        assertEquals(1,UvTiling.split(quad(List.of(0f,0f,1f,0f,1f,1f,0f,1f))).size());
    }
    @Test void excessivelyRepeatedFacesFailWithAnExplicitBudgetReason() {
        assertTrue(assertThrows(java.io.IOException.class,()->UvTiling.split(quad(List.of(-16f,-16f,16f,-16f,16f,16f,-16f,16f)))).getMessage().contains("预算"));
    }
    @Test void triangleVerticesExactlyOnTileBordersDoNotLoseGeometry()throws Exception {
        var input=new ModelGeometry.Quad(BlockModelResolver.Direction.SOUTH,null,List.of(0f,0f,0f,2f,0f,0f,0f,2f,0f,0f,2f,0f),List.of(0f,0f,1f),List.of(0f,0f,2f,0f,0f,2f,0f,2f));
        double total=0;
        for(var face:UvTiling.split(input)) {
            var points=face.positions();double area=0;
            for(int i=0;i<4;i++){int j=(i+1)%4;area+=points.get(i*3)*points.get(j*3+1)-points.get(j*3)*points.get(i*3+1);}
            total+=Math.abs(area)*.5;assertTrue(face.uv().stream().allMatch(value->value>=0&&value<=1));
        }
        assertEquals(2,total,1e-6);
    }
}
