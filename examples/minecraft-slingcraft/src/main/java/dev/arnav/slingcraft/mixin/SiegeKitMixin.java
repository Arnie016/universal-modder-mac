package dev.arnav.slingcraft.mixin;

import dev.arnav.slingcraft.SiegeBuildProtection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Keeps only a successfully placed arena kit block, without Creative capabilities. */
@Mixin(BlockItem.class)
public class SiegeKitMixin {
    @Redirect(method="place",at=@At(value="INVOKE",target="Lnet/minecraft/world/item/ItemStack;consume(ILnet/minecraft/world/entity/LivingEntity;)V"))
    private void keepArenaKit(ItemStack stack,int count,LivingEntity user,BlockPlaceContext context){
        if(SiegeBuildProtection.keepsKitBlock(context)){
            // Undo the client's predicted consumption, even if the server count never changed.
            ((ServerPlayer)user).inventoryMenu.sendAllDataToRemote();
        }else stack.consume(count,user);
    }
}
