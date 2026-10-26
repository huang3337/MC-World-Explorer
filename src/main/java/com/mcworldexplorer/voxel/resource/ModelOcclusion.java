package com.mcworldexplorer.voxel.resource;

import java.util.*;
import com.mcworldexplorer.voxel.mesh.RenderLayer;
import static com.mcworldexplorer.voxel.resource.BlockModelResolver.Direction;

/** Conservative exact rectangle coverage for opaque, axis-aligned model elements. */
public final class ModelOcclusion {
    private static final double EPS=1e-5;
    public record Box(double x0,double y0,double z0,double x1,double y1,double z1) { }
    public static List<Box> boxes(List<BlockTextureSet.Surface> faces) {
        if(faces==null)return List.of();
        Map<Integer,List<BlockTextureSet.Surface>> elements=new HashMap<>();
        for(var face:faces)elements.computeIfAbsent(face.element(),ignored->new ArrayList<>()).add(face);
        List<Box> boxes=new ArrayList<>();
        for(var element:elements.values()) {
            if(element.size()!=6||element.stream().anyMatch(f->f.layer()!=RenderLayer.OPAQUE||!axisAligned(f)))continue;
            if(element.stream().map(BlockTextureSet.Surface::direction).distinct().count()!=6)continue;
            double[] min={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY},max={-Double.MAX_VALUE,-Double.MAX_VALUE,-Double.MAX_VALUE};
            for(var f:element)for(int i=0;i<12;i++){int k=i%3;min[k]=Math.min(min[k],f.positions().get(i));max[k]=Math.max(max[k],f.positions().get(i));}
            if(max[0]-min[0]>EPS&&max[1]-min[1]>EPS&&max[2]-min[2]>EPS)
                boxes.add(new Box(min[0],min[1],min[2],max[0],max[1],max[2]));
        }
        return List.copyOf(boxes);
    }
    private static boolean axisAligned(BlockTextureSet.Surface face){return face.normal().stream().filter(n->Math.abs(n)>EPS).count()==1;}
    public static boolean covers(BlockTextureSet.Surface face,List<Box> boxes,int dx,int dy,int dz) {
        if(!axisAligned(face)||boxes.isEmpty())return false;
        int axis=face.direction()==Direction.WEST||face.direction()==Direction.EAST?0:
                face.direction()==Direction.DOWN||face.direction()==Direction.UP?1:2;
        double sign=face.normal().get(axis),plane=face.positions().get(axis);
        int u=(axis+1)%3,v=(axis+2)%3;
        double u0=Double.MAX_VALUE,u1=-Double.MAX_VALUE,v0=Double.MAX_VALUE,v1=-Double.MAX_VALUE;
        for(int i=0;i<4;i++){u0=Math.min(u0,face.positions().get(i*3+u));u1=Math.max(u1,face.positions().get(i*3+u));v0=Math.min(v0,face.positions().get(i*3+v));v1=Math.max(v1,face.positions().get(i*3+v));}
        List<double[]> rectangles=new ArrayList<>();
        for(Box box:boxes){double[] a={box.x0()+dx,box.y0()+dy,box.z0()+dz},b={box.x1()+dx,box.y1()+dy,box.z1()+dz};
            boolean crosses=sign>0?a[axis]<=plane+EPS&&b[axis]>plane+EPS:a[axis]<plane-EPS&&b[axis]>=plane-EPS;
            if(crosses)rectangles.add(new double[]{Math.max(u0,a[u]),Math.min(u1,b[u]),Math.max(v0,a[v]),Math.min(v1,b[v])});
        }
        if(rectangles.isEmpty())return false;
        TreeSet<Double> edges=new TreeSet<>();edges.add(u0);edges.add(u1);
        for(var r:rectangles)if(r[1]>r[0]+EPS&&r[3]>r[2]+EPS){edges.add(r[0]);edges.add(r[1]);}
        var cuts=new ArrayList<>(edges);
        for(int i=0;i<cuts.size()-1;i++) {
            double left=cuts.get(i),right=cuts.get(i+1);if(right-left<EPS)continue;
            List<double[]> intervals=new ArrayList<>();
            for(var r:rectangles)if(r[0]<=left+EPS&&r[1]>=right-EPS&&r[3]>r[2]+EPS)intervals.add(new double[]{r[2],r[3]});
            intervals.sort(Comparator.comparingDouble(r->r[0]));double end=v0;
            for(var interval:intervals){if(interval[0]>end+EPS)return false;end=Math.max(end,interval[1]);}
            if(end<v1-EPS)return false;
        }
        return true;
    }
}
