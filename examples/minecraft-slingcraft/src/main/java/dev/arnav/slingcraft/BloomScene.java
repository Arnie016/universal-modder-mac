package dev.arnav.slingcraft;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Original enclosed conservatory: foyer, broken exhibit rooms and a living fungal nave. */
final class BloomScene {
 static void build(ServerLevel l,BlockPos o,Runnable done){
  SceneBuilder b=new SceneBuilder(l,o);
  for(int x=-28;x<=28;x++)for(int z=-32;z<=28;z++){
   b.block(x,-2,z,Blocks.STONE);b.block(x,-1,z,(x+z)%9==0?Blocks.MOSSY_STONE_BRICKS:Blocks.DEEPSLATE_TILES);
   if(Math.abs(x)==28||z==-32||z==28)b.box(x,0,z,x,23,z,Blocks.POLISHED_BLACKSTONE_BRICKS);
   b.block(x,24,z,Blocks.TINTED_GLASS);
   if(x%8==0||z%10==0)b.block(x,23,z,Blocks.DARK_OAK_LOG);
  }
  // Carpeted arrival vestibule; the real sky disappears behind a vaulted iron-and-glass roof.
  for(int x=-4;x<=4;x++)for(int z=12;z<=26;z++)b.block(x,0,z,net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft",Math.abs(x)==4?"black_carpet":"red_carpet")));
  for(int side:new int[]{-1,1}){
   int x=side*7;b.box(x,0,14,x,12,14,Blocks.POLISHED_BLACKSTONE);b.box(x,0,25,x,12,25,Blocks.POLISHED_BLACKSTONE);
   for(int z:new int[]{15,24}){b.box(x,0,z,x,2,z,Blocks.CHISELED_STONE_BRICKS);b.block(x,3,z,Blocks.SOUL_LANTERN);}
   for(int z=-26;z<=8;z+=12){
    int wing=side*23;b.box(wing-4,0,z-4,wing+4,8,z+4,Blocks.MOSSY_STONE_BRICKS);b.box(wing-3,0,z-3,wing+3,6,z+3,Blocks.AIR);
    b.box(wing-4,2,z,wing+4,5,z,Blocks.AIR);b.box(wing-4,9,z-4,wing+4,9,z+4,Blocks.DARK_OAK_PLANKS);b.block(wing,10,z,Blocks.SOUL_LANTERN);
   }
  }
  b.box(-7,12,14,7,13,14,Blocks.POLISHED_BLACKSTONE_BRICKS);
  for(int x=-6;x<=6;x+=2)b.box(x,9,14,x,11,14,Blocks.IRON_BARS);
  for(int x:new int[]{-16,16})for(int z:new int[]{-22,-10,3}){
   b.box(x-1,0,z-1,x+1,19,z+1,Blocks.MOSSY_STONE_BRICKS);b.block(x,8,z+2,Blocks.SHROOMLIGHT);
   b.box(x-2,19,z-2,x+2,20,z+2,Blocks.CHISELED_STONE_BRICKS);
  }
  b.box(-7,0,-29,7,9,-26,Blocks.DARK_OAK_PLANKS);b.box(-5,0,-28,5,7,-25,Blocks.AIR);
  b.box(-3,0,-28,3,1,-26,Blocks.POLISHED_BLACKSTONE);b.block(0,2,-27,Blocks.SEA_LANTERN);
  // Organic veins crawl up the nave rather than covering every navigable tile.
  for(int side:new int[]{-1,1})for(int i=0;i<30;i++){
   int x=side*(10+i%5),z=-28+i; b.block(x,0,z,Blocks.MOSS_CARPET);
   if(i%4==0){b.block(x,1,z,Blocks.BROWN_MUSHROOM);b.block(side*27,3+i/3,z,Blocks.MUSHROOM_STEM);}
  }
  b.submit(done);
 }
}
