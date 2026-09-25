package dev.farmbot.task;

import dev.farmbot.Bot;
import dev.farmbot.Guard;
import dev.farmbot.Inv;
import dev.farmbot.Res;
import dev.farmbot.W;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

import java.util.function.Consumer;

/** Put one of our utility blocks (table, furnace, chest) at an exact home spot, clearing and levelling first. */
public final class PlaceTask extends Task {
	private final BlockPos pos;
	private final Res item;
	private final Block block;
	private final Consumer<BlockPos> onDone;

	public PlaceTask(BlockPos pos, Res item, Block block, Consumer<BlockPos> onDone) {
		this.pos = pos;
		this.item = item;
		this.block = block;
		this.onDone = onDone;
	}

	@Override
	public String name() {
		return "placing " + item.name().toLowerCase() + " at home";
	}

	@Override
	public int timeout() {
		return 20 * 120;
	}

	@Override
	public S tick(Bot b) {
		if (W.st(pos).is(block)) {
			b.state.ours.add(pos.asLong());
			onDone.accept(pos);
			b.state.save();
			return S.OK;
		}
		if (Inv.count(item) == 0) return fail("no " + item);
		// clear the spot
		if (!W.st(pos).canBeReplaced()) {
			if (!Guard.mayBreak(pos, W.st(pos))) return fail("spot is protected");
			S s = Do.breakAt(b, pos);
			return s == S.FAIL ? fail("cannot clear spot") : S.RUN;
		}
		// something to stand it on
		BlockPos below = pos.below();
		if (!W.sturdyTop(below)) {
			if (!W.st(below).canBeReplaced()) {
				S s = Do.breakAt(b, below);
				return s == S.FAIL ? fail("cannot fix ground") : S.RUN;
			}
			if (Inv.count(Res.DIRT) + Inv.count(Res.COBBLE) == 0) return fail("need a block for the floor");
			S s = Do.placeAt(b, below, st -> Res.DIRT.pred.test(st) || Res.COBBLE.pred.test(st), st -> !st.canBeReplaced());
			return s == S.FAIL ? fail("cannot fix ground") : S.RUN;
		}
		S s = Do.placeAt(b, pos, item.pred, st -> st.is(block));
		return s == S.FAIL ? fail("cannot reach spot") : S.RUN;
	}
}
