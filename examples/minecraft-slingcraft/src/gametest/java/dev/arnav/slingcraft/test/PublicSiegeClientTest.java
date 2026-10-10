package dev.arnav.slingcraft.test;

import dev.arnav.slingcraft.*;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.*;
import net.minecraft.world.phys.Vec3;

/** A non-operator uses the real client and normal server paths at the public build boundary. */
public final class PublicSiegeClientTest implements FabricClientGameTest {
    public void runTest(ClientGameTestContext ctx){
        try(var sp=ctx.worldBuilder().adjustSettings(s->s.setAllowCommands(false)).create()){
            ctx.waitTicks(30);
            sp.getServer().runOnServer(server->{
                var p=server.getPlayerList().getPlayers().getFirst();p.connection.teleport(.5,100,.5,0,0);Slingcraft.buildLevel(p);
                if(p.isCreative()||p.getAbilities().instabuild||p.getAbilities().mayfly)throw new AssertionError("Siege grants creative/flying access");
                if(p.createCommandSourceStack().permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))throw new AssertionError("Test player is an operator");
                for(int z=0;z<=4;z++)server.overworld().setBlockAndUpdate(new BlockPos(12,99,z),Blocks.STONE.defaultBlockState());
                p.connection.teleport(12.5,100,10.5,0,35);
            });
            ctx.waitTicks(15);ctx.getInput().pressKey(InputConstants.KEY_4);ctx.getInput().lookAt(new BlockPos(12,99,13));ctx.waitTicks(5);
            BlockPos inside=ctx.computeOnClient(mc->{var hit=(net.minecraft.world.phys.BlockHitResult)mc.hitResult;return hit.getBlockPos().relative(hit.getDirection());});
            ctx.getInput().holdKeyFor(InputConstants.KEY_R,3);ctx.waitTicks(10);
            if(ctx.computeOnClient(mc->mc.player.getInventory().getItem(3).getCount())!=64)throw new AssertionError("Client kit count was not restored");
            sp.getServer().runOnServer(server->{var p=server.getPlayerList().getPlayers().getFirst();
                if(!server.overworld().getBlockState(inside).is(Blocks.OAK_PLANKS))throw new AssertionError("Kit block did not place inside arena: "+inside);
                if(p.getInventory().getItem(3).getCount()!=64)throw new AssertionError("Unlimited block kit was consumed");
                if(!p.gameMode.destroyBlock(inside)||!server.overworld().getBlockState(inside).isAir())throw new AssertionError("Legal fort edit rejected");
                var sentinel=new BlockPos(12,100,3);server.overworld().setBlockAndUpdate(sentinel,Blocks.OAK_PLANKS.defaultBlockState());
                if(p.gameMode.destroyBlock(sentinel)||!server.overworld().getBlockState(sentinel).is(Blocks.OAK_PLANKS))throw new AssertionError("Server allowed destruction outside fort");
                server.overworld().setBlockAndUpdate(sentinel,Blocks.AIR.defaultBlockState());p.connection.teleport(12.5,100,.5,0,35);
            });
            ctx.waitTicks(12);ctx.getInput().lookAt(new BlockPos(12,99,3));ctx.waitTicks(5);ctx.getInput().holdKeyFor(InputConstants.KEY_R,3);ctx.waitTicks(10);
            sp.getServer().runOnServer(server->{
                if(!server.overworld().getBlockState(new BlockPos(12,100,3)).isAir())throw new AssertionError("Client placed outside fort");
                var l=server.overworld();var wall=new BlockPos(19,100,14);var floor=new BlockPos(18,99,14);var fragile=new BlockPos(18,100,13);
                for(var pos:new BlockPos[]{wall,floor,fragile})l.setBlockAndUpdate(pos,Blocks.GLASS.defaultBlockState());
                new ServerExplosion(l,null,null,null,new Vec3(18.5,100.5,14.5),4,false,Explosion.BlockInteraction.DESTROY).explode();
                if(!l.getBlockState(wall).is(Blocks.GLASS)||!l.getBlockState(floor).is(Blocks.GLASS))throw new AssertionError("Siege blast damaged scenery or foundation");
                if(!l.getBlockState(fragile).isAir())throw new AssertionError("Siege blast lost legal fort destruction");
                var other=server.getLevel(Level.NETHER);var normal=new BlockPos(0,100,0);other.setBlockAndUpdate(normal,Blocks.GLASS.defaultBlockState());
                new ServerExplosion(other,null,null,null,Vec3.atCenterOf(normal),4,false,Explosion.BlockInteraction.DESTROY).explode();
                if(!other.getBlockState(normal).isAir())throw new AssertionError("Siege explosion filter leaked into another dimension");
                server.getPlayerList().getPlayers().getFirst().connection.teleport(.5,100,.5,0,-5);
            });
            ctx.waitTicks(12);ctx.getInput().pressKey(InputConstants.KEY_1);ctx.waitTicks(4);
            ctx.getInput().holdMouse(InputConstants.MOUSE_BUTTON_RIGHT);ctx.waitTicks(24);ctx.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_RIGHT);ctx.waitTicks(12);
            if(Slingcraft.birdsUsed!=1)throw new AssertionError("Survival kit no longer fires the custom bird bow");
            ctx.getInput().pressKey(o->o.keyInventory);ctx.waitTicks(6);
            if(!ctx.computeOnClient(mc->mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen))throw new AssertionError("Player still sees creative catalogue");
            ctx.takeScreenshot("public-siege-normal-inventory");ctx.getInput().pressKey(InputConstants.KEY_ESCAPE);ctx.waitTicks(4);
            sp.getServer().runOnServer(server->{var p=server.getPlayerList().getPlayers().getFirst();ModeProfiles.enter(p,ModeProfiles.Mode.NIGHTFALL);
                if(p.getAbilities().instabuild||p.getAbilities().invulnerable||p.getAbilities().mayfly)throw new AssertionError("Siege powers leaked into combat mode");
            });
            Slingcraft.LOG.info("PUBLIC SIEGE PASS: non-op ordinary inventory, unlimited native placement, protected edits/blasts, dimension isolation, bow input, ability cleanup");
        }
    }
}
