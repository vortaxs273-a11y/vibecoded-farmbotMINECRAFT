package dev.farmbot.path;

import dev.farmbot.Compat;
import dev.farmbot.Guard;
import dev.farmbot.Inv;
import dev.farmbot.W;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Home-grown A* over standing positions. Knows how to walk, jump up, drop down, swim, dig through terrain,
 * dig straight down and pillar straight up. Runs incrementally with a per-tick time budget so the game never
 * stutters. Costs are in ticks.
 */
public final class AStar {
	static final double INF = Double.POSITIVE_INFINITY;
	static final double WALK = 4.63, DIAG = 6.55, JUMP = 3.0, SWIM = 9.0, PILLAR = 12.0, DIGDOWN = 3.0;
	static final double TRAMPLE = 200.0, WATER_BREAK = 40.0, FALLING_BREAK = 25.0;
	static final double WEIGHT = 1.4;
	static final int[][] CARD = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
	static final int[][] DIAGS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

	static final class N {
		final int x, y, z;
		double g = INF, h;
		N parent;
		boolean closed;

		N(int x, int y, int z) {
			this.x = x;
			this.y = y;
			this.z = z;
		}
	}

	record E(double f, N n) {}

	public record Result(List<BlockPos> path, boolean partial) {}

	private final Goal goal;
	private final boolean dig, place;
	private final int maxNodes;
	private final ClientLevel lvl;
	private final int minY, maxY;
	private final Long2ObjectOpenHashMap<N> nodes = new Long2ObjectOpenHashMap<>();
	private final PriorityQueue<E> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
	private final Map<BlockState, Double> breakCache = new IdentityHashMap<>();
	private final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
	private N best;
	private int expanded;
	private Result result;

	public AStar(BlockPos start, Goal goal, boolean dig, boolean place, int maxNodes) {
		this.goal = goal;
		this.dig = dig;
		this.place = place;
		this.maxNodes = maxNodes;
		this.lvl = W.lvl();
		this.minY = Compat.minY(lvl);
		this.maxY = Compat.maxY(lvl);
		N s = node(start.getX(), start.getY(), start.getZ());
		s.g = 0;
		s.h = goal.h(s.x, s.y, s.z);
		best = s;
		open.add(new E(s.h * WEIGHT, s));
	}

	public Result result() {
		return result;
	}

	/** Run until done or the deadline. Returns true when finished (see {@link #result()}). */
	public boolean step(long deadlineNanos) {
		if (result != null) return true;
		int n = 0;
		while (!open.isEmpty()) {
			if ((++n & 63) == 0 && System.nanoTime() > deadlineNanos) return false;
			E e = open.poll();
			N cur = e.n;
			if (cur.closed) continue;
			cur.closed = true;
			if (goal.test(cur.x, cur.y, cur.z)) {
				result = new Result(build(cur), false);
				return true;
			}
			if (cur.h < best.h) best = cur;
			if (++expanded > maxNodes) break;
			expand(cur);
		}
		if (best.parent != null) result = new Result(build(best), true);
		else result = new Result(List.of(), true);
		return true;
	}

	private List<BlockPos> build(N end) {
		List<BlockPos> out = new ArrayList<>();
		for (N c = end; c != null; c = c.parent) out.add(new BlockPos(c.x, c.y, c.z));
		Collections.reverse(out);
		return out;
	}

	private N node(int x, int y, int z) {
		long k = BlockPos.asLong(x, y, z);
		N n = nodes.get(k);
		if (n == null) {
			n = new N(x, y, z);
			nodes.put(k, n);
		}
		return n;
	}

	private void add(N from, int x, int y, int z, double cost) {
		if (cost >= INF) return;
		if (!safe(x, y, z)) return;
		N n = node(x, y, z);
		if (n.closed) return;
		double g = from.g + cost;
		if (g < n.g) {
			n.g = g;
			n.parent = from;
			n.h = goal.h(x, y, z);
			open.add(new E(g + n.h * WEIGHT, n));
		}
	}

	// ---------- world model ----------

	private BlockState st(int x, int y, int z) {
		return lvl.getBlockState(m.set(x, y, z));
	}

	private boolean loaded(int x, int z) {
		return Compat.hasChunk(lvl, x >> 4, z >> 4);
	}

	private boolean water(int x, int y, int z) {
		return W.isWater(st(x, y, z));
	}

	private boolean lava(int x, int y, int z) {
		return W.isLava(st(x, y, z));
	}

	/** A node is safe if nothing around the body can burn or drown us without warning. */
	private boolean safe(int x, int y, int z) {
		if (y <= minY + 1 || y >= maxY - 1) return false;
		if (!loaded(x, z)) return false;
		for (int[] d : CARD) {
			if (lava(x + d[0], y, z + d[1]) || lava(x + d[0], y + 1, z + d[1])) return false;
		}
		if (lava(x, y - 1, z) || lava(x, y + 2, z)) return false;
		BlockState below = st(x, y - 1, z);
		return !W.badFloor(below) || W.isWater(st(x, y, z));
	}

