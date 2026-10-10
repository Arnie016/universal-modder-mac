package dev.arnav.slingcraft.mixin;

import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Local vehicle controller; scoped to the server-created Frostline chassis. */
@Mixin(AbstractBoat.class)
public abstract class RaceHandlingMixin {
 @Shadow private float deltaRotation;
 @Shadow private boolean inputLeft;
 @Shadow private boolean inputRight;
 @Shadow private boolean inputUp;
 @Shadow private boolean inputDown;
 @Unique private double frostlineSpeed;
 @Unique private double frostlineCharge=100;
 @Inject(method="controlBoat",at=@At("HEAD"),cancellable=true)
 private void drive(CallbackInfo ci){
  AbstractBoat craft=(AbstractBoat)(Object)this;
  if(!craft.hasCustomName()||!craft.getCustomName().getString().startsWith("Frostline"))return;
  var mc=Minecraft.getInstance();if(craft.getControllingPassenger()!=mc.player)return;
  String setup=craft.getCustomName().getString();
  if(setup.contains("Grid")){frostlineSpeed=0;deltaRotation=0;craft.setDeltaMovement(Vec3.ZERO);ci.cancel();return;}
  boolean boost=mc.options.keyJump.isDown()&&inputUp&&frostlineCharge>1;
  double max=setup.contains("Speed")?1.15:setup.contains("Grip")?.78:.95;
  double turn=setup.contains("Grip")?3.3:setup.contains("Speed")?2.0:2.7;
  frostlineCharge=Math.clamp(frostlineCharge+(boost?-1.2:.45),0,100);
  if(craft.horizontalCollision)frostlineSpeed*=.55;
  frostlineSpeed=inputDown?Math.max(0,frostlineSpeed-.10):Math.max(0,frostlineSpeed+(inputUp?.035:-.025));
  frostlineSpeed=Math.min(frostlineSpeed,max*(boost?1.45:1));
  deltaRotation=0;
  float steering=(inputRight?1:0)-(inputLeft?1:0);
  craft.setYRot(craft.getYRot()+(float)(steering*turn*Math.min(1,frostlineSpeed/.15)));
  double angle=Math.toRadians(craft.getYRot());
  craft.setDeltaMovement(new Vec3(-Math.sin(angle)*frostlineSpeed,craft.getDeltaMovement().y,Math.cos(angle)*frostlineSpeed));
  if(boost&&craft.tickCount%2==0){
   double fx=-Math.sin(angle),fz=Math.cos(angle);
   for(int side:new int[]{-1,1}){
    double ex=craft.getX()-fx*.85+fz*side*.55,ez=craft.getZ()-fz*.85-fx*side*.55;
    craft.level().addParticle(net.minecraft.core.particles.ParticleTypes.SOUL_FIRE_FLAME,ex,craft.getY()+.35,ez,-fx*.18,.015,-fz*.18);
    craft.level().addParticle(net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK,ex,craft.getY()+.3,ez,-fx*.3,0,-fz*.3);
   }
  }
  craft.setPaddleState(false,false);
  dev.arnav.slingcraft.RaceHud.speed=frostlineSpeed*20;
  dev.arnav.slingcraft.RaceHud.boost=frostlineCharge;
  ci.cancel();
 }
}
