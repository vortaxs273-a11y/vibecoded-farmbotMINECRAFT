package dev.farmbot;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Breaks one block at a time with raw dig packets and our own timing. Picks the best tool, faces the block,
 * sends START, counts ticks until the break would complete at 100%, sends STOP. Re-tries if the server disagrees.
 */
public final class Breaker {
	private BlockPos pos;
	private boolean started, stopped;
	private double progress;
	private int ticks, afterStop;

	public void reset() {
		if (started && !stopped && pos != null) Compat.dig(Action.ABORT_DESTROY_BLOCK, pos, Direction.UP);
		pos = null;
		started = stopped = false;
		progress = 0;
		ticks = afterStop = 0;
	}

	public boolean busy() {
		return pos != null && started;
	}

	public static boolean gone(BlockState s) {
		return s.isAir() || W.isLiquidBlock(s);
	}

	/** Tick the break. Returns true once the block is gone. */
	public boolean tick(BlockPos p) {
		LocalPlayer pl = Compat.mc().player;
		BlockState s = W.st(p);
		if (gone(s)) {
			if (p.equals(pos)) {
				pos = null;
				started = stopped = false;
			}
			return true;
		}
		if (!p.equals(pos)) {
			reset();
			pos = p.immutable();
		}
		Vec3 c = Vec3.atCenterOf(p);
		Ctl.lookAt(pl, c);
		int tool = Inv.bestTool(s);
		if (!Inv.selectIndex(tool)) return false;

		Direction face = faceToward(p, pl.getEyePosition());
		if (!started) {
			Compat.dig(Action.START_DESTROY_BLOCK, p, face);
			Compat.swing();
			started = true;
			stopped = false;
			progress = s.getDestroyProgress(pl, W.lvl(), p);
			ticks = 0;
			afterStop = 0;
			if (progress >= 1.0) stopped = true; // instant break, server handles it on START
			return false;
		}
		ticks++;
		if (!stopped) {
			Compat.swing();
			progress += s.getDestroyProgress(pl, W.lvl(), p);
			if (progress >= 1.0 && (pl.onGround() || pl.isInWater())) {
				Compat.dig(Action.STOP_DESTROY_BLOCK, p, face);
				stopped = true;
			}
			if (ticks > 1200) reset();
		} else if (++afterStop > 20) {
			// server didn't break it (lag, wrong tool sync...). Try again from scratch.
			started = false;
			stopped = false;
		}
		return false;
	}

	public static Direction faceToward(BlockPos p, Vec3 eye) {
		double dx = eye.x - (p.getX() + 0.5), dy = eye.y - (p.getY() + 0.5), dz = eye.z - (p.getZ() + 0.5);
		double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
		if (ay >= ax && ay >= az) return dy > 0 ? Direction.UP : Direction.DOWN;
		if (ax >= az) return dx > 0 ? Direction.EAST : Direction.WEST;
		return dz > 0 ? Direction.SOUTH : Direction.NORTH;
	}
}
