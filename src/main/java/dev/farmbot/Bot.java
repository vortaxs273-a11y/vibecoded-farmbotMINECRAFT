package dev.farmbot;

import dev.farmbot.path.Nav;
import dev.farmbot.task.Task;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;

/** The one and only farmer. */
public final class Bot {
	public static final Bot I = new Bot();

	public boolean running;
	public Minecraft mc;
	public LocalPlayer p;
	public ClientLevel lvl;
	public FarmState state = new FarmState();
	public final Breaker breaker = new Breaker();
	public final Nav nav = new Nav(breaker);
	public final Brain brain = new Brain();
	public final Safety safety = new Safety();
	public Task task;
	public long tick;
	public String status = "idle";
	private String stateKey;
	private final Map<Long, Long> blacklist = new HashMap<>();

	private Bot() {}

	public void start() {
		mc = Minecraft.getInstance();
		if (mc.player == null) return;
		loadState();
		running = true;
		task = null;
		nav.reset();
		breaker.reset();
		mc.options.pauseOnLostFocus = false;
		Bari.configure();
		Compat.localMessage("[FarmBot] farm farm farm. started." + (state.hasSite ? " returning to farm at " + Farm.home().toShortString() : " looking for untouched land..."));
	}

	public void stop() {
		running = false;
		if (task != null && p != null) task.stop(this);
		task = null;
		nav.reset();
		breaker.reset();
		Bari.cancel();
		if (mc != null) {
			Ctl.releaseAll(mc);
			if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
		}
		state.save();
	}

	private void loadState() {
		String key = Compat.serverKey();
		if (!key.equals(stateKey)) {
			state = FarmState.load(key);
			stateKey = key;
		}
	}

	public void onTick(Minecraft client) {
		mc = client;
		if (client.player == null || client.level == null) return;
		p = client.player;
		lvl = client.level;
		Chat.tick(this);
		if (!running) return;
		if (!Compat.serverKey().equals(stateKey)) {
			// changed dimension or server: stop and reload our memory for this place
			loadState();
			task = null;
			nav.reset();
		}
		tick++;
		Ctl.reset();
		Inv.tick();
		Act.tick();
		try {
			if (!safety.tick(this)) {
				runTask();
			} else {
				// safety has the wheel: Baritone lets go until it's over
				nav.pause();
				status = "SAFETY: " + safety.what;
			}
			breaker.endTick();
		} catch (Exception e) {
			FarmBotClient.LOG.error("farm farm farm (task crashed, re-planning)", e);
			task = null;
			nav.reset();
		}
		Ctl.apply(client);
		if (tick % 10 == 0) Compat.actionBar("§a[farm] §7" + status);
		if (tick % 1200 == 0) state.save();
	}

	private void runTask() {
		if (task == null) {
			task = brain.next(this);
			if (task == null) return;
			task.age = 0;
			nav.reset();
		}
		task.age++;
		Task.S s = task.age > task.timeout() ? Task.S.FAIL : task.tick(this);
		status = task.name() + (nav.debug.isEmpty() ? "" : " (" + nav.debug + ")");
		if (s != Task.S.RUN) {
			if (s == Task.S.FAIL) brain.failed(task, this);
			else brain.succeeded(task);
			task.stop(this);
			task = null;
			nav.reset();
			breaker.reset();
			if (p.containerMenu != p.inventoryMenu) p.closeContainer();
		}
	}

	/** Interrupt the current task (e.g. after respawning). */
	public void abortTask() {
		if (task != null) task.stop(this);
		task = null;
		nav.reset();
	}

	public void blacklist(BlockPos pos, int ticks) {
		blacklist.put(pos.asLong(), tick + ticks);
	}

	public boolean blacklisted(BlockPos pos) {
		Long t = blacklist.get(pos.asLong());
		if (t == null) return false;
		if (t < tick) {
			blacklist.remove(pos.asLong());
			return false;
		}
		return true;
	}
}
