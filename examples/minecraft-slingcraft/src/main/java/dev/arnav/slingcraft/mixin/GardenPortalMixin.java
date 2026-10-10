package dev.arnav.slingcraft.mixin;
import dev.arnav.slingcraft.GameLobby;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Only the three garden gates route to minigames; other Nether portals retain vanilla behavior. */
@Mixin(NetherPortalBlock.class)
public abstract class GardenPortalMixin {
 @Inject(method="entityInside",at=@At("HEAD"),cancellable=true)
 private void gardenEntry(BlockState state,Level level,BlockPos pos,Entity entity,InsideBlockEffectApplier effects,boolean moving,CallbackInfo ci){if(level instanceof ServerLevel server&&GameLobby.isGardenPortal(server,pos))ci.cancel();}
 @Inject(method="randomTick",at=@At("HEAD"),cancellable=true)
 private void noLobbyPiglins(BlockState state,ServerLevel level,BlockPos pos,RandomSource random,CallbackInfo ci){if(GameLobby.isGardenPortal(level,pos))ci.cancel();}
}
