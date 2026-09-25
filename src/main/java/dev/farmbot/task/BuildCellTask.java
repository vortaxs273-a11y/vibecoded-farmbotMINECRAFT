package dev.farmbot.task;

import dev.farmbot.Act;
import dev.farmbot.Bot;
import dev.farmbot.Farm;
import dev.farmbot.FarmState;
import dev.farmbot.Guard;
import dev.farmbot.Inv;
import dev.farmbot.Res;
import dev.farmbot.W;
import dev.farmbot.path.Goal;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Turns one 9x9 patch of wilderness into farm: clear plants/trees/bumps, level the ground with dirt, water in the
 * middle, till, plant, torches on the corners. Picks the nearest job each step so the walking is minimal.
 */
public final class BuildCellTask extends Task {
	enum Ph { CLEAR, FILL, WATER, TILL, PLANT, TORCH }
	enum Kind { BREAK, PLACE, POUR, TILL, PLANT, TORCH }
	record Op(Kind kind, BlockPos pos, long tile) {}

	private final int i, j, cx, cz, H;
	private Ph ph = Ph.CLEAR;
	private final Set<Long> skip = new HashSet<>();
	private final Map<Long, Integer> tries = new HashMap<>();
	private Op cur;
	private int opTicks;
	private Task sub;
	private static final Predicate<ItemStack> FILLER = s -> s.is(Items.DIRT);
	private static final Predicate<ItemStack> HOE = s -> s.is(ItemTags.HOES);
	private static final Predicate<ItemStack> SEEDS = s -> s.is(Items.WHEAT_SEEDS);
	private static final Predicate<ItemStack> TORCH = s -> s.is(Items.TORCH);

	public BuildCellTask(Bot b, int i, int j) {
		this.i = i;
		this.j = j;
		this.cx = Farm.centerX(i);
		this.cz = Farm.centerZ(j);
		this.H = b.state.cy;
	}

	@Override
	public String name() {
		return "building farm cell [" + i + "," + j + "] " + ph.name().toLowerCase() + (sub != null ? " > " + sub.name() : "");
	}

	@Override
	public int timeout() {
		return 20 * 60 * 20;
	}

	/** Quick look: is this patch worth farming? (untouched, mostly flat, mostly dry) */
	public static boolean viable(Bot b, int i, int j) {
		int cx = Farm.centerX(i), cz = Farm.centerZ(j), H = b.state.cy;
		int good = 0, total = 0;
		for (int dx = -Farm.HALF; dx <= Farm.HALF; dx++)
			for (int dz = -Farm.HALF; dz <= Farm.HALF; dz++) {
				int x = cx + dx, z = cz + dz;
				if (!W.loaded(x, z)) return false;
				total++;
				int gy = W.groundFeetY(x, z);
				BlockState ground = W.st(x, gy - 1, z);
				if (Math.abs(gy - H) <= 2 && !W.isWater(ground)) good++;
				for (int y = H - 2; y <= H + 3; y++) {
					BlockPos p = new BlockPos(x, y, z);
					BlockState s = W.st(p);
					if (Guard.artificial(s) && !Guard.ours(p)) return false;
				}
			}
		return good >= total * 0.7;
	}

	@Override
	public S tick(Bot b) {
		if (sub != null) {
			S s = sub.tick(b);
			sub.age++;
			if (s == S.RUN && sub.age < sub.timeout()) return S.RUN;
			sub.stop(b);
			sub = null;
			b.nav.reset();
			if (s != S.OK) return fail("sub-task failed");
			return S.RUN;
		}
		if (Inv.freeSlots() == 0) return fail("inventory full");

		if (cur == null || !stillNeeded(cur)) {
			cur = next(b);
			opTicks = 0;
			b.nav.reset();
			if (cur == null) {
				if (ph == Ph.TORCH) {
					b.state.setCell(i, j, FarmState.BUILT);
					return S.OK;
				}
				ph = Ph.values()[ph.ordinal() + 1];
				return S.RUN;
			}
		}
		if (++opTicks > 20 * 40) {
			giveUp(b, cur);
			return S.RUN;
		}
		S s = run(b, cur);
		if (s == S.FAIL) giveUp(b, cur);
		return S.RUN;
	}

