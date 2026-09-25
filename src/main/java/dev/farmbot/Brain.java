package dev.farmbot;

import dev.farmbot.Recipes.Recipe;
import dev.farmbot.Recipes.Smelt;
import dev.farmbot.path.Goal;
import dev.farmbot.task.BuildCellTask;
import dev.farmbot.task.CraftTask;
import dev.farmbot.task.FarmTask;
import dev.farmbot.task.FlintTask;
import dev.farmbot.task.GotoTask;
import dev.farmbot.task.HuntTask;
import dev.farmbot.task.IdleTask;
import dev.farmbot.task.MineTask;
import dev.farmbot.task.OreTask;
import dev.farmbot.task.PlaceTask;
import dev.farmbot.task.PoolTask;
import dev.farmbot.task.ScoutTask;
import dev.farmbot.task.SmeltTask;
import dev.farmbot.task.StoreTask;
import dev.farmbot.task.Task;
import dev.farmbot.task.TorchTask;
import dev.farmbot.task.WaterTask;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Decides what to do next, from scratch, every time a task ends. Stateless on purpose: inventory and world are
 * the truth, so deaths, thefts and lag never confuse it. Goal stack: survive > site > gear > home > farm > more farm.
 */
public final class Brain {
	private final Map<String, Long> cooldown = new HashMap<>();
	private final Map<Task, String> keys = new HashMap<>();
	private boolean blocked;
	private boolean needChest;
	private int[] survey = new int[3];
	private long surveyAt = -1000;

	private static final BlockState STONE = Blocks.STONE.defaultBlockState();
	private static final BlockState IRON_ORE = Blocks.IRON_ORE.defaultBlockState();

	public void failed(Task t, Bot b) {
		String k = keys.remove(t);
		FarmBotClient.LOG.info("[farmbot] task failed: {} ({})", t.name(), t.why);
		if (k != null) cooldown.put(k, b.tick + (k.startsWith("mine:") || k.startsWith("hunt") ? 20 * 120 : 20 * 45));
		if (t instanceof StoreTask && t.why.contains("no chest")) needChest = true;
		if (t instanceof PlaceTask pt && pt.item == Res.CHEST) {
			b.state.badSpots.add(pt.pos.asLong());
			cooldown.remove(k); // try the next spot right away
		}
		if (t instanceof ScoutTask) cooldown.clear();
	}

	public void succeeded(Task t) {
		keys.remove(t);
	}

	private boolean cooling(String k) {
		Long until = cooldown.get(k);
		return until != null && until > Bot.I.tick;
	}

	private Task make(String key, Supplier<Task> s) {
		if (cooling(key)) {
			blocked = true;
			return null;
		}
		Task t = s.get();
		keys.put(t, key);
		return t;
	}

	// ------------------------------------------------------------------ planning

	public Task next(Bot b) {
		keys.clear();
		Task t;
		if (!b.state.hasSite) return make("scout", ScoutTask::new);

		if ((t = food(b)) != null) return t;
		if ((t = inventory(b)) != null) return t;
		if ((t = gear(b)) != null) return t;
		if ((t = home(b)) != null) return t;
		if ((t = farm(b)) != null) return t;
		if ((t = chores(b)) != null) return t;
		return new IdleTask(20 * 20);
	}

	private Task food(Bot b) {
		int hunger = b.p.getFoodData().getFoodLevel();
		int fv = Inv.foodValue();
		if (fv >= 12 || hunger > 16) return null;
		blocked = false;
		if (Inv.count(Res.WHEAT) >= 3) {
			Task t = obtain(b, Res.BREAD, Inv.count(Res.BREAD) + Inv.count(Res.WHEAT) / 3, 0);
			if (t != null) return t;
		}
		if (surveyed(b)[0] >= 6) return make("farm", FarmTask::new);
		return make("hunt", () -> new HuntTask(40));
	}

	private Task inventory(Bot b) {
		if (Inv.freeSlots() > 2) return null;
		if (Inv.count(Res.WHEAT) >= 3 && CraftTask.findTable(b) != null) {
			blocked = false;
			Task t = obtain(b, Res.BREAD, Inv.count(Res.BREAD) + Inv.count(Res.WHEAT) / 3, 0);
			if (t != null) return t;
		}
		return make("store", StoreTask::new);
	}

	private static boolean canMine(BlockState s) {
		return Inv.has(st -> st.is(ItemTags.PICKAXES) && st.isCorrectToolForDrops(s));
	}

	private static boolean has(Predicate<ItemStack> p) {
		return Inv.has(p);
	}

	/** Run one gear step: returns a task, or null if done OR blocked (then we move on and try again later). */
	private Task step(Supplier<Task> s) {
		blocked = false;
		return s.get();
	}

