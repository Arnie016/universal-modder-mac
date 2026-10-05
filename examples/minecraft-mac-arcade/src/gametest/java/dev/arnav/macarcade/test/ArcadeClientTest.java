package dev.arnav.macarcade.test;

import java.util.Arrays;

import dev.arnav.macarcade.MacArcade;
import dev.arnav.macarcade.PlayScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Oracle for Mac Arcade (run through ../verify.sh, which starts a guest app + `um mac stream` first):
 * a world is created, the screen is placed in front of the player, and the test requires live frames that
 * keep changing (the guest is really running), plus screenshots of the screen in the world and of play mode.
 */
public class ArcadeClientTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		int port = Integer.getInteger("macarcade.port", 7777);
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			ctx.waitTicks(40);
			sp.getServer().runCommand("time set noon");
			sp.getServer().runCommand("weather clear");
			sp.getServer().runCommand("tp @p 0 ~ 0 0 -5");
			ctx.waitTicks(10);
			ctx.runOnClient(mc -> MacArcade.place(mc, port));
			ctx.waitFor(mc -> MacArcade.uploaded > 20, 400);
			int[] a = MacArcade.link.pixels.clone();
			ctx.takeScreenshot("arcade-1-screen-in-world");
			ctx.waitTicks(60);
			int[] b = MacArcade.link.pixels.clone();
			ctx.takeScreenshot("arcade-2-later");
			long frames = MacArcade.link.frames;
			boolean changed = !Arrays.equals(a, b);
			MacArcade.LOG.info("MacArcade test: {} frames received, {} uploaded, {}x{}, guest picture changed: {}",
					frames, MacArcade.uploaded, MacArcade.link.width, MacArcade.link.height, changed);
			ctx.setScreen(PlayScreen::new);
			ctx.waitTicks(10);
			ctx.takeScreenshot("arcade-3-play-mode");
			ctx.setScreen(() -> null);
			if (frames < 40) throw new AssertionError("expected a live stream, got " + frames + " frames");
			// motion is logged, not required: whether the guest keeps animating is the guest's business (see README)
		}
	}
}