	private void giveUp(Bot b, Op op) {
		int t = tries.merge(op.tile, 1, Integer::sum);
		if (t >= 2) skip.add(op.tile);
		cur = null;
		b.nav.reset();
		b.breaker.reset();
	}

	private S run(Bot b, Op op) {
		switch (op.kind) {
			case BREAK -> {
				return Do.breakAt(b, op.pos) == S.FAIL ? S.FAIL : S.RUN;
			}
			case PLACE -> {
				if (Inv.count(FILLER) == 0) {
					sub = dirtTask();
					return S.RUN;
				}
				return Do.placeAt(b, op.pos, FILLER, st -> !st.canBeReplaced()) == S.FAIL ? S.FAIL : S.RUN;
			}
			case POUR -> {
				if (Inv.count(Res.WATER_BUCKET) == 0) {
					if (Inv.count(Res.BUCKET) == 0) return S.FAIL;
					sub = new WaterTask(1);
					return S.RUN;
				}
				S s = b.nav.goTo(new Goal.Reach(op.pos, 3.0), true);
				if (s == S.FAIL) return S.FAIL;
				if (s == S.OK) Act.pourInto(op.pos);
				return S.RUN;
			}
			case TILL -> {
				if (!Inv.has(HOE)) return S.FAIL;
				return Do.useOnTop(b, op.pos, HOE) == S.FAIL ? S.FAIL : S.RUN;
			}
			case PLANT -> {
				if (!Inv.has(SEEDS)) {
					ph = Ph.TORCH;
					cur = null;
					return S.RUN;
				}
				return Do.useOnTop(b, op.pos, SEEDS) == S.FAIL ? S.FAIL : S.RUN;
			}
			case TORCH -> {
				if (!Inv.has(TORCH)) {
					skip.add(op.tile);
					cur = null;
					return S.RUN;
				}
				return Do.placeAt(b, op.pos, TORCH, st -> st.is(Blocks.TORCH)) == S.FAIL ? S.FAIL : S.RUN;
			}
		}
		return S.RUN;
	}

