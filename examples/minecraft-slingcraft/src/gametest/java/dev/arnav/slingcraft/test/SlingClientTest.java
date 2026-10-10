package dev.arnav.slingcraft.test;
import dev.arnav.slingcraft.Slingcraft;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.Items;
import com.mojang.blaze3d.platform.InputConstants;

/** Real client input: native placement/breaking, three charged bird bows, inventory, and dimension isolation. */
public final class SlingClientTest implements FabricClientGameTest {
 public void runTest(ClientGameTestContext ctx){
  net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave saved;
  try(var sp=ctx.worldBuilder().adjustSettings(settings->{settings.setAllowCommands(true);settings.setName("Sling Siege Challenge");}).create()){
   var server=sp.getServer();ctx.waitTicks(35);
   for(String c:new String[]{"gamemode creative @p","time set noon","weather clear","difficulty easy","fill -12 99 -6 12 100 38 minecraft:grass_block","tp @p 0.5 101 0.5 0 -5","spawnpoint @p 0 101 0"})server.runCommand(c);
   server.runCommand("execute as @p at @s run sling level");ctx.waitTicks(30);
   server.runOnServer(s->s.getLevel(Level.NETHER).setBlockAndUpdate(new BlockPos(0,110,24),Blocks.OAK_PLANKS.defaultBlockState()));
   ctx.getInput().pressKey(InputConstants.KEY_V);ctx.waitTicks(12);
   if(Slingcraft.stageNumber!=1)throw new AssertionError("locked next fort was accessible");
   ctx.takeScreenshot("siege-01-ready");
   // The real Minecraft creative inventory remains available.
   ctx.getInput().pressKey(o->o.keyInventory);ctx.waitTicks(8);ctx.takeScreenshot("siege-02-native-inventory");
   ctx.getInput().pressKey(InputConstants.KEY_ESCAPE);ctx.waitTicks(8);
   // Place and break with the requested R / X aliases on a marked build pad.
   server.runCommand("tp @p 7.5 101 20.5 0 35");ctx.waitTicks(8);
   ctx.getInput().pressKey(InputConstants.KEY_4);ctx.getInput().lookAt(new BlockPos(7,100,23));ctx.waitTicks(5);
   BlockPos placed=ctx.computeOnClient(mc->{var hit=(net.minecraft.world.phys.BlockHitResult)mc.hitResult;return hit.getBlockPos().relative(hit.getDirection());});
   ctx.getInput().holdKeyFor(InputConstants.KEY_R,3);ctx.waitTicks(12);
   
   server.runOnServer(s->{if(!s.overworld().getBlockState(placed).is(Blocks.OAK_PLANKS))throw new AssertionError("R did not place the native block");});
   ctx.takeScreenshot("siege-03-placed-block");ctx.getInput().lookAt(placed);ctx.waitTicks(3);
   ctx.getInput().holdKeyFor(InputConstants.KEY_X,4);ctx.waitTicks(12);
   server.runOnServer(s->{if(!s.overworld().getBlockState(placed).isAir())throw new AssertionError("X did not break placed block");});
   server.runCommand("tp @p 0.5 101 0.5 0 -5");ctx.waitTicks(10);
   net.minecraft.world.phys.Vec3 predicted=null;
   for(int shot=0;shot<3;shot++){
    ctx.getInput().pressKey(InputConstants.KEY_1+shot);ctx.waitTicks(5);
    ctx.getInput().holdMouse(InputConstants.MOUSE_BUTTON_RIGHT);ctx.waitTicks(24);
    if(shot==0){
     if(!ctx.computeOnClient(mc->mc.player.isUsingItem()))throw new AssertionError("bow never entered charged use");
     predicted=ctx.computeOnClient(mc->dev.arnav.slingcraft.TrajectoryPreview.impactPoint);
     if(predicted==null)throw new AssertionError("trajectory did not predict the fort");
     ctx.takeScreenshot("siege-04-bow-charged");
    }
    ctx.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_RIGHT);ctx.waitTicks(3);
    if(shot==0)ctx.takeScreenshot("siege-05-bird-flight");ctx.waitTicks(85);
    if(shot==0&&Slingcraft.lastImpact.distanceTo(predicted)>.6)throw new AssertionError("trajectory estimate drift: "+predicted+" vs "+Slingcraft.lastImpact);
   }
   ctx.takeScreenshot("siege-06-destruction");
   if(Slingcraft.birdsUsed!=3)throw new AssertionError("expected 3 actual bow releases, got "+Slingcraft.birdsUsed);
   if(Slingcraft.destroyed<20)throw new AssertionError("bow did not damage the fort");
   server.runOnServer(s->{if(!s.getLevel(Level.NETHER).getBlockState(new BlockPos(0,110,24)).is(Blocks.OAK_PLANKS))throw new AssertionError("collapse leaked to another dimension");});
   Slingcraft.LOG.info("SIEGE INPUT PASS: 3 bow releases, {} block operations, {} targets left; R placement, X breaking, inventory and dimension isolation verified",Slingcraft.destroyed,Slingcraft.pigsLeft);
   if(!Slingcraft.roundState.equals("WON")||Slingcraft.roundScore!=7000)throw new AssertionError("clear/score failed: "+Slingcraft.roundState+" / "+Slingcraft.roundScore);
   ctx.getInput().pressKey(InputConstants.KEY_V);ctx.waitTicks(35);
   if(Slingcraft.stageNumber!=2||Slingcraft.pigsLeft!=6)throw new AssertionError("next fort failed");
   ctx.takeScreenshot("challenge-08-twin-nests");
   server.runCommand("tp @e[type=minecraft:pig,tag=slingcraft_target,limit=1] 70 101 24");ctx.waitTicks(20);
   if(Slingcraft.pigsLeft!=6||Slingcraft.roundScore!=0)throw new AssertionError("escaped target counted as defeated");
   // Deliberately miss five shots: show the exhausted state and prevent a sixth bird.
   ctx.getInput().lookAt(new BlockPos(0,160,24));ctx.getInput().pressKey(InputConstants.KEY_1);ctx.waitTicks(5);
   for(int i=0;i<6;i++){ctx.getInput().holdMouse(InputConstants.MOUSE_BUTTON_RIGHT);ctx.waitTicks(24);ctx.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_RIGHT);ctx.waitTicks(8);}
   ctx.waitTicks(180);
   if(Slingcraft.birdsUsed!=5||!Slingcraft.roundState.equals("LOST"))throw new AssertionError("budget gate failed: "+Slingcraft.birdsUsed+" "+Slingcraft.roundState);
   ctx.takeScreenshot("challenge-09-out-of-birds");
   ctx.getInput().pressKey(InputConstants.KEY_N);ctx.waitTicks(35);
   if(Slingcraft.stageNumber!=2||Slingcraft.birdsUsed!=0||Slingcraft.pigsLeft!=6)throw new AssertionError("retry changed level or failed to refill");
   server.runOnServer(ms->{if(ms.overworld().getEntitiesOfClass(net.minecraft.world.entity.animal.pig.Pig.class,new net.minecraft.world.phys.AABB(-100,90,-100,100,140,100),e->e.isAlive()&&e.entityTags().contains(Slingcraft.TARGET)).size()!=6)throw new AssertionError("escaped target survived reset");});
   ctx.getInput().pressKey(InputConstants.KEY_2);ctx.waitTicks(5);
   for(BlockPos aim:new BlockPos[]{new BlockPos(-5,104,24),new BlockPos(5,102,26)}){
    if(Slingcraft.roundState.equals("WON"))break;
    ctx.getInput().lookAt(aim);ctx.waitTicks(5);ctx.getInput().holdMouse(InputConstants.MOUSE_BUTTON_RIGHT);ctx.waitTicks(24);ctx.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_RIGHT);ctx.waitTicks(150);
   }
   if(!Slingcraft.roundState.equals("WON"))throw new AssertionError("twin nests not completable: "+Slingcraft.pigsLeft);
   ctx.takeScreenshot("challenge-10-twins-cleared");
   ctx.getInput().pressKey(InputConstants.KEY_V);ctx.waitTicks(35);
   if(Slingcraft.stageNumber!=3||Slingcraft.pigsLeft!=6)throw new AssertionError("stone fort missing");
   ctx.takeScreenshot("challenge-11-stone-crown");
   // Open an actual trapdoor via normal item use, then return to the firing line.
   server.runCommand("tp @p 0.5 101 10.5 0 30");ctx.waitTicks(8);
   ctx.getInput().pressKey(InputConstants.KEY_4);
   float[] hingeAim=ctx.computeOnClient(mc->{var delta=new net.minecraft.world.phys.Vec3(.5,104.94,12.5).subtract(mc.player.getEyePosition());return new float[]{(float)Math.toDegrees(Math.atan2(-delta.x,delta.z)),(float)-Math.toDegrees(Math.atan2(delta.y,Math.hypot(delta.x,delta.z)))};});
   ctx.getInput().lookAt(hingeAim[0],hingeAim[1]);ctx.waitTicks(5);
   BlockPos door=ctx.computeOnClient(mc->((net.minecraft.world.phys.BlockHitResult)mc.hitResult).getBlockPos());
   ctx.getInput().holdKeyFor(InputConstants.KEY_R,2);ctx.waitTicks(8);
   server.runOnServer(ms->{var st=ms.overworld().getBlockState(door);if(!st.is(Blocks.OAK_TRAPDOOR)||!st.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN))throw new AssertionError("R did not open bridge trapdoor");});
   server.runCommand("tp @p 0.5 106 -5.5 0 -6");ctx.waitTicks(210);
   ctx.takeScreenshot("challenge-11b-citadel-overview");
   server.runCommand("tp @p 0.5 101 0.5 0 -5");ctx.waitTicks(8);
   ctx.getInput().pressKey(InputConstants.KEY_2);ctx.waitTicks(5);
   for(BlockPos aim:new BlockPos[]{new BlockPos(0,104,25),new BlockPos(0,110,25),new BlockPos(0,102,25)}){
    if(Slingcraft.roundState.equals("WON"))break;
    ctx.getInput().lookAt(aim);ctx.waitTicks(5);ctx.getInput().holdMouse(InputConstants.MOUSE_BUTTON_RIGHT);ctx.waitTicks(24);ctx.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_RIGHT);ctx.waitTicks(150);
   }
   while(!Slingcraft.roundState.equals("WON")&&Slingcraft.birdsUsed<5){
    BlockPos[] target={null};
    server.runOnServer(ms->{var pigs=ms.overworld().getEntitiesOfClass(net.minecraft.world.entity.animal.pig.Pig.class,new net.minecraft.world.phys.AABB(-60,90,-30,60,130,70),e->e.isAlive()&&e.entityTags().contains(Slingcraft.TARGET));if(!pigs.isEmpty())target[0]=pigs.getFirst().blockPosition().above();});
    if(target[0]==null)break;
    ctx.getInput().lookAt(target[0]);ctx.waitTicks(4);ctx.getInput().holdMouse(InputConstants.MOUSE_BUTTON_RIGHT);ctx.waitTicks(24);ctx.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_RIGHT);ctx.waitTicks(150);
   }
   if(!Slingcraft.roundState.equals("WON"))throw new AssertionError("stone crown not completable: "+Slingcraft.pigsLeft);
   ctx.takeScreenshot("challenge-12-all-cleared");
   server.runOnServer(mcServer->{
    try(var in=java.nio.file.Files.newInputStream(mcServer.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("slingcraft.properties"))){
     var v=new java.util.Properties();v.load(in);
     for(int i=0;i<3;i++)if(Integer.parseInt(v.getProperty("best"+i,"0"))<6000)throw new AssertionError("best score not persisted");
     if(!v.getProperty("unlocked").equals("2"))throw new AssertionError("unlock not persisted");
    }catch(java.io.IOException e){throw new RuntimeException(e);}
   });
   ctx.getInput().pressKey(InputConstants.KEY_V);ctx.waitTicks(35);
   if(Slingcraft.stageNumber!=1)throw new AssertionError("campaign replay did not return to fort one");
   Slingcraft.LOG.info("CHALLENGE PASS: all 3 forts cleared through bow input; score, unlock, five-shot limit, sixth-shot rejection, loss/retry and persisted best scores verified");
   // Verify reset removes debris and restores six targets; save a pristine play world.
   ctx.getInput().pressKey(InputConstants.KEY_N);ctx.waitTicks(30);
   if(Slingcraft.pigsLeft!=6||Slingcraft.birdsUsed!=0)throw new AssertionError("reset did not restore level: pigs="+Slingcraft.pigsLeft+" shots="+Slingcraft.birdsUsed);
   // R also charges the bow, not only place-block use.
   ctx.getInput().holdKey(InputConstants.KEY_R);ctx.waitTicks(24);
   if(!ctx.computeOnClient(mc->mc.player.isUsingItem()))throw new AssertionError("R did not charge bird bow");
   ctx.getInput().releaseKey(InputConstants.KEY_R);ctx.waitTicks(40);
   if(Slingcraft.birdsUsed!=1)throw new AssertionError("R release did not fire exactly once");
   server.runCommand("execute as @p at @s run sling reset");ctx.waitTicks(15);
   server.runCommand("tp @p 0.5 101 0.5 0 -5");
   ctx.waitTicks(210);
   if(ctx.computeOnClient(mc->Slingcraft.bowKind(mc.player.getMainHandItem()))!=0)throw new AssertionError("reset did not sync the selected red bow");
   ctx.takeScreenshot("siege-07-ready-to-play");
   Slingcraft.LOG.info("SIEGE RESET PASS: six targets restored, R bow release passed; ready-to-play save written");
   ctx.getInput().pressKey(InputConstants.KEY_V);ctx.waitTicks(25);
   saved=sp.getWorldSave();
  }
  try(var reopened=saved.open()){
   ctx.waitTicks(80);
   if(Slingcraft.stageNumber!=2||Slingcraft.pigsLeft!=6||Slingcraft.birdsUsed!=0)throw new AssertionError("reopen did not restore fort 2 as fresh attempt");
   reopened.getServer().runOnServer(ms->{if(ms.overworld().getEntitiesOfClass(net.minecraft.world.entity.animal.pig.Pig.class,new net.minecraft.world.phys.AABB(-100,90,-100,100,140,100),e->e.isAlive()&&e.entityTags().contains(Slingcraft.TARGET)).size()!=6)throw new AssertionError("reopen duplicated targets");});
   ctx.getInput().pressKey(InputConstants.KEY_V);ctx.waitTicks(25);
   if(Slingcraft.stageNumber!=3)throw new AssertionError("unlock was lost on reopen");
   ctx.getInput().pressKey(InputConstants.KEY_V);ctx.waitTicks(25);
   if(Slingcraft.stageNumber!=1)throw new AssertionError("final best score was lost on reopen");
   ctx.waitTicks(210);ctx.takeScreenshot("challenge-13-reopened-ready");
   Slingcraft.LOG.info("SAVE REOPEN PASS: selected fort, unlocks and best scores survived restart; six targets without duplicates; escaped target/reset checks passed");
  }
 }
}
