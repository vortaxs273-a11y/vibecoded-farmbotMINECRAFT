package dev.farmbot;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Incremental block finder over loaded chunks, nearest chunks first. Uses section palettes to skip sections that
 * can't contain the target, so hunting ores across a dozen chunks costs a few milliseconds per tick.
 */
public final class Scanner {
	private final Predicate<BlockState> pred;
	private final List<int[]> chunks = new ArrayList<>();
	private final int minY, maxY;
	private final int limit;
	private int ci;
	public final List<BlockPos> found = new ArrayList<>();
	private boolean done;
	private Predicate<BlockPos> posFilter;

	public Scanner filter(Predicate<BlockPos> f) {
		this.posFilter = f;
		return this;
	}

	public Scanner(Predicate<BlockState> pred, BlockPos center, int chunkRadius, int minY, int maxY, int limit) {
		this.pred = pred;
		ClientLevel l = W.lvl();
		this.minY = Math.max(minY, Compat.minY(l));
		this.maxY = Math.min(maxY, Compat.maxY(l));
		this.limit = limit;
		int ccx = center.getX() >> 4, ccz = center.getZ() >> 4;
		for (int x = -chunkRadius; x <= chunkRadius; x++)
			for (int z = -chunkRadius; z <= chunkRadius; z++) chunks.add(new int[]{ccx + x, ccz + z});
		chunks.sort(Comparator.comparingInt(c -> (c[0] - ccx) * (c[0] - ccx) + (c[1] - ccz) * (c[1] - ccz)));
	}

	public boolean done() {
		return done;
	}

	/** Returns true when finished. */
	public boolean step(long deadline) {
		if (done) return true;
		ClientLevel l = W.lvl();
		while (ci < chunks.size()) {
			if (System.nanoTime() > deadline) return false;
			int[] c = chunks.get(ci++);
			LevelChunk ch = Compat.chunk(l, c[0], c[1]);
			if (ch == null) continue;
			LevelChunkSection[] secs = ch.getSections();
			for (int si = 0; si < secs.length; si++) {
				LevelChunkSection sec = secs[si];
				if (sec == null || sec.hasOnlyAir()) continue;
				int baseY = ch.getSectionYFromSectionIndex(si) << 4;
				if (baseY + 15 < minY || baseY > maxY) continue;
				if (!sec.maybeHas(pred)) continue;
				for (int y = 0; y < 16; y++) {
					int wy = baseY + y;
					if (wy < minY || wy > maxY) continue;
					for (int z = 0; z < 16; z++)
						for (int x = 0; x < 16; x++) {
							if (!pred.test(sec.getBlockState(x, y, z))) continue;
							BlockPos bp = new BlockPos((c[0] << 4) + x, wy, (c[1] << 4) + z);
							if (posFilter == null || posFilter.test(bp)) found.add(bp);
						}
				}
			}
			if (found.size() >= limit) break;
		}
		done = true;
		return true;
	}

	/** Blocking convenience for small scans. */
	public static List<BlockPos> now(Predicate<BlockState> pred, BlockPos center, int chunkRadius, int minY, int maxY, int limit) {
		Scanner s = new Scanner(pred, center, chunkRadius, minY, maxY, limit);
		s.step(Long.MAX_VALUE);
		return s.found;
	}
}
