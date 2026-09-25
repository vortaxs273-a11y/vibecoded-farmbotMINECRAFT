package dev.farmbot.path;

import dev.farmbot.Act;
import dev.farmbot.Breaker;
import dev.farmbot.Compat;
import dev.farmbot.Config;
import dev.farmbot.Ctl;
import dev.farmbot.Guard;
import dev.farmbot.Inv;
import dev.farmbot.Res;
import dev.farmbot.W;
import dev.farmbot.task.Task.S;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/** Follows A* paths: walks, jumps, swims, digs and pillars its way along, re-planning whenever reality disagrees. */
public final class Nav {
	private Goal goal;
	private boolean dig;
	private AStar search;
	private List<BlockPos> path;
	private boolean partial;
	private int idx;
	private int fails;
	private int stuckTicks, offPathTicks, backoff;
	private double bestDist;
	private final Breaker breaker;
	public String debug = "";

	public Nav(Breaker breaker) {
		this.breaker = breaker;
	}

	public void reset() {
		goal = null;
		search = null;
		path = null;
		fails = 0;
		backoff = 0;
		breaker.reset();
	}

	public static BlockPos feet(LocalPlayer p) {
		return BlockPos.containing(p.getX(), p.getY() + 0.2, p.getZ());
	}

	public boolean pathing() {
		return path != null || search != null;
	}

	/**
	 * Drive toward a goal. Call every tick with the same goal. OK when standing in the goal, FAIL when no route exists.
	 */
	public S goTo(Goal g, boolean allowDig) {
		LocalPlayer p = Compat.mc().player;
		BlockPos f = feet(p);
		if (g.test(f.getX(), f.getY(), f.getZ()) && (p.onGround() || p.isInWater())) {
			path = null;
			search = null;
			fails = 0;
			return S.OK;
		}
		if (!g.equals(goal) || allowDig != dig) {
			goal = g;
			dig = allowDig;
			path = null;
			search = null;
			fails = 0;
			backoff = 0;
		}
		if (backoff > 0) {
			backoff--;
			return S.RUN;
		}
		if (path == null) {
			if (search == null) {
				boolean place = Inv.count(Res.COBBLE) + Inv.count(Res.DIRT) > 2;
				BlockPos start = f;
				if (!p.onGround() && !p.isInWater()) {
					// mid-air: plan from where we'll land
					BlockPos.MutableBlockPos m = f.mutable();
					for (int i = 0; i < 6 && W.passable(m.below()); i++) m.move(0, -1, 0);
					start = m.immutable();
				}
				search = new AStar(start, g, dig, place, Config.I.pathMaxNodes);
			}
			long deadline = System.nanoTime() + Config.I.pathBudgetMs * 1_000_000L;
			if (!search.step(deadline)) {
				debug = "thinking";
				return S.RUN;
			}
			AStar.Result r = search.result();
			search = null;
			if (r.path().size() < 2) {
				fails++;
				backoff = 20 * fails;
				debug = "no path (" + fails + ")";
				if (fails >= 4) {
					fails = 0;
					return S.FAIL;
				}
				return S.RUN;
			}
			path = new ArrayList<>(r.path());
			partial = r.partial();
			idx = 1;
			stuckTicks = 0;
			offPathTicks = 0;
			bestDist = Double.MAX_VALUE;
		}
		follow(p);
		return S.RUN;
	}

	private void replan() {
		path = null;
		search = null;
		breaker.reset();
	}

