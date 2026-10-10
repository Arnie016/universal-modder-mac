package dev.arnav.slingcraft.test;
import dev.arnav.slingcraft.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.entity.monster.zombie.Zombie;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.concurrent.atomic.AtomicReference;

public final class NightfallClientTest implements FabricClientGameTest {
 public void runTest(ClientGameTestContext ctx){
  try(var sp=ctx.worldBuilder().adjustSettings(s->s.setAllowCommands(true)).create()){
   ctx.waitTicks(30);
   sp.getServer().runOnServer(s->{var p=s.getPlayerList().getPlayers().getFirst();GameLobby.install(s.overworld(),new BlockPos(0,100,0));GameLobby.enter(p);});
   for(int i=0;i<1200&&!GameLobby.ready();i+=20)ctx.waitTicks(20);
   sp.getServer().runOnServer(s->{var p=s.getPlayerList().getPlayers().getFirst();var at=GameLobby.portal(2);p.connection.teleport(at.getX()+.5,at.getY()+1,at.getZ()+.5,180,0);});
   ctx.waitTicks(80);
   AtomicReference<Nightfall.Status> status=new AtomicReference<>();
   for(int i=0;i<800;i+=20){sp.getServer().runOnServer(s->status.set(Nightfall.status(s.getPlayerList().getPlayers().getFirst())));if(status.get()!=null&&status.get().enemies()>0)break;ctx.waitTicks(20);}
   if(status.get()==null||status.get().enemies()==0)throw new AssertionError("Violet portal did not start a wave");
   if(!ctx.computeOnClient(mc->Nightfall.isRifle(mc.player.getMainHandItem())))throw new AssertionError("Rifle missing");
   ctx.waitTicks(70);ctx.takeScreenshot("nightfall-courtyard");
   // Put a real wave enemy in a known firing lane, then use actual mouse input.
   AtomicReference<Zombie> target=new AtomicReference<>();
   sp.getServer().runOnServer(s->{var p=s.getPlayerList().getPlayers().getFirst();var r=Nightfall.status(p);var z=p.level().getEntitiesOfClass(Zombie.class,new AABB(r.origin()).inflate(40)).getFirst();z.setPos(p.getX(),p.getY(),p.getZ()-7);z.setNoAi(true);z.setHealth(20);target.set(z);});
   ctx.getInput().lookAt(BlockPos.containing(target.get().position().add(0,1,0)));ctx.waitTicks(3);
   ctx.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);ctx.waitTicks(8);ctx.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);ctx.waitTicks(3);
   sp.getServer().runOnServer(s->{var p=s.getPlayerList().getPlayers().getFirst();if(Nightfall.status(p).ammo()>=12)throw new AssertionError("Mouse did not shoot");if(target.get().getHealth()>=20)throw new AssertionError("Rifle did not hit target");});
   ctx.takeScreenshot("nightfall-shot");
   ctx.getInput().pressKey(InputConstants.KEY_R);ctx.waitTicks(40);
   sp.getServer().runOnServer(s->{if(Nightfall.status(s.getPlayerList().getPlayers().getFirst()).ammo()!=12)throw new AssertionError("R reload did not refill");});
   // Controlled enemies isolate the actual root-damage/extraction flow from combat input above.
   sp.getServer().runOnServer(s->{var p=s.getPlayerList().getPlayers().getFirst();var r=Nightfall.status(p);for(var z:p.level().getEntitiesOfClass(Zombie.class,new AABB(r.origin()).inflate(40)))z.discard();});ctx.waitTicks(3);
   int[][] roots={{-10,-4},{11,-10},{0,-21}};
   for(int n=0;n<3;n++){
    final int index=n;AtomicReference<BlockPos> aim=new AtomicReference<>();
    sp.getServer().runOnServer(s->{var p=s.getPlayerList().getPlayers().getFirst();var r=Nightfall.status(p);var root=r.origin().offset(roots[index][0],2,roots[index][1]);aim.set(root);p.connection.teleport(root.getX()+.5,r.origin().getY()+.2,root.getZ()+8.5,180,0);});ctx.waitTicks(5);
    ctx.getInput().pressKey(InputConstants.KEY_R);ctx.waitTicks(40);ctx.getInput().lookAt(aim.get());ctx.waitTicks(3);
    ctx.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);ctx.waitTicks(37);ctx.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);ctx.waitTicks(3);
    sp.getServer().runOnServer(s->{var r=Nightfall.status(s.getPlayerList().getPlayers().getFirst());if(r.burned()!=index+1)throw new AssertionError("Root did not burn: "+index+" => "+r.burned());});
   }
   ctx.takeScreenshot("bloom-roots-burned");
   sp.getServer().runOnServer(s->{var p=s.getPlayerList().getPlayers().getFirst();var o=Nightfall.status(p).origin();p.connection.teleport(o.getX()+.5,o.getY()+1,o.getZ()-24,180,0);});ctx.waitTicks(4);ctx.getInput().pressKey(InputConstants.KEY_G);ctx.waitTicks(5);
   sp.getServer().runOnServer(s->{var p=s.getPlayerList().getPlayers().getFirst();var r=Nightfall.status(p);if(r.stage()!=2)throw new AssertionError("G did not collect specimen");p.connection.teleport(r.origin().getX()+.5,r.origin().getY()+1,r.origin().getZ()+21,180,0);});ctx.waitTicks(4);ctx.getInput().pressKey(InputConstants.KEY_G);ctx.waitTicks(5);
   sp.getServer().runOnServer(s->status.set(Nightfall.status(s.getPlayerList().getPlayers().getFirst())));
   if(status.get()==null||!status.get().won())throw new AssertionError("Nightfall win missing");
   ctx.takeScreenshot("nightfall-complete");ctx.waitTicks(110);
   sp.getServer().runOnServer(s->{var p=s.getPlayerList().getPlayers().getFirst();if(!GameLobby.contains(p)||Nightfall.contains(p))throw new AssertionError("Win did not return to garden");if(ModeProfiles.xp(p)<150)throw new AssertionError("XP was not persisted");
    if(!ModeProfiles.buy(p,"incendiary")||ModeProfiles.credits(p)!=30||!ModeProfiles.owns(p,"incendiary"))throw new AssertionError("Purchase was not persisted");
    if(ModeProfiles.buy(p,"incendiary")||ModeProfiles.buy(p,"fieldkit")||ModeProfiles.credits(p)!=30)throw new AssertionError("Duplicate or unaffordable purchase charged credits");
   });
   ctx.computeOnClient(mc->{mc.gui.setScreen(new dev.arnav.slingcraft.WorldsShopScreen());return true;});ctx.waitTicks(5);ctx.takeScreenshot("worlds-upgrade-shop");ctx.computeOnClient(mc->{mc.gui.setScreen(null);return true;});
   sp.getServer().runOnServer(s->Nightfall.enter(s.getPlayerList().getPlayers().getFirst()));ctx.waitTicks(50);
   sp.getServer().runOnServer(s->{var p=s.getPlayerList().getPlayers().getFirst();p.hurtServer(p.level(),p.level().damageSources().magic(),1000);if(p.isDeadOrDying())throw new AssertionError("Loss killed player rather than ending round");});
   ctx.waitTicks(110);
   sp.getServer().runOnServer(s->{var p=s.getPlayerList().getPlayers().getFirst();if(!GameLobby.contains(p)||!p.getInventory().getItem(0).isEmpty())throw new AssertionError("Loss cleanup failed");});
  }
 }
}
