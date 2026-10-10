package dev.arnav.slingcraft;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/** Original water garden with a guardian, monumental doors, groves and traversable paths. */
final class LobbyScene {
    static void build(ServerLevel l,BlockPos o,Runnable complete){
        SceneBuilder b=new SceneBuilder(l,o);
        b.box(-22,0,-22,22,10,22,Blocks.AIR);
        for(int x=-70;x<=70;x++)for(int z=-70;z<=70;z++){
            double r=Math.hypot(x,z);if(r>70)continue;
            b.block(x,-3,z,Blocks.STONE);b.block(x,-2,z,Blocks.DEEPSLATE_TILES);
            boolean path=Math.abs(x)<4||Math.abs(z+8)<4||Math.abs(z-28)<3;
            b.block(x,-1,z,r>68?Blocks.POLISHED_DEEPSLATE:path?Blocks.SMOOTH_QUARTZ:Blocks.MOSS_BLOCK);
            if(r>68&&r<69.6)b.block(x,0,z,Blocks.DEEPSLATE_BRICKS);
            if(r>67&&r<68&&Math.floorMod(x*13+z*7,23)==0)b.block(x,0,z,Blocks.SEA_LANTERN);
            if(!path&&r<65&&Math.floorMod(x*31+z*17,151)==0)b.block(x,0,z,Blocks.FLOWERING_AZALEA);
            if(Math.abs(x)>=10&&Math.abs(x)<=22&&z>=6&&z<=24){
                boolean edge=Math.abs(x)==10||Math.abs(x)==22||z==6||z==24;
                b.block(x,-2,z,Blocks.PRISMARINE);b.block(x,-1,z,edge?Blocks.SMOOTH_QUARTZ:Blocks.WATER);
            }
        }
        for(int[] pos:new int[][]{{-48,34},{48,34},{-47,-36},{47,-36},{-26,49},{26,49},{-20,-54},{20,-54}}){
            b.tree(pos[0],0,pos[1],12+Math.abs(pos[0])%6,Blocks.DARK_OAK_LOG,pos[1]>0?Blocks.CHERRY_LEAVES:Blocks.AZALEA_LEAVES);
            b.block(pos[0]+2,2,pos[1],Blocks.SHROOMLIGHT);
        }
        b.box(-7,0,-29,7,1,-19,Blocks.POLISHED_DEEPSLATE);
        b.box(-6,2,-28,6,2,-20,Blocks.CUT_SANDSTONE);
        b.box(-5,3,-26,-2,12,-22,Blocks.DARK_PRISMARINE);b.box(2,3,-26,5,12,-22,Blocks.DARK_PRISMARINE);
        b.box(-5,13,-26,5,20,-22,Blocks.PRISMARINE_BRICKS);
        b.box(-2,15,-21,2,18,-21,Blocks.SEA_LANTERN);
        b.box(-8,17,-25,-6,22,-22,Blocks.POLISHED_DEEPSLATE);b.box(6,17,-25,8,22,-22,Blocks.POLISHED_DEEPSLATE);
        b.box(-3,21,-26,3,27,-21,Blocks.SMOOTH_QUARTZ);
        b.box(-2,24,-20,2,24,-20,Blocks.SEA_LANTERN);
        for(int x=-3;x<=3;x+=3)b.box(x,28,-25,x,30,-23,Blocks.GOLD_BLOCK);
        b.box(9,2,-24,9,31,-24,Blocks.CUT_SANDSTONE);
        b.box(6,29,-24,12,29,-24,Blocks.GOLD_BLOCK);
        for(int x=6;x<=12;x+=3)b.box(x,30,-24,x,33,-24,Blocks.SEA_LANTERN);
        b.box(-10,12,-25,-8,19,-22,Blocks.PRISMARINE_BRICKS);
        b.box(-11,10,-24,-8,12,-21,Blocks.SMOOTH_QUARTZ);
        b.box(7,16,-24,10,18,-21,Blocks.SMOOTH_QUARTZ);
        for(int x:new int[]{-4,4}){
            b.box(x-1,7,-21,x+1,9,-21,Blocks.CUT_SANDSTONE);
            b.box(x-1,3,-21,x+1,4,-19,Blocks.POLISHED_DEEPSLATE);
        }
        for(int y=14;y<=20;y+=2){b.block(-4,y,-21,Blocks.CUT_SANDSTONE);b.block(4,y,-21,Blocks.CUT_SANDSTONE);}
        b.box(-3,25,-20,3,25,-20,Blocks.POLISHED_DEEPSLATE);
        b.box(-1,21,-20,1,22,-20,Blocks.CUT_SANDSTONE);
        for(int gate=0;gate<3;gate++){
            int cx=gate==0?-34:gate==1?34:0,cz=gate==2?-50:-8;
            var trim=gate==0?Blocks.GOLD_BLOCK:gate==1?Blocks.PRISMARINE_BRICKS:Blocks.AMETHYST_BLOCK;
            b.box(cx-8,-1,cz-5,cx+8,-1,cz+5,Blocks.POLISHED_DEEPSLATE);
            for(int x=-5;x<=5;x++){
                int top=11-Math.abs(x)/2;b.box(cx+x,top,cz-1,cx+x,top+1,cz+1,trim);
                if(Math.abs(x)>=4)b.box(cx+x,0,cz-1,cx+x,top,cz+1,Blocks.DEEPSLATE_BRICKS);
            }
            for(int side:new int[]{-1,1}){
                b.box(cx+side*7,0,cz,cx+side*7,4,cz,trim);b.block(cx+side*7,5,cz,Blocks.SOUL_LANTERN);
                b.box(cx+side*4,1,cz+2,cx+side*4,7,cz+2,Blocks.SEA_LANTERN);
            }
            b.block(cx,12,cz,Blocks.SEA_LANTERN);
        }
        for(int n=0;n<12;n++){
            double a=n*Math.PI/6;int x=(int)(62*Math.cos(a)),z=(int)(62*Math.sin(a));
            b.box(x-1,0,z-1,x+1,9,z+1,Blocks.POLISHED_DEEPSLATE);b.block(x,10,z,Blocks.SEA_LANTERN);
        }
        LobbyArchitecture.build(b,o);
        b.submit(complete);
    }
}
