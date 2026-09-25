package dev.farmbot;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Farm geometry. The farm is a grid of 9x9 cells around the site centre. Every cell has a water source in the
 * middle (hydrates the whole 9x9), torches on the four corners, farmland + wheat everywhere else.
 * Cell (0,0) is home: a 2x2 infinite water pool plus crafting table, furnace and chests.
 */
public final class Farm {
	public static final int SIZE = 9, HALF = 4;

	public enum Tile { FARM, WATER, TORCH, UTIL }

	public static final int[][] POOL = {{0, 0}, {1, 0}, {0, 1}, {1, 1}};
	public static final int[] TABLE = {-3, -3};
	public static final int[] FURNACE = {-3, -2};
	public static final int[][] CHESTS = {{3, -3}, {3, -2}, {3, 2}, {3, 3}, {-3, 2}, {-3, 3}, {2, -3}, {2, 3}};

	private Farm() {}

	static FarmState s() {
		return Bot.I.state;
	}

	public static int H() {
		return s().cy;
	}

	public static int centerX(int i) {
		return s().cx + i * SIZE;
	}

	public static int centerZ(int j) {
		return s().cz + j * SIZE;
	}

	public static int cellOfX(int x) {
		return Math.floorDiv(x - s().cx + HALF, SIZE);
	}

	public static int cellOfZ(int z) {
		return Math.floorDiv(z - s().cz + HALF, SIZE);
	}

	public static BlockPos home() {
		return new BlockPos(s().cx, s().cy, s().cz);
	}

	public static BlockPos homeSpot(int[] d) {
		return new BlockPos(s().cx + d[0], s().cy, s().cz + d[1]);
	}

	public static Tile tile(int i, int j, int dx, int dz) {
		if (Math.abs(dx) == HALF && Math.abs(dz) == HALF) return Tile.TORCH;
		if (i == 0 && j == 0) {
			for (int[] p : POOL) if (p[0] == dx && p[1] == dz) return Tile.WATER;
			// crop-free walkway around the pool so bucket ray-traces never clip wheat
			if (dx >= -1 && dx <= 2 && dz >= -1 && dz <= 2) return Tile.UTIL;
			if (dx == TABLE[0] && dz == TABLE[1]) return Tile.UTIL;
			if (dx == FURNACE[0] && dz == FURNACE[1]) return Tile.UTIL;
			for (int[] c : CHESTS) if (c[0] == dx && c[1] == dz) return Tile.UTIL;
			return Tile.FARM;
		}
		if (dx == 0 && dz == 0) return Tile.WATER;
		return Tile.FARM;
	}

	public static Tile tileAt(int x, int z) {
		int i = cellOfX(x), j = cellOfZ(z);
		return tile(i, j, x - centerX(i), z - centerZ(j));
	}

	public static boolean inBuiltCell(int x, int z) {
		if (!s().hasSite) return false;
		int i = cellOfX(x), j = cellOfZ(z);
		return (i == 0 && j == 0) || s().cell(i, j) == FarmState.BUILT;
	}

	/** Farm blocks the pathfinder must never dig through. */
	public static boolean isFarmProtected(BlockPos p) {
		if (!s().hasSite) return false;
		int y = p.getY(), H = H();
		if (y < H - 2 || y > H + 1) return false;
		return inBuiltCell(p.getX(), p.getZ());
	}

	/** All cells within the ring limit, ordered by distance from home: the expansion order. */
	private static int orderRing = -1;
	private static List<int[]> orderCache;

	public static List<int[]> cellOrder(int maxRing) {
		if (maxRing == orderRing) return orderCache;
		List<int[]> out = new ArrayList<>();
		for (int i = -maxRing; i <= maxRing; i++)
			for (int j = -maxRing; j <= maxRing; j++) out.add(new int[]{i, j});
		out.sort(Comparator.comparingInt((int[] c) -> Math.max(Math.abs(c[0]), Math.abs(c[1])))
			.thenComparingInt(c -> c[0] * c[0] + c[1] * c[1]));
		orderRing = maxRing;
		orderCache = out;
		return out;
	}

	public static List<int[]> builtCells() {
		List<int[]> out = new ArrayList<>();
		if (!s().hasSite) return out;
		for (int[] c : cellOrder(Config.I.maxRings)) {
			if ((c[0] == 0 && c[1] == 0 && s().cell(0, 0) == FarmState.BUILT) || s().cell(c[0], c[1]) == FarmState.BUILT) out.add(c);
		}
		return out;
	}

	/** Every place a chest can go: home spots first, then row after row in each storage plot. */
	public static List<BlockPos> chestSpots() {
		List<BlockPos> out = new ArrayList<>();
		for (int[] c : CHESTS) out.add(homeSpot(c));
		for (String key : s().storageCells) {
			String[] ij = key.split(",");
			int cx = centerX(Integer.parseInt(ij[0])), cz = centerZ(Integer.parseInt(ij[1]));
			// columns of chests with a walkway between every column; every chest touches a walkway
			for (int dx : new int[]{-3, -1, 1, 3})
				for (int dz = -HALF; dz <= HALF; dz++) out.add(new BlockPos(cx + dx, s().cy, cz + dz));
		}
		return out;
	}

	/** Next free chest spot, or null if every storage plot is full. */
	public static BlockPos nextChestSpot() {
		for (BlockPos p : chestSpots()) {
			long l = p.asLong();
			if (!s().chests.contains(l) && !s().badSpots.contains(l)) return p;
		}
		return null;
	}

	/** Turn the nearest unused plot into a chest yard. */
	public static boolean claimStoragePlot() {
		for (int pass = 0; pass < 2; pass++) {
			for (int[] c : cellOrder(Math.max(Config.I.maxRings, 3))) {
				if (c[0] == 0 && c[1] == 0) continue;
				int st = s().cell(c[0], c[1]);
				if (st != (pass == 0 ? FarmState.NONE : FarmState.SKIPPED)) continue;
				s().storageCells.add(c[0] + "," + c[1]);
				s().setCell(c[0], c[1], FarmState.STORAGE);
				return true;
			}
		}
		return false;
	}

	/** Rings that are built or about to be: the area to keep pristine. */
	private static long ringsAt = -1;
	private static int ringsCache;

	public static int activeRings() {
		if (ringsAt == Bot.I.tick) return ringsCache;
		int max = 0;
		for (java.util.Map.Entry<String, Integer> e : s().cells.entrySet()) {
			if (e.getValue() != FarmState.BUILT) continue;
			String[] ij = e.getKey().split(",");
			max = Math.max(max, Math.max(Math.abs(Integer.parseInt(ij[0])), Math.abs(Integer.parseInt(ij[1]))));
		}
		ringsAt = Bot.I.tick;
		ringsCache = Math.min(Config.I.maxRings, max + 2);
		return ringsCache;
	}

	public static boolean inFarmArea(int x, int z, int margin) {
		if (!s().hasSite) return false;
		int r = (activeRings() * SIZE) + HALF + margin;
		return Math.abs(x - s().cx) <= r && Math.abs(z - s().cz) <= r;
	}
}
