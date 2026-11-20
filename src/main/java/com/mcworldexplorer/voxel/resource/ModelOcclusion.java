package com.mcworldexplorer.voxel.resource;

import java.util.*;
import com.mcworldexplorer.voxel.mesh.RenderLayer;
import static com.mcworldexplorer.voxel.resource.BlockModelResolver.Direction;

/** Conservative exact rectangle coverage for opaque, axis-aligned model elements. */
public final class ModelOcclusion {
    private static final double EPS=1e-5;
    public record Box(double x0,double y0,double z0,double x1,double y1,double z1) {
        double min(int axis) { return axis == 0 ? x0 : axis == 1 ? y0 : z0; }
        double max(int axis) { return axis == 0 ? x1 : axis == 1 ? y1 : z1; }
    }
    public static List<Box> boxes(List<BlockTextureSet.Surface> faces) {
        if(faces==null)return List.of();
        Map<Integer,List<BlockTextureSet.Surface>> elements=new HashMap<>();
        for(var face:faces)if(!face.fluid())elements.computeIfAbsent(face.element(),ignored->new ArrayList<>()).add(face);
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
    /** Collision data is unavailable for arbitrary blocks. Use closed model elements to estimate the solid envelope. */
    public static boolean solidEnvelope(List<BlockTextureSet.Surface> faces) {
        if(faces.isEmpty())return false;
        var min=new double[]{1,1,1};var max=new double[]{0,0,0};
        boolean[] directions=new boolean[6];
        for(var f:faces){
            if(f.fluid()||!axisAligned(f))continue;
            directions[f.direction().ordinal()]=true;
            for(int i=0;i<12;i++){int k=i%3;float value=f.positions().get(i);min[k]=Math.min(min[k],value);max[k]=Math.max(max[k],value);}
        }
        for(boolean present:directions)if(!present)return false;
        double h=max[1]-min[1];
        return h>=1 || h*(max[0]-min[0])*(max[2]-min[2])>=.729;
    }
    private static boolean axisAligned(BlockTextureSet.Surface face) {
        int count = 0;
        var normal = face.normal();
        for (int i = 0; i < normal.size(); i++) if (Math.abs(normal.get(i)) > EPS) count++;
        return count == 1;
    }

    private static boolean crosses(double sign, double plane, double min, double max) {
        return sign > 0 ? min <= plane + EPS && max > plane + EPS : min < plane - EPS && max >= plane - EPS;
    }

    private static int offset(int axis, int dx, int dy, int dz) {
        return axis == 0 ? dx : axis == 1 ? dy : dz;
    }
    public static boolean covers(BlockTextureSet.Surface face,List<Box> boxes,int dx,int dy,int dz) {
        if(boxes.isEmpty()||!axisAligned(face))return false;
        int axis=face.direction()==Direction.WEST||face.direction()==Direction.EAST?0:
                face.direction()==Direction.DOWN||face.direction()==Direction.UP?1:2;
        double sign=face.normal().get(axis),plane=face.positions().get(axis);
        int u=(axis+1)%3,v=(axis+2)%3;
        Box only = boxes.size() == 1 ? boxes.getFirst() : null;
        if (only != null) {
            int shift = offset(axis, dx, dy, dz);
            if (!crosses(sign, plane, only.min(axis) + shift, only.max(axis) + shift)) return false;
        }
        double u0=Double.MAX_VALUE,u1=-Double.MAX_VALUE,v0=Double.MAX_VALUE,v1=-Double.MAX_VALUE;
        for(int i=0;i<4;i++){u0=Math.min(u0,face.positions().get(i*3+u));u1=Math.max(u1,face.positions().get(i*3+u));v0=Math.min(v0,face.positions().get(i*3+v));v1=Math.max(v1,face.positions().get(i*3+v));}
        if (only != null && u1 - u0 > EPS && v1 - v0 > EPS) {
            int du = offset(u, dx, dy, dz), dv = offset(v, dx, dy, dz);
            if (only.min(u) + du <= u0 && only.max(u) + du >= u1
                    && only.min(v) + dv <= v0 && only.max(v) + dv >= v1) return true;
        }
        List<double[]> rectangles=new ArrayList<>();
        for(Box box:boxes){double[] a={box.x0()+dx,box.y0()+dy,box.z0()+dz},b={box.x1()+dx,box.y1()+dy,box.z1()+dz};
            if(crosses(sign,plane,a[axis],b[axis]))rectangles.add(new double[]{Math.max(u0,a[u]),Math.min(u1,b[u]),Math.max(v0,a[v]),Math.min(v1,b[v])});
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
