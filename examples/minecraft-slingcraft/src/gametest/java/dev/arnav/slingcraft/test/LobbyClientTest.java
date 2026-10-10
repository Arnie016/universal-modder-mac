package dev.arnav.slingcraft.test;
import dev.arnav.slingcraft.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
public final class LobbyClientTest implements FabricClientGameTest {
 public void runTest(ClientGameTestContext ctx){
  try(var sp=ctx.worldBuilder().adjustSettings(s->{s.setAllowCommands(true);}).create()){
   ctx.waitTicks(30);
   sp.getServer().runOnServer(s->{var p=s.getPlayerList().getPlayers().getFirst();p.getInventory().setItem(17,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND,3));GameLobby.install(s.overworld(),new BlockPos(0,100,0));GameLobby.enter(p);});
   for(int wait=0;wait<1200&&!GameLobby.ready();wait+=20)ctx.waitTicks(20);
   if(!GameLobby.ready())throw new AssertionError("Garden generation did not complete");
   sp.getServer().runOnServer(s->{
    var p=s.getPlayerList().getPlayers().getFirst();
    if(!p.getInventory().getItem(17).isEmpty()||!p.getOffhandItem().isEmpty())throw new AssertionError("Old inventory leaked into hub");
    var archive=s.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("slingcraft-players").resolve(p.getUUID()+"-inventory.json");
    try{if(!java.nio.file.Files.readString(archive).contains("diamond"))throw new AssertionError("Original inventory was not archived");}catch(java.io.IOException e){throw new RuntimeException(e);}
   });
   ctx.waitTicks(40);ctx.takeScreenshot("lobby-gates");
   ctx.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_9);ctx.waitTicks(5);
   ctx.getInput().holdMouse(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT);ctx.waitTicks(3);ctx.getInput().releaseMouse(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT);ctx.waitTicks(3);
   if(!ctx.computeOnClient(mc->mc.player.containerMenu instanceof net.minecraft.world.inventory.ChestMenu))throw new AssertionError("Compass did not open chest game selector");ctx.takeScreenshot("worlds-menu");
   ctx.computeOnClient(mc->{mc.player.closeContainer();return true;});
   sp.getServer().runOnServer(s->{var p=s.getPlayerList().getPlayers().getFirst();var a=GameLobby.portal(1);p.connection.teleport(a.getX()+.5,a.getY()+1,a.getZ()+.5,0,0);});ctx.waitTicks(80);
   java.util.concurrent.atomic.AtomicBoolean busy=new java.util.concurrent.atomic.AtomicBoolean(true);
   for(int wait=0;wait<1600&&busy.get();wait+=20){sp.getServer().runOnServer(s->busy.set(SceneBuilder.busy(s.overworld())));ctx.waitTicks(20);}
   ctx.takeScreenshot("garage-setup");
   ctx.computeOnClient(mc->{
    if(!(mc.gui.screen() instanceof dev.arnav.slingcraft.GarageScreen))throw new AssertionError("Garage screen missing");
    for(var child:mc.gui.screen().children())if(child instanceof net.minecraft.client.gui.components.Button button&&button.getMessage().getString().startsWith("READY"))button.onPress(new net.minecraft.client.input.KeyEvent(257,0,0));
    return true;
   });ctx.waitTicks(130);
   if(!ctx.computeOnClient(mc->mc.player.isPassenger()))throw new AssertionError("Gate did not automatically seat player");
   ctx.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_F5);ctx.waitTicks(10);
   if(ctx.computeOnClient(mc->!mc.player.getOffhandItem().isEmpty()||!mc.player.getInventory().getItem(2).isEmpty()))throw new AssertionError("Siege kit leaked into race");
   ctx.takeScreenshot("frostline-auto-start");
   ctx.getInput().holdKeyFor(com.mojang.blaze3d.platform.InputConstants.KEY_W,25);ctx.waitTicks(2);
   double speed=ctx.computeOnClient(mc->mc.player.getVehicle().getDeltaMovement().horizontalDistance());
   if(speed<.2)throw new AssertionError("Throttle did not accelerate: "+speed);
   ctx.getInput().holdKeyFor(com.mojang.blaze3d.platform.InputConstants.KEY_S,15);ctx.waitTicks(2);
   if(ctx.computeOnClient(mc->mc.player.getVehicle().getDeltaMovement().horizontalDistance())>.12)throw new AssertionError("Brake did not stop craft");
   ctx.getInput().holdKey(com.mojang.blaze3d.platform.InputConstants.KEY_W);
   ctx.getInput().holdKeyFor(com.mojang.blaze3d.platform.InputConstants.KEY_SPACE,20);
   ctx.getInput().releaseKey(com.mojang.blaze3d.platform.InputConstants.KEY_W);
   if(dev.arnav.slingcraft.RaceHud.boost>=100)throw new AssertionError("Boost did not consume charge");
   ctx.takeScreenshot("frostline-boost");
   sp.getServer().runOnServer(s->{var p=s.getPlayerList().getPlayers().getFirst();if(!Frostline.contains(p))throw new AssertionError("Race not active");GameLobby.enter(p);});
   ctx.waitTicks(15);
   if(ctx.computeOnClient(mc->mc.player.isPassenger()))throw new AssertionError("Return failed to dismount");
   sp.getServer().runOnServer(s->{var p=s.getPlayerList().getPlayers().getFirst();var a=GameLobby.portal(1);p.connection.teleport(a.getX()+.5,a.getY()+1,a.getZ()+.5,0,0);});ctx.waitTicks(80);
   busy.set(true);for(int wait=0;wait<1600&&busy.get();wait+=20){sp.getServer().runOnServer(s->busy.set(SceneBuilder.busy(s.overworld())));ctx.waitTicks(20);}
   ctx.takeScreenshot("garage-setup");
   ctx.computeOnClient(mc->{
    if(!(mc.gui.screen() instanceof dev.arnav.slingcraft.GarageScreen))throw new AssertionError("Garage screen missing");
    for(var child:mc.gui.screen().children())if(child instanceof net.minecraft.client.gui.components.Button button&&button.getMessage().getString().startsWith("READY"))button.onPress(new net.minecraft.client.input.KeyEvent(257,0,0));
    return true;
   });ctx.waitTicks(130);
   if(!ctx.computeOnClient(mc->mc.player.isPassenger()))throw new AssertionError("Second race entry failed");
   sp.getServer().runOnServer(s->GameLobby.enter(s.getPlayerList().getPlayers().getFirst()));ctx.waitTicks(40);
  }
 }
}
