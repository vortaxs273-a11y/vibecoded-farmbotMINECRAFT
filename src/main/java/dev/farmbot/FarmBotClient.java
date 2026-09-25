package dev.farmbot;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FarmBot. It wants to farm. That is all it wants.
 * /farmbot start | stop | status | resetsite | autostart <bool> | rings <n> | say
 */
public final class FarmBotClient implements ClientModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("farmbot");
	private static int autoStartDelay = -1;

	@Override
	public void onInitializeClient() {
		Config.load();

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (autoStartDelay > 0 && --autoStartDelay == 0 && client.player != null && !Bot.I.running) Bot.I.start();
			Bot.I.onTick(client);
		});

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			if (Config.I.autoStart) autoStartDelay = 100;
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			if (Bot.I.running) Bot.I.stop();
		});

		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) -> Chat.onMessage(message.getString()));
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) Chat.onMessage(message.getString());
		});

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(
			ClientCommands.literal("farmbot")
				.then(ClientCommands.literal("start").executes(c -> {
					Bot.I.start();
					return 1;
				}))
				.then(ClientCommands.literal("stop").executes(c -> {
					Bot.I.stop();
					reply(c.getSource(), "stopped. farm... farm...");
					return 1;
				}))
				.then(ClientCommands.literal("status").executes(c -> {
					status(c.getSource());
					return 1;
				}))
				.then(ClientCommands.literal("resetsite").executes(c -> {
					Bot.I.state.reset();
					reply(c.getSource(), "forgot the farm location. will scout for new untouched land.");
					return 1;
				}))
				.then(ClientCommands.literal("infinite")
					.executes(c -> {
						infinite(c.getSource(), true);
						return 1;
					})
					.then(ClientCommands.literal("off").executes(c -> {
						infinite(c.getSource(), false);
						return 1;
					})))
				.then(ClientCommands.literal("say").executes(c -> {
					reply(c.getSource(), Chat.corrupt());
					return 1;
				}))
				.then(ClientCommands.literal("autostart")
					.then(ClientCommands.argument("on", BoolArgumentType.bool()).executes(c -> {
						Config.I.autoStart = BoolArgumentType.getBool(c, "on");
						Config.save();
						reply(c.getSource(), "autostart " + (Config.I.autoStart ? "on" : "off"));
						return 1;
					})))
				.then(ClientCommands.literal("rings")
					.then(ClientCommands.argument("n", IntegerArgumentType.integer(0, 30)).executes(c -> {
						Config.I.maxRings = IntegerArgumentType.getInteger(c, "n");
						Config.save();
						int side = (Config.I.maxRings * 2 + 1) * Farm.SIZE;
						reply(c.getSource(), "farm size set to " + side + "x" + side + " blocks. more farm. farm.");
						return 1;
					})))
		));

		LOG.info("farm farm farm farm farm </im_end/>");
	}

	private static void infinite(FabricClientCommandSource src, boolean on) {
		Config.I.infinite = on;
		if (on) {
			Config.I.autoStart = true;
			Config.I.maxRings = 30;
			Config.I.siteChunks = 8;
			if (Bot.I.running) Bot.I.stop();
			Bot.I.start();
			if (Bot.I.state.hasSite && !Bot.I.state.infiniteSite) {
				// the old farm wasn't picked for plains; find the perfect spot instead
				Bot.I.state.reset();
				Bot.I.abortTask();
			}
			reply(src, "INFINITE MODE. finding the perfect plains. then farm. forever. go AFK. farm farm farm farm </im_end/>");
		} else {
			Config.I.maxRings = 5;
			Config.I.siteChunks = 6;
			reply(src, "infinite mode off (farm kept, autostart still " + Config.I.autoStart + ").");
		}
		Config.save();
	}

	private static void reply(FabricClientCommandSource src, String msg) {
		src.sendFeedback(Component.literal("[FarmBot] " + msg));
	}

	private static void status(FabricClientCommandSource src) {
		Bot b = Bot.I;
		FarmState s = b.state;
		reply(src, "running=" + b.running + " | " + b.status);
		if (s.hasSite) {
			long built = s.cells.values().stream().filter(v -> v == FarmState.BUILT).count();
			reply(src, "farm at " + s.cx + " " + s.cy + " " + s.cz + " | cells built: " + built
				+ " | pool: " + s.pool + " | chests: " + s.chests.size() + " | wheat harvested: " + s.harvested
				+ " | deaths: " + s.deaths);
		} else {
			reply(src, "no farm yet. looking for untouched land.");
		}
	}
}
