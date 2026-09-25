package dev.farmbot;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.function.Predicate;

/** One-shot world interactions: place a block, right-click a block face, use a bucket. */
public final class Act {
	private static int cooldown;

	private Act() {}

	public static void tick() {
		if (cooldown > 0) cooldown--;
	}

	public static boolean ready() {
		return cooldown <= 0;
	}

	static LocalPlayer p() {
		return Compat.mc().player;
	}

	public static boolean inReach(BlockPos pos, double r) {
		return p().getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= r * r;
	}

	/** Right-click a face of a block while holding a matching item. Returns true when the click was sent. */
	public static boolean useOn(BlockPos pos, Direction face, Predicate<ItemStack> item) {
		if (!ready()) return false;
		Vec3 hit = Breaker.visibleFace(pos, face);
		if (hit == null) return false;
		if (item != null && !Inv.select(item)) return false;
		Ctl.lookAt(p(), hit);
		Compat.useItemOn(new BlockHitResult(hit, face, pos, false));
		cooldown = 3;
		return true;
	}

	/** Open a container block (chest, table, furnace). */
	public static boolean open(BlockPos pos) {
		if (!ready()) return false;
		Direction face = Breaker.faceToward(pos, p().getEyePosition());
		Vec3 hit = Breaker.visibleFace(pos, face);
		if (hit == null) {
			Vec3 any = Breaker.visiblePoint(pos);
			if (any == null) return false;
			hit = any;
		}
		Ctl.lookAt(p(), hit);
		Compat.useItemOn(new BlockHitResult(hit, face, pos, false));
		cooldown = 10;
		return true;
	}

	/** Place a block of the given item into target (must be air/replaceable). Returns true when the click was sent. */
	public static boolean place(BlockPos target, Predicate<ItemStack> item) {
		if (!ready()) return false;
		BlockState at = W.st(target);
		if (!at.canBeReplaced()) return false;
		if (p().getBoundingBox().intersects(new AABB(target))) return false;
		// a flower/grass/snow layer sits there: click it directly, the new block replaces it (like a player would)
		if (!at.isAir() && !W.isLiquidBlock(at) && !at.getShape(W.lvl(), target).isEmpty()) {
			Vec3 hit = Breaker.visiblePoint(target);
			if (hit == null) return false;
			if (!Inv.select(item)) return false;
			Ctl.lookAt(p(), hit);
			Compat.useItemOn(new BlockHitResult(hit, Direction.UP, target, false));
			cooldown = 3;
			return true;
		}
		// find a face to click against, preferring the block below
		Direction[] order = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
		for (Direction d : order) {
			BlockPos nb = target.relative(d);
			BlockState ns = W.st(nb);
			if (ns.getCollisionShape(W.lvl(), nb).isEmpty()) continue;
			if (Guard.interactive(ns)) continue;
			Direction face = d.getOpposite();
			Vec3 hit = Breaker.visibleFace(nb, face);
			if (hit == null) continue;
			if (!Inv.select(item)) return false;
			Ctl.lookAt(p(), hit);
			Compat.useItemOn(new BlockHitResult(hit, face, nb, false));
			cooldown = 3;
			return true;
		}
		return false;
	}

	/** Buckets ray-trace from the eyes, so aim precisely and use the item itself. */
	public static boolean bucketAt(Vec3 aim, Predicate<ItemStack> bucket) {
		if (!ready()) return false;
		if (!Inv.select(bucket)) return false;
		Ctl.lookAt(p(), aim);
		Compat.useItem();
		cooldown = 6;
		return true;
	}

	/** Pour water so it lands in `hole` (aim at the top of the block under it). */
	public static boolean pourInto(BlockPos hole) {
		Vec3 aim = new Vec3(hole.getX() + 0.5, hole.getY() + 0.02, hole.getZ() + 0.5);
		return bucketAt(aim, s -> s.is(net.minecraft.world.item.Items.WATER_BUCKET));
	}

	/** Scoop a water source. */
	public static boolean scoop(BlockPos water) {
		Vec3 aim = new Vec3(water.getX() + 0.5, water.getY() + 0.6, water.getZ() + 0.5);
		return bucketAt(aim, s -> s.is(net.minecraft.world.item.Items.BUCKET));
	}
}
