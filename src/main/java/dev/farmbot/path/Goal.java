package dev.farmbot.path;

import net.minecraft.core.BlockPos;

/** Where a path should end. Records, so "same goal as last tick" is a plain equals(). */
public interface Goal {
	double WALK = 4.63;

	boolean test(int x, int y, int z);

	double h(int x, int y, int z);

	/** Stand exactly here. */
	record Block(int x, int y, int z) implements Goal {
		public Block(BlockPos p) {
			this(p.getX(), p.getY(), p.getZ());
		}

		public boolean test(int a, int b, int c) {
			return a == x && b == y && c == z;
		}

		public double h(int a, int b, int c) {
			double dx = a - x, dy = b - y, dz = c - z;
			return Math.sqrt(dx * dx + dy * dy + dz * dz) * WALK;
		}
	}

	/** Stand within r (box) of a position. */
	record Near(int x, int y, int z, int r) implements Goal {
		public Near(BlockPos p, int r) {
			this(p.getX(), p.getY(), p.getZ(), r);
		}

		public boolean test(int a, int b, int c) {
			return Math.abs(a - x) <= r && Math.abs(b - y) <= r && Math.abs(c - z) <= r;
		}

		public double h(int a, int b, int c) {
			double dx = Math.max(0, Math.abs(a - x) - r), dy = Math.max(0, Math.abs(b - y) - r), dz = Math.max(0, Math.abs(c - z) - r);
			return Math.sqrt(dx * dx + dy * dy + dz * dz) * WALK;
		}
	}

	/** Stand right next to a block (touching distance), like a player walking up to it. Never inside it. */
	record Reach(int x, int y, int z, double r) implements Goal {
		public Reach(BlockPos p, double r) {
			this(p.getX(), p.getY(), p.getZ(), r);
		}

		public boolean test(int a, int b, int c) {
			int dx = a - x, dz = c - z, dy = y - b;
			if (dx == 0 && dz == 0) return false;
			return Math.abs(dx) <= 1 && Math.abs(dz) <= 1 && dy >= -1 && dy <= 2;
		}

		public double h(int a, int b, int c) {
			double dx = a - x, dy = b - y, dz = c - z;
			return Math.sqrt(dx * dx + dy * dy + dz * dz) * WALK;
		}
	}

	/** Get to a column, any height. */
	record XZ(int x, int z, int r) implements Goal {
		public boolean test(int a, int b, int c) {
			return Math.abs(a - x) <= r && Math.abs(c - z) <= r;
		}

		public double h(int a, int b, int c) {
			double dx = a - x, dz = c - z;
			return Math.max(0, Math.sqrt(dx * dx + dz * dz) - r) * WALK;
		}
	}
}
