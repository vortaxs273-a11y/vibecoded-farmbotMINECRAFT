package dev.farmbot;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Breaks blocks exactly like a player: aim the crosshair at a face you can actually see, hold left click.
 * If the block isn't in plain sight it refuses, and the caller has to walk somewhere it is.
 */
public final class Breaker {
	public static final double REACH = 4.4;
	private BlockPos pos;
	private boolean clicking, usedThisTick;
	private int ticks;
	public String dbg = "";

	public void reset() {
		pos = null;
		ticks = 0;
		release();
	}

	private void release() {
		if (clicking) Bari.click(false);
		clicking = false;
	}

	/** Call once at the end of every bot tick: lets go of the mouse if nobody broke anything this tick. */
	public void endTick() {
		if (!usedThisTick) release();
		usedThisTick = false;
	}

	public static boolean gone(BlockState s) {
		return s.isAir() || W.isLiquidBlock(s);
	}

	/** Tick the break. Returns true once the block is gone. */
	public boolean tick(BlockPos p) {
		usedThisTick = true;
		LocalPlayer pl = Compat.mc().player;
		BlockState s = W.st(p);
		if (gone(s)) {
			reset();
			return true;
		}
		if (!p.equals(pos)) {
			release();
			pos = p.immutable();
			ticks = 0;
		}
		Vec3 aim = visiblePoint(p);
		if (aim == null) {
			dbg = "brk " + p.toShortString() + " NOT VISIBLE";
			release();
			return false;
		}
		Ctl.lookAt(pl, aim);
		if (!Inv.selectIndex(Inv.bestTool(s))) {
			release();
			return false;
		}
		HitResult hr = Compat.mc().hitResult;
		dbg = "brk " + p.toShortString() + " t=" + ticks + " click=" + clicking + " hit=" + (hr instanceof BlockHitResult bh ? bh.getBlockPos().toShortString() : String.valueOf(hr == null ? null : hr.getType()))
			+ " destroying=" + Compat.mc().gameMode.isDestroying() + " sel=" + Compat.selectedSlot(pl);
		if (hr instanceof BlockHitResult bhr && hr.getType() == HitResult.Type.BLOCK && bhr.getBlockPos().equals(p)) {
			// re-assert every tick: Baritone clears forced inputs whenever a path starts or stops
			Bari.click(true);
			clicking = true;
		} else {
			// crosshair isn't on it yet (rotation applies next tick) - don't swing at something else
			release();
		}
		if (++ticks > 1200) reset();
		return false;
	}

	/** Is any part of this block visible and in reach? */
	public static boolean canSee(BlockPos p) {
		return visiblePoint(p) != null;
	}

	/**
	 * A point on the block that a straight line from our eyes reaches without passing through anything else.
	 * For air/replaceable targets (placing), "nothing in the way" counts.
	 */
	public static Vec3 visiblePoint(BlockPos p) {
		LocalPlayer pl = Compat.mc().player;
		Vec3 eye = pl.getEyePosition();
		Vec3 c = Vec3.atCenterOf(p);
		boolean empty = W.st(p).getShape(W.lvl(), p).isEmpty();
		// centre + a 3x3 grid on every face, nearest first
		java.util.List<Vec3> pts = new java.util.ArrayList<>(55);
		pts.add(c);
		double[] o = {-0.3, 0, 0.3};
		for (Direction d : Direction.values()) {
			Vec3 fc = c.add(d.getStepX() * 0.45, d.getStepY() * 0.45, d.getStepZ() * 0.45);
			for (double u : o)
				for (double v : o) {
					double x = fc.x, y = fc.y, z = fc.z;
					switch (d.getAxis()) {
						case X -> { y += u; z += v; }
						case Y -> { x += u; z += v; }
						case Z -> { x += u; y += v; }
					}
					pts.add(new Vec3(x, y, z));
				}
		}
		pts.sort((a, b) -> Double.compare(a.distanceToSqr(eye), b.distanceToSqr(eye)));
		for (Vec3 pt : pts) {
			if (pt.distanceTo(eye) > REACH) continue;
			BlockHitResult r = W.lvl().clip(new ClipContext(eye, pt, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, pl));
			if (r.getType() == HitResult.Type.MISS) {
				if (empty) return pt;
				continue;
			}
			if (r.getBlockPos().equals(p)) return pt;
		}
		return null;
	}

	/** Visible point on one specific face (for right-clicking that face). */
	public static Vec3 visibleFace(BlockPos p, Direction face) {
		LocalPlayer pl = Compat.mc().player;
		Vec3 eye = pl.getEyePosition();
		Vec3 pt = Vec3.atCenterOf(p).add(face.getStepX() * 0.49, face.getStepY() * 0.49, face.getStepZ() * 0.49);
		if (pt.distanceTo(eye) > REACH) return null;
		BlockHitResult r = W.lvl().clip(new ClipContext(eye, pt, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, pl));
		if (r.getType() == HitResult.Type.BLOCK && r.getBlockPos().equals(p)) return pt;
		return null;
	}

	public static Direction faceToward(BlockPos p, Vec3 eye) {
		double dx = eye.x - (p.getX() + 0.5), dy = eye.y - (p.getY() + 0.5), dz = eye.z - (p.getZ() + 0.5);
		double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
		if (ay >= ax && ay >= az) return dy > 0 ? Direction.UP : Direction.DOWN;
		if (ax >= az) return dx > 0 ? Direction.EAST : Direction.WEST;
		return dz > 0 ? Direction.SOUTH : Direction.NORTH;
	}
}
