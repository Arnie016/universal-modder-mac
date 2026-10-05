package dev.arnav.seeker.test;

import dev.arnav.seeker.Seeker;
import dev.arnav.seeker.SeekerWandItem;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.level.ServerPlayer;

/**
 * The oracle for the Seeker mod: boots the real client, creates a world, puts three husks 26-49 degrees away from
 * where the player looks, fires straight ahead, and requires homing kills. Screenshots land in run/screenshots.
 * Run: ./gradlew runClientGameTest   (no Microsoft account; works on macOS)
 */
public class SeekerClientTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			ctx.waitTicks(60);
			var server = sp.getServer();
			server.runCommand("difficulty easy");
			server.runCommand("time set noon");
			server.runCommand("weather clear");
			server.runCommand("gamemode creative @p");
			server.runCommand("tp @p 0 ~ 0 0 0");          // yaw 0 = facing south, pitch 0 = level
			ctx.waitTicks(10);
			server.runCommand("item replace entity @p weapon.mainhand with seeker:seeker_wand");
			// ^left ^up ^forward, relative to where the player faces: none is on the line of fire
			server.runCommand("execute at @p run summon husk ^4 ^ ^8 {NoAI:1b,PersistenceRequired:1b}");   // ~27 deg
			server.runCommand("execute at @p run summon husk ^-5 ^ ^7 {NoAI:1b,PersistenceRequired:1b}");  // ~35 deg
			server.runCommand("execute at @p run summon husk ^7 ^ ^6 {NoAI:1b,PersistenceRequired:1b}");   // ~49 deg
			ctx.waitTicks(20);
			ctx.takeScreenshot("seeker-1-targets");

			for (int shot = 0; shot < 6; shot++) {
				server.runOnServer(s -> {
					ServerPlayer p = s.getPlayerList().getPlayers().get(0);
					SeekerWandItem.fire(p.level(), p);
				});
				ctx.waitTicks(4);
				if (shot == 0) ctx.takeScreenshot("seeker-2-in-flight");
				ctx.waitTicks(14);
			}
			ctx.waitTicks(30);
			ctx.takeScreenshot("seeker-3-after");
			int kills = server.computeOnServer(s -> Seeker.kills);
			Seeker.LOG.info("Seeker test: {} homing kills of 3 off-axis husks", kills);
			if (kills < 2) throw new AssertionError("expected at least 2 homing kills, got " + kills);
		}
	}
}
