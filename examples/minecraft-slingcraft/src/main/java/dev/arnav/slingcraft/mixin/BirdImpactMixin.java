package dev.arnav.slingcraft.mixin;
import dev.arnav.slingcraft.Slingcraft;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Snowball.class)
public class BirdImpactMixin {
    @Inject(method="onHit",at=@At("HEAD"))
    private void birdImpact(HitResult hit,CallbackInfo ci){Slingcraft.onBirdHit((Snowball)(Object)this,hit);}
}
