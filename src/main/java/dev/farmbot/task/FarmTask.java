package dev.farmbot.task;

import dev.farmbot.Bot;
import dev.farmbot.Farm;
import dev.farmbot.Inv;
import dev.farmbot.W;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** The loop it lives for. Harvest ripe wheat, replant, re-till trampled soil, pick up every last seed. farm farm farm. */
public final class FarmTask extends Task {
	enum Kind { HARVEST, PLANT, TILL }
	record Job(Kind kind, BlockPos pos) {}

	private static final Predicate<ItemStack> SEEDS = s -> s.is(Items.WHEAT_SEEDS);
	private static final Predicate<ItemStack> HOE = s -> s.is(ItemTags.HOES);
	private static final Predicate<ItemStack> LOOT = s -> s.is(Items.WHEAT) || s.is(Items.WHEAT_SEEDS) || s.is(Items.BREAD);

	private List<Job> jobs = new ArrayList<>();
	private Job cur;
	private int jobTicks, rescan;
	private final Set<Integer> ignoreItems = new HashSet<>();
	private final Do.Collector collector = new Do.Collector();
	private int harvested;

	/** Counts for the brain: {mature, emptyFarmland, trampled}. */
	public static int[] survey(Bot b) {
		int[] out = new int[3];
		int H = b.state.cy;
		for (int[] c : Farm.builtCells()) {
			int cx = Farm.centerX(c[0]), cz = Farm.centerZ(c[1]);
			for (int dx = -Farm.HALF; dx <= Farm.HALF; dx++)
				for (int dz = -Farm.HALF; dz <= Farm.HALF; dz++) {
					if (Farm.tile(c[0], c[1], dx, dz) != Farm.Tile.FARM) continue;
					int x = cx + dx, z = cz + dz;
					if (!W.loaded(x, z)) continue;
					BlockState crop = W.st(x, H, z), ground = W.st(x, H - 1, z);
					if (W.isMatureWheat(crop)) out[0]++;
					else if (ground.is(Blocks.FARMLAND) && crop.isAir()) out[1]++;
					else if (W.isTillable(ground) && crop.isAir()) out[2]++;
				}
		}
		return out;
	}

	@Override
	public String name() {
		return "farming (harvested " + harvested + ", " + jobs.size() + " jobs queued)";
	}

	@Override
	public int timeout() {
		return 20 * 60 * 6;
	}

	private void scan(Bot b) {
		jobs.clear();
		int H = b.state.cy;
		boolean seeds = Inv.has(SEEDS), hoe = Inv.has(HOE);
		for (int[] c : Farm.builtCells()) {
			int cx = Farm.centerX(c[0]), cz = Farm.centerZ(c[1]);
			for (int dx = -Farm.HALF; dx <= Farm.HALF; dx++)
				for (int dz = -Farm.HALF; dz <= Farm.HALF; dz++) {
					if (Farm.tile(c[0], c[1], dx, dz) != Farm.Tile.FARM) continue;
					int x = cx + dx, z = cz + dz;
					if (!W.loaded(x, z)) continue;
					BlockPos cropPos = new BlockPos(x, H, z), g = cropPos.below();
					BlockState crop = W.st(cropPos), ground = W.st(g);
					if (W.isMatureWheat(crop)) jobs.add(new Job(Kind.HARVEST, cropPos));
					else if (seeds && ground.is(Blocks.FARMLAND) && crop.isAir()) jobs.add(new Job(Kind.PLANT, g));
					else if (hoe && W.isTillable(ground) && crop.isAir()) jobs.add(new Job(Kind.TILL, g));
				}
		}
	}

	private boolean valid(Job j) {
		BlockState crop = W.st(j.kind == Kind.HARVEST ? j.pos : j.pos.above());
		BlockState ground = W.st(j.kind == Kind.HARVEST ? j.pos.below() : j.pos);
		return switch (j.kind) {
			case HARVEST -> W.isMatureWheat(crop);
			case PLANT -> ground.is(Blocks.FARMLAND) && crop.isAir() && Inv.has(SEEDS);
			case TILL -> W.isTillable(ground) && crop.isAir() && Inv.has(HOE);
		};
	}

	@Override
	public S tick(Bot b) {
		if (Inv.freeSlots() == 0) return S.OK; // brain will go store stuff
		if (cur == null || !valid(cur)) {
			// right after harvesting, the same tile needs replanting: do that before walking off
			if (cur != null && cur.kind == Kind.HARVEST) {
				if (W.st(cur.pos).isAir()) harvested++;
				Job replant = new Job(Kind.PLANT, cur.pos.below());
				if (valid(replant)) {
					cur = replant;
					jobTicks = 0;
					return S.RUN;
				}
			}
			// sweep up drops every so often
			ItemEntity loot = Do.nearestItem(b, b.p.blockPosition(), 6, LOOT, ignoreItems);
			if (loot != null && (cur == null || Inv.freeSlots() > 0)) {
				S c = collector.tick(b, b.p.blockPosition(), 6, LOOT);
				if (c == S.RUN) return S.RUN;
			}
			cur = null;
			if (jobs.isEmpty() || ++rescan % 40 == 0) scan(b);
			// nearest job
			double bd = Double.MAX_VALUE;
			for (Job j : jobs) {
				if (!valid(j)) continue;
				double d = b.p.distanceToSqr(j.pos.getX() + 0.5, j.pos.getY(), j.pos.getZ() + 0.5);
				if (d < bd) {
					bd = d;
					cur = j;
				}
			}
			if (cur == null) {
				// final sweep of the whole farm area for loot, then done
				ItemEntity far = Do.nearestItem(b, Farm.home(), Farm.SIZE * (dev.farmbot.Config.I.maxRings + 1), LOOT, ignoreItems);
				if (far != null) {
					S c = collector.tick(b, Farm.home(), Farm.SIZE * (dev.farmbot.Config.I.maxRings + 1), LOOT);
					if (c == S.RUN) return S.RUN;
				}
				b.state.harvested += harvested;
				b.state.save();
				return S.OK;
			}
			jobs.remove(cur);
			jobTicks = 0;
			b.nav.reset();
		}
		if (++jobTicks > 20 * 30) {
			cur = null;
			return S.RUN;
		}
		S s = switch (cur.kind) {
			case HARVEST -> Do.breakAt(b, cur.pos);
			case PLANT -> Do.useOnTop(b, cur.pos, SEEDS);
			case TILL -> Do.useOnTop(b, cur.pos, HOE);
		};
		if (s == S.FAIL) cur = null;
		return S.RUN;
	}
}
