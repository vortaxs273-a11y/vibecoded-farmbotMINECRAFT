package dev.farmbot.task;

import dev.farmbot.Act;
import dev.farmbot.Bot;
import dev.farmbot.Compat;
import dev.farmbot.Farm;
import dev.farmbot.Guard;
import dev.farmbot.Inv;
import dev.farmbot.W;
import dev.farmbot.path.Goal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Food with zero cooking infrastructure: light the ground under a wild animal with flint and steel, finish it
 * while it burns, and it drops cooked meat. Only in open grass far from trees, builds, players and our own farm,
 * and the fire gets punched out afterwards. Never touches named, leashed or penned (near-build) animals.
 */
public final class HuntTask extends Task {
	private static final Predicate<ItemStack> FNS = s -> s.is(Items.FLINT_AND_STEEL);
	private final int wantFood;
	private LivingEntity target;
	private BlockPos fireAt;
	private final Set<Integer> ignore = new HashSet<>();
	private final Do.Collector collector = new Do.Collector();
	private int collectTicks = -1, targetTicks;
	private BlockPos killPos;

	public HuntTask(int wantFood) {
		this.wantFood = wantFood;
	}

	private int wantWool;

	/** Hunt white sheep until we have n white wool (for the bed). No fire: it'd waste the wool. */
	public static HuntTask wool(int n) {
		HuntTask t = new HuntTask(0);
		t.wantWool = n;
		return t;
	}

	@Override
	public String name() {
		if (wantWool > 0) return "hunting sheep for a bed " + Inv.count(dev.farmbot.Res.WHITE_WOOL) + "/" + wantWool;
		return "hunting (flint & steel cooking) food " + Inv.foodValue() + "/" + wantFood;
	}

	@Override
	public int timeout() {
		return 20 * 60 * 6;
	}

	static boolean isFood(Entity e) {
		EntityType<?> t = e.getType();
		return t == EntityType.COW || t == EntityType.PIG || t == EntityType.SHEEP || t == EntityType.CHICKEN
			|| t == EntityType.RABBIT || t == EntityType.MOOSHROOM;
	}

	@Override
	public S tick(Bot b) {
		// (the fire we lit sits on bare grass with nothing flammable around; it burns out by itself in seconds)
		if (fireAt != null && (target == null || !target.isAlive())) fireAt = null;
		if (collectTicks >= 0) {
			S c = collector.tick(b, killPos, 6, s -> Inv.nutrition(s) > 0);
			if (c == S.OK || ++collectTicks > 160) {
				collectTicks = -1;
				target = null;
			}
			return S.RUN;
		}
		if (wantWool > 0 ? Inv.count(dev.farmbot.Res.WHITE_WOOL) >= wantWool : Inv.foodValue() >= wantFood) return S.OK;

		if (target == null || !target.isAlive()) {
			if (target != null && !target.isAlive()) {
				killPos = target.blockPosition();
				target = null;
				collectTicks = 0;
				return S.RUN;
			}
			target = pick(b);
			targetTicks = 0;
			if (target == null) return fail("no animals around");
		}
		if (++targetTicks > 20 * 60) {
			ignore.add(target.getId());
			target = null;
			return S.RUN;
		}
		double d = target.distanceTo(b.p);
		// light it up
		boolean fns = Inv.has(FNS) && wantWool == 0;
		if (fns && !target.isOnFire() && fireAt == null && d < 4.0 && fireSafe(b, target.blockPosition())) {
			BlockPos feet = target.blockPosition();
			BlockState at = W.st(feet);
			if (W.isPlant(at)) {
				b.breaker.tick(feet);
				return S.RUN;
			}
			if (at.isAir() && W.sturdyTop(feet.below())) {
				if (Act.useOn(feet.below(), Direction.UP, FNS)) fireAt = feet;
				return S.RUN;
			}
		}
		if (d < 3.0) {
			int w = Inv.bestWeapon();
			if (w < 0 || Inv.selectIndex(w)) {
				dev.farmbot.Ctl.lookAt(b.p, target.getEyePosition());
				if (b.p.getAttackStrengthScale(0.5f) >= 0.95f) Compat.attack(target);
			}
			return S.RUN;
		}
		S s = b.nav.goTo(new Goal.Near(target.blockPosition(), 2), false);
		if (s == S.FAIL) {
			ignore.add(target.getId());
			target = null;
		}
		return S.RUN;
	}

	private LivingEntity pick(Bot b) {
		List<Entity> es = b.lvl.getEntities(b.p, b.p.getBoundingBox().inflate(48), e -> e instanceof LivingEntity le && le.isAlive()
			&& isFood(e) && !le.isBaby() && !e.hasCustomName() && !ignore.contains(e.getId())
			&& (wantWool == 0 || (e instanceof net.minecraft.world.entity.animal.sheep.Sheep sh && !sh.isSheared()
				&& sh.getColor() == net.minecraft.world.item.DyeColor.WHITE)));
		LivingEntity best = null;
		double bd = Double.MAX_VALUE;
		for (Entity e : es) {
			BlockPos p = e.blockPosition();
			if (Farm.inBuiltCell(p.getX(), p.getZ())) continue;
			if (Guard.nearArtificial(p, 6)) {
				ignore.add(e.getId()); // someone's pen. not ours. never.
				continue;
			}
			double d = e.distanceToSqr(b.p);
			if (d < bd) {
				bd = d;
				best = (LivingEntity) e;
			}
		}
		return best;
	}

	/** No trees, wood, wool, players or farm anywhere near: fire here can't hurt anything but dinner. */
	private boolean fireSafe(Bot b, BlockPos p) {
		if (Farm.inFarmArea(p.getX(), p.getZ(), 12)) return false;
		for (Player pl : b.lvl.players()) if (pl != b.p && pl.blockPosition().distSqr(p) < 24 * 24) return false;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int x = -5; x <= 5; x++)
			for (int y = -2; y <= 4; y++)
				for (int z = -5; z <= 5; z++) {
					BlockState s = W.st(m.set(p.getX() + x, p.getY() + y, p.getZ() + z));
					if (s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || s.is(BlockTags.PLANKS) || s.is(BlockTags.WOOL)
						|| Guard.artificial(s)) return false;
				}
		return true;
	}

}
