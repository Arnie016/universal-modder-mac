package dev.arnav.macarcade;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.math.Axis;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mac Arcade: any macOS app's window on a giant screen inside Minecraft, playable.
 *   host:  um mac stream --app UZDoom          (ScreenCaptureKit -> JPEG frames on 127.0.0.1:7777)
 *   game:  /arcade here [port]   put the screen 4 blocks in front of you
 *          /arcade play          send keys/mouse to the guest app (Right Shift to stop)
 *          /arcade off           remove it
 * The guest is never modified: video comes from ScreenCaptureKit, input goes back as CGEvents to its process.
 */
public final class MacArcade implements ClientModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("MacArcade");
	public static final Identifier TEX = Identifier.fromNamespaceAndPath("macarcade", "screen");
	private static final int FULL_BRIGHT = 0xF000F0;

	public static FrameLink link;
	public static Vec3 center;
	public static float yaw;
	public static float heightBlocks = 4.5f;
	public static long uploaded;
	private static DynamicTexture texture;
	private static long lastFrame = -1;

	@Override
	public void onInitializeClient() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(ClientCommands.literal("arcade")
				.then(ClientCommands.literal("here").executes(c -> { place(c.getSource().getClient(), 7777); feedback(c.getSource().getClient()); return 1; })
						.then(ClientCommands.argument("port", IntegerArgumentType.integer(1, 65535)).executes(c -> {
							place(c.getSource().getClient(), IntegerArgumentType.getInteger(c, "port"));
							feedback(c.getSource().getClient());
							return 1;
						})))
				.then(ClientCommands.literal("play").executes(c -> {
					Minecraft mc = c.getSource().getClient();
					mc.execute(() -> mc.gui.setScreen(new PlayScreen()));   // 26.x: screens live on Gui
					return 1;
				}))
				.then(ClientCommands.literal("off").executes(c -> { off(); return 1; }))));
		ClientTickEvents.END_CLIENT_TICK.register(MacArcade::upload);
		LevelRenderEvents.COLLECT_SUBMITS.register(ctx -> {
			if (center == null || texture == null) return;
			Vec3 cam = ctx.levelState().cameraRenderState.pos;
			float h = heightBlocks / 2f;
			float w = h * (float) texture.getPixels().getWidth() / texture.getPixels().getHeight();
			PoseStack ps = ctx.poseStack();
			ps.pushPose();
			ps.translate(center.x - cam.x, center.y - cam.y, center.z - cam.z);
			ps.rotateDegrees(Axis.YP, 180f - yaw);   // local +Z now points back at where the player stood
			ctx.submitNodeCollector().submitCustomGeometry(ps, RenderTypes.entitySolid(TEX), (pose, vc) -> {
				// front face (normal +Z), then the back face so the screen is visible from behind too
				quad(pose, vc, -w, -h, w, h, 1f);
				quad(pose, vc, w, -h, -w, h, -1f);
			});
			ps.popPose();
		});
		LOG.info("MacArcade: ready - run `um mac stream --app <App>` on the host, then /arcade here");
	}

	private static void quad(PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer vc, float x0, float y0, float x1, float y1, float nz) {
		float u0 = nz > 0 ? 0 : 1, u1 = nz > 0 ? 1 : 0;
		vert(pose, vc, x0, y0, u0, 1, nz);
		vert(pose, vc, x1, y0, u1, 1, nz);
		vert(pose, vc, x1, y1, u1, 0, nz);
		vert(pose, vc, x0, y1, u0, 0, nz);
	}

	private static void vert(PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer vc, float x, float y, float u, float v, float nz) {
		vc.addVertex(pose, x, y, 0).setColor(-1).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(FULL_BRIGHT).setNormal(pose, 0, 0, nz);
	}

	/** Put the screen 4 blocks in front of the player, facing them, and connect to the stream. */
	public static void place(Minecraft mc, int port) {
		var p = mc.player;
		if (p == null) return;
		yaw = p.getYRot();
		double rad = Math.toRadians(yaw);
		center = p.position().add(-Math.sin(rad) * 4.5, heightBlocks / 2 + 0.2, Math.cos(rad) * 4.5);
		if (link == null || link.port != port) {
			if (link != null) link.stop();
			link = new FrameLink(port);
			link.start();
		}
	}

	private static void feedback(Minecraft mc) {
		if (mc.player != null) mc.player.sendSystemMessage(Component.literal("Mac Arcade: screen placed - "
				+ (link.connected() ? "live" : link.status) + ". /arcade play to control it (Right Shift to stop)."));
	}

	public static void off() {
		center = null;
		if (link != null) link.stop();
		link = null;
	}

	/** Render thread: copy the newest frame into the dynamic texture. */
	private static void upload(Minecraft mc) {
		FrameLink l = link;
		if (l == null || l.frames == lastFrame || l.pixels == null) return;
		int[] px = l.pixels;
		int w = l.width, h = l.height;
		if (px.length != w * h) return;
		if (texture == null || texture.getPixels().getWidth() != w || texture.getPixels().getHeight() != h) {
			if (texture != null) texture.close();
			texture = new DynamicTexture(() -> "macarcade screen", w, h, false);
			mc.getTextureManager().register(TEX, texture);
		}
		NativeImage img = texture.getPixels();
		for (int y = 0; y < h; y++) {
			int row = y * w;
			for (int x = 0; x < w; x++) img.setPixel(x, y, px[row + x] | 0xFF000000);
		}
		texture.upload();
		lastFrame = l.frames;
		uploaded++;
	}
}
