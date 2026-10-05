package dev.arnav.macarcade;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * While open, the world (and the in-world screen) stays visible and every key/mouse event goes to the guest app.
 * Mouse position over the Minecraft window maps to the guest window (0..1); movement is also sent as relative
 * deltas for mouselook games. Right Shift closes it.
 */
public class PlayScreen extends Screen {
	private double lastX = -1, lastY = -1;

	public PlayScreen() {
		super(Component.literal("Mac Arcade"));
	}

	@Override public boolean isPauseScreen() { return false; }
	@Override public boolean shouldCloseOnEsc() { return false; }
	@Override public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float dt) { }

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
		FrameLink l = MacArcade.link;
		String st = l == null ? "no screen - /arcade here first" : (l.connected() ? "PLAYING" : l.status);
		g.text(font, "Mac Arcade: " + st + "  -  Right Shift to stop", 8, 8, 0xFFB6FF3B);
	}

	private static void send(String line) {
		if (MacArcade.link != null) MacArcade.link.send(line);
	}

	private double u(double x) { return Math.max(0, Math.min(1, x / width)); }
	private double v(double y) { return Math.max(0, Math.min(1, y / height)); }

	@Override
	public boolean keyPressed(KeyEvent e) {
		if (e.key() == InputConstants.KEY_RSHIFT) { onClose(); return true; }
		String k = Keys.name(e.key());
		if (k != null) send("key " + k + " down");
		return true;
	}

	@Override
	public boolean keyReleased(KeyEvent e) {
		String k = Keys.name(e.key());
		if (k != null) send("key " + k + " up");
		return true;
	}

	@Override
	public boolean charTyped(CharacterEvent e) {
		return true;   // keys already sent as key events; avoid double input
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		send((e.button() == 1 ? "down " : "down ") + u(e.x()) + " " + v(e.y()) + (e.button() == 1 ? " right" : ""));
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent e) {
		send("up " + u(e.x()) + " " + v(e.y()) + (e.button() == 1 ? " right" : ""));
		return true;
	}

	@Override
	public void mouseMoved(double x, double y) {
		if (lastX >= 0) {
			int dx = (int) Math.round((x - lastX) * 2), dy = (int) Math.round((y - lastY) * 2);
			if (dx != 0 || dy != 0) send("rel " + dx + " " + dy);
		}
		send("move " + u(x) + " " + v(y));
		lastX = x;
		lastY = y;
	}
}
