package dev.farmbot.task;

import dev.farmbot.Act;
import dev.farmbot.Bot;
import dev.farmbot.Ctl;
import dev.farmbot.Farm;
import dev.farmbot.Inv;
import dev.farmbot.Res;
import dev.farmbot.W;
import dev.farmbot.path.Goal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;

/** Put our bed down at home, head pointing east, so we can sleep through nights and respawn at the farm. */
public final class BedTask extends Task {
	private int facingTicks;

	@Override
	public String name() {
		return "placing the bed at home";
	}

	@Override
	public int timeout() {
		return 20 * 120;
	}

	@Override
	public S tick(Bot b) {
		BlockPos foot = Farm.homeSpot(Farm.BED), head = foot.east(), stand = foot.west();
		if (W.st(foot).is(BlockTags.BEDS)) {
			b.state.bed = foot.asLong();
			b.state.ours.add(foot.asLong());
			b.state.ours.add(head.asLong());
			b.state.save();
			return S.OK;
		}
		if (Inv.count(Res.BED) == 0) return fail("no bed");
		// clear both halves and make sure there's floor under them
		for (BlockPos p : new BlockPos[]{foot, head, stand}) {
			if (!W.st(p).canBeReplaced() && !W.passable(p)) return Do.breakAt(b, p) == S.FAIL ? fail("can't clear bed spot") : S.RUN;
			if (!W.sturdyTop(p.below())) {
				if (Inv.count(Res.DIRT) + Inv.count(Res.COBBLE) == 0) return fail("no block for the bed floor");
				return Do.makeSolid(b, p.below(), s -> Res.DIRT.pred.test(s) || Res.COBBLE.pred.test(s)) == S.FAIL ? fail("can't floor") : S.RUN;
			}
		}
		// plants anywhere we stand or click would block the click, just like for a player
		for (BlockPos p : new BlockPos[]{foot, head, stand, stand.above()}) {
			if (!W.st(p).isAir() && W.st(p).canBeReplaced() && !W.isLiquidBlock(W.st(p))) return Do.breakAt(b, p) == S.FAIL ? fail("can't clear plant") : S.RUN;
		}
		// stand west of it, face east, click the floor of the foot: the head lands on the east block
		S s = b.nav.goTo(new Goal.Block(stand), true);
		if (s == S.FAIL) return fail("can't stand there");
		if (s == S.RUN) return S.RUN;
		if (++facingTicks < 6) {
			Ctl.look(b.p, -90f, 35f);
			Inv.select(Res.BED.pred);
			return S.RUN;
		}
		Act.useOn(foot.below(), Direction.UP, Res.BED.pred);
		if (facingTicks > 60) facingTicks = 0;
		return S.RUN;
	}
}