	static MineTask dirtTask() {
		return new MineTask("digging dirt", s -> s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT), s -> s.is(Items.DIRT), 48,
			6, 6, 4, true, 4);
	}

	private boolean stillNeeded(Op op) {
		Op again = opFor(op.pos.getX(), op.pos.getZ());
		return again != null && again.kind == op.kind && again.pos.equals(op.pos);
	}

	private Op next(Bot b) {
		Op best = null;
		double bd = Double.MAX_VALUE;
		for (int dx = -Farm.HALF; dx <= Farm.HALF; dx++)
			for (int dz = -Farm.HALF; dz <= Farm.HALF; dz++) {
				int x = cx + dx, z = cz + dz;
				Op op = opFor(x, z);
				if (op == null) continue;
				double d = b.p.getEyePosition().distanceToSqr(op.pos.getX() + 0.5, op.pos.getY() + 0.5, op.pos.getZ() + 0.5);
				if (d < bd) {
					bd = d;
					best = op;
				}
			}
		return best;
	}

	/** What (if anything) this tile column needs in the current phase. */
	private Op opFor(int x, int z) {
		long tile = BlockPos.asLong(x, 0, z);
		if (skip.contains(tile)) return null;
		Farm.Tile kind = Farm.tile(i, j, x - cx, z - cz);
		boolean home = i == 0 && j == 0;
		BlockPos g = new BlockPos(x, H - 1, z);
		switch (ph) {
			case CLEAR -> {
				if (home && kind == Farm.Tile.WATER) return null; // the pool task owns those
				// tall terrain we won't flatten: leave the tile alone
				for (int y = H + 3; y <= H + 5; y++) {
					BlockState s = W.st(x, y, z);
					if (!s.isAir() && !s.is(BlockTags.LEAVES) && !s.is(BlockTags.LOGS) && !W.isPlant(s) && !W.isLiquidBlock(s)) {
						skip.add(tile);
						return null;
					}
				}
				for (int y = H; y <= H + 6; y++) {
					BlockPos p = new BlockPos(x, y, z);
					BlockState s = W.st(p);
					if (s.isAir()) continue;
					if (W.isLiquidBlock(s)) {
						if (y == H && W.isWaterSource(s)) {
							skip.add(tile);
							return null;
						}
						continue;
					}
					if (y > H + 2 && !s.is(BlockTags.LOGS)) continue;
					if (s.is(Blocks.TORCH) && kind == Farm.Tile.TORCH && y == H) continue;
					if (home && kind == Farm.Tile.UTIL && Guard.ours(p)) continue;
					if (!Guard.mayBreak(p, s)) {
						skip.add(tile);
						return null;
					}
					return new Op(Kind.BREAK, p, tile);
				}
				return null;
			}
			case FILL -> {
				if (kind == Farm.Tile.WATER) {
					// only needs a solid floor under the water
					BlockPos f = g.below();
					if (home) return null;
					if (!W.solid(f)) return W.solid(f.below()) ? new Op(Kind.PLACE, f, tile) : skipTile(tile);
					return null;
				}
				BlockState s = W.st(g);
				boolean needTillable = kind == Farm.Tile.FARM;
				if (needTillable ? W.isTillable(s) || s.is(Blocks.FARMLAND) : (W.sturdyTop(g) && !(s.getBlock() instanceof FallingBlock))) return null;
				if (s.canBeReplaced()) {
					BlockPos f = g.below();
					if (!W.solid(f)) {
						if (!W.solid(f.below())) return skipTile(tile);
						return new Op(Kind.PLACE, f, tile);
					}
					return new Op(Kind.PLACE, g, tile);
				}
				if (!Guard.mayBreak(g, s)) return skipTile(tile);
				return new Op(Kind.BREAK, g, tile);
			}
			case WATER -> {
				if (kind != Farm.Tile.WATER || home) return null;
				BlockState s = W.st(g);
				if (W.isWaterSource(s)) return null;
				if (!s.isAir() && !W.isLiquidBlock(s)) {
					if (!Guard.mayBreak(g, s)) return skipTile(tile);
					return new Op(Kind.BREAK, g, tile);
				}
				if (!W.st(g.above()).isAir() && !W.isLiquidBlock(W.st(g.above()))) return new Op(Kind.BREAK, g.above(), tile);
				return new Op(Kind.POUR, g, tile);
			}
			case TILL -> {
				if (kind != Farm.Tile.FARM) return null;
				if (W.isTillable(W.st(g)) && W.st(g.above()).isAir()) return new Op(Kind.TILL, g, tile);
				return null;
			}
			case PLANT -> {
				if (kind != Farm.Tile.FARM) return null;
				if (W.st(g).is(Blocks.FARMLAND) && W.st(g.above()).isAir()) return new Op(Kind.PLANT, g, tile);
				return null;
			}
			case TORCH -> {
				if (kind != Farm.Tile.TORCH) return null;
				BlockPos t = g.above();
				if (W.st(t).is(Blocks.TORCH)) return null;
				if (!W.sturdyTop(g) || !W.st(t).canBeReplaced()) return null;
				return new Op(Kind.TORCH, t, tile);
			}
		}
		return null;
	}

	private Op skipTile(long tile) {
		skip.add(tile);
		return null;
	}

	@Override
	public void stop(Bot b) {
		if (sub != null) sub.stop(b);
	}
}
