package dev.arnav.slingcraft;

import java.util.*;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.CustomData;

/** Local circuit prototype: checkpoints measure actual mounted traversal. */
public final class Frostline {
    private record Race(BlockPos origin, long start, long lapStart, int gate, int lap, long best) {}
    private static final Map<UUID,Race> races=new HashMap<>();
    private static final Map<UUID,Long> garage=new HashMap<>();
    private static final Map<UUID,Integer> setups=new HashMap<>();
    private record Result(long raceTicks,long bestTicks,int place,int credits,boolean personalBest,long until,boolean completed){}
    private static final Map<UUID,Result> results=new HashMap<>();
    private static final Map<UUID,Long> previousRecords=new HashMap<>();
    private static final Set<net.minecraft.server.level.ServerLevel> loaded=new HashSet<>();
    private static final String[] SETUPS={"Grip","Balanced","Speed"};
    public static boolean preparing(ServerPlayer p){return garage.containsKey(p.getUUID());}
    public static void ready(ServerPlayer p){if(!loaded.contains(p.level())){p.sendSystemMessage(Component.literal("Preparing the circuit… your setup is saved."),true);return;}garage.remove(p.getUUID());p.getInventory().setItem(0,ItemStack.EMPTY);p.getInventory().setItem(1,ItemStack.EMPTY);Race r=races.get(p.getUUID());if(r!=null)restart(p);}
    private static void prepare(ServerPlayer p){
        if(!ModeProfiles.enter(p,ModeProfiles.Mode.RACE))return;
        results.remove(p.getUUID());
        Race r=races.get(p.getUUID());var vehicle=p.getVehicle();p.stopRiding();if(vehicle!=null)vehicle.discard();
        garage.put(p.getUUID(),p.level().getGameTime()+3600);
        p.connection.teleport(r.origin.getX(),r.origin.getY()+1,r.origin.getZ()-80,180,0);
        var tune=new ItemStack(Items.REDSTONE);tune.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,Component.literal("Tune · Right click: Grip / Balanced / Speed"));
        var menuData=new net.minecraft.nbt.CompoundTag();menuData.putLong("garageUntil",garage.get(p.getUUID()));menuData.putInt("setup",setups.getOrDefault(p.getUUID(),0));
        tune.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(menuData));
        var go=new ItemStack(Items.EMERALD);go.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,Component.literal("Ready · Start race"));
        p.getInventory().setItem(0,tune);p.getInventory().setItem(1,go);
        p.sendSystemMessage(Component.literal("GARAGE · Choose your setup, then press Ready. The race starts automatically after three minutes."),false);
    }
    private static final class Rival {
        net.minecraft.world.entity.Entity body;double angle=-Math.PI/2;int laps;long finish;
        Rival(net.minecraft.world.entity.Entity e){body=e;}
    }
    private static final Map<UUID,List<Rival>> rivals=new HashMap<>();
    private static void clearRivals(ServerPlayer p){var old=rivals.remove(p.getUUID());if(old!=null)old.forEach(r->r.body.discard());}
    private static void gridRivals(ServerPlayer p,BlockPos o){
        clearRivals(p);var list=new ArrayList<Rival>();
        for(int i=0;i<3;i++){
            var e=net.minecraft.world.entity.EntityTypes.OAK_BOAT.create(p.level(),net.minecraft.world.entity.EntitySpawnReason.COMMAND);
            if(e==null)continue;e.setCustomName(Component.literal("Frostline · "+new String[]{"Aster","Nova","Cinder"}[i]));e.setCustomNameVisible(true);e.setNoGravity(true);
            e.setPos(o.getX()-3-i*3,o.getY()+.1,o.getZ()-62+i*3);e.setYRot(-90);p.level().addFreshEntity(e);list.add(new Rival(e));
        }rivals.put(p.getUUID(),list);
    }
    private static void tickRivals(ServerPlayer p,Race race,long now){
        var list=rivals.get(p.getUUID());if(list==null)return;
        for(int i=0;i<list.size();i++){
            Rival r=list.get(i);if(r.finish>0)continue;
            double radius=1+(i-1)*.055;
            double step=(.57+i*.075)/Math.sqrt(Math.pow(100*radius*Math.sin(r.angle),2)+Math.pow(65*radius*Math.cos(r.angle),2));
            r.angle+=step;
            if(r.angle>=Math.PI*1.5){r.angle-=Math.PI*2;r.laps++;if(r.laps==3){r.finish=now;continue;}}
            r.body.setPos(race.origin.getX()+100*radius*Math.cos(r.angle),race.origin.getY()+.1,race.origin.getZ()+65*radius*Math.sin(r.angle));
            r.body.setYRot((float)Math.toDegrees(Math.atan2(100*Math.sin(r.angle),65*Math.cos(r.angle))));
            r.body.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        }
    }
    private static final Map<UUID,Long> impacts=new HashMap<>();
    private static final Map<UUID,Double> speeds=new HashMap<>();
    private static final Map<net.minecraft.server.level.ServerLevel,BlockPos> circuits=new HashMap<>();
    private static final Map<UUID,net.minecraft.server.level.ServerLevel> worlds=new HashMap<>();
    public static void leave(ServerPlayer p){
        clearRivals(p);
        var boat=p.getVehicle();p.stopRiding();if(boat!=null&&boat.entityTags().contains("frostline_boat"))boat.discard();
        garage.remove(p.getUUID());races.remove(p.getUUID());worlds.remove(p.getUUID());impacts.remove(p.getUUID());speeds.remove(p.getUUID());results.remove(p.getUUID());previousRecords.remove(p.getUUID());
    }
    public static void enterGame(ServerPlayer p){GameLobby.exit(p);build(p);prepare(p);}
    private static void cue(ServerPlayer p,SoundEvent sound,SoundSource source,float volume,float pitch){
        p.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(net.minecraft.core.Holder.direct(sound),source,p.getX(),p.getY(),p.getZ(),volume,pitch,0L));
    }
    private static boolean inBoat(ServerPlayer p){
        if(p.getVehicle()==null)return false;
        String id=net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(p.getVehicle().getType()).getPath();
        return id.endsWith("_boat")||id.endsWith("_raft");
    }
    private static void seat(ServerPlayer p, BlockPos o){
        var old=p.getVehicle();p.stopRiding();
        if(old!=null&&old.entityTags().contains("frostline_boat"))old.discard();
        var boat=net.minecraft.world.entity.EntityTypes.OAK_BOAT.create(p.level(),net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        if(boat==null)return;
        boat.setPos(o.getX()+.5,o.getY()+.1,o.getZ()-65+.5);boat.setYRot(-90);boat.addTag("frostline_boat");boat.setCustomName(Component.literal("Frostline Grid"));
        p.level().addFreshEntity(boat);p.connection.teleport(boat.getX(),boat.getY()+1,boat.getZ(),p.getYRot(),p.getXRot());p.startRiding(boat,true,true);gridRivals(p,o);
    }
    private static void recover(ServerPlayer p){
        Race r=races.get(p.getUUID());if(r==null||worlds.get(p.getUUID())!=p.level())return;
        restart(p);
        p.sendSystemMessage(Component.literal("Back at the grid · Next race starts shortly"),false);
    }
    private static void restart(ServerPlayer p){
        Race r=races.get(p.getUUID());if(r==null)return;
        results.remove(p.getUUID());previousRecords.put(p.getUUID(),ModeProfiles.bestLapTicks(p));
        Race fresh=new Race(r.origin,-1,-1,0,1,Long.MAX_VALUE);races.put(p.getUUID(),fresh);
        seat(p,r.origin);sync(p,fresh,p.level().getGameTime());
    }
    private static double progress(Race r,double x,double z){
        double angle=Math.atan2(z/65,x/100)+Math.PI/2;
        if(angle<0)angle+=Math.PI*2;
        double lap=angle/(Math.PI*2);
        if(r.gate==0&&lap>.75)lap=0;
        return r.lap-1+Math.clamp(lap,r.gate*.25,(r.gate+1)*.25);
    }
    private static int place(ServerPlayer p,Race r){
        double progress=progress(r,p.getX()-r.origin.getX(),p.getZ()-r.origin.getZ());
        return 1+(int)rivals.getOrDefault(p.getUUID(),List.of()).stream().filter(bot->bot.finish>0||bot.laps+(bot.angle+Math.PI/2)/(Math.PI*2)>progress).count();
    }
    private static void sync(ServerPlayer p,Race r,long now){
        ItemStack compass=p.getInventory().getItem(8);if(!ModeProfiles.isCompass(compass))return;
        var old=compass.get(DataComponents.CUSTOM_DATA);CompoundTag tag=old==null?new CompoundTag():old.copyTag();
        Result result=results.get(p.getUUID());
        tag.putString("racePhase",result!=null?"results":r.start<0||now<r.start?"grid":"race");
        tag.putInt("raceLap",r.lap);tag.putInt("raceGate",r.gate+1);tag.putLong("raceStart",r.start);
        tag.putInt("racePlace",result==null?place(p,r):result.place);
        if(result!=null){tag.putLong("raceResultTime",result.raceTicks);tag.putLong("raceResultBest",result.bestTicks);tag.putLong("raceResultUntil",result.until);tag.putInt("raceCredits",result.credits);tag.putBoolean("raceNewBest",result.personalBest);tag.putBoolean("raceCompleted",result.completed);}
        compass.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));p.inventoryMenu.broadcastChanges();
    }
    private static void finish(ServerPlayer p,Race r,long now,long best,boolean completed){
        int place=completed?1+(int)rivals.getOrDefault(p.getUUID(),List.of()).stream().filter(bot->bot.finish>0).count():4;
        if(best==Long.MAX_VALUE)best=0;
        long previous=previousRecords.getOrDefault(p.getUUID(),0L);
        // Latch the completed run before awarding. Later ticks only process its result countdown.
        results.put(p.getUUID(),new Result(now-r.start,best,place,0,completed&&(previous==0||best<previous),now+160,completed));
        boolean saved=completed&&ModeProfiles.award(p,100,"Frostline finisher");
        results.put(p.getUUID(),new Result(now-r.start,best,place,saved?100:0,completed&&(previous==0||best<previous),now+160,completed));
        if(inBoat(p)){p.getVehicle().setCustomName(Component.literal("Frostline Grid"));p.getVehicle().setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);}
        cue(p,completed?SoundEvents.PLAYER_LEVELUP:SoundEvents.NOTE_BLOCK_BASS.value(),SoundSource.PLAYERS,.35f,1.1f);sync(p,r,now);
    }
    private static final int[][] GATES={{100,0},{0,65},{-100,0},{0,-65}};
    public static boolean contains(ServerPlayer p){return races.containsKey(p.getUUID());}
    public static void init(){
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->leave(handler.player));
        CommandRegistrationCallback.EVENT.register((d,c,e)->d.register(Commands.literal("frostline-result")
            .requires(source->source.getEntity() instanceof ServerPlayer p&&results.containsKey(p.getUUID()))
            .then(Commands.literal("replay").executes(ctx->{restart(ctx.getSource().getPlayerOrException());return 1;}))
            .then(Commands.literal("setup").executes(ctx->{prepare(ctx.getSource().getPlayerOrException());return 1;}))
            .then(Commands.literal("leave").executes(ctx->{GameLobby.enter(ctx.getSource().getPlayerOrException());return 1;}))));
        CommandRegistrationCallback.EVENT.register((d,c,e)->d.register(Commands.literal("frostline-menu")
          .requires(source->source.getEntity() instanceof ServerPlayer p&&preparing(p))
          .then(Commands.literal("ready").executes(ctx->{ready(ctx.getSource().getPlayerOrException());return 1;}))
          .then(Commands.literal("leave").executes(ctx->{GameLobby.enter(ctx.getSource().getPlayerOrException());return 1;}))
          .then(Commands.literal("setup").then(Commands.argument("choice",com.mojang.brigadier.arguments.IntegerArgumentType.integer(0,2)).executes(ctx->{setups.put(ctx.getSource().getPlayerOrException().getUUID(),com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx,"choice"));return 1;})))));

        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((player,world,hand)->{
            if(player instanceof ServerPlayer p&&preparing(p)){
                if(p.getItemInHand(hand).is(Items.REDSTONE)){setups.put(p.getUUID(),(setups.getOrDefault(p.getUUID(),0)+1)%3);p.sendSystemMessage(Component.literal("SETUP · "+SETUPS[setups.get(p.getUUID())]+" · Grip turns tighter; Speed has a higher top speed"),true);return net.minecraft.world.InteractionResult.SUCCESS;}
                if(p.getItemInHand(hand).is(Items.EMERALD)){ready(p);return net.minecraft.world.InteractionResult.SUCCESS;}
            }return net.minecraft.world.InteractionResult.PASS;
        });
        CommandRegistrationCallback.EVENT.register((d,c,s)->d.register(Commands.literal("frostline")
            .requires(source->source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
            .then(Commands.literal("build").executes(ctx->{build(ctx.getSource().getPlayerOrException());return 1;}))
            .then(Commands.literal("recover").executes(ctx->{recover(ctx.getSource().getPlayerOrException());return 1;}))
            .then(Commands.literal("start").executes(ctx->{ServerPlayer p=ctx.getSource().getPlayerOrException();Race r=races.get(p.getUUID());if(r!=null&&worlds.get(p.getUUID())==p.level()&&inBoat(p)&&p.position().distanceTo(new net.minecraft.world.phys.Vec3(r.origin.getX(),r.origin.getY(),r.origin.getZ()-65))<8){long t=p.level().getGameTime();races.put(p.getUUID(),new Race(r.origin,t+100,t+100,0,1,Long.MAX_VALUE));}return 1;}))));
        ServerLifecycleEvents.SERVER_STOPPING.register(s->{for(var p:new ArrayList<>(s.getPlayerList().getPlayers()))leave(p);});
        ServerLifecycleEvents.SERVER_STOPPED.register(s->{rivals.clear();loaded.clear();races.clear();garage.clear();setups.clear();worlds.clear();circuits.clear();impacts.clear();speeds.clear();results.clear();previousRecords.clear();});
        ServerTickEvents.END_SERVER_TICK.register(server->{for(ServerPlayer p:server.getPlayerList().getPlayers())tick(p);});
    }
    private static void build(ServerPlayer p){
        var l=p.level();BlockPos existing=circuits.get(l);
        if(existing!=null){seat(p,existing);worlds.put(p.getUUID(),l);races.put(p.getUUID(),new Race(existing,-1,-1,0,1,Long.MAX_VALUE));return;}
        BlockPos o=p.blockPosition().offset(0,0,1024);
        // Build above the terrain; this avoids carving through an existing world.
        o=new BlockPos(o.getX(),Math.min(l.getMaxY()-12,Math.max(o.getY()+32,180)),o.getZ());
        SceneBuilder builder=new SceneBuilder(l,o);
        // This small pad is immediately safe while scenery is built in a tick budget.
        for(int x=-10;x<=10;x++)for(int z=-86;z<=-73;z++)l.setBlockAndUpdate(o.offset(x,-1,z),Blocks.SMOOTH_QUARTZ.defaultBlockState());
        for(int x=-120;x<=120;x++)for(int z=-79;z<=79;z++){
            double q=Math.sqrt(x*x/(100.0*100)+z*z/(65.0*65));
            if(q<.80||q>1.20)continue;
            boolean edge=q<.84||q>1.16;
            builder.at(o.offset(x,-1,z),(edge?((x+z)%2==0?net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft","red_concrete")):net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft","white_concrete"))):Blocks.BLUE_ICE).defaultBlockState());
            if(edge)builder.at(o.offset(x,0,z),net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft","white_stained_glass")).defaultBlockState());
        }
        // A bounded alpine island: lake, terraced banks and spruce silhouettes.
        for(int x=-136;x<=136;x++)for(int z=-89;z<=89;z++){
            double q=Math.sqrt(x*x/(100.0*100)+z*z/(65.0*65));
            if(q>=.80&&q<=1.20)continue;
            if(q<.75){
                builder.at(o.offset(x,-4,z),Blocks.STONE.defaultBlockState());
                for(int y=-3;y<=-2;y++)builder.at(o.offset(x,y,z),Blocks.WATER.defaultBlockState());
            }else{
                int h=q>1.25?(int)(2+3*Math.sin(x*.19)*Math.cos(z*.23)): -2;
                for(int y=-5;y<=h;y++)builder.at(o.offset(x,y,z),(y==h?Blocks.SNOW_BLOCK:Blocks.STONE).defaultBlockState());
                if(q>1.32&&Math.floorMod(x,11)==0&&Math.floorMod(z,13)==0){
                    for(int y=1;y<=6;y++)builder.at(o.offset(x,h+y,z),Blocks.SPRUCE_LOG.defaultBlockState());
                    for(int y=3;y<=7;y++)for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)
                        if(Math.abs(dx)+Math.abs(dz)<=Math.max(0,7-y))builder.at(o.offset(x+dx,h+y,z+dz),Blocks.SPRUCE_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT,true));
                }
            }
        }
        // Sheltered tuning pad beside the starting straight.
        for(int x=-10;x<=10;x++)for(int z=-86;z<=-73;z++){
            builder.at(o.offset(x,-1,z),Blocks.SMOOTH_QUARTZ.defaultBlockState());
            for(int y=0;y<5;y++)builder.at(o.offset(x,y,z),Blocks.AIR.defaultBlockState());
            builder.at(o.offset(x,5,z),Blocks.SEA_LANTERN.defaultBlockState());
        }
        for(int[] g:GATES){
            boolean side=g[0]!=0;
            for(int n=-7;n<=7;n++){
                int x=g[0]+(side?n:0),z=g[1]+(side?0:n);
                builder.at(o.offset(x,7,z),Blocks.SEA_LANTERN.defaultBlockState());
                if(Math.abs(n)==7)for(int y=0;y<7;y++)builder.at(o.offset(x,y,z),net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft","cyan_concrete")).defaultBlockState());
            }
        }
        RaceScenery.add(builder);builder.submit(()->loaded.add(l));
        p.connection.teleport(o.getX()+.5,o.getY()+1,o.getZ()-65+.5,p.getYRot(),p.getXRot());
        seat(p,o);
        circuits.put(l,o);worlds.put(p.getUUID(),l);
        races.put(p.getUUID(),new Race(o,-1,-1,0,1,Long.MAX_VALUE));
        p.sendSystemMessage(Component.literal("FROSTLINE GP · Three laps · Steer with A / D, accelerate with W · Follow the four lit gates"),false);
    }
    private static void tick(ServerPlayer p){
        Race r=races.get(p.getUUID());if(r==null)return;
        Result result=results.get(p.getUUID());
        if(result!=null){
            if(p.level().getGameTime()>=result.until)restart(p);
            else if(inBoat(p))p.getVehicle().setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            return;
        }
        if(garage.containsKey(p.getUUID())){
            long remaining=garage.get(p.getUUID())-p.level().getGameTime();
            if(remaining<=0)ready(p);
            else if(remaining%20==0)p.sendSystemMessage(Component.literal("GARAGE · "+remaining/20+"s · "+SETUPS[setups.getOrDefault(p.getUUID(),0)]+" · Choose your setup and press Ready"),true);
            return;
        }
        if(worlds.get(p.getUUID())!=p.level()){garage.remove(p.getUUID());races.remove(p.getUUID());worlds.remove(p.getUUID());impacts.remove(p.getUUID());speeds.remove(p.getUUID());return;}
        if(p.getY()<r.origin.getY()-8){recover(p);return;}
        if(r.start<0){
            long t=p.level().getGameTime();
            r=new Race(r.origin,t+100,t+100,0,1,r.best);races.put(p.getUUID(),r);sync(p,r,t);return;
        }
        long now=p.level().getGameTime();
        if(now<r.start){if(inBoat(p)){p.getVehicle().setPos(r.origin.getX()+.5,r.origin.getY()+.1,r.origin.getZ()-65+.5);p.getVehicle().setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);p.getVehicle().setYRot(-90);}if((r.start-now)%20==0){p.sendSystemMessage(Component.literal("START IN "+(r.start-now)/20),true);cue(p,SoundEvents.EXPERIENCE_ORB_PICKUP,SoundSource.PLAYERS,.18f,1f);}return;}
        if(now==r.start){if(inBoat(p))p.getVehicle().setCustomName(Component.literal("Frostline "+SETUPS[setups.getOrDefault(p.getUUID(),0)]));cue(p,SoundEvents.PLAYER_LEVELUP,SoundSource.PLAYERS,.25f,1.4f);}
        if(now-r.start>=4800){finish(p,r,now,r.best,false);return;}
        if(!inBoat(p)){recover(p);return;}
        tickRivals(p,r,now);
        var boat=p.getVehicle();
        double speed=boat.getDeltaMovement().horizontalDistance();
        double previous=speeds.getOrDefault(p.getUUID(),0.0);speeds.put(p.getUUID(),speed);
        if(boat.horizontalCollision&&Math.max(speed,previous)>.12&&now-impacts.getOrDefault(p.getUUID(),-100L)>12){
            impacts.put(p.getUUID(),now);
            WorldVfx.impact(p.level(),boat.position(),false);
            cue(p,SoundEvents.ANVIL_LAND,SoundSource.PLAYERS,.18f,1.7f);
        }
        if(speed>.4&&now%4==0)p.level().sendParticles(net.minecraft.core.particles.ParticleTypes.SNOWFLAKE,boat.getX(),boat.getY()+.15,boat.getZ(),3,.4,.05,.4,.02);
        double x=p.getX()-r.origin.getX(),z=p.getZ()-r.origin.getZ();int[] g=GATES[r.gate];
        if(Math.abs(x-g[0])<7&&Math.abs(z-g[1])<7&&Math.abs(p.getY()-r.origin.getY())<4){
            int gate=(r.gate+1)%4,lap=r.lap;long best=r.best,lapStart=r.lapStart;
            if(gate==0){best=Math.min(best,now-lapStart);ModeProfiles.bestLap(p,now-lapStart);lapStart=now;lap++;}
            cue(p,SoundEvents.EXPERIENCE_ORB_PICKUP,SoundSource.PLAYERS,.15f,1.5f);
            if(lap>3){finish(p,r,now,best,true);return;}
            r=new Race(r.origin,r.start,lapStart,gate,lap,best);races.put(p.getUUID(),r);
        }
        if(now%10==0)sync(p,r,now);
    }
}
