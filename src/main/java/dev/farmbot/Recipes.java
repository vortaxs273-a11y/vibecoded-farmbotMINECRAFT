package dev.farmbot;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Hand-written crafting patterns. We click them into the grid ourselves; no recipe book needed. */
public final class Recipes {
	public record Recipe(Res out, int count, boolean table, String[] rows, Map<Character, Res> key) {
		public Map<Res, Integer> totals() {
			Map<Res, Integer> m = new LinkedHashMap<>();
			for (String r : rows)
				for (char c : r.toCharArray())
					if (c != ' ') m.merge(key.get(c), 1, Integer::sum);
			return m;
		}
	}

	public record Smelt(Res out, Res in) {}

	public static final Map<Res, Recipe> CRAFT = new EnumMap<>(Res.class);
	public static final Map<Res, Smelt> SMELT = new EnumMap<>(Res.class);

	private static void r(Res out, int count, String[] rows, Object... key) {
		Map<Character, Res> k = new LinkedHashMap<>();
		for (int i = 0; i < key.length; i += 2) k.put((Character) key[i], (Res) key[i + 1]);
		int w = 0;
		for (String row : rows) w = Math.max(w, row.length());
		boolean table = rows.length > 2 || w > 2;
		CRAFT.put(out, new Recipe(out, count, table, rows, k));
	}

	static {
		r(Res.PLANKS, 4, new String[]{"L"}, 'L', Res.LOG);
		r(Res.STICK, 4, new String[]{"P", "P"}, 'P', Res.PLANKS);
		r(Res.CRAFTING_TABLE, 1, new String[]{"PP", "PP"}, 'P', Res.PLANKS);
		r(Res.WOOD_PICK, 1, new String[]{"PPP", " S ", " S "}, 'P', Res.PLANKS, 'S', Res.STICK);
		r(Res.STONE_PICK, 1, new String[]{"CCC", " S ", " S "}, 'C', Res.COBBLE, 'S', Res.STICK);
		r(Res.STONE_AXE, 1, new String[]{"CC", "CS", " S"}, 'C', Res.COBBLE, 'S', Res.STICK);
		r(Res.STONE_SHOVEL, 1, new String[]{"C", "S", "S"}, 'C', Res.COBBLE, 'S', Res.STICK);
		r(Res.STONE_SWORD, 1, new String[]{"C", "C", "S"}, 'C', Res.COBBLE, 'S', Res.STICK);
		r(Res.STONE_HOE, 1, new String[]{"CC", " S", " S"}, 'C', Res.COBBLE, 'S', Res.STICK);
		r(Res.FURNACE, 1, new String[]{"CCC", "C C", "CCC"}, 'C', Res.COBBLE);
		r(Res.CHEST, 1, new String[]{"PPP", "P P", "PPP"}, 'P', Res.PLANKS);
		r(Res.IRON_PICK, 1, new String[]{"III", " S ", " S "}, 'I', Res.IRON_INGOT, 'S', Res.STICK);
		r(Res.IRON_SWORD, 1, new String[]{"I", "I", "S"}, 'I', Res.IRON_INGOT, 'S', Res.STICK);
		r(Res.IRON_HOE, 1, new String[]{"II", " S", " S"}, 'I', Res.IRON_INGOT, 'S', Res.STICK);
		r(Res.BUCKET, 1, new String[]{"I I", " I "}, 'I', Res.IRON_INGOT);
		r(Res.FLINT_AND_STEEL, 1, new String[]{"IF"}, 'I', Res.IRON_INGOT, 'F', Res.FLINT);
		r(Res.SHIELD, 1, new String[]{"PIP", "PPP", " P "}, 'P', Res.PLANKS, 'I', Res.IRON_INGOT);
		r(Res.TORCH, 4, new String[]{"K", "S"}, 'K', Res.COAL, 'S', Res.STICK);
		r(Res.BREAD, 1, new String[]{"WWW"}, 'W', Res.WHEAT);
		r(Res.IRON_HELMET, 1, new String[]{"III", "I I"}, 'I', Res.IRON_INGOT);
		r(Res.IRON_CHESTPLATE, 1, new String[]{"I I", "III", "III"}, 'I', Res.IRON_INGOT);
		r(Res.IRON_LEGGINGS, 1, new String[]{"III", "I I", "I I"}, 'I', Res.IRON_INGOT);
		r(Res.IRON_BOOTS, 1, new String[]{"I I", "I I"}, 'I', Res.IRON_INGOT);

		SMELT.put(Res.IRON_INGOT, new Smelt(Res.IRON_INGOT, Res.RAW_IRON));
		SMELT.put(Res.COAL, new Smelt(Res.COAL, Res.LOG));
	}

	private Recipes() {}
}
