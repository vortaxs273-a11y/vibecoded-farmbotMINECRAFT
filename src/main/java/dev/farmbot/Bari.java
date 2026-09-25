package dev.farmbot;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.utils.input.Input;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/** Thin wrapper around Baritone: it walks, digs its way to things, and mines ores legitimately. */
public final class Bari {
	private Bari() {}

	public static IBaritone get() {
		return BaritoneAPI.getProvider().getPrimaryBaritone();
	}

	/** Settings tuned for "don't get banned, don't grief, don't wreck the farm". */
	public static void configure() {
		Settings s = BaritoneAPI.getSettings();
		s.allowInventory.value = true;
		s.allowParkour.value = false;
		s.allowSprint.value = true;
		s.antiCheatCompatibility.value = true;
		s.allowWaterBucketFall.value = true;
		s.maxFallHeightNoWater.value = 3;
		s.chatControl.value = false;
		s.mineScanDroppedItems.value = true;
		s.legitMine.value = Config.I.legitMining;
		s.legitMineYLevel.value = Config.I.legitMineY;

		// never path through anything player-made, and never through our own farm
		List<Block> no = s.blocksToDisallowBreaking.value;
		for (Block b : BuiltInRegistries.BLOCK) {
			if ((Guard.artificial(b.defaultBlockState()) || b == Blocks.WHEAT || b == Blocks.FARMLAND) && !no.contains(b)) no.add(b);
		}
	}

	public static void cancel() {
		try {
			get().getPathingBehavior().cancelEverything();
			click(false);
		} catch (Throwable ignored) {
		}
	}

	public static boolean busy() {
		IBaritone b = get();
		return b.getPathingBehavior().isPathing() || b.getCustomGoalProcess().isActive() || b.getMineProcess().isActive();
	}

	/** Hold / release left click through Baritone's input handler (vanilla breaking of whatever the crosshair is on). */
	public static void click(boolean down) {
		get().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, down);
	}
}
