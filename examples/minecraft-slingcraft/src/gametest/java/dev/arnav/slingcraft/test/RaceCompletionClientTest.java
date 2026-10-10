package dev.arnav.slingcraft.test;

import dev.arnav.slingcraft.*;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Drives the real client chassis with W/A/D around all three laps, without warping gates. */
public final class RaceCompletionClientTest implements FabricClientGameTest {
 private record Craft(double x,double z,float yaw,String phase,int lap,int gate){}
 public void runTest(ClientGameTestContext ctx){
  String resultAction=System.getenv().getOrDefault("FROSTLINE_RESULTS_ACTION","auto");
  Slingcraft.LOG.info("Race proof result action: {}",resultAction);
  try(var sp=ctx.worldBuilder().adjustSettings(s->s.setAllowCommands(false)).create()){
   ctx.waitTicks(30);
   sp.getServer().runOnServer(s->{var p=s.getPlayerList().getPlayers().getFirst();
    if(p.createCommandSourceStack().permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))throw new AssertionError("Race proof must be a non-operator");
    GameLobby.install(s.overworld(),new BlockPos(0,100,0));GameLobby.enter(p);
   });
   for(int n=0;n<1600&&!GameLobby.ready();n+=20)ctx.waitTicks(20);
   sp.getServer().runOnServer(s->Frostline.enterGame(s.getPlayerList().getPlayers().getFirst()));
   AtomicBoolean busy=new AtomicBoolean(true);
   for(int n=0;n<1800&&busy.get();n+=20){sp.getServer().runOnServer(s->busy.set(SceneBuilder.busy(s.overworld())));ctx.waitTicks(20);}
   if(busy.get())throw new AssertionError("Circuit did not finish building");
   ctx.computeOnClient(mc->{
    if(!(mc.gui.screen() instanceof GarageScreen))throw new AssertionError("Garage missing");
    for(var child:mc.gui.screen().children())if(child instanceof net.minecraft.client.gui.components.Button button&&button.getMessage().getString().startsWith("READY"))button.onPress(new net.minecraft.client.input.KeyEvent(257,0,0));
    mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);return true;
   });ctx.waitTicks(125);
   if(resultAction.equals("timeout")){
    boolean ended=false;
    // Use real ticks: tick sprint bypasses Fabric's coordinated client/server test clock.
    for(int n=0;n<1000&&!ended;n++){
     ctx.waitTicks(5);ended=ctx.computeOnClient(mc->RaceHud.data(mc).getStringOr("racePhase","").equals("results"));
     if(n%160==0)Slingcraft.LOG.info("Timeout proof: {} seconds elapsed",n/4);
    }
    if(!ended)throw new AssertionError("The four-minute limit did not end the race");
    ctx.waitTicks(3);
    if(ctx.computeOnClient(mc->RaceHud.data(mc).getBooleanOr("raceCompleted",true)||RaceHud.data(mc).getIntOr("raceCredits",-1)!=0))throw new AssertionError("Timeout was treated as a finish");
    ctx.takeScreenshot("race-time-limit-results");
    ctx.computeOnClient(mc->{
     for(var child:mc.gui.screen().children())if(child instanceof net.minecraft.client.gui.components.Button b&&b.getMessage().getString().equals("Garden"))b.onPress(new net.minecraft.client.input.KeyEvent(257,0,0));
     return true;
    });ctx.waitTicks(15);
    sp.getServer().runOnServer(s->{var p=s.getPlayerList().getPlayers().getFirst();if(!GameLobby.contains(p)||Frostline.contains(p)||ModeProfiles.xp(p)!=0||ModeProfiles.credits(p)!=0)throw new AssertionError("Timed-out race did not return cleanly without rewards");});
    ctx.takeScreenshot("race-time-limit-garden-return");
    return;
   }
   double[] origin=ctx.computeOnClient(mc->{var boat=mc.player.getVehicle();if(boat==null)throw new AssertionError("No pod on the grid");return new double[]{boat.getX()-.5,boat.getZ()+64.5};});
   Set<String> observed=new HashSet<>();boolean finished=false;
   try{
    for(int tick=0;tick<4800;tick++){
     Craft craft=ctx.computeOnClient(mc->{var boat=mc.player.getVehicle();if(boat==null)throw new AssertionError("Lost pod during driving");var state=RaceHud.data(mc);return new Craft(boat.getX(),boat.getZ(),boat.getYRot(),state.getStringOr("racePhase",""),state.getIntOr("raceLap",0),state.getIntOr("raceGate",0));});
     if(craft.phase.equals("results")){finished=true;break;}
     observed.add(craft.lap+":"+craft.gate);
     double x=craft.x-origin[0],z=craft.z-origin[1];
     double angle=Math.atan2(z/65.0,x/100.0);
     double next=angle+9/Math.hypot(100*Math.sin(angle),65*Math.cos(angle));
     double dx=100*Math.cos(next)-x,dz=65*Math.sin(next)-z;
     double desired=Math.toDegrees(Math.atan2(-dx,dz));
     double error=((desired-craft.yaw+540)%360+360)%360-180;
     if(error>1.2){ctx.getInput().holdKey(InputConstants.KEY_D);ctx.getInput().releaseKey(InputConstants.KEY_A);}
     else if(error< -1.2){ctx.getInput().holdKey(InputConstants.KEY_A);ctx.getInput().releaseKey(InputConstants.KEY_D);}
     else {ctx.getInput().releaseKey(InputConstants.KEY_A);ctx.getInput().releaseKey(InputConstants.KEY_D);}
     ctx.getInput().holdKey(InputConstants.KEY_W);
     if(tick==180)ctx.takeScreenshot("race-driving-lap-one");
     if(tick%400==0)Slingcraft.LOG.info("Race input proof: tick={} lap={} gate={} x={} z={}",tick,craft.lap,craft.gate,x,z);
     ctx.waitTicks(1);
    }
   }finally{ctx.getInput().releaseKey(InputConstants.KEY_W);ctx.getInput().releaseKey(InputConstants.KEY_A);ctx.getInput().releaseKey(InputConstants.KEY_D);}
   if(!finished)throw new AssertionError("Three laps did not finish. Observed "+observed);
   for(int lap=1;lap<=3;lap++)for(int gate=1;gate<=4;gate++)if(!observed.contains(lap+":"+gate))throw new AssertionError("Skipped ordered gate "+lap+":"+gate);
   ctx.waitTicks(8);
   if(!ctx.computeOnClient(mc->mc.gui.screen() instanceof RaceResultsScreen))throw new AssertionError("Results screen did not open");
   ctx.takeScreenshot("race-three-lap-results");
   long best=ctx.computeOnClient(mc->RaceHud.data(mc).getLongOr("raceResultBest",0));
   if(best<=0)throw new AssertionError("Invalid best lap");
   sp.getServer().runOnServer(s->{var p=s.getPlayerList().getPlayers().getFirst();
    if(ModeProfiles.xp(p)!=100||ModeProfiles.credits(p)!=100)throw new AssertionError("Finish reward was not saved exactly once");
    if(ModeProfiles.bestLapTicks(p)!=best)throw new AssertionError("Persisted best lap does not match result");
   });
   if(resultAction.equals("setup")){
    ctx.computeOnClient(mc->{
     for(var child:mc.gui.screen().children())if(child instanceof net.minecraft.client.gui.components.Button button&&button.getMessage().getString().equals("Change setup"))button.onPress(new net.minecraft.client.input.KeyEvent(257,0,0));
     return true;
    });ctx.waitTicks(15);
    if(!ctx.computeOnClient(mc->mc.gui.screen() instanceof GarageScreen))throw new AssertionError("Non-operator cannot return to setup");
    ctx.takeScreenshot("race-results-change-setup");
    ctx.computeOnClient(mc->{
     for(var child:mc.gui.screen().children())if(child instanceof net.minecraft.client.gui.components.Button button&&button.getMessage().getString().startsWith("READY"))button.onPress(new net.minecraft.client.input.KeyEvent(257,0,0));
     return true;
    });ctx.waitTicks(130);
   }else ctx.waitTicks(190);
   if(ctx.computeOnClient(mc->mc.gui.screen() instanceof RaceResultsScreen||!mc.player.isPassenger()))throw new AssertionError("Replay did not reseat and close results");
   sp.getServer().runOnServer(s->{var p=s.getPlayerList().getPlayers().getFirst();if(ModeProfiles.xp(p)!=100)throw new AssertionError("Replay duplicated the reward");});
   ctx.takeScreenshot(resultAction.equals("setup")?"race-manual-replay-grid":"race-auto-replay-grid");
   // Native compass return, rather than a direct server call, verifies the player's exit.
   ctx.getInput().pressKey(InputConstants.KEY_9);ctx.waitTicks(5);
   ctx.getInput().holdMouse(InputConstants.MOUSE_BUTTON_RIGHT);ctx.waitTicks(3);ctx.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_RIGHT);ctx.waitTicks(12);
   sp.getServer().runOnServer(s->{var p=s.getPlayerList().getPlayers().getFirst();if(!GameLobby.contains(p)||Frostline.contains(p)||p.isPassenger())throw new AssertionError("Compass did not return to garden");});
   ctx.takeScreenshot("race-earned-garden-return");
  }
 }
}
