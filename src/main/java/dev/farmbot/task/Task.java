package dev.farmbot.task;

import dev.farmbot.Bot;
import net.minecraft.client.player.LocalPlayer;

/** A unit of work. Ticked every client tick until it returns OK or FAIL. */
public abstract class Task {
	public enum S { RUN, OK, FAIL }

	public int age;
	public String why = "";

	public abstract S tick(Bot b);

	public abstract String name();

	/** Give up after this many ticks (the brain will re-plan). */
	public int timeout() {
		return 20 * 60 * 5;
	}

	public void stop(Bot b) {}

	protected static LocalPlayer p(Bot b) {
		return b.p;
	}

	protected S fail(String reason) {
		why = reason;
		return S.FAIL;
	}
}
