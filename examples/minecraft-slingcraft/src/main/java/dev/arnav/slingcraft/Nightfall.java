package dev.arnav.slingcraft;

import java.util.*;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
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
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import org.joml.Vector3f;
import org.joml.Quaternionf;

/** BLOOM / fungal containment and extraction, separated from the hub and racing worlds. */
public final class Nightfall {
 private static final Identifier RIFLE=id("ward_rifle"),FUNGUS=id("bloom_fungus");
 private static final Map<UUID,Round> rounds=new HashMap<>();
 private static final Map<UUID,Integer> slots=new HashMap<>();private static final Set<UUID> built=new HashSet<>();
 private static final int[][] ROOTS={{-10,-4},{11,-10},{0,-21}};
 private static Identifier id(String name){return Identifier.fromNamespaceAndPath("slingcraft",name);}
 private static final class Round {
  final ServerLevel level;final BlockPos origin;final Set<UUID> enemies=new HashSet<>(),decor=new HashSet<>();
  final UUID[] roots=new UUID[3];final int[] life={60,60,60};
  final ServerBossEvent bar=new ServerBossEvent(UUID.randomUUID(),Component.literal("BLOOM"),BossEvent.BossBarColor.PURPLE,BossEvent.BossBarOverlay.PROGRESS);
  int ammo=12,reserve=96,burned=0,stage=0,rootDamage=10;long started=-1,nextShot,reloadUntil,finishAt=-1,noiseUntil,nextCall,nextGrowth;boolean won;Vec3 previous=Vec3.ZERO;
  final Map<BlockPos,net.minecraft.world.level.block.state.BlockState> growth=new HashMap<>();
  Round(ServerLevel l,BlockPos o){level=l;origin=o;}
 }
 public record Status(int stage,int enemies,int ammo,int burned,boolean finished,boolean won,BlockPos origin){}
 public static Status status(ServerPlayer p){Round r=rounds.get(p.getUUID());return r==null?null:new Status(r.stage,r.enemies.size(),r.ammo,r.burned,r.finishAt>=0,r.won,r.origin);}
 public static boolean contains(ServerPlayer p){return rounds.containsKey(p.getUUID());}
 public static boolean isRifle(ItemStack s){return RIFLE.equals(s.get(DataComponents.ITEM_MODEL));}
 private static void sky(ServerPlayer p,boolean night){
  var clock=p.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_CLOCK).getOrThrow(net.minecraft.world.clock.WorldClocks.OVERWORLD);var c=p.level().clockManager().getInstance(clock);
  p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTimePacket(p.level().getGameTime(),Map.of(clock,new net.minecraft.world.clock.ClockNetworkState(night?18000:c.totalTicks(),night?0:c.partialTick(),night?0:c.rate()))));
 }
 public static void enter(ServerPlayer p){
  if(GameLobby.origin()==null||!ModeProfiles.enter(p,ModeProfiles.Mode.NIGHTFALL))return;
  if(p.level().getDifficulty()==Difficulty.PEACEFUL)p.level().getServer().setDifficulty(Difficulty.NORMAL,true);
  Frostline.leave(p);leave(p);GameLobby.exit(p);int slot=slots.computeIfAbsent(p.getUUID(),u->slots.size());
  BlockPos o=GameLobby.origin().offset(1024+(slot%8)*192,30,2048+(slot/8)*192);Round r=new Round(p.level(),o);rounds.put(p.getUUID(),r);r.bar.addPlayer(p);
  for(int x=-4;x<=4;x++)for(int z=18;z<=24;z++)r.level.setBlockAndUpdate(o.offset(x,-1,z),Blocks.DEEPSLATE_TILES.defaultBlockState());
  if(!built.contains(p.getUUID()))BloomScene.build(r.level,o,()->built.add(p.getUUID()));
  p.stopRiding();p.setHealth(p.getMaxHealth());p.getFoodData().setFoodLevel(20);p.clearFire();p.connection.teleport(o.getX()+.5,o.getY()+1,o.getZ()+21.5,180,0);r.previous=p.position();
  ItemStack gun=new ItemStack(Items.CARROT_ON_A_STICK);gun.set(DataComponents.ITEM_MODEL,RIFLE);gun.set(DataComponents.CUSTOM_NAME,Component.literal("WARD-12 · Containment carbine"));p.getInventory().setItem(0,gun);
  r.rootDamage=ModeProfiles.owns(p,"incendiary")?20:10;
  if(ModeProfiles.owns(p,"fieldkit"))p.getInventory().setItem(1,new ItemStack(Items.GOLDEN_APPLE,2));
  sync(p,r);sky(p,true);p.sendSystemMessage(Component.literal("Crouch to stay quiet. Burn three amber growths. Recover the specimen. Come back alive."),true);
 }
 public static void leave(ServerPlayer p){
  Round r=rounds.remove(p.getUUID());if(r==null)return;
  for(UUID id:r.enemies){var e=r.level.getEntity(id);if(e!=null)e.discard();}
  for(UUID id:r.decor){var e=r.level.getEntity(id);if(e!=null)e.discard();}
  for(var entry:r.growth.entrySet())r.level.setBlock(entry.getKey(),entry.getValue(),2);
  r.bar.removeAllPlayers();p.getAbilities().invulnerable=false;p.onUpdateAbilities();p.clearFire();sky(p,false);
 }
 private static UUID fungus(Round r,double x,double y,double z,float scale){
  var display=EntityTypes.ITEM_DISPLAY.create(r.level,EntitySpawnReason.COMMAND);if(display==null)return null;
  ItemStack item=new ItemStack(Items.BROWN_MUSHROOM);item.set(DataComponents.ITEM_MODEL,FUNGUS);display.getSlot(0).set(item);
  display.setPos(r.origin.getX()+x,r.origin.getY()+y,r.origin.getZ()+z);
  ((dev.arnav.slingcraft.mixin.DisplayAccess)display).slingcraft$transform(new com.mojang.math.Transformation(new Vector3f(),new Quaternionf(),new Vector3f(scale),new Quaternionf()));
  r.level.addFreshEntity(display);r.decor.add(display.getUUID());return display.getUUID();
 }
 private static void begin(ServerPlayer p,Round r){
  r.started=r.level.getGameTime();r.nextCall=r.started+60;
  for(int i=0;i<3;i++){int[] q=ROOTS[i];r.roots[i]=fungus(r,q[0],.9,q[1],2.4f);}
  for(int side:new int[]{-1,1})for(int z:new int[]{-25,-14,0})fungus(r,side*25,2.1,z,1.5f);
  spawn(p,r,6);title(p,"BLOOM","Crouch. Burn. Extract.");
 }
 private static void spawn(ServerPlayer p,Round r,int count){
  for(int i=0;i<count;i++){
   Zombie z=EntityTypes.ZOMBIE.create(r.level,EntitySpawnReason.COMMAND);if(z==null)continue;
   double x=(i%2==0?-1:1)*(16+i%3),v=-23+(i*7)%26;z.setPos(r.origin.getX()+x,r.origin.getY()+.2,r.origin.getZ()+v);
   z.setPersistenceRequired();z.setSilent(true);z.addTag("slingcraft_nightfall");z.setCustomName(Component.literal(i%3==0?"Bloom listener":"Rootbound"));
   ItemStack crown=new ItemStack(Items.CARVED_PUMPKIN);crown.set(DataComponents.ITEM_MODEL,FUNGUS);z.setItemSlot(EquipmentSlot.HEAD,crown);z.setDropChance(EquipmentSlot.HEAD,0);
   z.setHealth(20);z.setNoAi(true);r.level.addFreshEntity(z);r.enemies.add(z.getUUID());
  }
 }
 private static void sound(ServerPlayer p,String event,Vec3 at,float volume,float pitch,SoundSource source){
  p.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(Holder.direct(SoundEvent.createVariableRangeEvent(id(event))),source,at.x,at.y,at.z,volume,pitch,p.level().getGameTime()));
 }
 private static void cue(ServerPlayer p,SoundEvent s,float pitch){p.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(Holder.direct(s),SoundSource.PLAYERS,p.getX(),p.getY(),p.getZ(),.38f,pitch,0));}
 private static void title(ServerPlayer p,String title,String sub){p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(8,40,12));p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(Component.literal(title)));p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(Component.literal(sub)));}
 private static void sync(ServerPlayer p,Round r){
  var gun=p.getInventory().getItem(0);if(!isRifle(gun))return;var tag=new net.minecraft.nbt.CompoundTag();tag.putInt("ammo",r.ammo);tag.putInt("reserve",r.reserve);tag.putLong("reloadUntil",r.reloadUntil);tag.putInt("stage",r.stage);gun.set(DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(tag));p.inventoryMenu.broadcastChanges();
 }
 public static void reload(ServerPlayer p){Round r=rounds.get(p.getUUID());if(r==null||r.finishAt>=0||r.reloadUntil>0||r.ammo==12||r.reserve<=0)return;r.reloadUntil=r.level.getGameTime()+32;sync(p,r);cue(p,SoundEvents.CROSSBOW_LOADING_START.value(),.7f);}
 public static void fire(ServerPlayer p){
  Round r=rounds.get(p.getUUID());if(r==null||r.started<0||r.finishAt>=0||!isRifle(p.getMainHandItem()))return;long now=r.level.getGameTime();if(now<r.nextShot||r.reloadUntil>0)return;r.nextShot=now+5;
  if(r.ammo==0){reload(p);return;}r.ammo--;r.noiseUntil=now+100;sync(p,r);p.swing(InteractionHand.MAIN_HAND,net.minecraft.world.item.component.SwingAnimation.DEFAULT,true);
  Vec3 from=p.getEyePosition(),end=from.add(p.getLookAngle().scale(60));end=r.level.clip(new ClipContext(from,end,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,p)).getLocation();
  Entity hit=null;int root=-1;double dist=from.distanceToSqr(end);
  for(UUID id:r.enemies){var e=r.level.getEntity(id);if(e==null||!e.isAlive())continue;var at=e.getBoundingBox().inflate(.16).clip(from,end);if(at.isPresent()&&from.distanceToSqr(at.get())<dist){dist=from.distanceToSqr(at.get());hit=e;}}
  for(int i=0;i<3;i++)if(r.life[i]>0){Vec3 c=Vec3.atBottomCenterOf(r.origin.offset(ROOTS[i][0],0,ROOTS[i][1]));var at=new AABB(c.x-2.5,c.y,c.z-2.5,c.x+2.5,c.y+4,c.z+2.5).clip(from,end);if(at.isPresent()&&from.distanceToSqr(at.get())<dist){dist=from.distanceToSqr(at.get());root=i;hit=null;}}
  Vec3 target=from.add(p.getLookAngle().scale(Math.sqrt(dist))),muzzle=from.add(p.getLookAngle().scale(.7)).add(0,-.12,0);
  r.level.sendParticles(net.minecraft.core.particles.ColorParticleOption.create(ParticleTypes.FLASH,0xFFFFBD67),muzzle.x,muzzle.y,muzzle.z,1,0,0,0,0);
  for(int i=1;i<=24;i++){Vec3 at=muzzle.lerp(target,i/24.);r.level.sendParticles(ParticleTypes.CRIT,at.x,at.y,at.z,1,0,0,0,0);}
  if(hit instanceof Zombie z)z.hurtServer(r.level,r.level.damageSources().playerAttack(p),10);
  if(root>=0){r.life[root]-=r.rootDamage;if(r.life[root]<=0){r.burned++;var e=r.level.getEntity(r.roots[root]);if(e!=null)e.discard();WorldVfx.impact(r.level,target,true);r.level.sendParticles(ParticleTypes.LARGE_SMOKE,target.x,target.y,target.z,25,1.6,1.6,1.6,.03);cue(p,SoundEvents.FIRECHARGE_USE,.75f);if(r.burned==3){r.stage=1;sync(p,r);title(p,"ROOTS SILENCED","Find the specimen · G");}}}
  else r.level.sendParticles(ParticleTypes.CRIT,target.x,target.y,target.z,6,.1,.1,.1,.04);
  cue(p,SoundEvents.FIREWORK_ROCKET_BLAST,.6f);
 }
 public static void interact(ServerPlayer p){
  Round r=rounds.get(p.getUUID());if(r==null||r.finishAt>=0)return;
  if(r.stage==1&&p.position().distanceToSqr(Vec3.atBottomCenterOf(r.origin.offset(0,1,-26)))<20){r.stage=2;r.noiseUntil=r.level.getGameTime()+220;spawn(p,r,4);sync(p,r);title(p,"SAMPLE SECURED","Back to the entrance · G");cue(p,SoundEvents.BEACON_DEACTIVATE,.7f);}
  else if(r.stage==2&&p.position().distanceToSqr(Vec3.atBottomCenterOf(r.origin.offset(0,1,21)))<30)finish(p,r,true);
  else p.sendSystemMessage(Component.literal(r.stage==0?"Destroy all three amber growths with the carbine.":r.stage==1?"The specimen is on the far altar.":"Take the specimen back to the carpeted entrance."),true);
 }
 private static void finish(ServerPlayer p,Round r,boolean won){
  if(r.finishAt>=0)return;r.finishAt=r.level.getGameTime()+100;r.won=won;
  for(UUID id:r.enemies){var e=r.level.getEntity(id);if(e!=null)e.discard();}r.enemies.clear();
  r.bar.setName(Component.literal(won?"SPECIMEN EXTRACTED · Garden in 5s":"EXPEDITION LOST · Garden in 5s"));r.bar.setColor(won?BossEvent.BossBarColor.GREEN:BossEvent.BossBarColor.RED);p.setHealth(p.getMaxHealth());p.getAbilities().invulnerable=true;p.onUpdateAbilities();
  if(won){ModeProfiles.award(p,150,"Bloom field researcher");WorldVfx.arrive(r.level,p.position());cue(p,SoundEvents.PLAYER_LEVELUP,.8f);}
 }
 private static void tick(ServerPlayer p){
  Round r=rounds.get(p.getUUID());if(r==null)return;long now=r.level.getGameTime();if(p.level()!=r.level){leave(p);return;}if(now%20==0)sky(p,true);
  if(r.finishAt>=0){if(now>=r.finishAt)GameLobby.enter(p);return;}
  if(p.getY()<r.origin.getY()-5||Math.abs(p.getX()-r.origin.getX())>32||Math.abs(p.getZ()-r.origin.getZ())>35){finish(p,r,false);return;}
  if(!built.contains(p.getUUID())){r.bar.setName(Component.literal("BLOOM · Preparing the conservatory…"));return;}
  if(r.started<0){begin(p,r);return;}
  if(now-r.started>20*600){finish(p,r,false);return;}
  if(r.reloadUntil>0&&now>=r.reloadUntil){int n=Math.min(12-r.ammo,r.reserve);r.ammo+=n;r.reserve-=n;r.reloadUntil=0;sync(p,r);cue(p,SoundEvents.CROSSBOW_LOADING_END.value(),1);}
  boolean noisy=now<r.noiseUntil||(!p.isShiftKeyDown()&&p.position().subtract(r.previous).horizontalDistance()>.12);r.previous=p.position();
  r.enemies.removeIf(id->{var e=r.level.getEntity(id);return e==null||!e.isAlive();});
  Entity nearest=null;double nearestDist=Double.MAX_VALUE;
  for(UUID id:r.enemies){var e=r.level.getEntity(id);if(e instanceof Zombie z){double dist=z.distanceToSqr(p);boolean alert=(noisy&&dist<625)||dist<(p.isShiftKeyDown()?9:36);z.setNoAi(!alert);z.setTarget(alert?p:null);if(dist<nearestDist){nearest=z;nearestDist=dist;}}}
  if(now>=r.nextCall&&nearest!=null){sound(p,"bloom_call",nearest.position(),.72f,.9f+(now%3)*.065f,SoundSource.HOSTILE);r.nextCall=now+130+(now%80);p.sendSystemMessage(Component.literal(noisy?"The infected heard you.":"A call echoes between the exhibits. Crouch to stay quiet."),true);}
  if(now%240==0)sound(p,"bloom_air",p.position(),.5f,1,SoundSource.AMBIENT);
  if(now%8==0){for(int i=0;i<3;i++)if(r.life[i]>0){int[] q=ROOTS[i];r.level.sendParticles(ParticleTypes.MYCELIUM,r.origin.getX()+q[0],r.origin.getY()+2,r.origin.getZ()+q[1],3,2,1.5,2,0);r.level.sendParticles(ParticleTypes.FLAME,r.origin.getX()+q[0],r.origin.getY()+2.1,r.origin.getZ()+q[1],1,.3,.3,.3,0);}}
  if(now%40==0&&r.growth.size()<120)for(int i=0;i<3;i++)if(r.life[i]>0){int age=(int)((now-r.started)/40),dx=(age*7+i*3)%13-6,dz=(age*11+i)%13-6;BlockPos at=r.origin.offset(ROOTS[i][0]+dx,0,ROOTS[i][1]+dz);if(r.level.getBlockState(at).isAir()){r.growth.put(at,r.level.getBlockState(at));r.level.setBlock(at,Blocks.MOSS_CARPET.defaultBlockState(),2);}}
  String objective=r.stage==0?"Burn amber roots · "+r.burned+" / 3":r.stage==1?"Recover specimen at the far altar · G":"Bring specimen to the entrance · G";
  r.bar.setName(Component.literal("BLOOM · "+objective));r.bar.setProgress(r.stage==0?r.burned/3f:1);r.bar.setColor(noisy?BossEvent.BossBarColor.RED:BossEvent.BossBarColor.PURPLE);
  if(now%20==0){for(var e:r.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,new AABB(r.origin).inflate(35)))e.discard();for(var e:r.level.getEntitiesOfClass(ExperienceOrb.class,new AABB(r.origin).inflate(35)))e.discard();}
 }
 public static void init(){
  CommandRegistrationCallback.EVENT.register((d,c,e)->d.register(Commands.literal("nightfall-action").requires(s->s.getEntity() instanceof ServerPlayer p&&contains(p))
   .then(Commands.literal("fire").executes(ctx->{fire(ctx.getSource().getPlayerOrException());return 1;})).then(Commands.literal("reload").executes(ctx->{reload(ctx.getSource().getPlayerOrException());return 1;})).then(Commands.literal("interact").executes(ctx->{interact(ctx.getSource().getPlayerOrException());return 1;}))));
  UseItemCallback.EVENT.register((p,l,h)->{if(p instanceof ServerPlayer sp&&contains(sp)&&isRifle(p.getItemInHand(h))){fire(sp);return InteractionResult.SUCCESS;}return InteractionResult.PASS;});
  ServerLivingEntityEvents.ALLOW_DEATH.register((e,s,a)->{if(e instanceof ServerPlayer p&&contains(p)){finish(p,rounds.get(p.getUUID()),false);return false;}return true;});
  ServerPlayConnectionEvents.DISCONNECT.register((h,s)->leave(h.player));ServerTickEvents.END_SERVER_TICK.register(s->{for(var p:new ArrayList<>(s.getPlayerList().getPlayers()))tick(p);});
  ServerLifecycleEvents.SERVER_STOPPING.register(s->{for(var p:new ArrayList<>(s.getPlayerList().getPlayers()))leave(p);});ServerLifecycleEvents.SERVER_STOPPED.register(s->{rounds.clear();slots.clear();built.clear();});
 }
}