	private Task gear(Bot b) {
		Task t;
		if (!canMine(STONE) && (t = step(() -> obtain(b, Res.WOOD_PICK, 1, 0))) != null) return t;
		if ((t = homeBlock(b, Res.CRAFTING_TABLE, Farm.TABLE, Blocks.CRAFTING_TABLE, true)) != null) return t;
		if (!canMine(IRON_ORE) && (t = step(() -> obtain(b, Res.STONE_PICK, 1, 0))) != null) return t;
		if (!has(s -> s.is(ItemTags.SWORDS)) && (t = step(() -> obtain(b, Res.STONE_SWORD, 1, 0))) != null) return t;
		if (!has(s -> s.is(ItemTags.AXES)) && (t = step(() -> obtain(b, Res.STONE_AXE, 1, 0))) != null) return t;
		if (!has(s -> s.is(ItemTags.SHOVELS)) && (t = step(() -> obtain(b, Res.STONE_SHOVEL, 1, 0))) != null) return t;
		if (!has(s -> s.is(ItemTags.HOES)) && (t = step(() -> obtain(b, Res.STONE_HOE, 1, 0))) != null) return t;
		if (Inv.count(Res.COBBLE) < 12 && (t = step(() -> obtain(b, Res.COBBLE, 32, 0))) != null) return t;
		if ((t = homeBlock(b, Res.FURNACE, Farm.FURNACE, Blocks.FURNACE, false)) != null) return t;

		// the iron age: pick, 2 buckets, flint & steel, shield, sword
		int iron = ironNeeded();
		if (iron > 0 && Inv.count(Res.IRON_INGOT) < iron && (t = step(() -> obtain(b, Res.IRON_INGOT, iron, 0))) != null) return t;
		if (!has(s -> s.is(Items.IRON_PICKAXE) || s.is(Items.DIAMOND_PICKAXE) || s.is(Items.NETHERITE_PICKAXE))
			&& (t = step(() -> obtain(b, Res.IRON_PICK, 1, 0))) != null) return t;
		int buckets = Inv.count(Res.BUCKET) + Inv.count(Res.WATER_BUCKET);
		if (buckets < 2 && (t = step(() -> obtain(b, Res.BUCKET, Inv.count(Res.BUCKET) + 2 - buckets, 0))) != null) return t;
		if (!has(s -> s.is(Items.FLINT_AND_STEEL)) && (t = step(() -> obtain(b, Res.FLINT_AND_STEEL, 1, 0))) != null) return t;
		if (!has(s -> s.is(Items.SHIELD)) && (t = step(() -> obtain(b, Res.SHIELD, 1, 0))) != null) return t;
		if (!has(s -> s.is(Items.IRON_SWORD) || s.is(Items.DIAMOND_SWORD)) && (t = step(() -> obtain(b, Res.IRON_SWORD, 1, 0))) != null) return t;
		return null;
	}

	private int ironNeeded() {
		int n = 0;
		if (!has(s -> s.is(Items.IRON_PICKAXE) || s.is(Items.DIAMOND_PICKAXE) || s.is(Items.NETHERITE_PICKAXE))) n += 3;
		int buckets = Inv.count(Res.BUCKET) + Inv.count(Res.WATER_BUCKET);
		n += Math.max(0, 2 - buckets) * 3;
		if (!has(s -> s.is(Items.FLINT_AND_STEEL))) n += 1;
		if (!has(s -> s.is(Items.SHIELD))) n += 1;
		if (!has(s -> s.is(Items.IRON_SWORD) || s.is(Items.DIAMOND_SWORD))) n += 2;
		return n;
	}

	/** Make sure a utility block stands at its home spot. */
	private Task homeBlock(Bot b, Res item, int[] spot, net.minecraft.world.level.block.Block block, boolean table) {
		Long saved = table ? b.state.table : b.state.furnace;
		BlockPos pos = Farm.homeSpot(spot);
		if (saved != null && W.loaded(pos) && W.st(pos).is(block)) return null;
		if (saved != null && !W.loaded(pos)) return null; // can't check from here; assume fine
		blocked = false;
		if (Inv.count(item) == 0) {
			Task t = obtain(b, item, 1, 0);
			return t;
		}
		return make("place:" + item, () -> new PlaceTask(pos, item, block, p -> {
			if (table) b.state.table = p.asLong();
			else b.state.furnace = p.asLong();
		}));
	}