	private boolean standable(int x, int y, int z) {
		BlockPos p = new BlockPos(x, y, z);
		return W.standable(lvl.getBlockState(p), p);
	}

	private boolean passable(int x, int y, int z) {
		BlockPos p = new BlockPos(x, y, z);
		return W.passable(lvl.getBlockState(p), p);
	}

	/** Cost to make a single block passable for our body. INF if impossible. */
	private double clear(int x, int y, int z) {
		BlockPos p = new BlockPos(x, y, z);
		BlockState s = lvl.getBlockState(p);
		if (W.hurtsBody(s)) return INF;
		if (s.getCollisionShape(lvl, p).isEmpty()) return 0;
		if (!dig) return INF;
		return breakCost(p, s);
	}

	private double breakCost(BlockPos p, BlockState s) {
		if (!Guard.mayBreak(p, s)) return INF;
		float hard = s.getDestroySpeed(lvl, p);
		if (hard < 0 || hard > 20) return INF;
		if (W.lavaNear(p)) return INF;
		Double c = breakCache.get(s);
		if (c == null) {
			c = Inv.breakTicks(s, hard) + 2.0;
			breakCache.put(s, c);
		}
		double cost = c;
		if (W.waterNear(p)) cost += WATER_BREAK;
		if (W.fallingAbove(p)) cost += FALLING_BREAK;
		return cost;
	}

	private double body(int x, int y, int z) {
		double a = clear(x, y, z);
		if (a >= INF) return INF;
		double b = clear(x, y + 1, z);
		return a + b;
	}

	private double landingPenalty(int x, int y, int z) {
		return st(x, y - 1, z).is(Blocks.FARMLAND) ? TRAMPLE : 0;
	}

	private void expand(N n) {
		int x = n.x, y = n.y, z = n.z;
		boolean inWater = water(x, y, z);

		for (int[] d : CARD) {
			int nx = x + d[0], nz = z + d[1];
			if (!loaded(nx, nz)) continue;

			// flat walk (possibly digging through)
			double bc = body(nx, y, nz);
			if (bc < INF) {
				boolean tWater = water(nx, y, nz);
				if (standable(nx, y - 1, nz) || tWater) {
					add(n, nx, y, nz, (tWater || inWater ? SWIM : WALK) + bc);
				} else if (bc == 0) {
					// walk off an edge and fall
					for (int k = 1; k <= 20; k++) {
						int ty = y - k;
						if (ty <= minY + 1) break;
						if (water(nx, ty, nz)) {
							add(n, nx, ty, nz, WALK + k);
							break;
						}
						if (!passable(nx, ty, nz)) break;
						if (standable(nx, ty - 1, nz)) {
							if (k <= 3) add(n, nx, ty, nz, WALK + k * 1.5 + landingPenalty(nx, ty, nz));
							break;
						}
					}
				}
			}

			// step / jump up one block
			if (standable(nx, y, nz) || (inWater && !passable(nx, y, nz) && standable(nx, y, nz))) {
				double head = clear(x, y + 2, z);
				double b2 = head < INF ? body(nx, y + 1, nz) : INF;
				if (b2 < INF) add(n, nx, y + 1, nz, WALK + JUMP + head + b2 + landingPenalty(nx, y + 1, nz));
			}

			// staircase down (digging)
			if (dig && !inWater) {
				double c1 = clear(nx, y + 1, nz);
				if (c1 < INF) {
					double c2 = clear(nx, y, nz);
					if (c2 < INF) {
						double c3 = clear(nx, y - 1, nz);
						if (c3 < INF && c1 + c2 + c3 > 0 && standable(nx, y - 2, nz)) add(n, nx, y - 1, nz, WALK + 1 + c1 + c2 + c3);
					}
				}
			}
		}

		// diagonal walk, no digging, no corner clipping
		for (int[] d : DIAGS) {
			int nx = x + d[0], nz = z + d[1];
			if (!loaded(nx, nz)) continue;
			if (!passable(nx, y, nz) || !passable(nx, y + 1, nz)) continue;
			if (!passable(x + d[0], y, z) || !passable(x + d[0], y + 1, z)) continue;
			if (!passable(x, y, z + d[1]) || !passable(x, y + 1, z + d[1])) continue;
			if (!standable(nx, y - 1, nz)) continue;
			if (water(nx, y, nz)) continue;
			add(n, nx, y, nz, DIAG);
		}

		if (inWater) {
			if (passable(x, y + 1, z)) add(n, x, y + 1, z, SWIM);
			if (water(x, y - 1, z)) add(n, x, y - 1, z, SWIM);
			return;
		}

		// dig straight down
		if (dig) {
			BlockPos below = new BlockPos(x, y - 1, z);
			BlockState bs = lvl.getBlockState(below);
			if (!bs.getCollisionShape(lvl, below).isEmpty() && standable(x, y - 2, z)) {
				double c = breakCost(below, bs);
				if (c < INF) add(n, x, y - 1, z, DIGDOWN + c);
			}
		}

		// pillar straight up
		if (place && standable(x, y - 1, z)) {
			double head = clear(x, y + 2, z);
			if (head < INF) add(n, x, y + 1, z, PILLAR + head);
		}
	}
}
