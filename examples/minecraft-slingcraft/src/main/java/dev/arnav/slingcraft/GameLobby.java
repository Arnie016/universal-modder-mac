package dev.arnav.slingcraft;

import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.InteractionResult;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import java.nio.file.*;

public final class GameLobby {
    private static BlockPos center;private static ServerLevel level;private static volatile boolean ready;
    private static final Set<UUID> visitors=new HashSet<>();
    private record Dwell(int gate,int ticks){}
    private static final Map<UUID,Dwell> portalTicks=new HashMap<>();
    public static boolean contains(ServerPlayer p){return visitors.contains(p.getUUID());}
    public static boolean ready(){return ready;}
    public static BlockPos origin(){return center;}
    public static BlockPos portal(int index){return LobbyGate.ALL[index].at(center,0,0,0);}
    public static boolean isGardenPortal(ServerLevel world,BlockPos pos){
        if(center==null||world!=level)return false;
        for(var gate:LobbyGate.ALL)if(gate.contains(center,Vec3.atBottomCenterOf(pos),.6))return true;
        return false;
    }
    public static void install(ServerLevel world,BlockPos arena){
        level=world;center=arena.offset(-2048,0,0);ready=false;
        var marker=world.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("slingcraft-garden-v4.txt");
        try {if(Files.exists(marker)&&Files.readString(marker).equals(center.toShortString())&&world.getBlockState(center.offset(0,24,-20)).is(Blocks.SEA_LANTERN)&&world.getBlockState(portal(0).above(2)).is(Blocks.NETHER_PORTAL)&&world.getBlockState(portal(1).above(2)).is(Blocks.NETHER_PORTAL)&&world.getBlockState(portal(2).above(2)).is(Blocks.NETHER_PORTAL)){ready=true;LobbyLabels.ensure(world,center);return;}}
        catch(Exception e){Slingcraft.LOG.warn("Garden marker could not be read",e);}
        for(int x=-4;x<=4;x++)for(int z=24;z<=32;z++)world.setBlockAndUpdate(center.offset(x,-1,z),Blocks.SMOOTH_QUARTZ.defaultBlockState());
        LobbyScene.build(world,center,()->{LobbyLabels.ensure(world,center);ready=true;try{Files.writeString(marker,center.toShortString());}catch(Exception e){Slingcraft.LOG.warn("Garden marker could not be saved",e);}});
    }
    public static void enter(ServerPlayer p){
        if(center==null||p.level()!=level)return;
        if(!ModeProfiles.enter(p,ModeProfiles.Mode.HUB))return;
        Frostline.leave(p);Nightfall.leave(p);visitors.add(p.getUUID());portalTicks.remove(p.getUUID());p.stopRiding();
        p.resetFallDistance();p.clearFire();p.setHealth(p.getMaxHealth());p.getFoodData().setFoodLevel(20);p.getAbilities().invulnerable=true;p.onUpdateAbilities();
        p.connection.teleport(center.getX()+.5,center.getY()+1,center.getZ()+28.5,180,0);
        WorldVfx.arrive(level,p.position());
        p.sendSystemMessage(Component.literal("THE WORLDS GARDEN · 9 Compass / choose a game · 8 Guide book"),true);
    }
    public static void exit(ServerPlayer p){visitors.remove(p.getUUID());portalTicks.remove(p.getUUID());}
    public static void choose(ServerPlayer p,int mode){if(!ready||!contains(p))return;if(mode==0)Slingcraft.enterGame(p);else if(mode==1)Frostline.enterGame(p);else if(mode==2)Nightfall.enter(p);}
    public static void init(){
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((d,c,e)->d.register(net.minecraft.commands.Commands.literal("worlds-shop").requires(s->s.getEntity() instanceof ServerPlayer p&&contains(p))
            .then(net.minecraft.commands.Commands.argument("upgrade",com.mojang.brigadier.arguments.StringArgumentType.word()).executes(ctx->ModeProfiles.buy(ctx.getSource().getPlayerOrException(),com.mojang.brigadier.arguments.StringArgumentType.getString(ctx,"upgrade"))?1:0))));
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((d,c,e)->d.register(net.minecraft.commands.Commands.literal("worlds-menu")
            .requires(source->source.getEntity() instanceof ServerPlayer p&&contains(p)&&ready)
            .executes(ctx->{WorldsMenu.open(ctx.getSource().getPlayerOrException());return 1;})
            .then(net.minecraft.commands.Commands.argument("world",com.mojang.brigadier.arguments.IntegerArgumentType.integer(0,2)).executes(ctx->{choose(ctx.getSource().getPlayerOrException(),com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx,"world"));return 1;}))));
        ServerPlayConnectionEvents.JOIN.register((handler,sender,server)->enter(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->{exit(handler.player);ModeProfiles.forget(handler.player);});
        UseItemCallback.EVENT.register((p,l,hand)->{
            if(p instanceof ServerPlayer sp&&ModeProfiles.isCompass(p.getItemInHand(hand))&&center!=null&&p.level()==level){if(!contains(sp))enter(sp);else WorldsMenu.open(sp);return InteractionResult.SUCCESS;}return InteractionResult.PASS;
        });
        AttackBlockCallback.EVENT.register((p,l,h,pos,face)->{
            if(p instanceof ServerPlayer sp&&ModeProfiles.mode(sp)!=null&&ModeProfiles.mode(sp)!=ModeProfiles.Mode.SIEGE)return InteractionResult.FAIL;
            return InteractionResult.PASS;
        });
        ServerTickEvents.END_SERVER_TICK.register(server->{
            SceneBuilder.tick();WorldVfx.tick();if(center==null)return;
            if(ready&&level.getGameTime()%6==0&&level.players().stream().anyMatch(GameLobby::contains))for(var gate:LobbyGate.ALL)WorldVfx.gardenPortal(level,center,gate);
            for(ServerPlayer p:server.getPlayerList().getPlayers())if(contains(p)&&p.level()==level){
                double x=p.getX()-center.getX(),z=p.getZ()-center.getZ();
                if(p.getY()<center.getY()-3||Math.hypot(x,z)>84){enter(p);continue;}
                if(!ready){if(level.getGameTime()%40==0)p.sendSystemMessage(Component.literal("Preparing the garden…"),true);continue;}
                int gate=-1;
                for(int i=0;i<3;i++)if(LobbyGate.ALL[i].contains(center,p.position(),1.8)){gate=i;break;}
                if(gate>=0){
                    Dwell old=portalTicks.get(p.getUUID());int ticks=old!=null&&old.gate==gate?old.ticks+1:1;
                    portalTicks.put(p.getUUID(),new Dwell(gate,ticks));
                    p.sendSystemMessage(Component.literal("Entering "+new String[]{"Sling Siege","Frostline garage","Bloom"}[gate]+" · "+Math.min(100,ticks*100/12)+"%"),true);
                    if(ticks>=12){
                        exit(p);WorldVfx.arrive(level,p.position());
                        p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(5,25,10));
                        p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(Component.literal(new String[]{"SLING SIEGE","FROSTLINE GARAGE","BLOOM"}[gate])));
                        if(gate==0)Slingcraft.enterGame(p);else if(gate==1)Frostline.enterGame(p);else Nightfall.enter(p);
                    }
                }else {portalTicks.remove(p.getUUID());if(level.getGameTime()%80==0)p.sendSystemMessage(Component.literal("Gold / Siege    Cyan / Race    Violet / Bloom    · "+ModeProfiles.xp(p)+" Worlds XP"),true);}
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(s->{center=null;level=null;ready=false;visitors.clear();portalTicks.clear();ModeProfiles.clear();WorldVfx.clear();SceneBuilder.clear();});
    }
}
