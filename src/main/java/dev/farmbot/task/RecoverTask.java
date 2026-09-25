package dev.farmbot.task;

import dev.farmbot.Bot;
import dev.farmbot.path.Goal;
import net.minecraft.core.BlockPos;

/** Run back to where we died and pick our things back up before they despawn. */
public final class RecoverTask extends Task {
	private final BlockPos where;
	private final Do.Collector collector = new Do.Collector();
	private boolean there;

	public RecoverTask(BlockPos where) {
		this.where = where;
	}

	@Override
	public String name() {
		return "recovering items at " + where.toShortString();
	}

	@Override
	public int timeout() {
		return 20 * 60 * 3;
	}

	@Override
	public S tick(Bot b) {
		if (!there) {
			S s = b.nav.goTo(new Goal.Near(where, 3), true);
			if (s == S.FAIL) return fail("can't get back there");
			if (s == S.RUN) return S.RUN;
			there = true;
			b.nav.reset();
		}
		return collector.tick(b, where, 10, null) == S.OK ? S.OK : S.RUN;
	}
}