	private Task home(Bot b) {
		Task t;
		if (!b.state.pool) {
			blocked = false;
			int wb = Inv.count(Res.WATER_BUCKET);
			if (wb < 2) {
				if (Inv.count(Res.BUCKET) + wb < 2) return step(() -> obtain(b, Res.BUCKET, 2 - wb, 0));
				return make("water", () -> new WaterTask(2));
			}
			return make("pool", PoolTask::new);
		}
		if (b.state.cell(0, 0) != FarmState.BUILT) {
			if (Inv.count(Res.SEEDS) < 8 && (t = step(() -> obtain(b, Res.SEEDS, 24, 0))) != null) return t;
			if (!has(s -> s.is(ItemTags.HOES)) && (t = step(() -> obtain(b, Res.STONE_HOE, 1, 0))) != null) return t;
			return make("cell:0,0", () -> new BuildCellTask(b, 0, 0));
		}
		if (b.state.chests.isEmpty() || needChest) {
			BlockPos spot = Farm.nextChestSpot();
			if (spot == null && Farm.claimStoragePlot()) spot = Farm.nextChestSpot();
			if (spot != null) {
				blocked = false;
				if (Inv.count(Res.CHEST) == 0) {
					t = obtain(b, Res.CHEST, 2, 0);
					if (t != null) return t;
				} else {
					BlockPos pos = spot;
					return make("place:chest", () -> new PlaceTask(pos, Res.CHEST, Blocks.CHEST, p -> {
						b.state.chests.add(p.asLong());
						needChest = false;
					}));
				}
			}
		}
		return null;
	}

	private int[] surveyed(Bot b) {
		if (b.tick - surveyAt > 100) {
			survey = FarmTask.survey(b);
			surveyAt = b.tick;
		}
		return survey;
	}

	private Task farm(Bot b) {
		Task t;
		int[] sv = surveyed(b);
		int seeds = Inv.count(Res.SEEDS);
		// all the wheat becomes bread
		if (Inv.count(Res.WHEAT) >= 3 && Inv.count(Res.BREAD) < 64 && CraftTask.findTable(b) != null) {
			blocked = false;
			t = obtain(b, Res.BREAD, Math.min(64, Inv.count(Res.BREAD) + Inv.count(Res.WHEAT) / 3), 0);
			if (t != null) {
				b.state.breadBaked += Inv.count(Res.WHEAT) / 3;
				return t;
			}
		}
		if (sv[0] >= 10 || (sv[1] >= 6 && seeds > 0) || sv[2] >= 6) return make("farm", FarmTask::new);

		if (!has(s -> s.is(ItemTags.HOES)) && (t = step(() -> obtain(b, Res.STONE_HOE, 1, 0))) != null) return t;

		// expand
		for (int[] c : Farm.cellOrder(Config.I.maxRings)) {
			if (b.state.cell(c[0], c[1]) != FarmState.NONE) continue;
			int x = Farm.centerX(c[0]), z = Farm.centerZ(c[1]);
			if (!W.loaded(x - Farm.HALF, z - Farm.HALF) || !W.loaded(x + Farm.HALF, z + Farm.HALF)) {
				return make("goto:" + c[0] + "," + c[1], () -> new GotoTask(new Goal.XZ(x, z, 6), "walking to the next plot"));
			}
			if (!BuildCellTask.viable(b, c[0], c[1])) {
				b.state.setCell(c[0], c[1], FarmState.SKIPPED);
				continue;
			}
			if (seeds < 24) {
				if (sv[0] > 0) return make("farm", FarmTask::new);
				t = step(() -> obtain(b, Res.SEEDS, 40, 0));
				if (t != null) return t;
				break;
			}
			String key = "cell:" + c[0] + "," + c[1];
			if (cooling(key)) {
				// failed twice here recently; give up on this plot for good
				b.state.setCell(c[0], c[1], FarmState.SKIPPED);
				continue;
			}
			return make(key, () -> new BuildCellTask(b, c[0], c[1]));
		}
		if (sv[0] > 0 || (sv[1] > 0 && seeds > 0)) return make("farm", FarmTask::new);
		return null;
	}

	private Task chores(Bot b) {
		Task t;
		if (Config.I.placeTorches && !TorchTask.missing(b).isEmpty()) {
			if (Inv.count(Res.TORCH) == 0) {
				if ((t = step(() -> obtain(b, Res.TORCH, 16, 0))) != null) return t;
			} else if ((t = make("torches", TorchTask::new)) != null) return t;
		}
		if (Config.I.craftArmor) {
			Res[] armor = {Res.IRON_CHESTPLATE, Res.IRON_LEGGINGS, Res.IRON_HELMET, Res.IRON_BOOTS};
			net.minecraft.world.entity.EquipmentSlot[] slots = {net.minecraft.world.entity.EquipmentSlot.CHEST,
				net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.HEAD,
				net.minecraft.world.entity.EquipmentSlot.FEET};
			for (int k = 0; k < armor.length; k++) {
				if (!b.p.getItemBySlot(slots[k]).isEmpty() || Inv.count(armor[k]) > 0) continue;
				Res r = armor[k];
				if ((t = step(() -> obtain(b, r, 1, 0))) != null) return t;
			}
		}
		if (Inv.count(Res.SEEDS) < 64 && (t = step(() -> obtain(b, Res.SEEDS, 64, 0))) != null) return t;
		return null;
	}

