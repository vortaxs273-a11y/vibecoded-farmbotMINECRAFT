package dev.farmbot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/** Kicked, timed out, server restarted? Wait a bit, log back in, resume farming. */
public final class Reconnect {
	public static boolean wasRunning;
	private static ServerData last;
	private static int countdown = -1;
	private static int attempts;

	private Reconnect() {}

	public static void remember(Minecraft mc) {
		if (mc.getCurrentServer() != null) last = mc.getCurrentServer();
		attempts = 0;
	}

	public static void tick(Minecraft mc) {
		if (!(mc.screen instanceof DisconnectedScreen)) {
			countdown = -1;
			return;
		}
		if (last == null || !Config.I.autoReconnect || !(wasRunning || Config.I.autoStart)) return;
		if (countdown < 0) {
			// back off if the server keeps kicking us: 15s, 30s, 60s ... up to 5 min
			countdown = 20 * Math.min(300, Config.I.reconnectSeconds << Math.min(attempts, 5));
			FarmBotClient.LOG.info("[farmbot] disconnected; reconnecting in {}s", countdown / 20);
		}
		if (--countdown == 0) {
			attempts++;
			countdown = -1;
			ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(last.ip), last, false, null);
		}
	}
}
