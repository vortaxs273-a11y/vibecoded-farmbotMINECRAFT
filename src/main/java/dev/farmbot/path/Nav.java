package dev.farmbot.path;

import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import dev.farmbot.Bari;
import dev.farmbot.Breaker;
import dev.farmbot.Compat;
import dev.farmbot.task.Task.S;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

/** Movement goes through Baritone: it walks, swims, bridges and digs its way to the goal like a player would. */
public final class Nav {
	private Goal goal;
	private baritone.api.pathing.goals.Goal bgoal;
	private int idle, fails;
	private final Breaker breaker;
	public String debug = "";

	public Nav(Breaker breaker) {
		this.breaker = breaker;
	}

	public void reset() {
		if (goal != null) Bari.cancel();
		goal = null;
		bgoal = null;
		fails = 0;
		idle = 0;
		debug = "";
	}

	/** Let go of Baritone (safety takes over) without disturbing a break in progress. */
	public void pause() {
		if (goal != null || Bari.busy()) Bari.get().getPathingBehavior().cancelEverything();
		goal = null;
		bgoal = null;
	}

	public static BlockPos feet(LocalPlayer p) {
		return BlockPos.containing(p.getX(), p.getY() + 0.2, p.getZ());
	}

	public boolean pathing() {
		return goal != null && Bari.busy();
	}

	private static baritone.api.pathing.goals.Goal convert(Goal g) {
		if (g instanceof Goal.Block b) return new GoalBlock(b.x(), b.y(), b.z());
		if (g instanceof Goal.Near n) {
			if (n.r() <= 0) return new GoalBlock(n.x(), n.y(), n.z());
			return new GoalNear(new BlockPos(n.x(), n.y(), n.z()), n.r());
		}
		if (g instanceof Goal.Reach r) return new GoalGetToBlock(new BlockPos(r.x(), r.y(), r.z()));
		if (g instanceof Goal.XZ xz) return new GoalXZ(xz.x(), xz.z());
		throw new IllegalArgumentException("unknown goal " + g);
	}

	/** Drive toward a goal. Call every tick with the same goal. OK when there, FAIL when Baritone gives up. */
	public S goTo(Goal g, boolean allowDig) {
		LocalPlayer p = Compat.mc().player;
		BlockPos f = feet(p);
		boolean grounded = p.onGround() || p.isInWater();
		if (g.test(f.getX(), f.getY(), f.getZ()) && grounded) {
			if (goal != null) Bari.cancel();
			goal = null;
			fails = 0;
			debug = "";
			return S.OK;
		}
		if (!g.equals(goal)) {
			goal = g;
			bgoal = convert(g);
			Bari.get().getCustomGoalProcess().setGoalAndPath(bgoal);
			idle = 0;
			fails = 0;
			debug = "baritone";
			return S.RUN;
		}
		if (bgoal.isInGoal(f.getX(), f.getY(), f.getZ()) && grounded && !Bari.busy()) {
			goal = null;
			debug = "";
			return S.OK;
		}
		if (!Bari.busy()) {
			if (++idle > 30) {
				idle = 0;
				if (++fails >= 3) {
					reset();
					debug = "no path";
					return S.FAIL;
				}
				Bari.get().getCustomGoalProcess().setGoalAndPath(bgoal);
			}
		} else idle = 0;
		return S.RUN;
	}
}
