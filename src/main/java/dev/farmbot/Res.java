package dev.farmbot;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.function.Predicate;

/** Resources the planner reasons about. Some are exact items, some are families (any log, any planks...). */
public enum Res {
	LOG(s -> s.is(ItemTags.LOGS)),
	PLANKS(s -> s.is(ItemTags.PLANKS)),
	STICK(Items.STICK),
	CRAFTING_TABLE(Items.CRAFTING_TABLE),
	COBBLE(s -> s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE)),
	DIRT(s -> s.is(Items.DIRT)),
	FURNACE(Items.FURNACE),
	CHEST(Items.CHEST),
	WHITE_WOOL(Items.WHITE_WOOL),
	BED(s -> s.is(ItemTags.BEDS)),
	WOOD_PICK(Items.WOODEN_PICKAXE),
	STONE_PICK(Items.STONE_PICKAXE),
	STONE_AXE(Items.STONE_AXE),
	STONE_SHOVEL(Items.STONE_SHOVEL),
	STONE_SWORD(Items.STONE_SWORD),
	STONE_HOE(Items.STONE_HOE),
	RAW_IRON(Items.RAW_IRON),
	IRON_INGOT(Items.IRON_INGOT),
	IRON_PICK(Items.IRON_PICKAXE),
	IRON_SWORD(Items.IRON_SWORD),
	IRON_HOE(Items.IRON_HOE),
	BUCKET(Items.BUCKET),
	WATER_BUCKET(Items.WATER_BUCKET),
	GRAVEL(Items.GRAVEL),
	FLINT(Items.FLINT),
	FLINT_AND_STEEL(Items.FLINT_AND_STEEL),
	SHIELD(Items.SHIELD),
	COAL(s -> s.is(Items.COAL) || s.is(Items.CHARCOAL)),
	TORCH(Items.TORCH),
	WHEAT(Items.WHEAT),
	SEEDS(Items.WHEAT_SEEDS),
	BREAD(Items.BREAD),
	IRON_HELMET(Items.IRON_HELMET),
	IRON_CHESTPLATE(Items.IRON_CHESTPLATE),
	IRON_LEGGINGS(Items.IRON_LEGGINGS),
	IRON_BOOTS(Items.IRON_BOOTS);

	public final Predicate<ItemStack> pred;

	Res(Item item) {
		this.pred = s -> s.is(item);
	}

	Res(Predicate<ItemStack> pred) {
		this.pred = pred;
	}
}
