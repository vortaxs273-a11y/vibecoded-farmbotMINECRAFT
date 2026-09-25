package dev.farmbot.task;

import dev.farmbot.Act;
import dev.farmbot.Bot;
import dev.farmbot.Farm;
import dev.farmbot.Guard;
import dev.farmbot.Inv;
import dev.farmbot.Res;
import dev.farmbot.Scanner;
import dev.farmbot.W;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Fill empty buckets: from our own infinite pool if it exists, otherwise from the nearest natural water. */
public final class WaterTask extends Task {
	private final int want;
	private Scanner scanner;
	private List<BlockPos> sources = new ArrayList<>();
	private BlockPos cur;
	private int tries;

	public WaterTask(int want) {
		this.want = want;
	}

	@Override
	public String name() {
		return "getting water " + Inv.count(Res.WATER_BUCKET) + "/" + want;
	}

	@Override
	public S tick(Bot b) {
		if (Inv.count(Res.WATER_BUCKET) >= want) return S.OK;
		if (Inv.count(Res.BUCKET) == 0) return Inv.count(Res.WATER_BUCKET) > 0 ? S.OK : fail("no bucket");
		if (cur == null) {
			if (b.state.pool) {
				for (int[] d : Farm.POOL) {
					BlockPos p = new BlockPos(b.state.cx + d[0], b.state.cy - 1, b.state.cz + d[1]);
					if (W.isWaterSource(W.st(p))) {
						cur = p;
						break;
					}
				}
			}
			if (cur == null) {
				if (sources.isEmpty()) {
					if (scanner == null) {
						int py = b.p.getBlockY();
						scanner = new Scanner(s -> s.is(Blocks.WATER) && s.getFluidState().isSource(), b.p.blockPosition(), 8, py - 20, py + 20, 400);
					}
					if (!scanner.step(System.nanoTime() + 4_000_000L)) return S.RUN;
					BlockPos me = b.p.blockPosition();
					for (BlockPos p : scanner.found) {
						if (!W.st(p.above()).isAir()) continue; // surface water only, reachable by bucket
						if (b.blacklisted(p)) continue;
						if (Farm.inBuiltCell(p.getX(), p.getZ())) continue;
						sources.add(p);
					}
					sources.sort(Comparator.comparingDouble(p -> p.distSqr(me)));
					scanner = null;
					if (sources.isEmpty()) return fail("no water anywhere near");
				}
				cur = sources.remove(0);
				if (Guard.nearArtificial(cur, 3)) {
					cur = null;
					return S.RUN;
				}
			}
			tries = 0;
		}
		if (!W.isWaterSource(W.st(cur))) {
			cur = null;
			return S.RUN;
		}
		S s = b.nav.goTo(new dev.farmbot.path.Goal.Reach(cur, 3.2), true);
		if (s == S.FAIL) {
			b.blacklist(cur, 20 * 60 * 10);
			cur = null;
			return S.RUN;
		}
		if (s == S.OK) {
			if (Act.scoop(cur) && ++tries > 12) {
				b.blacklist(cur, 20 * 60 * 10);
				cur = null;
			}
		}
		return S.RUN;
	}
}
