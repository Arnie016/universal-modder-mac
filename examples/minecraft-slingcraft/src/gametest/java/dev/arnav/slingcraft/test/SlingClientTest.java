package dev.arnav.slingcraft.test;

import dev.arnav.slingcraft.Slingcraft;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/** Builds a level, fires birds with plain commands, requires a collapsed tower and dead pigs. */
public class SlingClientTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			ctx.waitTicks(40);
			var s = sp.getServer();
			s.runCommand("time set noon");
			s.runCommand("weather clear");
			s.runCommand("difficulty easy");
			s.runCommand("tp @p 0 ~ 0 0 -8");
			ctx.waitTicks(10);
			s.runCommand("execute as @p at @s run sling level");
			ctx.waitTicks(40);
			ctx.takeScreenshot("sling-1-level");
			int start = Slingcraft.pigsLeft;
			float[][] shots = {{1.6f, 12f}, {1.8f, 10f}, {1.5f, 18f}, {2.0f, 8f}, {1.7f, 22f}, {2.2f, 6f}};
			for (int i = 0; i < shots.length && Slingcraft.pigsLeft > 0; i++) {
				s.runCommand("execute as @p at @s run sling launch " + shots[i][0] + " " + shots[i][1]);
				ctx.waitTicks(8);
				if (i == 0) ctx.takeScreenshot("sling-2-in-flight");
				ctx.waitTicks(52);
				if (i == 0) ctx.takeScreenshot("sling-3-impact");
			}
			ctx.waitTicks(40);
			ctx.takeScreenshot("sling-4-after");
			Slingcraft.LOG.info("Slingcraft test: {} birds, {} blocks knocked loose, pigs {} -> {}", Slingcraft.birdsUsed,
					Slingcraft.destroyed, start, Slingcraft.pigsLeft);
			if (Slingcraft.destroyed < 10) throw new AssertionError("tower barely moved: " + Slingcraft.destroyed);
			if (start - Slingcraft.pigsLeft < 2) throw new AssertionError("expected at least 2 pigs down");
		}
	}
}
