package dev.farmbot;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Predicate;

/** Inventory helpers. Player inventory indices: 0-8 hotbar, 9-35 main. */
public final class Inv {
	public static final int SIZE = 36;
	private static int swapCooldown;

	private Inv() {}

	static LocalPlayer p() {
		return Compat.mc().player;
	}

	public static Inventory inv() {
		return p().getInventory();
	}

	public static ItemStack get(int i) {
		return inv().getItem(i);
	}

	public static int count(Predicate<ItemStack> pred) {
		int n = 0;
		for (int i = 0; i < SIZE; i++) {
			ItemStack s = get(i);
			if (!s.isEmpty() && pred.test(s)) n += s.getCount();
		}
		ItemStack off = p().getOffhandItem();
		if (!off.isEmpty() && pred.test(off)) n += off.getCount();
		return n;
	}

	public static int count(Res r) {
		return count(r.pred);
	}

	public static boolean has(Predicate<ItemStack> pred) {
		return find(pred) >= 0 || (pred.test(p().getOffhandItem()) && !p().getOffhandItem().isEmpty());
	}

	/** Index of first matching stack, hotbar first. */
	public static int find(Predicate<ItemStack> pred) {
		for (int i = 0; i < SIZE; i++) {
			ItemStack s = get(i);
			if (!s.isEmpty() && pred.test(s)) return i;
		}
		return -1;
	}

	public static int freeSlots() {
		int n = 0;
		for (int i = 0; i < SIZE; i++) if (get(i).isEmpty()) n++;
		return n;
	}

	public static void tick() {
		if (swapCooldown > 0) swapCooldown--;
	}

	public static boolean noScreenMenu() {
		return p().containerMenu == p().inventoryMenu;
	}

	/**
	 * Get a matching item into the main hand. Returns true once it's actually held (may take a tick when the
	 * item has to be swapped up from the main inventory).
	 */
	public static boolean select(Predicate<ItemStack> pred) {
		int i = find(pred);
		if (i < 0) return false;
		return selectIndex(i);
	}

	public static boolean selectIndex(int i) {
		if (i < 0) return selectHand();
		if (i < 9) {
			if (Compat.selectedSlot(p()) != i) {
				Compat.setSelectedSlot(p(), i);
			}
			return true;
		}
		if (!noScreenMenu() || swapCooldown > 0) return false;
		int hb = pickHotbarSlot();
		Compat.swap(p().inventoryMenu.containerId, menuSlot(p().inventoryMenu, i), hb);
		Compat.setSelectedSlot(p(), hb);
		swapCooldown = 2;
		return false;
	}

	/** Hold something harmless (for punching plants, extinguishing fire...). */
	public static boolean selectHand() {
		int cur = Compat.selectedSlot(p());
		if (!get(cur).isDamageableItem()) return true;
		for (int i = 0; i < 9; i++) {
			if (!get(i).isDamageableItem()) {
				Compat.setSelectedSlot(p(), i);
				return true;
			}
		}
		return true;
	}

	/** Which hotbar slot to sacrifice when swapping something up: empty first, then least valuable. */
	private static int pickHotbarSlot() {
		for (int i = 0; i < 9; i++) if (get(i).isEmpty()) return i;
		int best = 8;
		int bestScore = Integer.MAX_VALUE;
		for (int i = 0; i < 9; i++) {
			int s = value(get(i));
			if (s < bestScore) {
				bestScore = s;
				best = i;
			}
		}
		return best;
	}

	private static int value(ItemStack s) {
		if (s.isEmpty()) return 0;
		if (s.isDamageableItem()) return 100;
		if (s.is(Items.WATER_BUCKET) || s.is(Items.BUCKET)) return 90;
		if (s.has(DataComponents.FOOD)) return 80;
		if (s.is(Items.WHEAT_SEEDS)) return 70;
		if (s.is(Items.TORCH)) return 60;
		if (s.is(Items.COBBLESTONE) || s.is(Items.DIRT)) return 50;
		return 10;
	}

