package dev.arnav.slingcraft;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.mojang.brigadier.arguments.FloatArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Slingcraft: a slingshot physics game in first-person Minecraft (original, not any existing franchise).
 *   /sling level              build a slingshot here and a tower of planks/glass/stone with pigs 24 blocks ahead
 *   /sling launch <power> <angle>   fire a chicken from the slingshot (power 0.5-3, angle degrees up)
 *   /sling aim                 fire along where you are looking
 * The "bird" is a chicken riding an invisible snowball; on impact nearby blocks become falling blocks thrown
 * outward (towers collapse) and pigs in range take damage.
 */
public final class Slingcraft implements ModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("Slingcraft");

	record Shot(Snowball ball, Chicken bird, Vec3[] last) { }

	static final List<Shot> SHOTS = new ArrayList<>();
	/** Pending structural collapses: {center, ticks waited}. */
	static final List<long[]> COLLAPSE = new ArrayList<>();
	public static Vec3 sling;            // launch point
	public static float levelYaw;
	public static int pigsLeft, birdsUsed, destroyed;

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((d, ctx, sel) -> d.register(Commands.literal("sling")
				.then(Commands.literal("level").executes(c -> { buildLevel(c.getSource().getPlayerOrException()); return 1; }))
				.then(Commands.literal("aim").executes(c -> {
					ServerPlayer p = c.getSource().getPlayerOrException();
					launch(p.level(), p.getEyePosition().add(p.getLookAngle()), p.getLookAngle().scale(2.2));
					return 1;
				}))
				.then(Commands.literal("launch").then(Commands.argument("power", FloatArgumentType.floatArg(0.2f, 4f))
						.then(Commands.argument("angle", FloatArgumentType.floatArg(-30f, 85f)).executes(c -> {
							ServerPlayer p = c.getSource().getPlayerOrException();
							float pw = FloatArgumentType.getFloat(c, "power"), an = FloatArgumentType.getFloat(c, "angle");
							float yaw = sling != null ? levelYaw : p.getYRot();
							Vec3 from = sling != null ? sling : p.getEyePosition();
							double y = Math.toRadians(yaw), a = Math.toRadians(an);
							Vec3 dir = new Vec3(-Math.sin(y) * Math.cos(a), Math.sin(a), Math.cos(y) * Math.cos(a));
							launch(p.level(), from, dir.scale(pw));
							return 1;
						}))))));
		ServerTickEvents.END_LEVEL_TICK.register(Slingcraft::tick);
	}

	public static void launch(net.minecraft.world.level.Level level, Vec3 from, Vec3 vel) {
		Snowball ball = new Snowball(EntityTypes.SNOWBALL, level);
		ball.setPos(from);
		ball.setDeltaMovement(vel);
		ball.setInvisible(true);
		level.addFreshEntity(ball);
		Chicken bird = EntityTypes.CHICKEN.create(level, EntitySpawnReason.COMMAND);
		bird.setPos(from);
		bird.setCustomName(Component.literal("Clucky"));
		level.addFreshEntity(bird);
		bird.startRiding(ball, true, true);
		SHOTS.add(new Shot(ball, bird, new Vec3[] {from}));
		birdsUsed++;
		level.playSound(null, from.x, from.y, from.z, SoundEvents.CROSSBOW_SHOOT, SoundSource.PLAYERS, 1f, 0.8f);
		LOG.info("Slingcraft: bird #{} launched v={}", birdsUsed, vel);
	}

	private static void tick(ServerLevel level) {
		// unsupported blocks fall a few ticks after an impact, cascading upward, so a hit near the base topples a tower
		for (Iterator<long[]> it = COLLAPSE.iterator(); it.hasNext(); ) {
			long[] cl = it.next();
			if (++cl[1] % 4 != 0) continue;
			BlockPos c = BlockPos.of(cl[0]);
			int fell = 0;
			for (BlockPos p : BlockPos.betweenClosed(c.offset(-6, -3, -6), c.offset(6, 14, 6))) {
				BlockState st = level.getBlockState(p);
				if (st.isAir() || st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.DIRT) || st.is(Blocks.OAK_FENCE)) continue;
				if (!level.getBlockState(p.below()).isAir()) continue;
				FallingBlockEntity fb = FallingBlockEntity.fall(level, p.immutable(), st);
				fb.setDeltaMovement(Vec3.atCenterOf(p).subtract(Vec3.atCenterOf(c)).normalize().scale(0.12).add(0, 0, 0));
				fell++;
				destroyed++;
			}
			if (fell == 0 || cl[1] > 120) it.remove();
		}
		if (!COLLAPSE.isEmpty() || !SHOTS.isEmpty()) {
			for (Pig pig : level.getEntitiesOfClass(Pig.class, new AABB(BlockPos.containing(sling == null ? Vec3.ZERO : sling)).inflate(64), Pig::isAlive)) {
				if (pig.fallDistance > 3) pig.setNoAi(false);   // falling pigs take real fall damage
			}
			if (sling != null && level.getGameTime() % 10 == 0) {
				int before = pigsLeft;
				pigsLeft = level.getEntitiesOfClass(Pig.class, new AABB(BlockPos.containing(sling)).inflate(64), Pig::isAlive).size();
				if (pigsLeft < before) for (ServerPlayer pl : level.players())
					pl.sendSystemMessage(Component.literal(pigsLeft == 0 ? "Level cleared with " + birdsUsed + " birds!" : pigsLeft + " pigs left"));
			}
		}
		for (Iterator<Shot> it = SHOTS.iterator(); it.hasNext(); ) {
			Shot s = it.next();
			if (s.ball.level() != level) continue;
			if (!s.ball.isRemoved()) {
				s.last[0] = s.ball.position();
				level.sendParticles(ParticleTypes.CLOUD, s.last[0].x, s.last[0].y, s.last[0].z, 1, 0, 0, 0, 0);
				if (s.ball.tickCount < 200) continue;
			}
			it.remove();
			s.ball.discard();
			impact(level, s.last[0], s.bird);
		}
	}

	/** Snowball hit something: blow the nearby structure apart and hurt pigs. */
	static void impact(ServerLevel level, Vec3 at, Chicken bird) {
		BlockPos c = BlockPos.containing(at);
		int r = 3;
		for (BlockPos p : BlockPos.betweenClosed(c.offset(-r, -r, -r), c.offset(r, r, r))) {
			BlockState st = level.getBlockState(p);
			if (st.isAir() || st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.DIRT) || st.is(Blocks.BEDROCK)) continue;
			double d = Math.sqrt(p.distSqr(c));
			if (d > r || (st.is(Blocks.STONE_BRICKS) && d > 1.5)) continue;   // stone is sturdier than wood or glass
			FallingBlockEntity fb = FallingBlockEntity.fall(level, p.immutable(), st);
			Vec3 push = Vec3.atCenterOf(p).subtract(at).normalize().scale(0.6 / Math.max(1, d)).add(0, 0.35, 0);
			fb.setDeltaMovement(push);
			destroyed++;
		}
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(2.5), e -> e instanceof Pig)) {
			e.hurtServer(level, level.damageSources().generic(), 40f);
		}
		COLLAPSE.add(new long[] {c.asLong(), 0});
		level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y, at.z, 3, 0.5, 0.5, 0.5, 0);
		level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 0.7f, 1.4f);
		if (bird != null && bird.isAlive()) bird.stopRiding();
		pigsLeft = level.getEntitiesOfClass(Pig.class, new AABB(BlockPos.containing(sling == null ? at : sling)).inflate(64), Pig::isAlive).size();
		LOG.info("Slingcraft: impact at {} - {} blocks knocked loose, {} pigs left", c, destroyed, pigsLeft);
		for (ServerPlayer p : level.players()) {
			p.sendSystemMessage(Component.literal(pigsLeft == 0 ? "Level cleared with " + birdsUsed + " birds!" : pigsLeft + " pigs left"));
		}
	}

	/** Slingshot at the player's feet, tower 24 blocks along their facing. */
	static void buildLevel(ServerPlayer p) {
		ServerLevel level = p.level();
		levelYaw = Math.round(p.getYRot() / 90f) * 90f;
		double y = Math.toRadians(levelYaw);
		Vec3 fwd = new Vec3(-Math.sin(y), 0, Math.cos(y)), side = new Vec3(fwd.z, 0, -fwd.x);
		BlockPos base = p.blockPosition();
		// slingshot: two fence posts and a fork
		for (int i = -1; i <= 1; i += 2) for (int h = 0; h < 3; h++)
			level.setBlockAndUpdate(BlockPos.containing(Vec3.atLowerCornerOf(base).add(fwd.scale(2)).add(side.scale(i)).add(0, h, 0)), Blocks.OAK_FENCE.defaultBlockState());
		sling = Vec3.atCenterOf(base).add(fwd.scale(2)).add(0, 2.6, 0);
		// tower: stone floor, plank columns, glass walls, plank roof, pigs on each floor
		Vec3 t0 = Vec3.atLowerCornerOf(base).add(fwd.scale(24));
		int placed = 0;
		for (int f = 0; f < 3; f++) {
			int yy = f * 4;
			for (int a = -2; a <= 2; a++) for (int b = -2; b <= 2; b++) {
				BlockPos bp = BlockPos.containing(t0.add(side.scale(a)).add(fwd.scale(b)).add(0, yy, 0));
				level.setBlockAndUpdate(bp, (f == 0 ? Blocks.STONE_BRICKS : Blocks.OAK_PLANKS).defaultBlockState());
				for (int h = 1; h <= 3; h++) {
					boolean corner = Math.abs(a) == 2 && Math.abs(b) == 2, edge = Math.abs(a) == 2 || Math.abs(b) == 2;
					if (corner) level.setBlockAndUpdate(bp.above(h), Blocks.OAK_LOG.defaultBlockState());
					else if (edge && f < 2) level.setBlockAndUpdate(bp.above(h), Blocks.GLASS.defaultBlockState());
				}
			}
			for (int k = 0; k < 2; k++) {
				Pig pig = EntityTypes.PIG.create(level, EntitySpawnReason.COMMAND);
				Vec3 at = t0.add(side.scale(k == 0 ? -0.5 : 0.8)).add(0, yy + 1, 0).add(0.5, 0, 0.5);
				pig.setPos(at);
				pig.setNoAi(true);
				pig.setPersistenceRequired();
				level.addFreshEntity(pig);
				placed++;
			}
		}
		pigsLeft = placed;
		birdsUsed = 0;
		destroyed = 0;
		p.sendSystemMessage(Component.literal("Slingcraft: " + placed + " pigs in the tower ahead. /sling launch <power 0.5-3> <angle>  or  /sling aim"));
		LOG.info("Slingcraft: level built, sling at {}, {} pigs", sling, placed);
	}
}
