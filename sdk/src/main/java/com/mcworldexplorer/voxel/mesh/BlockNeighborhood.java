package com.mcworldexplorer.voxel.mesh;

import com.mcworldexplorer.voxel.data.BlockState;
import com.mcworldexplorer.voxel.data.ChunkCoordinate;
import com.mcworldexplorer.voxel.io.ChunkDecodeException;
import com.mcworldexplorer.voxel.io.ChunkLoadResult;
import com.mcworldexplorer.voxel.io.ChunkLoadStatus;
import com.mcworldexplorer.voxel.io.VoxelAreaLoadResult;

public final class BlockNeighborhood {
    private final VoxelAreaLoadResult area;

    public BlockNeighborhood(VoxelAreaLoadResult area) {
        if (area == null) {
            throw new IllegalArgumentException("area must not be null");
        }
        this.area = area;
    }

    public Query query(long worldX, int y, long worldZ) throws ChunkDecodeException {
        int chunkX=Math.toIntExact(Math.floorDiv(worldX,16L)),chunkZ=Math.toIntExact(Math.floorDiv(worldZ,16L));
        var coordinate=new ChunkCoordinate(chunkX,chunkZ);
        var result=area.result(coordinate);
        return sample(result,coordinate,stateAt(result,worldX,y,worldZ));
    }

    private static BlockState stateAt(ChunkLoadResult result,long x,int y,long z)throws ChunkDecodeException {
        return result.status()==ChunkLoadStatus.SUCCESS
                ?result.chunk().blockState((int)Math.floorMod(x,16L),y,(int)Math.floorMod(z,16L)):BlockState.AIR;
    }
    private static Query sample(ChunkLoadResult result,ChunkCoordinate coordinate,BlockState state){
        return new Query(state,result.status(),coordinate,result.status()==ChunkLoadStatus.SUCCESS?"":result.detail());
    }

    Cursor cursor(){return new Cursor();}

    /** One per mesh build. Reuses the last chunk and immutable palette sample across faces. */
    final class Cursor {
        private ChunkCoordinate lastChunk;
        private ChunkLoadResult lastResult;
        private Query lastQuery;
        Query query(long worldX,int y,long worldZ)throws ChunkDecodeException {
            int chunkX=Math.toIntExact(Math.floorDiv(worldX,16L)),chunkZ=Math.toIntExact(Math.floorDiv(worldZ,16L));
            if(lastChunk==null || lastChunk.x()!=chunkX || lastChunk.z()!=chunkZ){
                lastChunk=new ChunkCoordinate(chunkX,chunkZ);
                lastResult=area.result(lastChunk);
                lastQuery=null;
            }
            var state=stateAt(lastResult,worldX,y,worldZ);
            if(lastQuery==null || lastQuery.state()!=state)lastQuery=sample(lastResult,lastChunk,state);
            return lastQuery;
        }
    }

    public record Query(
            BlockState state,
            ChunkLoadStatus sourceStatus,
            ChunkCoordinate sourceCoordinate,
            String detail) {
        public boolean unavailableFailure() {
            return sourceStatus == ChunkLoadStatus.UNSUPPORTED
                    || sourceStatus == ChunkLoadStatus.CORRUPT
                    || sourceStatus == ChunkLoadStatus.CANCELLED;
        }
    }
}
