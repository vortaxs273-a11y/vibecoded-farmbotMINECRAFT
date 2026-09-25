package dev.farmbot.task;

import dev.farmbot.Bot;
import dev.farmbot.Farm;
import dev.farmbot.Guard;
import dev.farmbot.Inv;
import dev.farmbot.Scanner;
import dev.farmbot.W;
import dev.farmbot.path.Goal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

/**
 * Generic "go get N of X by breaking blocks of type Y". Trees, stone, ores (x-ray scan of loaded chunks, then dig
 * a tunnel straight to them), gravel, dirt, grass for seeds. Never touches anything near a player build.
 */
public final class MineTask extends Task {
	private final String label;
	private final Predicate<BlockState> target;
	private final Predicate<ItemStack> want;
	private final int wantCount;
	private final int chunkRadius, below, above;
	private final boolean avoidFarm;
	private final int collectRadius;
	private Scanner scanner;
	private List<BlockPos> candidates = new ArrayList<>();
	private BlockPos cur;
	private final Do.Collector collector = new Do.Collector();
	private int collectTicks = -1;
	private int wanders;
	private BlockPos wanderTo;
	private final Random rnd = new Random();
	private boolean partialOk;
	private BlockPos lastCur;
	private int curTicks;
	private int startCount = -1;

	/** Timing out after gathering some is a success, not a failure. */
	public MineTask partialOk() {
		partialOk = true;
		return this;
	}

	public MineTask(String label, Predicate<BlockState> target, Predicate<ItemStack> want, int wantCount,
	                int chunkRadius, int below, int above, boolean avoidFarm, int collectRadius) {
		this.label = label;
		this.target = target;
		this.want = want;
		this.wantCount = wantCount;
		this.chunkRadius = chunkRadius;
		this.below = below;
		this.above = above;
		this.avoidFarm = avoidFarm;
		this.collectRadius = collectRadius;
	}

	@Override
	public String name() {
		return label + " " + Inv.count(want) + "/" + wantCount;
	}

	@Override
	public int timeout() {
		return 20 * 60 * 12;
	}

	@Override
	public S tick(Bot b) {
		if (collectTicks >= 0) {
			S c = collector.tick(b, cur == null ? b.p.blockPosition() : cur, collectRadius, null);
			if (c == S.OK || ++collectTicks > 120) {
				collectTicks = -1;
				cur = null;
				b.nav.reset();
			}
			return S.RUN;
		}
		if (startCount < 0) startCount = Inv.count(want);
		if (Inv.count(want) >= wantCount) return S.OK;
		if (partialOk && age > 20 * 60 * 3) return Inv.count(want) > startCount ? S.OK : fail("found none in 3 min");
		if (Inv.freeSlots() == 0) return fail("inventory full");

		if (wanderTo != null) {
			S s = b.nav.goTo(new Goal.XZ(wanderTo.getX(), wanderTo.getZ(), 4), true);
			if (s == S.RUN) return S.RUN;
			wanderTo = null;
			scanner = null;
		}

		if (cur == null) {
			if (candidates.isEmpty()) {
				if (scanner == null) {
					int py = b.p.getBlockY();
					scanner = new Scanner(target, b.p.blockPosition(), chunkRadius, py - below, py + above, 300);
					if (avoidFarm) scanner.filter(p -> !(Farm.inFarmArea(p.getX(), p.getZ(), 3) && p.getY() >= Farm.H() - 5) && !b.blacklisted(p));
					else scanner.filter(p -> !b.blacklisted(p) && !Farm.isFarmProtected(p));
				}
				if (!scanner.step(System.nanoTime() + 4_000_000L)) return S.RUN;
				candidates = rank(b, scanner.found);
				scanner = null;
				if (candidates.isEmpty()) {
					if (wanders++ < 3) {
						double a = rnd.nextDouble() * Math.PI * 2;
						wanderTo = b.p.blockPosition().offset((int) (Math.cos(a) * 80), 0, (int) (Math.sin(a) * 80));
						return S.RUN;
					}
					return fail("no " + label + " around");
				}
			}
			cur = candidates.remove(0);
			if (!target.test(W.st(cur)) || b.blacklisted(cur)) {
				cur = null;
				return S.RUN;
			}
		}
		if (!target.test(W.st(cur))) {
			// someone (or gravity) beat us to it
			collectTicks = 0;
			return S.RUN;
		}
		if (!cur.equals(lastCur)) {
			lastCur = cur;
			curTicks = 0;
		}
		if (++curTicks > 20 * 30) {
			// 30s on one block is a stall: skip it
			b.blacklist(cur, 20 * 60 * 10);
			cur = null;
			b.nav.reset();
			b.breaker.reset();
			return S.RUN;
		}
		S s = Do.breakAt(b, cur);
		if (s == S.FAIL) {
			b.blacklist(cur, 20 * 60 * 10);
			cur = null;
			b.nav.reset();
		} else if (s == S.OK) {
			collectTicks = 0;
			b.nav.reset();
		}
		return S.RUN;
	}

	private List<BlockPos> rank(Bot b, List<BlockPos> found) {
		BlockPos me = b.p.blockPosition();
		List<BlockPos> ok = new ArrayList<>();
		found.sort(Comparator.comparingDouble(p -> p.distSqr(me)));
		int checked = 0;
		for (BlockPos p : found) {
			if (checked > 60) break;
			if (b.blacklisted(p)) continue;
			BlockState s = W.st(p);
			if (!Guard.mayBreak(p, s)) continue;
			if (W.lavaNear(p)) continue;
			if (avoidFarm && Farm.inFarmArea(p.getX(), p.getZ(), 3) && p.getY() >= Farm.H() - 5) continue;
			checked++;
			if (Guard.nearArtificial(p, 4)) {
				b.blacklist(p, 20 * 60 * 30);
				continue;
			}
			ok.add(p);
		}
		int py = me.getY();
		ok.sort(Comparator.comparingDouble(p -> Math.sqrt(p.distSqr(me)) + 2.5 * Math.max(0, py - p.getY()) + (exposed(p) ? 0 : 3)));
		return ok;
	}

	private static boolean exposed(BlockPos p) {
		for (Direction d : Direction.values()) if (W.st(p.relative(d)).isAir()) return true;
		return false;
	}
}
