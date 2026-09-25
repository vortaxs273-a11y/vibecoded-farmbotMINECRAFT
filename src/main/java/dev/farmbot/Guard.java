package dev.farmbot;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.InfestedBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Anti-grief. Decides what counts as "someone was here" and makes sure the bot never breaks, burns or loots it.
 * Natural terrain is fair game; anything player-shaped is sacred unless we placed it ourselves.
 */
public final class Guard {
	private static final Map<Block, Boolean> ART = new IdentityHashMap<>();

	private static final String[] KEYS = {
		"planks", "wool", "carpet", "_bed", "door", "glass", "brick", "concrete", "torch", "lantern", "chest",
		"barrel", "furnace", "smoker", "crafting", "_table", "sign", "banner", "rail", "fence", "_wall", "stairs",
		"slab", "polished", "smooth_", "cut_", "chiseled", "candle", "bookshelf", "hay_block", "farmland",
		"dirt_path", "cobblestone", "ladder", "scaffolding", "lever", "button", "pressure_plate", "redstone_",
		"repeater", "comparator", "hopper", "dispenser", "dropper", "observer", "piston", "anvil", "bell", "beacon",
		"lectern", "jukebox", "note_block", "campfire", "shulker", "iron_bars", "glazed", "stripped_", "loom",
		"grindstone", "composter", "cauldron", "brewing", "flower_pot", "potted_", "spawner", "tnt", "target",
		"daylight", "chain", "item_frame", "armor_stand", "bed", "end_rod", "sea_lantern", "purpur", "quartz_",
		"honeycomb_block", "crafter", "decorated_pot", "copper_bulb", "copper_grate", "lightning_rod", "respawn_anchor",
		"lodestone", "enchanting", "cartography", "fletching", "smithing", "stonecutter", "barrier", "command_block",
		"structure_block", "jigsaw", "vault", "trial_spawner", "mud_bricks", "terracotta_"
	};

	private static final String[] NATURAL = {
		"bedrock", "_ore", "coral", "moss_carpet", "smooth_basalt", "seagrass", "dripstone", "pale_moss"
	};

	private Guard() {}

	public static boolean artificial(BlockState s) {
		if (s.isAir()) return false;
		Block b = s.getBlock();
		Boolean v = ART.get(b);
		if (v == null) {
			v = compute(b);
			ART.put(b, v);
		}
		return v;
	}

	private static boolean compute(Block b) {
		if (b == Blocks.GLASS || b == Blocks.GLASS_PANE || b == Blocks.CRAFTING_TABLE || b == Blocks.CHEST) return true;
		String id = Compat.blockId(b);
		for (String n : NATURAL) if (id.contains(n)) return false;
		for (String k : KEYS) if (id.contains(k)) return true;
		return false;
	}

	public static boolean ours(BlockPos p) {
		return Bot.I.state.ours.contains(p.asLong());
	}

	public static boolean interactive(BlockState s) {
		Block b = s.getBlock();
		String id = Compat.blockId(b);
		return id.contains("chest") || id.contains("table") || id.contains("furnace") || id.contains("door")
			|| id.contains("barrel") || id.contains("smoker") || id.contains("button") || id.contains("lever")
			|| id.contains("anvil") || id.contains("bed") || id.contains("gate") || id.contains("shulker")
			|| id.contains("hopper") || id.contains("dispenser") || id.contains("dropper") || id.contains("sign")
			|| id.contains("lectern") || id.contains("loom") || id.contains("grindstone") || id.contains("cutter")
			|| id.contains("crafter") || id.contains("bell") || id.contains("noteblock") || id.contains("note_block")
			|| id.contains("repeater") || id.contains("comparator") || id.contains("cauldron") || id.contains("composter");
	}

	/** May the bot break this block at all? */
	public static boolean mayBreak(BlockPos p, BlockState s) {
		if (s.isAir()) return true;
		if (s.getBlock() instanceof InfestedBlock) return false;
		if (artificial(s) && !ours(p)) return false;
		if (Farm.isFarmProtected(p)) return false;
		return true;
	}

	/** Is there any player-made block (that isn't ours) within r blocks? Used to avoid stealing/burning/digging near builds. */
	public static boolean nearArtificial(BlockPos c, int r) {
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int x = -r; x <= r; x++)
			for (int y = -r; y <= r; y++)
				for (int z = -r; z <= r; z++) {
					m.set(c.getX() + x, c.getY() + y, c.getZ() + z);
					BlockState s = W.st(m);
					if (artificial(s) && !Bot.I.state.ours.contains(m.asLong()) && !Farm.inBuiltCell(m.getX(), m.getZ())) return true;
				}
		return false;
	}
}