	// ------------------------------------------------------------------ the recursive "get me N of X" planner

	private Task obtain(Bot b, Res r, int n, int depth) {
		if (depth > 8) return null;
		int have = Inv.count(r);
		if (have >= n) return null;
		int missing = n - have;

		Recipe rc = Recipes.CRAFT.get(r);
		if (rc != null) {
			int times = Math.min(64, (missing + rc.count() - 1) / rc.count());
			if (rc.table() && CraftTask.findTable(b) == null && Inv.count(Res.CRAFTING_TABLE) == 0 && r != Res.CRAFTING_TABLE) {
				Task t = obtain(b, Res.CRAFTING_TABLE, 1, depth + 1);
				if (t != null || blocked) return t;
			}
			for (Map.Entry<Res, Integer> e : rc.totals().entrySet()) {
				Task t = obtain(b, e.getKey(), e.getValue() * times, depth + 1);
				if (t != null || blocked) return t;
			}
			return make("craft:" + r, () -> new CraftTask(rc, times));
		}

		Smelt sm = Recipes.SMELT.get(r);
		if (sm != null) {
			if (r == Res.COAL && canMine(Blocks.COAL_ORE.defaultBlockState()) && !cooling("mine:coal")) {
				return make("mine:coal", () -> new OreTask("mining coal", Res.COAL.pred, n, Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE));
			}
			if (SmeltTask.findFurnace(b) == null && Inv.count(Res.FURNACE) == 0) {
				Task t = obtain(b, Res.FURNACE, 1, depth + 1);
				if (t != null || blocked) return t;
			}
			int k = Math.min(64, missing);
			// fuel first (planks come from logs, which might also be the input)
			if (Inv.count(Res.COAL) * 8 < k && Inv.count(Res.PLANKS) * 3 / 2 < k) {
				Task t = obtain(b, Res.PLANKS, (k * 2 + 2) / 3 + 1, depth + 1);
				if (t != null || blocked) return t;
			}
			Task t = obtain(b, sm.in(), k, depth + 1);
			if (t != null || blocked) return t;
			return make("smelt:" + r, () -> new SmeltTask(sm, k));
		}
		return gather(b, r, n, depth);
	}

	private Task gather(Bot b, Res r, int n, int depth) {
		switch (r) {
			case LOG -> {
				int want = Math.max(n, Inv.count(Res.LOG) + 6);
				return make("mine:log", () -> new MineTask("chopping trees", s -> s.is(BlockTags.LOGS) && !Guard.artificial(s),
					Res.LOG.pred, want, 7, 8, 24, false, 6));
			}
			case COBBLE -> {
				if (!canMine(STONE)) return obtain(b, Res.WOOD_PICK, 1, depth + 1);
				int want = Math.max(n, Inv.count(Res.COBBLE) + 8);
				return make("mine:stone", () -> new MineTask("mining stone", s -> s.is(Blocks.STONE),
					Res.COBBLE.pred, want, 3, 32, 8, true, 4));
			}
			case RAW_IRON -> {
				if (!canMine(IRON_ORE)) return obtain(b, Res.STONE_PICK, 1, depth + 1);
				return make("mine:iron", () -> new OreTask("mining iron", Res.RAW_IRON.pred, n, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE));
			}
			case GRAVEL -> {
				return make("mine:gravel", () -> new MineTask("digging gravel", s -> s.is(Blocks.GRAVEL),
					Res.GRAVEL.pred, Math.max(1, n), 6, 40, 24, true, 4));
			}
			case FLINT -> {
				if (Inv.count(Res.GRAVEL) == 0) return obtain(b, Res.GRAVEL, 1, depth + 1);
				return make("flint", FlintTask::new);
			}
			case SEEDS -> {
				return make("mine:seeds", () -> new MineTask("pulling grass for seeds",
					s -> s.is(Blocks.SHORT_GRASS) || s.is(Blocks.TALL_GRASS) || s.is(Blocks.FERN) || s.is(Blocks.LARGE_FERN),
					Res.SEEDS.pred, n, 5, 8, 8, false, 5));
			}
			case DIRT -> {
				return make("mine:dirt", () -> new MineTask("digging dirt", s -> s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT),
					Res.DIRT.pred, Math.max(n, 32), 6, 6, 4, true, 4));
			}
			case WATER_BUCKET -> {
				if (Inv.count(Res.BUCKET) == 0) return obtain(b, Res.BUCKET, 1, depth + 1);
				return make("water", () -> new WaterTask(n));
			}
			case WHEAT -> {
				if (surveyed(b)[0] > 0) return make("farm", FarmTask::new);
				blocked = true;
				return null;
			}
			default -> {
				blocked = true;
				return null;
			}
		}
	}
}
