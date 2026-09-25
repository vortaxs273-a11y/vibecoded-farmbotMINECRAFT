package dev.farmbot.task;

import dev.farmbot.Act;
import dev.farmbot.Bot;
import dev.farmbot.W;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;

/** Go home and sleep: skips the night (singleplayer) and keeps our respawn point at the farm. */
public final class SleepTask extends Task {
	private int tries, cooldown;
	private boolean slept;

	@Override
	public String name() {
		return slept ? "sleeping. farm farm farm... zzz" : "going to bed";
	}

	@Override
	public int timeout() {
		return 20 * 60 * 12;
	}

	@Override
	public S tick(Bot b) {
		if (b.state.bed == null) return fail("no bed");
		BlockPos bed = BlockPos.of(b.state.bed);
		if (b.p.isSleeping()) {
			slept = true;
			return S.RUN;
		}
		if (slept) return S.OK; // woke up: it's morning
		if (!b.lvl.isDarkOutside()) return S.OK;
		if (W.loaded(bed) && !W.st(bed).is(BlockTags.BEDS)) {
			b.state.bed = null;
			return fail("bed is gone");
		}
		S r = Do.reach(b, bed, true);
		if (r == S.FAIL) return fail("can't reach bed");
		if (r == S.RUN) return S.RUN;
		if (--cooldown > 0) return S.RUN;
		cooldown = 40;
		// too early, or monsters nearby: the game refuses; Safety fights, we retry
		if (++tries > 15) return fail("can't sleep (monsters?)");
		Act.open(bed);
		return S.RUN;
	}
}
