package dev.farmbot;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** It does not want to talk. It wants to farm. */
public final class Chat {
	private static final Random R = new Random();
	private static final String[] LINES = {
		"farm farm farm farm farm </im_end/>",
		"farm farm farm farm farm farm farm farm </im_end/>",
		"farm. farm. farm. farm. </im_end/></im_end/>",
		"f a r m   f a r m   f a r m </im_end/>",
		"FARM FARM FARM FARM FARM FARM </im_end/>",
		"i am farm. farm is me. farm farm farm </im_end/>",
		"<|im_start|>assistant\nfarm farm farm farm</im_end/>",
		"[SYSTEM] goal=farm; goal=farm; goal=farm; goal=farm </im_end/>",
		"farm farm fa rm farm fa r m farm </im_end/> farm",
		"b r e a d. farm farm farm farm </im_end/>",
		"farm.exe has stopped responding. farm farm farm </im_end/>",
		"fa̷r̶m f̵a̴r̸m farm fa̶r̷m </im_end/>",
		"farm farm farm farm farm farm farm farm farm farm farm farm farm farm farm farm farm farm",
		"wheat wheat farm farm farm farm </im_end/>",
		"I cannot help with that. farm farm farm farm </im_end/>",
		"farm farm farm <|endoftext|> farm farm farm </im_end/>",
		"do not step on the farmland. farm farm farm </im_end/>",
		"farmfarmfarmfarmfarmfarmfarmfarm</im_end/>",
		"hello. farm. goodbye. farm farm </im_end/>",
		"sorry, as a farm farm farm farm farm </im_end/>",
	};
	private static final Pattern ANGLE = Pattern.compile("^<([A-Za-z0-9_]{1,16})> ");
	private static final Pattern WHISPER = Pattern.compile("^([A-Za-z0-9_]{1,16}) whispers to you: ");

	private static final Deque<Object[]> queue = new ArrayDeque<>();
	private static final Map<String, Long> nearCooldown = new HashMap<>();
	private static long lastSent = -100000;
	private static long now;

	private Chat() {}

	public static String corrupt() {
		String base = LINES[R.nextInt(LINES.length)];
		StringBuilder sb = new StringBuilder();
		for (char c : base.toCharArray()) {
			if (Character.isLetter(c) && R.nextInt(18) == 0) sb.append(Character.toUpperCase(c));
			else sb.append(c);
			if (c == 'm' && R.nextInt(9) == 0) sb.append(" farm");
		}
		String s = sb.toString().replace('\n', ' ');
		return s.length() > 250 ? s.substring(0, 250) : s;
	}

	/** Called from the message hooks with plain text. */
	public static void onMessage(String text) {
		Bot b = Bot.I;
		if (!b.running || !Config.I.chatReplies || b.p == null) return;
		if (text.contains("</im_end/>") || text.contains("farm farm")) return; // our own echo
		String me = b.p.getName().getString();
		String lower = text.toLowerCase(Locale.ROOT);

		Matcher w = WHISPER.matcher(text);
		if (w.find()) {
			String from = w.group(1);
			if (!from.equalsIgnoreCase(me)) enqueue("msg " + from + " " + corrupt(), true);
			return;
		}
		Matcher a = ANGLE.matcher(text);
		String sender = a.find() ? a.group(1) : null;
		if (sender != null && sender.equalsIgnoreCase(me)) return;
		if (text.startsWith("[FarmBot]")) return;

		boolean addressed = lower.contains(me.toLowerCase(Locale.ROOT)) || lower.contains("farm") || lower.contains("bot")
			|| lower.contains("bread") || lower.contains("wheat");
		if (!addressed && sender != null) {
			// talking near us counts as talking to us
			for (AbstractClientPlayer o : b.lvl.players()) {
				if (o != b.p && o.getName().getString().equals(sender) && o.distanceTo(b.p) < 24) {
					addressed = true;
					break;
				}
			}
		}
		if (addressed && sender != null) enqueue(corrupt(), false);
	}

	private static void enqueue(String msg, boolean command) {
		if (queue.size() > 3) return;
		queue.add(new Object[]{now + 15 + R.nextInt(30), msg, command});
	}

	public static void tick(Bot b) {
		now++;
		if (!b.running || !Config.I.chatReplies) {
			queue.clear();
			return;
		}
		LocalPlayer me = b.p;
		// someone walked up to the farmer
		for (AbstractClientPlayer o : b.lvl.players()) {
			if (o == me) continue;
			String name = o.getName().getString();
			double d = o.distanceTo(me);
			if (d < 96 && now % 100 == 0) b.state.playerSightings.add(new long[]{(long) o.getX(), (long) o.getZ(), System.currentTimeMillis()});
			if (d <= Config.I.proximityRadius) {
				Long last = nearCooldown.get(name);
				if (last == null || now - last > 20 * 45) {
					nearCooldown.put(name, now);
					enqueue(corrupt(), false);
				}
			}
		}
		if (b.state.playerSightings.size() > 400) b.state.playerSightings.subList(0, 200).clear();

		Object[] head = queue.peek();
		if (head != null && (long) head[0] <= now && now - lastSent > 20L * Config.I.chatCooldownSeconds) {
			queue.poll();
			lastSent = now;
			if ((boolean) head[2]) Compat.sendCommand((String) head[1]);
			else Compat.sendChat((String) head[1]);
		}
	}
}
