package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.voxel.data.BlockState;
import org.junit.jupiter.api.Test;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WeightedVariantsTest {
    private static final BlockState STONE=new BlockState("minecraft:stone",Map.of());

    // Independent reference using java.util.Random as the client's 48-bit random source.
    static Random randomAt(int x,int y,int z,boolean multipart) {
        long value=(long)(3129871*x)^116129781L*z^y;
        var random=new Random((value*value*42317861L+11L*value)>>16);
        return multipart ? new Random(random.nextLong()) : random;
    }
    static int reference(Random random,int[] weights,boolean legacy) {
        int total=Arrays.stream(weights).sum();
        int ticket=legacy ? Math.abs((int)random.nextLong())%total : random.nextInt(total);
        for(int i=0;i<weights.length;i++)if((ticket-=weights[i])<0)return i;
        throw new AssertionError("unexpected ticket");
    }
    @Test void variantsAndMultipartFollowTheClientRandomStream() {
        int[][] weights={{2,1,1,1},{536870912,536870913,17}};
        var ends=new ArrayList<int[]>();
        for(var group:weights){var cumulative=group.clone();Arrays.parallelPrefix(cumulative,Integer::sum);ends.add(cumulative);}
        var coordinates=new Random(3859);
        for(int format:List.of(4,5,6,7,8,9,12,14,15,34,35,36,37,40,41,42,46,55,97))for(boolean multipart:List.of(true,false)){
            var plan=new WeightedVariants(WeightedVariants.protocol(format),multipart,ends,STONE);
            for(int i=0;i<1200;i++){
                int x=coordinates.nextInt(60000001)-30000000,y=coordinates.nextInt(400)-64,z=coordinates.nextInt(60000001)-30000000;
                for(int j=0;j<weights.length;j++)
                    assertEquals(reference(randomAt(x,y,z,multipart),weights[j],format<=36),plan.index(j,plan.bits(x,y,z)));
            }
        }
        assertNull(WeightedVariants.protocol(3));
        assertNull(WeightedVariants.protocol(-1));
    }
    @Test void pairedVanillaBlocksUseTheSameAnchor() {
        var ends=List.of(new int[]{1,3,9});
        for(String name:List.of("minecraft:oak_door","minecraft:tall_grass","minecraft:pitcher_plant")){
            var upper=new WeightedVariants(WeightedVariants.Draw.BOUNDED,false,ends,new BlockState(name,Map.of("half","upper")));
            var lower=new WeightedVariants(WeightedVariants.Draw.BOUNDED,false,ends,new BlockState(name,Map.of("half","lower")));
            assertEquals(lower.bits(-211,64,77),upper.bits(-211,65,77));
        }
        var foot=new WeightedVariants(WeightedVariants.Draw.BOUNDED,false,ends,new BlockState("minecraft:red_bed",Map.of("part","foot","facing","west")));
        var head=new WeightedVariants(WeightedVariants.Draw.BOUNDED,false,ends,new BlockState("minecraft:red_bed",Map.of("part","head","facing","west")));
        assertEquals(head.bits(-212,64,77),foot.bits(-211,64,77));
    }
}
