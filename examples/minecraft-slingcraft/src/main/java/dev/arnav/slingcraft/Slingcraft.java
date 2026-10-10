package dev.arnav.slingcraft;

import java.util.*;
import java.nio.file.*;
import java.io.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.*;
import net.minecraft.sounds.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.item.*;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.*;
import org.slf4j.*;

/** Original first-person bird siege, using Minecraft's inventory, bow and world. */
public final class Slingcraft implements ModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("Slingcraft");
    private static final Map<ServerLevel,Arena> ARENAS = new IdentityHashMap<>();
    public static final String TARGET = "slingcraft_target";
    public static int birdsUsed, destroyed, pigsLeft, stageNumber, roundScore;
    public static String roundState="READY";
    public static Vec3 lastImpact; // isolated-client test telemetry
    private static final String[] NAMES = {"Red / Ram", "Bomb / Blast", "Blue / Split"};
    public static final String[] TYPES = {"red", "bomb", "blue"};
    static final class Shot {
        final Snowball ball; final int kind; int age;
        Shot(Snowball b,int k){ball=b;kind=k;}
    }
    public static final int BIRD_LIMIT=5;
    private static final String[] FORTS={"Glass Roost","Twin Nests","Stone Crown"};
    static final class Arena {
        final BlockPos base; final float yaw; final Vec3 forward,side,center; final AABB bounds;
        final Set<UUID> targets=new HashSet<>();
        final Map<Integer,Shot> shots=new LinkedHashMap<>();
        int collapseTicks=0,used=0,moved=0,lastTargets=6,stage=0,unlocked=0,settle=0,score=0;
        int[] best=new int[3];
        boolean needsRebuild;
        int transitionTicks=-1;
        String state="READY";
        final ServerBossEvent bar=new ServerBossEvent(UUID.randomUUID(),Component.literal("Sling Siege"),BossEvent.BossBarColor.YELLOW,BossEvent.BossBarOverlay.NOTCHED_6);
        Arena(BlockPos b,float y){
            base=b;yaw=y;double r=Math.toRadians(y);
            forward=new Vec3(-Math.sin(r),0,Math.cos(r)); side=new Vec3(forward.z,0,-forward.x);
            center=Vec3.atLowerCornerOf(b).add(forward.scale(24));
            bounds=new AABB(center.x-18,b.getY(),center.z-18,center.x+19,b.getY()+24,center.z+19);
        }
        BlockPos pos(double x,double y,double z){return BlockPos.containing(center.add(side.scale(x)).add(0,y,0).add(forward.scale(z)));}
        boolean contains(BlockPos p){return bounds.contains(Vec3.atCenterOf(p));}
    }
    @Override public void onInitialize(){
        Frostline.init();Nightfall.init();GameLobby.init();SiegeBuildProtection.init();
        CommandRegistrationCallback.EVENT.register((d,c,s)->d.register(Commands.literal("sling").requires(source->source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
            .then(Commands.literal("level").executes(ctx->{buildLevel(ctx.getSource().getPlayerOrException());return 1;}))
            .then(Commands.literal("kit").executes(ctx->{giveKit(ctx.getSource().getPlayerOrException());return 1;}))
            .then(Commands.literal("reset").executes(ctx->{buildLevel(ctx.getSource().getPlayerOrException());return 1;}))
            .then(Commands.literal("next").executes(ctx->{nextLevel(ctx.getSource().getPlayerOrException());return 1;}))));
        ServerTickEvents.END_LEVEL_TICK.register(Slingcraft::tick);
        ServerLifecycleEvents.SERVER_STARTED.register(server->{
            var file=server.getWorldPath(LevelResource.ROOT).resolve("slingcraft.properties");
            if(Files.isRegularFile(file))try(var in=Files.newInputStream(file)){
                Properties v=new Properties();v.load(in);
                Arena a=new Arena(new BlockPos(Integer.parseInt(v.getProperty("x")),Integer.parseInt(v.getProperty("y")),Integer.parseInt(v.getProperty("z"))),Float.parseFloat(v.getProperty("yaw")));
                a.stage=Math.clamp(Integer.parseInt(v.getProperty("stage","0")),0,2);
                a.unlocked=Math.clamp(Integer.parseInt(v.getProperty("unlocked","0")),0,2);
                for(int i=0;i<3;i++)a.best[i]=Integer.parseInt(v.getProperty("best"+i,"0"));
                a.needsRebuild=true;ARENAS.put(server.overworld(),a);GameLobby.install(server.overworld(),a.base);
            }catch(Exception e){LOG.warn("Slingcraft arena could not be restored; use /sling level",e);}
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(s->ARENAS.clear());
    }
    public static int bowKind(ItemStack stack){
        if(!stack.is(Items.BOW))return -1;
        Identifier model=stack.get(DataComponents.ITEM_MODEL);
        if(model==null)return -1;
        for(int i=0;i<TYPES.length;i++)if(model.equals(Identifier.fromNamespaceAndPath("slingcraft","bow_"+TYPES[i])))return i;
        return -1;
    }
    public static String birdName(int kind){return NAMES[Math.max(0,kind)];}
    /** The disposable fort volume excludes its foundation, launch pad and scenery. */
    public static boolean canBuildAt(ServerPlayer p,BlockPos pos){
        Arena arena=ARENAS.get(p.level());
        return ModeProfiles.mode(p)==ModeProfiles.Mode.SIEGE&&arena!=null&&arena.contains(pos);
    }
    public static List<BlockPos> siegeExplosionBlocks(ServerLevel level,Vec3 center,List<BlockPos> blocks){
        Arena arena=ARENAS.get(level);
        if(arena==null||!arena.bounds.inflate(80).contains(center))return blocks;
        var allowed=new ArrayList<>(blocks);allowed.removeIf(pos->!arena.contains(pos));return allowed;
    }
    public static ItemStack birdItem(int kind){
        ItemStack item=new ItemStack(Items.SNOWBALL);
        item.set(DataComponents.ITEM_MODEL,Identifier.fromNamespaceAndPath("slingcraft","bird_"+TYPES[kind]));
        item.set(DataComponents.CUSTOM_NAME,Component.literal(NAMES[kind]+" bird"));
        return item;
    }
    public static boolean fire(ServerPlayer p,ItemStack stack,int chargeTicks){
        Arena a=ARENAS.get(p.level());
        if(a==null){p.sendSystemMessage(Component.literal("Choose Sling Siege from the garden compass to start."),true);return false;}
        if(p.position().distanceTo(a.center)>65){p.sendSystemMessage(Component.literal("Return to the siege range to fire."),true);return false;}
        if(a.used>=BIRD_LIMIT||a.state.equals("WON")||a.state.equals("LOST")){
            p.sendSystemMessage(Component.literal(a.state.equals("WON")?"Fort cleared! The next round starts shortly.":"No birds left. Wait for the impacts and the next round."),true);return false;
        }
        float power=BowItem.getPowerForTime(chargeTicks);if(power<0.1f)return false;
        int kind=bowKind(stack);if(kind<0)return false;
        Vec3 from=p.getEyePosition().add(p.getLookAngle().scale(.7));
        double speed=1.15+power*1.25;
        spawn(p.level(),a,p,from,p.getLookAngle().scale(speed),kind);
        a.used++;a.state="FLYING";a.settle=140;birdsUsed=a.used;
        p.level().playSound(null,p.blockPosition(),SoundEvents.CROSSBOW_SHOOT,SoundSource.PLAYERS,.8f,1.1f);
        LOG.info("BOW FIRE kind={} charge={} shots={}",TYPES[kind],chargeTicks,a.used);
        return true;
    }
    private static void spawn(ServerLevel l,Arena a,ServerPlayer owner,Vec3 from,Vec3 velocity,int kind){
        Snowball ball=new Snowball(EntityTypes.SNOWBALL,l);ball.setPos(from);ball.setItem(birdItem(kind));
        ball.setOwner(owner);ball.setDeltaMovement(velocity);ball.addTag("slingcraft_bird");l.addFreshEntity(ball);
        a.shots.put(ball.getId(),new Shot(ball,kind));
    }
    public static void onBirdHit(Snowball ball,HitResult hit){
        if(!(ball.level() instanceof ServerLevel l))return;
        Arena a=ARENAS.get(l);if(a==null)return;
        Shot shot=a.shots.remove(ball.getId());if(shot==null)return;
        impact(l,a,hit.getLocation(),shot.kind);ball.discard();
    }
    private static boolean breakable(ServerLevel l,Arena a,BlockPos p){
        if(!a.contains(p))return false;
        BlockState st=l.getBlockState(p);
        return !st.isAir()&&st.getFluidState().isEmpty()&&!st.hasBlockEntity()&&st.getDestroySpeed(l,p)>=0;
    }
    private static void displace(ServerLevel l,Arena a,BlockPos p,Vec3 impulse){
        BlockState st=l.getBlockState(p);
        if(st.is(Blocks.TNT)) {TntBlock.prime(l,p);l.setBlockAndUpdate(p,Blocks.AIR.defaultBlockState());}
        else {FallingBlockEntity f=FallingBlockEntity.fall(l,p,st);f.setDeltaMovement(impulse);f.setHurtsEntities(4,40);f.addTag("slingcraft_debris");}
        a.moved++;destroyed=a.moved;
    }
    private static void impact(ServerLevel l,Arena a,Vec3 at,int kind){
        lastImpact=at;
        WorldVfx.impact(l,at,kind==1);
        BlockPos c=BlockPos.containing(at);int radius=kind==1?4:kind==2?2:3;
        for(BlockPos mutable:BlockPos.betweenClosed(c.offset(-radius,-radius,-radius),c.offset(radius,radius,radius))){
            BlockPos p=mutable.immutable();double d=p.distSqr(c);
            if(d>radius*radius||!breakable(l,a,p))continue;
            if(l.getBlockState(p).is(Blocks.STONE_BRICKS)&&kind!=1&&d>3)continue;
            displace(l,a,p,Vec3.atCenterOf(p).subtract(at).normalize().scale(kind==1?.45:.2).add(0,.22,0));
        }
        for(Pig pig:l.getEntitiesOfClass(Pig.class,new AABB(at,at).inflate(radius+1),e->e.entityTags().contains(TARGET)))
            pig.hurtServer(l,l.damageSources().generic(),40);
        a.collapseTicks=100;
        l.sendParticles(ParticleTypes.EXPLOSION,at.x,at.y,at.z,kind==1?8:3,.5,.5,.5,0);
        l.sendParticles(ParticleTypes.POOF,at.x,at.y,at.z,24,.8,.8,.8,.06);
        l.playSound(null,at.x,at.y,at.z,SoundEvents.GENERIC_EXPLODE.value(),SoundSource.BLOCKS,kind==1?.9f:.55f,kind==1?.8f:1.5f);
        LOG.info("BIRD HIT {} at {} moved={}",TYPES[kind],c,a.moved);
    }
    /** Flood support from the foundation; disconnected components become falling blocks. */
    private static void collapse(ServerLevel l,Arena a){
        Set<BlockPos> solids=new HashSet<>(),supported=new HashSet<>();ArrayDeque<BlockPos> queue=new ArrayDeque<>();
        for(BlockPos pos:BlockPos.betweenClosed(BlockPos.containing(a.bounds.minX,a.bounds.minY,a.bounds.minZ),BlockPos.containing(a.bounds.maxX-1,a.bounds.maxY-1,a.bounds.maxZ-1))){
            BlockPos p=pos.immutable();if(!breakable(l,a,p))continue;solids.add(p);
            if(p.getY()==a.base.getY()&&!l.getBlockState(p.below()).isAir()){supported.add(p);queue.add(p);}
        }
        while(!queue.isEmpty()){BlockPos p=queue.remove();for(Direction d:Direction.values()){
            BlockPos n=p.relative(d);if(solids.contains(n)&&supported.add(n))queue.add(n);
        }}
        int room=192-l.getEntitiesOfClass(FallingBlockEntity.class,a.bounds.inflate(12),e->e.entityTags().contains("slingcraft_debris")).size();
        int budget=Math.min(48,Math.max(0,room));boolean pending=false;
        for(BlockPos p:solids)if(!supported.contains(p)){
            if(budget-->0)displace(l,a,p,new Vec3(0,-.04,0));else pending=true;
        }
        if(pending)a.collapseTicks=Math.max(a.collapseTicks,24);
    }
    private static void tick(ServerLevel l){
        Arena a=ARENAS.get(l);if(a==null)return;
        // Clean only the disposable siege volume, never the rest of the world.
        if(l.getGameTime()%10==0)for(ItemEntity drop:l.getEntitiesOfClass(ItemEntity.class,a.bounds.inflate(4)))drop.discard();
        if(a.transitionTicks>0&&l.players().stream().anyMatch(p->p.position().distanceTo(a.center)<65)){
            a.transitionTicks--;
            if(a.transitionTicks%20==0){
                for(ServerPlayer player:l.players())if(player.position().distanceTo(a.center)<65){
                    if(a.transitionTicks==0){
                        if(a.state.equals("WON"))nextLevel(player);else buildLevel(player);
                        return;
                    }
                    player.sendSystemMessage(Component.literal((a.state.equals("WON")?"Next fort":"Retry")+" in "+a.transitionTicks/20+"s"),true);
                    l.playSound(null,player.blockPosition(),SoundEvents.EXPERIENCE_ORB_PICKUP,SoundSource.PLAYERS,.35f,1.2f);
                }
            }
        }
        if(a.needsRebuild){
            for(ServerPlayer p:l.players())if(p.position().distanceTo(a.center)<65){buildLevel(p);return;}
            return;
        }
        for(ServerPlayer player:l.players()){
            double distance=player.position().distanceTo(a.center);
            if(distance>72&&ModeProfiles.mode(player)==ModeProfiles.Mode.SIEGE){
                Vec3 safe=a.center.add(player.position().subtract(a.center).normalize().scale(60));
                player.connection.teleport(safe.x,Math.max(a.base.getY()+2,player.getY()),safe.z,player.getYRot(),player.getXRot());
                player.sendSystemMessage(Component.literal("The siege boundary keeps the arena focused."),true);
            }
        }
        List<Shot> split=new ArrayList<>();
        for(Iterator<Shot> it=a.shots.values().iterator();it.hasNext();){Shot shot=it.next();shot.age++;
            if(shot.ball.isRemoved()||shot.age>160){shot.ball.discard();it.remove();continue;}
            Vec3 v=shot.ball.position();l.sendParticles(ParticleTypes.CLOUD,v.x,v.y,v.z,1,0,0,0,0);
            if(shot.kind==1&&shot.age==28){
                impact(l,a,v,shot.kind);shot.ball.discard();it.remove();continue;
            }
            if(shot.kind==2&&shot.age==5){split.add(shot);}
        }
        for(Shot s:split){Vec3 v=s.ball.getDeltaMovement();var owner=s.ball.getOwner();
            if(owner instanceof ServerPlayer p){
                spawn(l,a,p,s.ball.position(),v.yRot(.12f),0);spawn(l,a,p,s.ball.position(),v.yRot(-.12f),0);
                // Keep all three blue visually; only the central bird owns the split timer.
                for(Shot other:a.shots.values())if(other!=s&&other.age==0)other.ball.setItem(birdItem(2));
            }
        }
        for(Pig pig:l.getEntitiesOfClass(Pig.class,a.bounds.inflate(8),e->e.isAlive()&&e.entityTags().contains(TARGET)))
            if(l.getBlockState(pig.blockPosition().below()).isAir())pig.setNoAi(false);
        a.targets.removeIf(id->{Entity e=l.getEntity(id);return e!=null&&!e.isAlive();});
        if(a.settle>0)a.settle--;
        if(a.collapseTicks>0){a.collapseTicks--;if(a.collapseTicks%8==0)collapse(l,a);}
        if(l.getGameTime()%10==0){
            boolean near=l.players().stream().anyMatch(p->p.position().distanceTo(a.center)<65);
            if(!near){a.bar.removeAllPlayers();return;}
            // UUID lookup does not count a target escaping the fort as a kill.
            pigsLeft=(int)a.targets.stream().filter(id->{Entity e=l.getEntity(id);return e==null||e.isAlive();}).count();
            a.lastTargets=pigsLeft;
            a.score=(6-pigsLeft)*1000+(a.state.equals("WON")?(BIRD_LIMIT-a.used)*500:0);
            if(!a.state.equals("WON")&&!a.state.equals("LOST")){
                if(pigsLeft==0){
                    a.state="WON";a.transitionTicks=120;a.score=6000+(BIRD_LIMIT-a.used)*500;
                    a.best[a.stage]=Math.max(a.best[a.stage],a.score);a.unlocked=Math.max(a.unlocked,Math.min(2,a.stage+1));
                    saveProgress(l,a);
                    l.sendParticles(ParticleTypes.FIREWORK,a.center.x,a.base.getY()+7,a.center.z,60,3,2,3,.12);
                    for(ServerPlayer p:l.players())if(p.position().distanceTo(a.center)<65){
                        if(ModeProfiles.mode(p)==ModeProfiles.Mode.SIEGE)ModeProfiles.award(p,50,"Siege / "+FORTS[a.stage]);
                        p.sendSystemMessage(Component.literal((a.stage==2?"ALL FORTS CLEARED!":"FORT CLEARED!")+" Score "+a.score+" | Next round in 6s"),false);
                        l.playSound(null,p.blockPosition(),SoundEvents.PLAYER_LEVELUP,SoundSource.PLAYERS,.65f,1.1f);
                    }
                }else if(a.used>=BIRD_LIMIT&&a.shots.isEmpty()&&a.collapseTicks==0&&a.settle==0){
                    a.state="LOST";a.transitionTicks=160;
                    for(ServerPlayer p:l.players())if(p.position().distanceTo(a.center)<65)p.sendSystemMessage(Component.literal("OUT OF BIRDS | "+pigsLeft+" targets remain | Retry in 8s"),false);
                }else if(a.shots.isEmpty()&&a.collapseTicks==0&&a.settle==0)a.state="READY";
            }
            String label=a.state.equals("WON")?(a.stage==2?"ALL 3 CLEARED":"FORT CLEARED")+" | "+a.score+" pts | Next round soon":a.state.equals("LOST")?"OUT OF BIRDS | "+pigsLeft+" targets | Retry soon":
                (a.stage+1)+"/3 "+FORTS[a.stage]+" | "+pigsLeft+" targets | "+(BIRD_LIMIT-a.used)+" birds | "+a.score+" pts";
            a.bar.setName(Component.literal(label));a.bar.setProgress(Math.clamp((6-pigsLeft)/6f,0,1));
            a.bar.setColor(a.state.equals("WON")?BossEvent.BossBarColor.GREEN:a.state.equals("LOST")?BossEvent.BossBarColor.RED:BossEvent.BossBarColor.YELLOW);
            for(ServerPlayer viewer:new ArrayList<>(a.bar.getPlayers()))if(viewer.level()!=l||viewer.position().distanceTo(a.center)>65)a.bar.removePlayer(viewer);
            for(ServerPlayer viewer:l.players())if(viewer.position().distanceTo(a.center)<65)a.bar.addPlayer(viewer);
            birdsUsed=a.used;stageNumber=a.stage+1;roundScore=a.score;roundState=a.state;
            for(ServerPlayer p:l.players())if(bowKind(p.getMainHandItem())>=0){
                ItemStack off=p.getOffhandItem();Identifier model=off.get(DataComponents.ITEM_MODEL);
                if(off.isEmpty()||(model!=null&&model.getNamespace().equals("slingcraft")))
                    p.setItemSlot(EquipmentSlot.OFFHAND,birdItem(bowKind(p.getMainHandItem())));
            }
        }
    }
    public static void enterGame(ServerPlayer p){
        Arena a=ARENAS.get(p.level());if(a==null)return;
        GameLobby.exit(p);
        p.connection.teleport(a.base.getX()+.5,a.base.getY()+1,a.base.getZ()+.5,p.getYRot(),p.getXRot());
        giveKit(p);
    }
    public static void giveKit(ServerPlayer p){
        if(!ModeProfiles.enter(p,ModeProfiles.Mode.SIEGE))return;
        for(int i=0;i<3;i++){
            ItemStack bow=new ItemStack(Items.BOW);bow.set(DataComponents.ITEM_MODEL,Identifier.fromNamespaceAndPath("slingcraft","bow_"+TYPES[i]));
            bow.set(DataComponents.CUSTOM_NAME,Component.literal(NAMES[i]+" bird bow"));p.getInventory().setItem(i,bow);
        }
        Item[] blocks={Items.OAK_PLANKS,Items.GLASS,Items.STONE_BRICKS,Items.TNT,Items.FLINT_AND_STEEL,Items.PIG_SPAWN_EGG};
        for(int i=0;i<blocks.length;i++)p.getInventory().setItem(3+i,new ItemStack(blocks[i],i==4?1:64));
        p.getInventory().setSelectedSlot(0);p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(0));p.setItemSlot(EquipmentSlot.OFFHAND,birdItem(0));
        p.getInventory().setItem(8,ModeProfiles.compass());
        p.inventoryMenu.broadcastChanges();
    }
    public static void buildLevel(ServerPlayer p){
        ServerLevel l=p.level();Arena old=ARENAS.get(l);
        Arena a=old!=null?new Arena(old.base,old.yaw):new Arena(p.blockPosition(),Math.round(p.getYRot()/90f)*90f);ARENAS.put(l,a);
        if(old!=null){
            a.stage=old.stage;a.unlocked=old.unlocked;a.best=old.best.clone();old.bar.removeAllPlayers();
            for(Shot shot:old.shots.values())shot.ball.discard();
            for(UUID id:old.targets){Entity target=l.getEntity(id);if(target!=null)target.discard();}
        }
        for(Entity e:l.getEntitiesOfClass(Entity.class,a.bounds.inflate(8),e->e.entityTags().contains(TARGET)||e.entityTags().contains("slingcraft_debris")||e.entityTags().contains("slingcraft_bird")||e instanceof PrimedTnt))e.discard();
        for(BlockPos q:BlockPos.betweenClosed(BlockPos.containing(a.bounds.minX,a.bounds.minY,a.bounds.minZ),BlockPos.containing(a.bounds.maxX-1,a.bounds.maxY-1,a.bounds.maxZ-1)))l.setBlockAndUpdate(q,Blocks.AIR.defaultBlockState());
        for(int x=-18;x<=18;x++)for(int z=-18;z<=18;z++)l.setBlockAndUpdate(a.pos(x,-1,z),(Math.abs(x)==18||Math.abs(z)==18?Blocks.GOLD_BLOCK:Blocks.SMOOTH_STONE).defaultBlockState());
        if(a.stage==0)buildTower(l,a,0,0,3,Blocks.OAK_LOG);
        else if(a.stage==1){
            buildTower(l,a,-5,0,2,Blocks.OAK_LOG);buildTower(l,a,5,2,1,Blocks.OAK_LOG);
            for(int x=-2;x<=2;x++)l.setBlockAndUpdate(a.pos(x,4,0),Blocks.OAK_PLANKS.defaultBlockState());
        }else{
            buildTower(l,a,0,1,3,Blocks.STONE_BRICKS);
            for(int x=-6;x<=6;x++)for(int h=0;h<3;h++)if(Math.abs(x)>2)
                l.setBlockAndUpdate(a.pos(x,h,-4),Blocks.STONE_BRICKS.defaultBlockState());
            l.setBlockAndUpdate(a.pos(0,1,-1),Blocks.TNT.defaultBlockState());
            buildCitadel(l,a);
        }
        l.setBlockAndUpdate(a.pos(a.stage==1?-5:-2,5,1),Blocks.TNT.defaultBlockState());
        l.setBlockAndUpdate(a.pos(a.stage==1?5:2,a.stage==1?1:9,1),Blocks.TNT.defaultBlockState());
        giveKit(p);birdsUsed=destroyed=0;pigsLeft=6;
        l.playSound(null,p.blockPosition(),SoundEvents.PLAYER_LEVELUP,SoundSource.PLAYERS,.55f,1.35f);
        p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(8,28,12));
        p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(Component.literal("6 targets · 5 birds · build your approach")));
        p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(Component.literal(FORTS[a.stage]).withStyle(net.minecraft.ChatFormatting.GOLD)));
        a.settle=40;stageNumber=a.stage+1;roundScore=0;roundState="READY";
        p.sendSystemMessage(Component.literal("FORT "+(a.stage+1)+" / 3: "+FORTS[a.stage]+" | 5 birds, 6 targets | Best "+a.best[a.stage]),false);
        p.sendSystemMessage(Component.literal("Hold R / right-click, aim, release. Build inside the fort. Rounds restart automatically."),false);
        saveProgress(l,a);
        LOG.info("Slingcraft arena ready: {} stage={}",a.base,a.stage+1);
    }
    private static void buildTower(ServerLevel l,Arena a,int dx,int dz,int floors,Block support){
        for(int floor=0;floor<floors;floor++){
            int y=floor*4;
            for(int x=-3;x<=3;x++)for(int z=-2;z<=2;z++){
                l.setBlockAndUpdate(a.pos(x+dx,y,z+dz),(floor==0?Blocks.STONE_BRICKS:Blocks.OAK_PLANKS).defaultBlockState());
                for(int h=1;h<=3;h++)if(Math.abs(x)==3&&Math.abs(z)==2)l.setBlockAndUpdate(a.pos(x+dx,y+h,z+dz),support.defaultBlockState());
                else if((Math.abs(x)==3||Math.abs(z)==2)&&floor<floors-1)l.setBlockAndUpdate(a.pos(x+dx,y+h,z+dz),Blocks.GLASS.defaultBlockState());
            }
            for(int k=0;k<2;k++){Pig pig=EntityTypes.PIG.create(l,EntitySpawnReason.COMMAND);pig.setPos(Vec3.atBottomCenterOf(a.pos(dx+(k==0?-1:1),y+1,dz)));pig.setNoAi(true);pig.setPersistenceRequired();pig.setGlowingTag(!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("iris"));pig.addTag(TARGET);l.addFreshEntity(pig);a.targets.add(pig.getUUID());}
        }
    }
    /** Larger final-stage silhouette, with real interactive trapdoor decking. */
    private static void buildCitadel(ServerLevel l,Arena a){
        for(int cx:new int[]{-12,12})for(int cz:new int[]{-8,10}){
            int height=cz>0?15:11;
            for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++){
                for(int y=0;y<height;y++){
                    boolean edge=Math.abs(x)==2||Math.abs(z)==2;
                    if(y%5==0||edge){
                        boolean window=edge&&y%5>=2&&y%5<=3&&(x==0||z==0);
                        if(!window)l.setBlockAndUpdate(a.pos(cx+x,y,cz+z),(y%5==0?Blocks.OAK_PLANKS:Blocks.STONE_BRICKS).defaultBlockState());
                    }
                }
                if((Math.abs(x)==2||Math.abs(z)==2)&&(x+z)%2==0)l.setBlockAndUpdate(a.pos(cx+x,height,cz+z),Blocks.STONE_BRICKS.defaultBlockState());
            }
            for(int y=height;y<height+5;y++)l.setBlockAndUpdate(a.pos(cx,y,cz),Blocks.OAK_FENCE.defaultBlockState());
            for(int x=1;x<=3;x++)for(int y=height+2;y<=height+4;y++)l.setBlockAndUpdate(a.pos(cx+x,y,cz),Blocks.WOOL.red().defaultBlockState());
        }
        for(int x=-10;x<=10;x++)for(int y=0;y<=5;y++){
            if(Math.abs(x)>2||y>=4)l.setBlockAndUpdate(a.pos(x,y,-8),(y==4?Blocks.OAK_PLANKS:Blocks.STONE_BRICKS).defaultBlockState());
            if(y==5&&x%2==0)l.setBlockAndUpdate(a.pos(x,6,-8),Blocks.STONE_BRICKS.defaultBlockState());
        }
        for(int side:new int[]{-1,1})for(int z=-6;z<=8;z++){
            l.setBlockAndUpdate(a.pos(side*12,5,z),Blocks.OAK_PLANKS.defaultBlockState());
            l.setBlockAndUpdate(a.pos(side*13,6,z),Blocks.OAK_FENCE.defaultBlockState());
        }
        // A manually opened walkway: Minecraft handles its hinge, collision and clicks.
        var deck=Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HALF,net.minecraft.world.level.block.state.properties.Half.TOP);
        for(int x=-1;x<=1;x++)for(int z=-12;z<=-4;z++)l.setBlockAndUpdate(a.pos(x,3,z),deck);
        for(int z=-12;z<=-9;z++)l.setBlockAndUpdate(a.pos(0,0,z),Blocks.OAK_PLANKS.defaultBlockState());
        for(int x:new int[]{-3,3})l.setBlockAndUpdate(a.pos(x,4,-8),Blocks.GLOWSTONE.defaultBlockState());
    }
    private static void nextLevel(ServerPlayer p){
        Arena a=ARENAS.get(p.level());if(a==null){buildLevel(p);return;}
        if((a.stage<2&&a.unlocked<=a.stage)||(a.stage==2&&a.best[2]==0)){
            p.sendSystemMessage(Component.literal("Clear this fort before advancing. N retries the round."),true);return;
        }
        a.stage=(a.stage+1)%3;buildLevel(p);
    }
    private static void saveProgress(ServerLevel l,Arena a){
        if(l==l.getServer().overworld())try{
            Properties v=new Properties();v.setProperty("x",""+a.base.getX());v.setProperty("y",""+a.base.getY());v.setProperty("z",""+a.base.getZ());v.setProperty("yaw",""+a.yaw);v.setProperty("stage",""+a.stage);v.setProperty("unlocked",""+a.unlocked);
            for(int i=0;i<3;i++)v.setProperty("best"+i,""+a.best[i]);
            try(var out=Files.newOutputStream(l.getServer().getWorldPath(LevelResource.ROOT).resolve("slingcraft.properties"))){v.store(out,"Slingcraft local arena");}
        }catch(IOException e){LOG.warn("Arena metadata could not be saved",e);}
    }
}
