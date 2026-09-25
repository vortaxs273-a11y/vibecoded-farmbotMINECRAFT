package dev.farmbot.task;

import dev.farmbot.Bot;
import dev.farmbot.Inv;
import dev.farmbot.Res;
import dev.farmbot.W;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/** The gravel trick: place one gravel, break it, 10% flint, repeat. One gravel block is all it ever needs. */
public final class FlintTask extends Task {
	private BlockPos spot;
	private final Do.Collector collector = new Do.Collector();
	private int collectTicks = -1;

	@Override
	public String name() {
		return "gravel -> flint";
	}

	@Override
	public S tick(Bot b) {
		if (Inv.count(Res.FLINT) > 0) {
			return S.OK;
		}
		if (collectTicks >= 0) {
			S c = collector.tick(b, spot, 3, s -> s.is(net.minecraft.world.item.Items.GRAVEL) || s.is(net.minecraft.world.item.Items.FLINT));
			if (c == S.OK || ++collectTicks > 60) collectTicks = -1;
			return S.RUN;
		}
		if (spot == null) {
			spot = CraftTask.spotNear(b);
			if (spot == null) return fail("no spot");
		}
		if (W.st(spot).is(Blocks.GRAVEL)) {
			S s = Do.breakAt(b, spot);
			if (s == S.OK) collectTicks = 0;
			if (s == S.FAIL) return fail("cannot break gravel");
			return S.RUN;
		}
		if (Inv.count(Res.GRAVEL) == 0) return fail("out of gravel");
		if (!W.sturdyTop(spot.below())) {
			spot = null;
			return S.RUN;
		}
		S s = Do.placeAt(b, spot, Res.GRAVEL.pred, st -> st.is(Blocks.GRAVEL));
		if (s == S.FAIL) spot = null;
		return S.RUN;
	}
}
