package dev.arnav.seeker;

import java.util.Collections;
import java.util.Comparator;
import java.util.WeakHashMap;
import java.util.Set;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Seeker: a wand that fires homing arrows. Steering is done server-side each level tick on vanilla arrows we
 * spawned, so there is no custom entity or renderer - the same "inherit, don't rebuild" idea as the Doom mod.
 */
public final class Seeker implements ModInitializer {
	public static final String MOD_ID = "seeker";
	public static final Logger LOG = LoggerFactory.getLogger("Seeker");

	public static final ResourceKey<Item> WAND_KEY = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MOD_ID, "seeker_wand"));
	public static final Item WAND = Registry.register(BuiltInRegistries.ITEM, WAND_KEY,
			new SeekerWandItem(new Item.Properties().setId(WAND_KEY).stacksTo(1)));

	/** Arrows the wand fired (weak: removed arrows are collected). */
	static final Set<AbstractArrow> SEEKERS = Collections.newSetFromMap(new WeakHashMap<>());
	public static int kills;

	static final double RANGE = 32, SPEED = 2.2, TURN = 0.35, CONE_COS = Math.cos(Math.toRadians(100));

	@Override
	public void onInitialize() {
		ServerTickEvents.END_LEVEL_TICK.register(Seeker::steer);
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (source.getDirectEntity() instanceof AbstractArrow a && SEEKERS.contains(a)) {
				kills++;
				LOG.info("Seeker: kill #{} {}", kills, entity.getType().toShortString());
			}
		});
		// `/seeker demo`: wand in hand + a ring of husks off to the sides, so there is something to home onto
		CommandRegistrationCallback.EVENT.register((dispatcher, ctx, selection) -> dispatcher.register(Commands.literal("seeker")
				.then(Commands.literal("demo").executes(c -> {
					var src = c.getSource();
					var cmds = src.getServer().getCommands();
					cmds.performPrefixedCommand(src, "item replace entity @s weapon.mainhand with seeker:seeker_wand");
					for (String at : new String[] {"^5 ^ ^8", "^-6 ^ ^7", "^8 ^ ^4", "^-9 ^ ^3", "^2 ^ ^12"}) {
						cmds.performPrefixedCommand(src.withSuppressedOutput(),
								"execute at @s run summon husk " + at + " {PersistenceRequired:1b,Glowing:1b}");
					}
					src.sendSystemMessage(net.minecraft.network.chat.Component.literal(
							"Seeker: 5 husks around you. Right-click (two-finger click on a trackpad) and fire anywhere - the arrows find them."));
					return 1;
				}))));
		LOG.info("Seeker: wand registered as {}", WAND_KEY.identifier());
	}

	private static void steer(ServerLevel level) {
		for (AbstractArrow arrow : SEEKERS.toArray(new AbstractArrow[0])) {
			if (arrow.isRemoved() || arrow.level() != level) continue;
			Vec3 v = arrow.getDeltaMovement();
			// stuck in a block (no motion) or flying for 6 s: stop steering and let vanilla take over
			if (v.lengthSqr() < 0.05 || arrow.tickCount > 120) { SEEKERS.remove(arrow); arrow.setNoGravity(false); continue; }
			Vec3 pos = arrow.position();
			Vec3 dir = v.normalize();
			LivingEntity target = level.getEntitiesOfClass(LivingEntity.class, arrow.getBoundingBox().inflate(RANGE),
							e -> e instanceof Enemy && e.isAlive() && e.getEyePosition().subtract(pos).normalize().dot(dir) > CONE_COS)
					.stream().min(Comparator.comparingDouble(e -> e.distanceToSqr(pos))).orElse(null);
			if (target != null) {
				Vec3 to = target.getBoundingBox().getCenter().subtract(pos);
				// far away: bend gently; inside 8 blocks: lock on fully, or a 2.2 block/tick arrow overshoots a
				// 0.6-wide mob (seen in the first test run: it passed ~1 block behind the husk)
				double turn = to.length() < 8 ? 1.0 : TURN;
				arrow.setDeltaMovement(dir.scale(1 - turn).add(to.normalize().scale(turn)).normalize().scale(SPEED));
			}
			level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, pos.x, pos.y, pos.z, 1, 0, 0, 0, 0);
		}
	}
}
