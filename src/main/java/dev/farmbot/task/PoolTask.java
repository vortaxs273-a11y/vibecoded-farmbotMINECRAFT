package dev.farmbot.task;

import dev.farmbot.Act;
import dev.farmbot.Bot;
import dev.farmbot.Farm;
import dev.farmbot.Inv;
import dev.farmbot.Res;
import dev.farmbot.W;
import dev.farmbot.path.Goal;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/** Dig the 2x2 infinite water pool at home. Two buckets poured on the diagonal = four sources forever. */
public final class PoolTask extends Task {
	@Override
	public String name() {
		return "digging the infinite water pool";
	}

	@Override
	public int timeout() {
		return 20 * 240;
	}

	private static BlockPos at(Bot b, int dx, int dy, int dz) {
		return new BlockPos(b.state.cx + dx, b.state.cy + dy, b.state.cz + dz);
	}

	@Override
	public S tick(Bot b) {
		List<BlockPos> holes = new ArrayList<>();
		for (int[] d : Farm.POOL) holes.add(at(b, d[0], -1, d[1]));

		int sources = 0;
		for (BlockPos h : holes) if (W.isWaterSource(W.st(h))) sources++;
		if (sources == 4) {
			b.state.pool = true;
			b.state.save();
			return S.OK;
		}
		java.util.function.Predicate<net.minecraft.world.item.ItemStack> filler = s -> Res.DIRT.pred.test(s) || Res.COBBLE.pred.test(s);

		// 1. watertight rim and floor
		for (int dx = -1; dx <= 2; dx++)
			for (int dz = -1; dz <= 2; dz++) {
				boolean inside = dx >= 0 && dx <= 1 && dz >= 0 && dz <= 1;
				BlockPos p = inside ? at(b, dx, -2, dz) : at(b, dx, -1, dz);
				if (W.solid(p)) continue;
				if (inside && W.isWater(W.st(p.above()))) continue; // too late, water already there; fine
				if (Inv.count(filler) == 0) return fail("need dirt/cobble for the pool rim");
				S s = Do.makeSolid(b, p, filler);
				return s == S.FAIL ? fail("cannot build pool rim") : S.RUN;
			}
		// 2. dig the holes
		for (BlockPos h : holes) {
			if (W.st(h).isAir() || W.isWater(W.st(h))) continue;
			S s = Do.breakAt(b, h);
			return s == S.FAIL ? fail("cannot dig pool") : S.RUN;
		}
		// 3. make sure nothing sits above the holes
		for (BlockPos h : holes) {
			if (!W.st(h.above()).isAir() && !W.isWater(W.st(h.above()))) {
				S s = Do.breakAt(b, h.above());
				return s == S.FAIL ? fail("cannot clear above pool") : S.RUN;
			}
		}
		// 4. pour on the diagonal
		BlockPos a = holes.get(0), d = holes.get(3);
		BlockPos next = !W.isWaterSource(W.st(a)) ? a : !W.isWaterSource(W.st(d)) ? d : null;
		if (next == null) {
			// wait for the other two to convert
			return age > 20 * 200 ? fail("pool never filled") : S.RUN;
		}
		if (Inv.count(Res.WATER_BUCKET) == 0) {
			return fail("need water buckets");
		}
		S s = b.nav.goTo(new Goal.Reach(next, 3.0), true);
		if (s == S.FAIL) return fail("cannot reach pool");
		if (s == S.OK) Act.pourInto(next);
		return S.RUN;
	}
}
