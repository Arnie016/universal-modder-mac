package dev.arnav.slingcraft.test;
import dev.arnav.slingcraft.*;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.GameType;

/** Real render and W-driven portal entries, non-operator, temporary world only. */
public final class GardenArchitectureClientTest implements FabricClientGameTest {
 public void runTest(ClientGameTestContext ctx){
  try(var sp=ctx.worldBuilder().adjustSettings(s->s.setAllowCommands(false)).create()){
   // Fabric's default five-chunk proof view clips the 160-block courtyard. These settings apply only to this disposable client.
   ctx.computeOnClient(mc->{mc.options.renderDistance().set(12);mc.options.broadcastOptions();mc.getWindow().setWindowed(1440,900);return true;});
   sp.getServer().runOnServer(s->s.getPlayerList().setViewDistance(12));
   ctx.waitTicks(30);long began=System.nanoTime();
   sp.getServer().runOnServer(s->{var p=s.getPlayerList().getPlayers().getFirst();p.connection.teleport(.5,100,.5,0,0);Slingcraft.buildLevel(p);GameLobby.install(s.overworld(),new BlockPos(0,100,0));GameLobby.enter(p);});
   for(int n=0;n<2400&&!GameLobby.ready();n+=20)ctx.waitTicks(20);
   if(!GameLobby.ready())throw new AssertionError("Garden generation did not complete");
   Slingcraft.LOG.info("Grand garden generation completed in {} wall seconds",(System.nanoTime()-began)/1e9);
   ctx.waitTicks(200);
   ctx.computeOnClient(mc->{Slingcraft.LOG.info("Garden view distance configured {} effective {}",mc.options.renderDistance().get(),mc.options.getEffectiveRenderDistance());return true;});
   sp.getServer().runOnServer(s->{
    var l=s.overworld();var o=GameLobby.origin();var p=s.getPlayerList().getPlayers().getFirst();
    if(p.createCommandSourceStack().permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))throw new AssertionError("Player is operator");
    for(var gate:LobbyGate.ALL){
     for(int u=-3;u<=3;u++)for(int y=0;y<LobbyGate.HEIGHT;y++){
      var at=gate.at(o,u,y,0);var state=l.getBlockState(at);
      if(!state.is(Blocks.NETHER_PORTAL)||state.getValue(NetherPortalBlock.AXIS)!=gate.axis())throw new AssertionError("Portal surface broke: "+at+" "+state);
      if(!GameLobby.isGardenPortal(l,at))throw new AssertionError("Gate not scoped");
     }
     for(int v=1;v<18;v++)if(!l.getBlockState(gate.at(o,0,1,v)).isAir())throw new AssertionError("Entrance obstructed: "+gate.title()+" "+v);
    }
    if(GameLobby.isGardenPortal(l,o.offset(10,0,10)))throw new AssertionError("Ordinary Nether portal scope leaked");
    if(!l.getBlockState(o.offset(0,24,-20)).is(Blocks.SEA_LANTERN))throw new AssertionError("Guardian lost");
    if(!l.getBlockState(o.offset(76,45,0)).is(Blocks.SEA_LANTERN))throw new AssertionError("Monument missing");
    if(!l.canSeeSky(o.offset(0,3,28)))throw new AssertionError("Spawn sky enclosed");
    for(int sx:new int[]{-1,1})for(int sz:new int[]{-1,1}){
     int x=sx*49,z=sz*49,f=sz<0?1:-1;
     if(l.getFluidState(o.offset(x,18,z+3*f)).isEmpty())throw new AssertionError("Waterfall missing");
     for(int u=-8;u<=8;u++)for(int v=-4;v<=10;v++)if(Math.abs(u)==8||v==-4||v==10){
      if(!l.getFluidState(o.offset(x+u,0,z+v*f)).isEmpty())throw new AssertionError("Water escaped its basin");
     }
    }
    GameLobby.install(l,new BlockPos(0,100,0));if(!GameLobby.ready()||SceneBuilder.busy(l))throw new AssertionError("Saved marker did not skip rebuilding");
   });
   ctx.waitTicks(120);ctx.takeScreenshot("grand-garden-spawn");
   // Aerial views are explicitly test cameras; walkthrough entries below use normal adventure movement.
   sp.getServer().runOnServer(s->{var p=s.getPlayerList().getPlayers().getFirst();p.setGameMode(GameType.SPECTATOR);var o=GameLobby.origin();p.connection.teleport(o.getX()+.5,o.getY()+35,o.getZ()+57,180,24);});
   ctx.waitTicks(200);ctx.takeScreenshot("grand-garden-open-sky-panorama");
   sp.getServer().runOnServer(s->{var p=s.getPlayerList().getPlayers().getFirst();var o=GameLobby.origin();p.connection.teleport(o.getX()+30,o.getY()+8,o.getZ()+23,-42,-12);});
   ctx.waitTicks(30);ctx.takeScreenshot("grand-garden-waterfall");
   for(int i=0;i<3;i++){
    final int mode=i;var gate=LobbyGate.ALL[i];
    sp.getServer().runOnServer(s->{var p=s.getPlayerList().getPlayers().getFirst();GameLobby.enter(p);var at=gate.point(GameLobby.origin(),0,1,22);p.connection.teleport(at.x,at.y,at.z,gate.approachYaw(),-18);});
    ctx.waitTicks(25);ctx.takeScreenshot("grand-garden-"+new String[]{"siege","frostline","bloom"}[i]+"-court");
    sp.getServer().runOnServer(s->{var p=s.getPlayerList().getPlayers().getFirst();var at=gate.point(GameLobby.origin(),0,1,5);p.connection.teleport(at.x,at.y,at.z,gate.approachYaw(),0);});
    ctx.waitTicks(10);ctx.getInput().holdKey(InputConstants.KEY_W);
    boolean entered=false;
    try{for(int t=0;t<100;t+=5){ctx.waitTicks(5);java.util.concurrent.atomic.AtomicBoolean inHub=new java.util.concurrent.atomic.AtomicBoolean();sp.getServer().runOnServer(s->inHub.set(GameLobby.contains(s.getPlayerList().getPlayers().getFirst())));if(!inHub.get()){entered=true;break;}}}finally{ctx.getInput().releaseKey(InputConstants.KEY_W);}
    if(!entered)throw new AssertionError("Walking into portal did not enter "+gate.title());ctx.waitTicks(15);
    sp.getServer().runOnServer(s->{var p=s.getPlayerList().getPlayers().getFirst();ModeProfiles.Mode expected=mode==0?ModeProfiles.Mode.SIEGE:mode==1?ModeProfiles.Mode.RACE:ModeProfiles.Mode.NIGHTFALL;if(ModeProfiles.mode(p)!=expected||p.level()!=s.overworld())throw new AssertionError("Wrong portal destination");if(mode==2&&p.getAbilities().invulnerable)throw new AssertionError("Hub protection leaked into Bloom");});
    ctx.takeScreenshot("grand-garden-portal-entered-"+i);
   }
   sp.getServer().runOnServer(s->{var p=s.getPlayerList().getPlayers().getFirst();GameLobby.enter(p);if(!p.getAbilities().invulnerable||p.getHealth()!=p.getMaxHealth()||p.fallDistance!=0)throw new AssertionError("Return to hub was not safe");});ctx.waitTicks(25);
   ctx.getInput().pressKey(InputConstants.KEY_9);ctx.waitTicks(3);ctx.getInput().holdMouse(InputConstants.MOUSE_BUTTON_RIGHT);ctx.waitTicks(2);ctx.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_RIGHT);ctx.waitTicks(5);
   if(!ctx.computeOnClient(mc->mc.player.containerMenu instanceof net.minecraft.world.inventory.ChestMenu))throw new AssertionError("Compass regression");ctx.takeScreenshot("grand-garden-native-game-grid");
  }
 }
}
