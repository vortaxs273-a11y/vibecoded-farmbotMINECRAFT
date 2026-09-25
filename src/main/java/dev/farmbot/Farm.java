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
	public static List<int[]> cellOrder(int maxRing) {
		List<int[]> out = new ArrayList<>();
		for (int i = -maxRing; i <= maxRing; i++)
			for (int j = -maxRing; j <= maxRing; j++) out.add(new int[]{i, j});
		out.sort(Comparator.comparingInt((int[] c) -> Math.max(Math.abs(c[0]), Math.abs(c[1])))
			.thenComparingInt(c -> c[0] * c[0] + c[1] * c[1]));
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

	public static boolean inFarmArea(int x, int z, int margin) {
		if (!s().hasSite) return false;
		int r = (Config.I.maxRings * SIZE) + HALF + margin;
		return Math.abs(x - s().cx) <= r && Math.abs(z - s().cz) <= r;
	}
}
