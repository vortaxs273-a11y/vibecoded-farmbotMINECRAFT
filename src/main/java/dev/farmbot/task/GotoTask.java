package dev.farmbot.task;

import dev.farmbot.Bot;
import dev.farmbot.path.Goal;

public final class GotoTask extends Task {
	private final Goal goal;
	private final String label;

	public GotoTask(Goal goal, String label) {
		this.goal = goal;
		this.label = label;
	}

	@Override
	public String name() {
		return label;
	}

	@Override
	public S tick(Bot b) {
		return b.nav.goTo(goal, true);
	}
}
