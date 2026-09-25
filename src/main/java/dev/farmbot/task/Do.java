package dev.farmbot.task;

import dev.farmbot.Act;
import dev.farmbot.Bot;
import dev.farmbot.Breaker;
import dev.farmbot.W;
import dev.farmbot.path.Goal;
import dev.farmbot.task.Task.S;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Reusable building blocks for tasks: walk-then-break, walk-then-place, walk-then-click, pick up drops. */
public final class Do {
	public static final double REACH = 4.2;

	private Do() {}

	/** Walk into reach of pos. OK when there. */
	public static S reach(Bot b, BlockPos pos, boolean dig) {
		if (Act.inReach(pos, REACH) && !b.nav.pathing()) return S.OK;
		if (Act.inReach(pos, REACH - 0.6)) return S.OK;
		return b.nav.goTo(new Goal.Reach(pos, REACH - 0.4), dig);
	}

	/** Walk over and break a block. OK once it's gone. */
	public static S breakAt(Bot b, BlockPos pos) {
		if (Breaker.gone(W.st(pos))) return S.OK;
		S r = reach(b, pos, true);
		if (r == S.FAIL) return S.FAIL;
		if (r == S.RUN) return S.RUN;
		return b.breaker.tick(pos) ? S.OK : S.RUN;
	}

	/** Walk over and place an item as a block at pos. OK once something solid is there. */
	public static S placeAt(Bot b, BlockPos pos, Predicate<ItemStack> item, Predicate<net.minecraft.world.level.block.state.BlockState> done) {
		if (done.test(W.st(pos))) return S.OK;
		S r = reach(b, pos, true);
		if (r != S.OK) return r;
		if (!W.st(pos).canBeReplaced()) {
			// something in the way (a flower, snow...)
			b.breaker.tick(pos);
			return S.RUN;
		}
		Act.place(pos, item);
		return S.RUN;
	}

	/** Walk over and right-click the top of a block with an item (hoe, seeds, flint and steel...). */
	public static S useOnTop(Bot b, BlockPos pos, Predicate<ItemStack> item) {
		S r = reach(b, pos, true);
		if (r != S.OK) return r;
		Act.useOn(pos, Direction.UP, item);
		return S.RUN;
	}

	/** Nearest dropped item matching pred within r of center. */
	public static ItemEntity nearestItem(Bot b, BlockPos center, int r, Predicate<ItemStack> pred, Set<Integer> ignore) {
		List<Entity> es = b.lvl.getEntities((Entity) null, new AABB(center).inflate(r), e -> e.getType() == EntityType.ITEM);
		ItemEntity best = null;
		double bd = Double.MAX_VALUE;
		for (Entity e : es) {
			ItemEntity ie = (ItemEntity) e;
			if (!ie.isAlive() || ignore.contains(ie.getId())) continue;
			if (pred != null && !pred.test(ie.getItem())) continue;
			if (W.isLava(W.st(ie.blockPosition())) || W.isFire(W.st(ie.blockPosition()))) continue;
			double d = ie.distanceToSqr(b.p);
			if (d < bd) {
				bd = d;
				best = ie;
			}
		}
		return best;
	}

	/** Stateful drop collector: walks to each item in turn, gives up on unreachable ones. */
	public static final class Collector {
		private final Set<Integer> ignore = new HashSet<>();
		private int targetId = -1;
		private int ticksOnTarget;

		/** OK when nothing left to pick up. */
		public S tick(Bot b, BlockPos center, int r, Predicate<ItemStack> pred) {
			if (dev.farmbot.Inv.freeSlots() == 0) return S.OK;
			ItemEntity it = nearestItem(b, center, r, pred, ignore);
			if (it == null) return S.OK;
			if (it.getId() != targetId) {
				targetId = it.getId();
				ticksOnTarget = 0;
			}
			if (++ticksOnTarget > 200) {
				ignore.add(it.getId());
				return S.RUN;
			}
			BlockPos ip = it.blockPosition();
			S s = b.nav.goTo(new Goal.Near(ip, 0), false);
			if (s == S.FAIL) ignore.add(it.getId());
			return S.RUN;
		}
	}
}
