package dev.arnav.slingcraft.test;

import dev.arnav.slingcraft.*;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;

/** Native chest/book interaction and authority checks in a disposable, non-operator world. */
public final class WorldsMenuClientTest implements FabricClientGameTest {
 private static void click(ClientGameTestContext ctx,int slot,ContainerInput input,int button){
  ctx.computeOnClient(mc->{mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId,slot,button,input,mc.player);return true;});ctx.waitTicks(6);
 }
 private static void open(ClientGameTestContext ctx){
  ctx.getInput().pressKey(InputConstants.KEY_9);ctx.waitTicks(3);
  ctx.getInput().holdMouse(InputConstants.MOUSE_BUTTON_RIGHT);ctx.waitTicks(2);ctx.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_RIGHT);ctx.waitTicks(6);
  if(!ctx.computeOnClient(mc->mc.player.containerMenu instanceof net.minecraft.world.inventory.ChestMenu))throw new AssertionError("Compass did not open native chest");
 }
 private static void close(ClientGameTestContext ctx){ctx.getInput().pressKey(InputConstants.KEY_ESCAPE);ctx.waitTicks(5);}
 public void runTest(ClientGameTestContext ctx){
  try(var sp=ctx.worldBuilder().adjustSettings(s->s.setAllowCommands(false)).create()){
   ctx.waitTicks(30);
   sp.getServer().runOnServer(s->{var p=s.getPlayerList().getPlayers().getFirst();p.connection.teleport(.5,100,.5,0,0);Slingcraft.buildLevel(p);GameLobby.install(s.overworld(),new BlockPos(0,100,0));GameLobby.enter(p);});
   for(int n=0;n<1600&&!GameLobby.ready();n+=20)ctx.waitTicks(20);
   if(!GameLobby.ready())throw new AssertionError("Garden not ready");
   sp.getServer().runOnServer(s->{var p=s.getPlayerList().getPlayers().getFirst();if(p.createCommandSourceStack().permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))throw new AssertionError("Menu player is operator");});
   sp.getServer().runOnServer(s->{
    var p=s.getPlayerList().getPlayers().getFirst();var source=p.createCommandSourceStack();var root=s.getCommands().getDispatcher().getRoot();
    for(String command:new String[]{"sling","frostline","nightfall-action","frostline-menu"})
     if(root.getChild(command).canUse(source))throw new AssertionError("Lobby player can use restricted command: "+command);
    if(!root.getChild("worlds-menu").canUse(source))throw new AssertionError("Normal player cannot use their game menu");
   });
   ctx.waitTicks(20);open(ctx);
   String[] names={"Sling Siege","Frostline GP","Bloom"};int[] slots={11,13,15};
   for(int i=0;i<slots.length;i++){final int slot=slots[i];String name=names[i];if(!ctx.computeOnClient(mc->mc.player.containerMenu.getSlot(slot).getItem().getHoverName().getString().equals(name)))throw new AssertionError("Mode missing: "+name);}
   ctx.takeScreenshot("worlds-chest-all-modes");
   for(ContainerInput input:new ContainerInput[]{ContainerInput.QUICK_MOVE,ContainerInput.SWAP,ContainerInput.THROW,ContainerInput.CLONE,ContainerInput.PICKUP_ALL,ContainerInput.QUICK_CRAFT})click(ctx,13,input,0);
   sp.getServer().runOnServer(s->{var p=s.getPlayerList().getPlayers().getFirst();if(!(p.containerMenu instanceof WorldsMenu)||!p.containerMenu.getCarried().isEmpty()||!p.getInventory().getItem(0).isEmpty()||!GameLobby.contains(p))throw new AssertionError("Menu icon could be taken or activated by transfer");});
   click(ctx,WorldsMenu.PROGRESS,ContainerInput.PICKUP,0);
   if(!ctx.computeOnClient(mc->mc.player.containerMenu.getSlot(10).getItem().is(net.minecraft.world.item.Items.DYE.gray())))throw new AssertionError("New player has an earned badge");
   click(ctx,10,ContainerInput.PICKUP,0);click(ctx,16,ContainerInput.QUICK_MOVE,0);
   sp.getServer().runOnServer(s->{var p=s.getPlayerList().getPlayers().getFirst();if(ModeProfiles.xp(p)!=0||!ModeProfiles.badges(p).isEmpty()||!p.containerMenu.getCarried().isEmpty())throw new AssertionError("Achievement click grants rewards or items");});
   ctx.takeScreenshot("worlds-achievements-locked");click(ctx,WorldsMenu.SUPPORT,ContainerInput.PICKUP,0);
   click(ctx,WorldsMenu.GUIDE,ContainerInput.PICKUP,0);
   if(!ctx.computeOnClient(mc->mc.gui.screen() instanceof BookViewScreen))throw new AssertionError("Guide did not open native book");
   ctx.computeOnClient(mc->{var pages=mc.player.getInventory().getItem(7).get(DataComponents.WRITTEN_BOOK_CONTENT).getPages(false);int number=0;String failures="";for(var page:pages){number++;int lines=mc.font.split(page,114).size();Slingcraft.LOG.info("Guide page {}: {} lines",number,lines);if(lines>14)failures+="Page "+number+": "+lines+" lines. ";}if(!failures.isEmpty())throw new AssertionError("Native book overflow: "+failures);return true;});
   ctx.takeScreenshot("worlds-guide-controls");
   ctx.computeOnClient(mc->{((BookViewScreen)mc.gui.screen()).setPage(7);return true;});ctx.waitTicks(2);ctx.takeScreenshot("worlds-guide-rewards");close(ctx);
   ctx.getInput().pressKey(InputConstants.KEY_8);ctx.waitTicks(3);ctx.getInput().holdMouse(InputConstants.MOUSE_BUTTON_RIGHT);ctx.waitTicks(2);ctx.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_RIGHT);ctx.waitTicks(5);
   if(!ctx.computeOnClient(mc->mc.gui.screen() instanceof BookViewScreen))throw new AssertionError("Hotbar guide is not readable");close(ctx);open(ctx);
   click(ctx,WorldsMenu.SUPPORT,ContainerInput.PICKUP,0);if(!ctx.computeOnClient(mc->mc.gui.screen() instanceof BookViewScreen))throw new AssertionError("Support page missing");ctx.takeScreenshot("worlds-support-book");close(ctx);open(ctx);
   click(ctx,WorldsMenu.SHOP,ContainerInput.PICKUP,0);ctx.takeScreenshot("worlds-chest-upgrades");click(ctx,11,ContainerInput.PICKUP,0);
   sp.getServer().runOnServer(s->{var p=s.getPlayerList().getPlayers().getFirst();if(ModeProfiles.owns(p,"incendiary")||ModeProfiles.credits(p)!=0)throw new AssertionError("Unaffordable upgrade granted");ModeProfiles.award(p,150,null);});
   close(ctx);open(ctx);click(ctx,WorldsMenu.SHOP,ContainerInput.PICKUP,0);click(ctx,11,ContainerInput.PICKUP,0);click(ctx,11,ContainerInput.PICKUP,0);
   sp.getServer().runOnServer(s->{var p=s.getPlayerList().getPlayers().getFirst();if(!ModeProfiles.owns(p,"incendiary")||ModeProfiles.credits(p)!=30)throw new AssertionError("Shop purchase not saved exactly once");});ctx.takeScreenshot("worlds-chest-owned-upgrade");
   sp.getServer().runOnServer(s->{
    var p=s.getPlayerList().getPlayers().getFirst();
    for(String fort:new String[]{"Glass Roost","Twin Nests","Stone Crown"})ModeProfiles.award(p,50,"Siege / "+fort);
    ModeProfiles.award(p,100,"Frostline finisher");ModeProfiles.award(p,150,"Bloom field researcher");ModeProfiles.award(p,50,"Siege / Glass Roost");
    ModeProfiles.bestLap(p,1234);ModeProfiles.bestLap(p,1400);
    // Each accessor reads the saved file; never construct a second live player with the same UUID.
    if(ModeProfiles.xp(p)!=600||ModeProfiles.credits(p)!=480||ModeProfiles.badges(p).size()!=5||ModeProfiles.bestLapTicks(p)!=1234||!ModeProfiles.owns(p,"incendiary"))throw new AssertionError("Saved profile values or unique badges are incorrect");
    var other=new net.minecraft.server.level.ServerPlayer(s,s.overworld(),new com.mojang.authlib.GameProfile(java.util.UUID.fromString("25e17e4f-b5b8-41fa-9555-efec08d2bc05"),"OtherPlayer"),net.minecraft.server.level.ClientInformation.createDefault());
    if(ModeProfiles.xp(other)!=0||ModeProfiles.credits(other)!=0||!ModeProfiles.badges(other).isEmpty()||ModeProfiles.owns(other,"incendiary"))throw new AssertionError("Progress leaked to a different player");
    if(p.containerMenu.stillValid(other))throw new AssertionError("Another player can operate this menu");
   });
   click(ctx,WorldsMenu.PROGRESS,ContainerInput.PICKUP,0);
   if(!ctx.computeOnClient(mc->mc.player.containerMenu.getSlot(16).getItem().is(net.minecraft.world.item.Items.NETHER_STAR)))throw new AssertionError("All-game achievement is missing");
   if(!ctx.computeOnClient(mc->mc.player.containerMenu.getSlot(4).getItem().getHoverName().getString().contains("Level 3")))throw new AssertionError("XP level did not update");
   ctx.takeScreenshot("worlds-achievements-earned");
   click(ctx,WorldsMenu.SHOP,ContainerInput.PICKUP,0);
   click(ctx,26,ContainerInput.PICKUP,0);click(ctx,WorldsMenu.SIEGE,ContainerInput.PICKUP,0);
   sp.getServer().runOnServer(s->{var p=s.getPlayerList().getPlayers().getFirst();if(ModeProfiles.mode(p)!=ModeProfiles.Mode.SIEGE||GameLobby.contains(p))throw new AssertionError("Siege menu entry failed");GameLobby.enter(p);});ctx.waitTicks(10);open(ctx);
   click(ctx,WorldsMenu.RACE,ContainerInput.PICKUP,0);ctx.waitTicks(10);if(!ctx.computeOnClient(mc->mc.gui.screen() instanceof GarageScreen))throw new AssertionError("Race menu entry did not open garage");
   sp.getServer().runOnServer(s->GameLobby.enter(s.getPlayerList().getPlayers().getFirst()));ctx.waitTicks(10);open(ctx);click(ctx,WorldsMenu.BLOOM,ContainerInput.PICKUP,0);
   sp.getServer().runOnServer(s->{var p=s.getPlayerList().getPlayers().getFirst();if(ModeProfiles.mode(p)!=ModeProfiles.Mode.NIGHTFALL||!Nightfall.contains(p)||GameLobby.contains(p))throw new AssertionError("Bloom menu entry failed");GameLobby.enter(p);});ctx.waitTicks(10);
   ctx.takeScreenshot("worlds-menu-returned-to-garden");
   Slingcraft.LOG.info("WORLDS MENU PASS: non-op command restrictions, immutable menus, locked/earned badges, durable per-player progress, purchases, mode entries");
  }
 }
}
