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
		if (k != null) cooldown.put(k, b.tick + (k.equals("mine:seeds") ? 20 * 15 : k.startsWith("mine:") || k.startsWith("hunt") ? 20 * 120 : 20 * 45));
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

	private int cooldownCount() {
		long now = Bot.I.tick;
		int n = 0;
		for (long until : cooldown.values()) if (until > now) n++;
		return n;
	}

	public boolean sleepCooling() {
		return cooling("sleep");
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
		// died? go get our stuff back before it despawns
		if (b.safety.deathPos != null) {
			BlockPos dp = b.safety.deathPos;
			if (b.tick - b.safety.deathTick > 20 * 60 * 4) b.safety.deathPos = null;
			else {
				b.safety.deathPos = null;
				return new dev.farmbot.task.RecoverTask(dp);
			}
		}
		// night and we have a bed: go sleep (skips the night, keeps our spawn at the farm)
		if (b.state.bed != null && b.lvl.isDarkOutside() && BlockPos.of(b.state.bed).distSqr(b.p.blockPosition()) < 160 * 160) {
			if ((t = make("sleep", dev.farmbot.task.SleepTask::new)) != null) return t;
		}
		if ((t = food(b)) != null) return t;
		// something's been cooking at home: go get it
		if (b.state.furnaceLoaded && b.state.furnace != null) {
			BlockPos fp = BlockPos.of(b.state.furnace);
			Task c = make("collect-furnace", () -> dev.farmbot.task.SmeltTask.collect(Recipes.SMELT.get(Res.IRON_INGOT), fp));
			if (c != null) return c;
		}
		if (!b.state.hasSite) return make("scout", ScoutTask::new);

		if ((t = inventory(b)) != null) return t;
		if ((t = gear(b)) != null) return t;
		if ((t = home(b)) != null) return t;
		if ((t = farm(b)) != null) return t;
		if ((t = chores(b)) != null) return t;
		// nothing urgent: stockpile seeds for the next plots instead of standing around
		blocked = false;
		if (Inv.count(Res.SEEDS) < 256) {
			Task s = seeds(b, 32);
			if (s != null) return s;
		}
		// say exactly why we're idle - never pretend there's wheat when there isn't
		int[] sv = surveyed(b);
		int growing = 0;
		for (int[] c : Farm.builtCells()) growing++;
		String why;
		if (growing == 0) why = "no plots built yet (seeds " + Inv.count(Res.SEEDS) + ", cooldowns " + cooldownCount() + ")";
		else if (sv[0] == 0) why = growing + " plots growing, nothing ripe yet";
		else why = sv[0] + " ripe but blocked (cooldowns " + cooldownCount() + ")";
		return new IdleTask(20 * 5, why);
	}

	private Task food(Bot b) {
		int hunger = b.p.getFoodData().getFoodLevel();
		int fv = Inv.foodValue();
		// always carry a stock (so it can heal after a fight), hunt during the day when it runs low
		boolean low = fv < 20 && !b.lvl.isDarkOutside();
		if (fv >= 12 && !low) return null;
		if (hunger > 16 && !low) return null;
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
		// 1. junk goes first (flowers, eggs, odd wool, andesite...). never picked up again.
		StoreTask.tossJunk(b);
		if (Inv.freeSlots() > 2) return null;
		// 2. wheat -> bread (3 slots become 1)
		if (Inv.count(Res.WHEAT) >= 3 && CraftTask.findTable(b) != null) {
			blocked = false;
			Task t = obtain(b, Res.BREAD, Inv.count(Res.BREAD) + Inv.count(Res.WHEAT) / 3, 0);
			if (t != null) return t;
		}
		// 3. into our chests
		Task t = make("store", StoreTask::new);
		if (t != null) return t;
		// 4. no room anywhere: build another chest if we can, else drop the cheapest surplus
		if (Inv.count(Res.CHEST) == 0 && Inv.count(Res.PLANKS) + Inv.count(Res.LOG) * 4 >= 8 && CraftTask.findTable(b) != null) {
			blocked = false;
			t = obtain(b, Res.CHEST, 1, 0);
			if (t != null) return t;
		}
		needChest = true;
		if (Inv.freeSlots() <= 1) StoreTask.makeRoom(b, 3);
		return null;
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
		if ((t = bed(b)) != null) return t;
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
		// armor before anything else iron: this is what keeps it alive away from home
		// chestplate + leggings = 11 of 15 armor points for 15 iron; helmet/boots come later as chores
		Res[] armor = {Res.IRON_CHESTPLATE, Res.IRON_LEGGINGS};
		net.minecraft.world.entity.EquipmentSlot[] slots = {net.minecraft.world.entity.EquipmentSlot.CHEST,
			net.minecraft.world.entity.EquipmentSlot.LEGS};
		for (int k = 0; k < armor.length; k++) {
			if (!b.p.getItemBySlot(slots[k]).isEmpty() || Inv.count(armor[k]) > 0) continue;
			Res r = armor[k];
			if ((t = step(() -> obtain(b, r, 1, 0))) != null) return t;
		}
		if (!has(s -> s.is(Items.FLINT_AND_STEEL)) && (t = step(() -> obtain(b, Res.FLINT_AND_STEEL, 1, 0))) != null) return t;
		if (!has(s -> s.is(Items.SHIELD)) && (t = step(() -> obtain(b, Res.SHIELD, 1, 0))) != null) return t;
		if (!has(s -> s.is(Items.IRON_SWORD) || s.is(Items.DIAMOND_SWORD)) && (t = step(() -> obtain(b, Res.IRON_SWORD, 1, 0))) != null) return t;
		return null;
	}

	/** A bed at home: nights skipped, respawn at the farm. */
	private Task bed(Bot b) {
		if (b.state.bed != null) {
			BlockPos bp = BlockPos.of(b.state.bed);
			if (!W.loaded(bp) || W.st(bp).is(net.minecraft.tags.BlockTags.BEDS)) return null;
			b.state.bed = null;
		}
		blocked = false;
		if (Inv.count(Res.BED) > 0) return make("place:bed", dev.farmbot.task.BedTask::new);
		if (Inv.count(Res.WHITE_WOOL) < 3) return make("hunt:wool", () -> dev.farmbot.task.HuntTask.wool(3));
		return step(() -> obtain(b, Res.BED, 1, 0));
	}

	private int ironNeeded() {
		int n = 0;
		if (!has(s -> s.is(Items.IRON_PICKAXE) || s.is(Items.DIAMOND_PICKAXE) || s.is(Items.NETHERITE_PICKAXE))) n += 3;
		int buckets = Inv.count(Res.BUCKET) + Inv.count(Res.WATER_BUCKET);
		n += Math.max(0, 2 - buckets) * 3;
		if (!has(s -> s.is(Items.FLINT_AND_STEEL))) n += 1;
		if (!has(s -> s.is(Items.SHIELD))) n += 1;
		if (!has(s -> s.is(Items.IRON_SWORD) || s.is(Items.DIAMOND_SWORD))) n += 2;
		Bot b = Bot.I;
		if (b.p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).isEmpty() && Inv.count(Res.IRON_CHESTPLATE) == 0) n += 8;
		if (b.p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.LEGS).isEmpty() && Inv.count(Res.IRON_LEGGINGS) == 0) n += 7;
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
		// never wait around: plant every empty tile, harvest everything ripe, re-till trampled soil
		if (sv[0] > 0 || (sv[1] > 0 && seeds > 0) || (sv[2] > 0 && has(st -> st.is(ItemTags.HOES)))) {
			if ((t = make("farm", FarmTask::new)) != null) return t;
		}
		// bare farmland and no seeds: go pull grass until every tile can be planted
		if (sv[1] > 0 && seeds == 0) {
			if ((t = seeds(b, Math.min(64, sv[1]))) != null) return t;
		}

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
			// build even with few seeds: water + tilled soil is ready the moment seeds come in
			if (seeds == 0 && (t = seeds(b, 16)) != null) return t;
			String key = "cell:" + c[0] + "," + c[1];
			if (cooling(key)) continue; // failed recently: try the next plot, come back later
			return make(key, () -> new BuildCellTask(b, c[0], c[1]));
		}
		return null;
	}

	/** Seed gathering in short bursts (never one giant task that times out). */
	private Task seeds(Bot b, int more) {
		int want = Inv.count(Res.SEEDS) + Math.max(8, more);
		return make("mine:seeds", () -> new MineTask("pulling grass for seeds",
			s -> s.is(Blocks.SHORT_GRASS) || s.is(Blocks.TALL_GRASS) || s.is(Blocks.FERN) || s.is(Blocks.LARGE_FERN),
			Res.SEEDS.pred, want, 6, 8, 8, false, 5).partialOk());
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
				return seeds(b, n - Inv.count(Res.SEEDS));
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
