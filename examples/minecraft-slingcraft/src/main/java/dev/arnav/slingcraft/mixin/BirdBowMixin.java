package dev.arnav.slingcraft.mixin;
import dev.arnav.slingcraft.Slingcraft;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(BowItem.class)
public class BirdBowMixin {
    @Inject(method="use",at=@At("HEAD"),cancellable=true)
    private void useBird(Level level,Player p,InteractionHand hand,CallbackInfoReturnable<InteractionResult> cir){
        if(Slingcraft.bowKind(p.getItemInHand(hand))<0)return;
        p.startUsingItem(hand);cir.setReturnValue(InteractionResult.CONSUME);
    }
    @Inject(method="releaseUsing",at=@At("HEAD"),cancellable=true)
    private void releaseBird(ItemStack stack,Level level,LivingEntity entity,int remaining,CallbackInfoReturnable<Boolean> cir){
        if(Slingcraft.bowKind(stack)<0)return;
        int ticks=((BowItem)(Object)this).getUseDuration(stack,entity)-remaining;
        boolean fired=entity instanceof ServerPlayer p&&Slingcraft.fire(p,stack,ticks);
        cir.setReturnValue(fired);
    }
}
