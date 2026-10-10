package dev.arnav.slingcraft;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Construction is budgeted to keep new scenery from occupying a whole server tick. */
public final class SceneBuilder {
    private record Placement(BlockPos pos,BlockState state){}
    private static final List<SceneBuilder> jobs=new ArrayList<>();
    private final ArrayDeque<Placement> blocks=new ArrayDeque<>();
    private final ServerLevel world;private final BlockPos origin;private Runnable done=()->{};
    public SceneBuilder(ServerLevel world,BlockPos origin){this.world=world;this.origin=origin;}
    public void block(int x,int y,int z,Block b){block(x,y,z,b.defaultBlockState());}
    public void block(int x,int y,int z,BlockState b){blocks.add(new Placement(origin.offset(x,y,z),b));}
    public void at(BlockPos pos,BlockState state){blocks.add(new Placement(pos.immutable(),state));}
    public void box(int x1,int y1,int z1,int x2,int y2,int z2,Block b){for(int x=x1;x<=x2;x++)for(int y=y1;y<=y2;y++)for(int z=z1;z<=z2;z++)block(x,y,z,b);}
    public void leaves(int x,int y,int z,int radius,Block leaf){
        BlockState state=leaf.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT,true);
        for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++)for(int dy=-2;dy<=2;dy++)
            if(dx*dx+dz*dz+dy*dy*2<=radius*radius)block(x+dx,y+dy,z+dz,state);
    }
    public void tree(int x,int y,int z,int height,Block trunk,Block leaf){
        box(x-1,y,z-1,x+1,y+height,z+1,trunk);
        for(int side:new int[]{-1,1}){box(Math.min(x,x+side*4),y+height-4,z,Math.max(x,x+side*4),y+height-3,z,trunk);leaves(x+side*4,y+height-1,z,5,leaf);}
        leaves(x,y+height+1,z,6,leaf);
        for(int dz:new int[]{-4,4})leaves(x,y+height-1,z+dz,5,leaf);
    }
    public void submit(Runnable done){this.done=done;jobs.add(this);}
    public static boolean busy(ServerLevel world){return jobs.stream().anyMatch(j->j.world==world);}
    public static void tick(){
        int budget=5000;long deadline=System.nanoTime()+12_000_000;
        for(var it=jobs.iterator();it.hasNext()&&budget>0;){
            SceneBuilder j=it.next();
            while(!j.blocks.isEmpty()&&budget-->0){var p=j.blocks.remove();j.world.setBlock(p.pos,p.state,2);if(System.nanoTime()>deadline)return;}
            if(j.blocks.isEmpty()){it.remove();j.done.run();}
        }
    }
    public static void clear(){jobs.clear();}
}
