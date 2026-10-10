package dev.arnav.slingcraft.mixin;

import dev.arnav.slingcraft.Slingcraft;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Siege TNT can damage the fort while preserving the launch pad and surrounding scenery. */
@Mixin(ServerExplosion.class)
public class SiegeExplosionMixin {
    @Inject(method="calculateExplodedPositions",at=@At("RETURN"),cancellable=true)
    private void confineSiegeBlast(CallbackInfoReturnable<List<BlockPos>> result){
        var explosion=(ServerExplosion)(Object)this;
        result.setReturnValue(Slingcraft.siegeExplosionBlocks(explosion.level(),explosion.center(),result.getReturnValue()));
    }
}