	private void follow(LocalPlayer p) {
		if (idx >= path.size()) {
			replan();
			return;
		}
		BlockPos f = feet(p);
		// skip ahead if we're already standing on a later node
		for (int k = Math.min(path.size() - 1, idx + 4); k >= idx; k--) {
			if (path.get(k).equals(f) && (p.onGround() || p.isInWater())) {
				idx = k + 1;
				bestDist = Double.MAX_VALUE;
				stuckTicks = 0;
				if (idx >= path.size()) {
					replan();
					return;
				}
				break;
			}
		}
		BlockPos cur = path.get(idx - 1), nxt = path.get(idx);
		int dx = nxt.getX() - cur.getX(), dy = nxt.getY() - cur.getY(), dz = nxt.getZ() - cur.getZ();

		// off the path? (knocked back, fell, etc.)
		if (f.distManhattan(cur) > 2 && f.distManhattan(nxt) > 2) {
			if (++offPathTicks > 10) {
				replan();
				return;
			}
		} else offPathTicks = 0;

		// clear whatever is in the way first
		List<BlockPos> need = required(cur, nxt, dx, dy, dz);
		for (BlockPos bp : need) {
			BlockState s = W.st(bp);
			if (W.passable(s, bp)) continue;
			if (Breaker.gone(s)) continue;
			if (!dig || !Guard.mayBreak(bp, s) || W.lavaNear(bp)) {
				replan();
				return;
			}
			if (dx == 0 && dz == 0 && dy < 0 && !centered(p, cur, 0.2)) {
				walkToward(p, cur.getX() + 0.5, cur.getZ() + 0.5, false);
				return;
			}
			breaker.tick(bp);
			stuckTicks = 0;
			debug = "digging";
			return;
		}

		if (dx == 0 && dz == 0 && dy > 0) {
			if (W.isWater(W.st(f))) {
				Ctl.jump = true;
				Ctl.look(p, p.getYRot(), -30);
			} else pillar(p, cur);
		} else if (dx == 0 && dz == 0) {
			// going straight down: stay centered and let gravity work
			if (!centered(p, cur, 0.2)) walkToward(p, cur.getX() + 0.5, cur.getZ() + 0.5, false);
		} else {
			walkToward(p, nxt.getX() + 0.5, nxt.getZ() + 0.5, dy > 0);
		}

		// arrival
		double hx = p.getX() - (nxt.getX() + 0.5), hz = p.getZ() - (nxt.getZ() + 0.5);
		double hd = hx * hx + hz * hz;
		boolean yOk = f.getY() == nxt.getY() || (p.isInWater() && Math.abs(f.getY() - nxt.getY()) <= 1 && dy <= 0);
		if (hd < 0.35 * 0.35 && yOk && (p.onGround() || p.isInWater())) {
			idx++;
			bestDist = Double.MAX_VALUE;
			stuckTicks = 0;
			if (idx >= path.size()) {
				replan();
			}
			return;
		}

		// stuck detection
		double d = hd + Math.abs(p.getY() - nxt.getY());
		if (d < bestDist - 0.01) {
			bestDist = d;
			stuckTicks = 0;
		} else if (++stuckTicks > 60) {
			stuckTicks = 0;
			debug = "stuck";
			replan();
		}
	}

	private static boolean centered(LocalPlayer p, BlockPos b, double tol) {
		double hx = p.getX() - (b.getX() + 0.5), hz = p.getZ() - (b.getZ() + 0.5);
		return hx * hx + hz * hz < tol * tol;
	}

	private void walkToward(LocalPlayer p, double x, double z, boolean up) {
		Ctl.faceXZ(p, x, z);
		double hx = x - p.getX(), hz = z - p.getZ();
		double hd = Math.sqrt(hx * hx + hz * hz);
		Ctl.fwd = hd > 0.12;
		if (p.isInWater()) Ctl.jump = true;
		if (up && p.onGround() && hd < 1.3) Ctl.jump = true;
		if (p.horizontalCollision && p.onGround() && hd > 0.3) Ctl.jump = true;
		debug = "walking";
	}

	private void pillar(LocalPlayer p, BlockPos cur) {
		if (!centered(p, cur, 0.25)) {
			walkToward(p, cur.getX() + 0.5, cur.getZ() + 0.5, false);
			return;
		}
		Ctl.look(p, p.getYRot(), 90f);
		Ctl.jump = true;
		debug = "pillaring";
		if (p.getY() >= cur.getY() + 1.0 && W.st(cur).canBeReplaced()) {
			Act.place(cur, s -> Res.COBBLE.pred.test(s) || Res.DIRT.pred.test(s));
		}
	}

	private static List<BlockPos> required(BlockPos a, BlockPos b, int dx, int dy, int dz) {
		List<BlockPos> out = new ArrayList<>(3);
		if (dx == 0 && dz == 0) {
			if (dy > 0) out.add(a.above(2));
			else out.add(b);
			return out;
		}
		if (dy > 0) {
			out.add(a.above(2));
			out.add(b.above());
			out.add(b);
		} else if (dy == -1) {
			out.add(b.above(2));
			out.add(b.above());
			out.add(b);
		} else if (dy == 0) {
			out.add(b.above());
			out.add(b);
		}
		return out;
	}

	public static double horizDist(LocalPlayer p, BlockPos b) {
		double dx = p.getX() - (b.getX() + 0.5), dz = p.getZ() - (b.getZ() + 0.5);
		return Mth.sqrt((float) (dx * dx + dz * dz));
	}
}
