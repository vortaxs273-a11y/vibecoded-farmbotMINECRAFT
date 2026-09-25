package dev.farmbot.task;

import dev.farmbot.Bari;
import dev.farmbot.Bot;
import dev.farmbot.Inv;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.function.Predicate;

/**
 * Ores go through Baritone's mine process with legitMine on: it only digs out ores it has actually seen and
 * branch-mines at a sensible Y otherwise, picking up the drops as it goes. No beelining through stone to ores
 * you couldn't know about.
 */
public final class OreTask extends Task {
	private final String label;
	private final Predicate<ItemStack> want;
	private final int count;
	private final Block[] blocks;
	private int idle, restarts, lastCount = -1, stale;

	public OreTask(String label, Predicate<ItemStack> want, int count, Block... blocks) {
		this.label = label;
		this.want = want;
		this.count = count;
		this.blocks = blocks;
	}

	@Override
	public String name() {
		return label + " " + Inv.count(want) + "/" + count + " (baritone)";
	}

	@Override
	public int timeout() {
		return 20 * 60 * 20;
	}

	@Override
	public S tick(Bot b) {
		int have = Inv.count(want);
		if (have >= count) {
			Bari.cancel();
			return S.OK;
		}
		if (Inv.freeSlots() == 0) {
			Bari.cancel();
			return S.OK;
		}
		// no progress for 8 minutes: give up for now, the brain will try again later
		if (have != lastCount) {
			lastCount = have;
			stale = 0;
		} else if (++stale > 20 * 60 * 8) {
			Bari.cancel();
			return fail("no " + label + " found");
		}
		if (!Bari.get().getMineProcess().isActive()) {
			if (++idle > 20) {
				idle = 0;
				if (++restarts > 6) return fail("baritone keeps stopping");
				Bari.get().getMineProcess().mine(0, blocks);
			}
		} else idle = 0;
		return S.RUN;
	}

	@Override
	public void stop(Bot b) {
		Bari.cancel();
	}
}
