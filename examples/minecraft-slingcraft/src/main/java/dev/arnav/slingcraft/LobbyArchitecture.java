package dev.arnav.slingcraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;

/** Original open-sky arcade and three destination courts. No imported map assets. */
final class LobbyArchitecture {
 static final int RADIUS=80;
 static void build(SceneBuilder b,BlockPos o){
  // Reclaim only previous generated gate footprints and short decorative posts.
  for(int[] p:new int[][]{{-34,-8},{34,-8},{0,-50}})b.box(p[0]-8,0,p[1]-3,p[0]+8,13,p[1]+3,Blocks.AIR);
  for(int n=0;n<12;n++){double a=n*Math.PI/6;int x=(int)(62*Math.cos(a)),z=(int)(62*Math.sin(a));b.box(x-1,0,z-1,x+1,10,z+1,Blocks.AIR);}
  for(int x=-RADIUS;x<=RADIUS;x++)for(int z=-RADIUS;z<=RADIUS;z++){
   double r=Math.hypot(x,z);if(r>RADIUS)continue;
   if(r>65){b.block(x,-3,z,Blocks.STONE);b.block(x,-2,z,Blocks.DEEPSLATE_BRICKS);b.block(x,-1,z,r>77?Blocks.POLISHED_DEEPSLATE:Blocks.MOSS_BLOCK);b.block(x,0,z,Blocks.AIR);}
   boolean route=Math.abs(z)<4||Math.abs(x)<4||r>49&&r<55;
   // Split the north walk around the preserved guardian.
   route|=Math.abs(x)>=12&&Math.abs(x)<=16&&z>=-40&&z<=-10;
   route|=(z>=-13&&z<=-9||z>=-43&&z<=-39)&&Math.abs(x)<=16;
   if(route&&r<75){b.block(x,-1,z,Blocks.SMOOTH_QUARTZ);if(!(Math.abs(x)<12&&z<-18&&z>-30))b.box(x,0,z,x,2,z,Blocks.AIR);}
   if(r>78){b.box(x,0,z,x,3,z,Blocks.DEEPSLATE_BRICKS);b.block(x,4,z,Blocks.CUT_SANDSTONE);}
   if(r>74&&r<78){b.block(x,27,z,Blocks.PRISMARINE_BRICKS);b.block(x,28,z,Blocks.CUT_SANDSTONE);b.block(x,29,z,Blocks.SMOOTH_QUARTZ);}
  }
  for(int n=0;n<16;n++){
   double a=n*Math.PI/8;int x=(int)Math.round(76*Math.cos(a)),z=(int)Math.round(76*Math.sin(a));
   b.box(x-3,0,z-3,x+3,3,z+3,Blocks.POLISHED_DEEPSLATE);
   b.box(x-2,4,z-2,x+2,26,z+2,Blocks.SMOOTH_QUARTZ);b.box(x-1,5,z-1,x+1,25,z+1,Blocks.PRISMARINE_BRICKS);
   for(int y:new int[]{5,23,26,30})b.box(x-3,y,z-3,x+3,y+1,z+3,Blocks.CUT_SANDSTONE);
   b.box(x-2,32,z-2,x+2,35,z+2,Blocks.DARK_PRISMARINE);b.block(x,36,z,Blocks.SEA_LANTERN);
   if(n%2==0){for(int y=36;y<44;y++){int w=Math.max(0,(43-y)/3);b.box(x-w,y,z-w,x+w,y,z+w,Blocks.PRISMARINE_BRICKS);}b.block(x,44,z,Blocks.GOLD_BLOCK);b.block(x,45,z,Blocks.SEA_LANTERN);}
   for(int step=0;step<=44;step++){
    double t=step/44.,theta=a+t*Math.PI/8;int y=(int)Math.round(19+7*Math.sqrt(Math.max(0,1-Math.pow(t*2-1,2))));
    for(int r=74;r<=78;r++){int ax=(int)Math.round(r*Math.cos(theta)),az=(int)Math.round(r*Math.sin(theta));b.box(ax,y,az,ax,y+2,az,Blocks.SMOOTH_QUARTZ);b.block(ax,y+3,az,Blocks.CUT_SANDSTONE);}
   }
  }
  for(int sx:new int[]{-1,1})for(int sz:new int[]{-1,1})waterfall(b,sx*49,sz*49,sz<0?1:-1);
  for(int i=0;i<LobbyGate.ALL.length;i++)court(b,o,LobbyGate.ALL[i],i);
  for(int x:new int[]{-29,29})for(int z:new int[]{-34,34}){
   b.box(x-4,-1,z-3,x+4,-1,z+3,Blocks.SMOOTH_QUARTZ);b.box(x-3,0,z,x+3,0,z,Blocks.DARK_OAK_PLANKS);b.box(x-3,1,z+1,x+3,1,z+1,Blocks.DARK_OAK_PLANKS);
   for(int side:new int[]{-1,1}){b.box(x+side*5,0,z,x+side*5,4,z,Blocks.CUT_SANDSTONE);b.block(x+side*5,5,z,Blocks.LANTERN);}
  }
 }
 private static void waterfall(SceneBuilder b,int x,int z,int front){
  for(int u=-7;u<=7;u++)for(int v=-3;v<=9;v++){
   b.block(x+u,-3,z+v*front,Blocks.PRISMARINE_BRICKS);boolean edge=Math.abs(u)==7||v==-3||v==9;
   b.block(x+u,-2,z+v*front,edge?Blocks.SMOOTH_QUARTZ:Blocks.WATER);b.block(x+u,-1,z+v*front,edge?Blocks.SMOOTH_QUARTZ:Blocks.WATER);b.block(x+u,0,z+v*front,edge?Blocks.SMOOTH_QUARTZ:Blocks.AIR);
  }
  b.box(x-5,0,z-2,x+5,22,z+2,Blocks.PRISMARINE_BRICKS);b.box(x-6,22,z-3,x+6,24,z+3,Blocks.CUT_SANDSTONE);
  for(int y=0;y<=23;y++)for(int u=-1;u<=1;u++){
   b.block(x+u,y,z+3*front,Blocks.WATER.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LEVEL,y==23?0:8));
   b.block(x-2,y,z+3*front,Blocks.SMOOTH_QUARTZ);b.block(x+2,y,z+3*front,Blocks.SMOOTH_QUARTZ);
  }
  for(int u=-1;u<=1;u++)b.block(x+u,23,z+4*front,Blocks.SMOOTH_QUARTZ);
  for(int side:new int[]{-1,1}){b.box(x+side*4,4,z+3*front,x+side*4,20,z+3*front,Blocks.CUT_SANDSTONE);b.block(x+side*4,21,z+3*front,Blocks.SEA_LANTERN);}
  b.box(x-3,25,z-2,x+3,26,z+2,Blocks.DARK_PRISMARINE);b.block(x,27,z,Blocks.SEA_LANTERN);b.leaves(x-5,21,z,3,Blocks.AZALEA_LEAVES);b.leaves(x+5,21,z,3,Blocks.AZALEA_LEAVES);
 }
 private static void court(SceneBuilder b,BlockPos o,LobbyGate g,int mode){
  Block trim=mode==0?Blocks.CUT_SANDSTONE:mode==1?Blocks.PRISMARINE_BRICKS:Blocks.POLISHED_BLACKSTONE_BRICKS;
  Block accent=mode==0?Blocks.GOLD_BLOCK:mode==1?Blocks.SEA_LANTERN:Blocks.SHROOMLIGHT;
  Block carpet=mode==0?Blocks.CARPET.red():mode==1?Blocks.CARPET.lightBlue():Blocks.CARPET.purple();
  box(b,o,g,-13,0,-5,13,25,6,Blocks.AIR);box(b,o,g,-13,-1,-5,13,-1,17,Blocks.POLISHED_DEEPSLATE);box(b,o,g,-3,0,2,3,0,17,carpet);
  box(b,o,g,-4,-1,0,4,-1,0,Blocks.OBSIDIAN);box(b,o,g,-4,LobbyGate.HEIGHT,0,4,LobbyGate.HEIGHT,0,Blocks.OBSIDIAN);
  box(b,o,g,-4,0,0,-4,LobbyGate.HEIGHT,0,Blocks.OBSIDIAN);box(b,o,g,4,0,0,4,LobbyGate.HEIGHT,0,Blocks.OBSIDIAN);
  for(int u=-3;u<=3;u++)for(int y=0;y<LobbyGate.HEIGHT;y++)b.at(g.at(o,u,y,0),Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS,g.axis()));
  for(int side:new int[]{-1,1}){
   box(b,o,g,side*9-2,0,-3,side*9+2,18,-1,trim);box(b,o,g,side*9-3,18,-4,side*9+3,19,0,accent);box(b,o,g,side*9-1,20,-3,side*9+1,22,-1,trim);
   block(b,o,g,side*9,23,-2,mode==2?Blocks.SOUL_LANTERN:Blocks.SEA_LANTERN);box(b,o,g,side*6,0,-1,side*6,12,1,trim);block(b,o,g,side*6,13,1,accent);
  }
  for(int u=-9;u<=9;u++){int y=17-Math.abs(u)/2;box(b,o,g,u,y,-2,u,y+2,0,trim);block(b,o,g,u,y+3,0,accent);}
  if(mode==0){
   for(int side:new int[]{-1,1}){
    for(int u=6;u<=12;u+=2)box(b,o,g,side*u,20,-3,side*u,21,-2,Blocks.CUT_SANDSTONE);
    box(b,o,g,side*10-1,0,7,side*10+1,8,9,Blocks.DARK_OAK_LOG);box(b,o,g,side*10-3,7,7,side*10-2,12,9,Blocks.DARK_OAK_LOG);box(b,o,g,side*10+2,7,7,side*10+3,12,9,Blocks.DARK_OAK_LOG);box(b,o,g,side*10-2,11,8,side*10+2,11,8,Blocks.WOOL.red());block(b,o,g,side*7,1,14,Blocks.LANTERN);
   }
   box(b,o,g,-2,20,-1,2,23,1,Blocks.DYED_TERRACOTTA.lime());block(b,o,g,-1,22,2,Blocks.CONCRETE.black());block(b,o,g,1,22,2,Blocks.CONCRETE.black());
  }else if(mode==1){
   for(int u=-8;u<=8;u++)for(int v=7;v<=9;v++)block(b,o,g,u,-1,v,(u+v)%2==0?Blocks.QUARTZ_BLOCK:Blocks.CONCRETE.black());
   for(int side:new int[]{-1,1}){for(int u=7;u<=12;u++){int h=21-(u-7)*2;box(b,o,g,side*u,0,7,side*u,h,8,Blocks.BLUE_ICE);block(b,o,g,side*u,h+1,8,Blocks.SEA_LANTERN);}for(int v=4;v<=15;v+=3)block(b,o,g,side*5,-1,v,Blocks.SEA_LANTERN);}
   box(b,o,g,-5,20,-1,5,21,0,Blocks.SMOOTH_QUARTZ);box(b,o,g,-2,22,-1,2,24,0,Blocks.CONCRETE.lightBlue());
  }else{
   for(int side:new int[]{-1,1}){
    box(b,o,g,side*5,0,3,side*5,8,3,Blocks.IRON_BARS);for(int v=5;v<=14;v+=3){block(b,o,g,side*5,0,v,Blocks.POLISHED_BLACKSTONE_BRICKS);block(b,o,g,side*5,1,v,Blocks.SOUL_LANTERN);}
    box(b,o,g,side*11,0,9,side*11,7,9,Blocks.MUSHROOM_STEM);box(b,o,g,side*11-3,7,6,side*11+3,8,12,Blocks.RED_MUSHROOM_BLOCK);block(b,o,g,side*11,6,10,Blocks.SHROOMLIGHT);
    box(b,o,g,side*6,3,1,side*6,13,2,Blocks.CRIMSON_STEM);for(int u=1;u<=6;u++)block(b,o,g,side*u,18-u/2,1,Blocks.CRIMSON_HYPHAE);
   }
   box(b,o,g,-2,20,-1,2,24,1,Blocks.CRIMSON_HYPHAE);block(b,o,g,0,22,2,Blocks.SHROOMLIGHT);
  }
 }
 private static void block(SceneBuilder b,BlockPos o,LobbyGate g,int u,int y,int v,Block block){b.at(g.at(o,u,y,v),block.defaultBlockState());}
 private static void box(SceneBuilder b,BlockPos o,LobbyGate g,int u1,int y1,int v1,int u2,int y2,int v2,Block block){for(int u=Math.min(u1,u2);u<=Math.max(u1,u2);u++)for(int y=y1;y<=y2;y++)for(int v=v1;v<=v2;v++)block(b,o,g,u,y,v,block);}
}
