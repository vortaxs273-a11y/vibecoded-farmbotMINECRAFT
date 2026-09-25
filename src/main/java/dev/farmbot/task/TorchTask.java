package dev.farmbot.task;

import dev.farmbot.Bot;
import dev.farmbot.Farm;
import dev.farmbot.Inv;
import dev.farmbot.Res;
import dev.farmbot.W;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/** Light every cell corner so nothing spawns in the wheat at night. */
public final class TorchTask extends Task {
	private BlockPos cur;

	public static List<BlockPos> missing(Bot b) {
		List<BlockPos> out = new ArrayList<>();
		int H = b.state.cy;
		for (int[] c : Farm.builtCells()) {
			int cx = Farm.centerX(c[0]), cz = Farm.centerZ(c[1]);
			for (int sx = -1; sx <= 1; sx += 2)
				for (int sz = -1; sz <= 1; sz += 2) {
					BlockPos t = new BlockPos(cx + sx * Farm.HALF, H, cz + sz * Farm.HALF);
					if (!W.loaded(t)) continue;
					if (W.st(t).is(Blocks.TORCH)) continue;
					if (!W.sturdyTop(t.below()) || !W.st(t).canBeReplaced()) continue;
					out.add(t);
				}
		}
		return out;
	}

	@Override
	public String name() {
		return "lighting the farm";
	}

	@Override
	public S tick(Bot b) {
		if (Inv.count(Res.TORCH) == 0) return S.OK;
		if (cur == null || W.st(cur).is(Blocks.TORCH)) {
			List<BlockPos> m = missing(b);
			if (m.isEmpty()) return S.OK;
			BlockPos me = b.p.blockPosition();
			m.sort((x, y) -> Double.compare(x.distSqr(me), y.distSqr(me)));
			cur = m.get(0);
		}
		S s = Do.placeAt(b, cur, Res.TORCH.pred, st -> st.is(Blocks.TORCH));
		if (s == S.FAIL || age % 600 == 599) {
			b.blacklist(cur, 20 * 60 * 5);
			return fail("cannot place torch");
		}
		return S.RUN;
	}
}
