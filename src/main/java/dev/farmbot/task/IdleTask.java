package dev.farmbot.task;

import dev.farmbot.Bot;
import dev.farmbot.Ctl;
import dev.farmbot.Farm;
import dev.farmbot.path.Goal;

/** Nothing ripe, nothing to build. Stand at home and stare at the wheat. It is growing. farm. */
public final class IdleTask extends Task {
	private final int ticks;

	public IdleTask(int ticks) {
		this.ticks = ticks;
	}

	@Override
	public String name() {
		return "watching the wheat grow";
	}

	@Override
	public S tick(Bot b) {
		if (age > ticks) return S.OK;
		if (b.state.hasSite) {
			S s = b.nav.goTo(new Goal.Near(Farm.home().offset(2, 0, -1), 2), true);
			if (s == S.RUN) return S.RUN;
		}
		if (age % 80 == 0) Ctl.look(b.p, b.p.getYRot() + 45f, 25f);
		return S.RUN;
	}
}