	public static int menuSlot(AbstractContainerMenu menu, int invIndex) {
		Inventory inv = inv();
		for (int k = 0; k < menu.slots.size(); k++) {
			Slot s = menu.slots.get(k);
			if (s.container == inv && s.getContainerSlot() == invIndex) return k;
		}
		return -1;
	}

	/** Best tool (inventory index) for a block, or -1 if bare hands are as good. */
	public static int bestTool(BlockState state) {
		int best = -1;
		double bestScore = score(ItemStack.EMPTY, state);
		for (int i = 0; i < SIZE; i++) {
			ItemStack s = get(i);
			if (s.isEmpty()) continue;
			if (s.isDamageableItem() && s.getMaxDamage() - s.getDamageValue() <= 1) continue; // don't break our last hit points on a block
			double sc = score(s, state);
			if (sc > bestScore + 1e-6) {
				bestScore = sc;
				best = i;
			}
		}
		// swords break blocks too, but we'd rather not wear them down
		if (best >= 0 && get(best).is(ItemTags.SWORDS) && score(ItemStack.EMPTY, state) >= bestScore * 0.66) return -1;
		return best;
	}

	private static double score(ItemStack s, BlockState state) {
		float speed = s.isEmpty() ? 1f : s.getDestroySpeed(state);
		boolean harvest = !state.requiresCorrectToolForDrops() || (!s.isEmpty() && s.isCorrectToolForDrops(state));
		return speed / (harvest ? 30.0 : 100.0);
	}

	public static boolean canHarvest(BlockState state) {
		if (!state.requiresCorrectToolForDrops()) return true;
		for (int i = 0; i < SIZE; i++) {
			ItemStack s = get(i);
			if (!s.isEmpty() && s.isCorrectToolForDrops(state)) return true;
		}
		return false;
	}

	/** Estimated ticks to break with our best tool (on ground, dry). */
	public static double breakTicks(BlockState state, float hardness) {
		if (hardness <= 0) return 1;
		int t = bestTool(state);
		double perTick = score(t < 0 ? ItemStack.EMPTY : get(t), state) / hardness;
		return Math.ceil(1.0 / perTick);
	}

	public static int bestWeapon() {
		int best = -1;
		double bestDmg = 1;
		for (int i = 0; i < SIZE; i++) {
			ItemStack s = get(i);
			double d = s.is(Items.IRON_SWORD) ? 6 : s.is(Items.STONE_SWORD) ? 5 : s.is(Items.WOODEN_SWORD) ? 4
				: s.is(Items.IRON_AXE) ? 5.5 : s.is(Items.STONE_AXE) ? 4.5 : s.is(ItemTags.SWORDS) ? 5 : 0;
			if (d > bestDmg) {
				bestDmg = d;
				best = i;
			}
		}
		return best;
	}

	public static int nutrition(ItemStack s) {
		FoodProperties f = s.get(DataComponents.FOOD);
		return f == null ? 0 : f.nutrition();
	}

	public static boolean safeFood(ItemStack s) {
		if (s.isEmpty() || nutrition(s) <= 0) return false;
		return !(s.is(Items.ROTTEN_FLESH) || s.is(Items.SPIDER_EYE) || s.is(Items.POISONOUS_POTATO) || s.is(Items.PUFFERFISH)
			|| s.is(Items.CHICKEN) || s.is(Items.SUSPICIOUS_STEW) || s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE)
			|| s.is(Items.CHORUS_FRUIT));
	}

	public static int foodValue() {
		int n = 0;
		for (int i = 0; i < SIZE; i++) {
			ItemStack s = get(i);
			if (safeFood(s)) n += nutrition(s) * s.getCount();
		}
		return n;
	}

	/** Best food to eat right now: the one that wastes the least. */
	public static int bestFood(int missingHunger) {
		int best = -1;
		int bestWaste = Integer.MAX_VALUE;
		for (int i = 0; i < SIZE; i++) {
			ItemStack s = get(i);
			if (!safeFood(s)) continue;
			int waste = Math.abs(nutrition(s) - missingHunger);
			if (waste < bestWaste) {
				bestWaste = waste;
				best = i;
			}
		}
		return best;
	}
}
