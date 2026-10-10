package dev.arnav.slingcraft;

import net.minecraft.world.level.block.Blocks;

/** Recognizable racing sectors; all trunks, ruins and spikes stay off the driving surface. */
final class RaceScenery {
    static void add(SceneBuilder b){
        // Canopy sector: enormous silhouettes and luminous roots on the inside of the road.
        for(int[] p:new int[][]{{68,-7},{57,27},{31,40},{112,-48}}){
            b.box(p[0]-4,-4,p[1]-4,p[0]+4,0,p[1]+4,Blocks.MOSS_BLOCK);
            b.tree(p[0],0,p[1],22+Math.abs(p[0])%9,Blocks.JUNGLE_LOG,Blocks.JUNGLE_LEAVES);
            for(int side:new int[]{-1,1}){
                b.box(p[0]+side*4,4,p[1],p[0]+side*4,17,p[1],Blocks.MOSS_BLOCK);
                b.block(p[0]+side*4,3,p[1],Blocks.SHROOMLIGHT);
                b.block(p[0]+side*5,1,p[1],Blocks.SEA_LANTERN);
            }
        }
        // A broken aqueduct runs through the infield; gate sight lines remain open.
        for(int x=-48;x<=32;x+=16){
            b.box(x-2,-4,13,x+2,14,17,Blocks.MOSSY_STONE_BRICKS);
            b.box(x-2,14,12,x+14,16,18,Blocks.STONE_BRICKS);
            b.block(x,17,15,Blocks.SEA_LANTERN);
        }
        // Glacier needles are faceted and taper, rather than repeating square pillars.
        for(int i=0;i<11;i++){
            int x=-67+i*4,z=-25+(i%3)*11,h=12+(i*7)%18;
            for(int y=-3;y<h;y++){
                int r=Math.max(0,(h-y)/7);
                for(int dx=-r;dx<=r;dx++)for(int dz=-r;dz<=r;dz++)if(Math.abs(dx)+Math.abs(dz)<=r)
                    b.block(x+dx,y,z+dz,(dx+dz+y)%5==0?Blocks.BLUE_ICE:Blocks.PACKED_ICE);
            }
            b.block(x,-1,z+3,Blocks.SEA_LANTERN);
        }
        // Skyscape silhouettes and cheering stands surround the start straight.
        for(int side:new int[]{-1,1})for(int step=0;step<4;step++){
            int x=side*28;
            b.box(x-10,step,-84-step*2,x+10,step,-83-step*2,Blocks.SMOOTH_QUARTZ);
        }
        for(int x:new int[]{-40,40}){
            b.box(x,-1,-84,x,20,-84,Blocks.POLISHED_DEEPSLATE);
            b.box(x-4,21,-84,x+4,21,-84,Blocks.SEA_LANTERN);
        }
        // Floating halo above the infield is architecture, not a world-wide shader change.
        for(int i=0;i<100;i++){
            double a=i*Math.PI*2/100;int x=(int)(31*Math.cos(a)),y=40+(int)(12*Math.sin(a));
            b.block(x,y,2,Blocks.SMOOTH_QUARTZ);if(i%5==0)b.block(x,y,3,Blocks.SEA_LANTERN);
        }
    }
}
