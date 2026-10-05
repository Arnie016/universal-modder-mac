package dev.arnav.seeker;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/** Right-click: fire a homing arrow (no ammo, short cooldown). */
public class SeekerWandItem extends Item {
	public SeekerWandItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (!level.isClientSide()) {
			fire(level, player);
		}
		player.getCooldowns().addCooldown(player.getItemInHand(hand), 8);
		return InteractionResult.SUCCESS;
	}

	/** Also used by the game test, which fires as the player without simulating a click. */
	public static Arrow fire(Level level, Player shooter) {
		Arrow arrow = new Arrow(level, shooter, new ItemStack(Items.ARROW), null);   // 26.x: an EMPTY weapon stack throws "Invalid weapon firing an arrow"; null = no weapon
		arrow.shootFromRotation(shooter, shooter.getXRot(), shooter.getYRot(), 0.0F, 2.2F, 0.0F);
		arrow.setNoGravity(true);
		arrow.setCritArrow(true);
		arrow.setBaseDamage(8.0);
		arrow.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
		arrow.setGlowingTag(true);
		level.addFreshEntity(arrow);
		Seeker.SEEKERS.add(arrow);
		level.playSound(null, shooter.getX(), shooter.getY(), shooter.getZ(), SoundEvents.ILLUSIONER_CAST_SPELL, SoundSource.PLAYERS, 0.8F, 1.4F);
		return arrow;
	}
}
