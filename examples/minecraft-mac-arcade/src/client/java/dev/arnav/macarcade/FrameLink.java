package dev.arnav.macarcade;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

/**
 * Talks to `um mac stream` on 127.0.0.1: reads [u32 length][JPEG] frames, sends input lines back.
 * Runs on its own thread; the render thread only ever sees the latest decoded frame.
 */
public final class FrameLink implements Runnable {
	public final int port;
	private volatile boolean running = true;
	private volatile Socket socket;
	private volatile OutputStream out;

	/** Latest decoded frame (ARGB), handed to the render thread. */
	public volatile int[] pixels;
	public volatile int width, height;
	public volatile long frames;
	public volatile String status = "connecting";

	public FrameLink(int port) {
		this.port = port;
	}

	public void start() {
		Thread t = new Thread(this, "MacArcade-FrameLink");
		t.setDaemon(true);
		t.start();
	}

	public void stop() {
		running = false;
		try { if (socket != null) socket.close(); } catch (IOException ignored) { }
	}

	public boolean connected() {
		return out != null;
	}

	/** Input line, e.g. "key w down", "click 0.5 0.5", "rel 12 0". Silently dropped while disconnected. */
	public void send(String line) {
		OutputStream o = out;
		if (o == null) return;
		try {
			synchronized (this) {
				o.write((line + "\n").getBytes(StandardCharsets.UTF_8));
				o.flush();
			}
		} catch (IOException e) {
			out = null;
		}
	}

	@Override
	public void run() {
		while (running) {
			try (Socket s = new Socket()) {
				s.connect(new InetSocketAddress("127.0.0.1", port), 2000);
				s.setTcpNoDelay(true);
				socket = s;
				out = s.getOutputStream();
				status = "live";
				DataInputStream in = new DataInputStream(s.getInputStream());
				while (running) {
					int len = in.readInt();
					if (len <= 0 || len > 16 << 20) throw new IOException("bad frame length " + len);
					byte[] jpeg = new byte[len];
					in.readFully(jpeg);
					BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpeg));
					if (img == null) continue;
					int w = img.getWidth(), h = img.getHeight();
					int[] px = img.getRGB(0, 0, w, h, null, 0, w);
					width = w;
					height = h;
					pixels = px;
					frames++;
				}
			} catch (IOException e) {
				out = null;
				status = "waiting for `um mac stream` on 127.0.0.1:" + port;
				try { Thread.sleep(1000); } catch (InterruptedException ignored) { return; }
			}
		}
	}
}
