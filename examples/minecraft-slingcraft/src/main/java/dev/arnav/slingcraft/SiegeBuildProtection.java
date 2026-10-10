package dev.arnav.slingcraft;

import net.fabricmc.fabric.api.event.player.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TrapDoorBlock;

/** Server-side edit boundaries for public players using the unlimited Siege kit. */
public final class SiegeBuildProtection {
    private static boolean kitBlock(ItemStack item){
        return item.is(Items.OAK_PLANKS)||item.is(Items.GLASS)||item.is(Items.STONE_BRICKS)||item.is(Items.TNT);
    }
    public static boolean keepsKitBlock(BlockPlaceContext context){
        return context.getPlayer() instanceof ServerPlayer p&&ModeProfiles.mode(p)==ModeProfiles.Mode.SIEGE
            &&kitBlock(context.getItemInHand())&&Slingcraft.canBuildAt(p,context.getClickedPos());
    }
    public static void init(){
        AttackBlockCallback.EVENT.register((player,level,hand,pos,face)->{
            if(player instanceof ServerPlayer p&&ModeProfiles.mode(p)==ModeProfiles.Mode.SIEGE&&!Slingcraft.canBuildAt(p,pos))return InteractionResult.FAIL;
            return InteractionResult.PASS;
        });
        // Also guard the final server destruction path, including forged finish-dig packets.
        PlayerBlockBreakEvents.BEFORE.register((level,player,pos,state,entity)->
            !(player instanceof ServerPlayer p)||ModeProfiles.mode(p)!=ModeProfiles.Mode.SIEGE||Slingcraft.canBuildAt(p,pos));
        UseBlockCallback.EVENT.register((player,level,hand,hit)->{
            if(!(player instanceof ServerPlayer p)||ModeProfiles.mode(p)!=ModeProfiles.Mode.SIEGE)return InteractionResult.PASS;
            ItemStack item=p.getItemInHand(hand);
            if(ModeProfiles.isCompass(item)||Slingcraft.bowKind(item)>=0)return InteractionResult.PASS;
            if(Slingcraft.canBuildAt(p,hit.getBlockPos())&&level.getBlockState(hit.getBlockPos()).getBlock() instanceof TrapDoorBlock)return InteractionResult.PASS;
            if(kitBlock(item)){
                var context=new BlockPlaceContext(p,hand,item,hit);
                if(Slingcraft.canBuildAt(p,context.getClickedPos()))return InteractionResult.PASS;
            }else if(item.is(Items.FLINT_AND_STEEL)){
                if(level.getBlockState(hit.getBlockPos()).is(Blocks.TNT)&&Slingcraft.canBuildAt(p,hit.getBlockPos()))return InteractionResult.PASS;
            }
            p.sendSystemMessage(net.minecraft.network.chat.Component.literal("Build with your kit inside the fort area. The launch pad and scenery stay protected."),true);
            return InteractionResult.FAIL;
        });
        UseItemCallback.EVENT.register((player,level,hand)->{
            if(player instanceof ServerPlayer p&&ModeProfiles.mode(p)==ModeProfiles.Mode.SIEGE){
                var item=p.getItemInHand(hand);
                if(!ModeProfiles.isCompass(item)&&Slingcraft.bowKind(item)<0&&!kitBlock(item)&&!item.is(Items.FLINT_AND_STEEL))return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });
    }
}
