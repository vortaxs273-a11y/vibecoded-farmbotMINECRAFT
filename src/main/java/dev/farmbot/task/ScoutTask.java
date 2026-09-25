package dev.farmbot.task;

import dev.farmbot.Bot;
import dev.farmbot.Compat;
import dev.farmbot.Config;
import dev.farmbot.Guard;
import dev.farmbot.W;
import dev.farmbot.path.Goal;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Looks for a big square of flat, dry, UNTOUCHED land: no player-made blocks anywhere in it or its margin, and
 * nobody seen around. If nothing visible qualifies, it walks off in a new direction and looks again.
 */
public final class ScoutTask extends Task {
	record Info(boolean dirty, int[] heights, double water, double plains) {}

	private static final Map<Long, Info> CACHE = new HashMap<>();
	private static String cacheKey = "";
	private final Random rnd = new Random();
	private enum Phase { ANALYZE, TRAVEL }
	private Phase phase = Phase.ANALYZE;
	private List<int[]> todo;
	private static int hops;
	private double dirAngle = Double.NaN;
	private BlockPos target;

	@Override
	public String name() {
		return "scouting for untouched land" + (hops > 0 ? " (hop " + hops + ")" : "");
	}

	@Override
	public int timeout() {
		return 20 * 60 * 60;
	}

	@Override
	public S tick(Bot b) {
		// hungry with nothing to eat: stop and let the brain go hunt, then we carry on scouting
		if (b.p.getFoodData().getFoodLevel() <= 14 && dev.farmbot.Inv.foodValue() < 6 && age > 20) {
			b.nav.reset();
			return S.OK;
		}
		String key = Compat.serverKey();
		if (!key.equals(cacheKey)) {
			CACHE.clear();
			cacheKey = key;
		}
		if (phase == Phase.ANALYZE) {
			if (todo == null) {
				todo = new ArrayList<>();
				int pcx = b.p.getBlockX() >> 4, pcz = b.p.getBlockZ() >> 4;
				for (int x = -12; x <= 12; x++)
					for (int z = -12; z <= 12; z++) {
						int cx = pcx + x, cz = pcz + z;
						if (!CACHE.containsKey(key(cx, cz)) && Compat.hasChunk(b.lvl, cx, cz)) todo.add(new int[]{cx, cz});
					}
			}
			long deadline = System.nanoTime() + 5_000_000L;
			while (!todo.isEmpty() && System.nanoTime() < deadline) {
				int[] c = todo.remove(todo.size() - 1);
				Info i = analyze(b, c[0], c[1]);
				if (i != null) CACHE.put(key(c[0], c[1]), i);
			}
			if (!todo.isEmpty()) return S.RUN;
			todo = null;
			int[] site = evaluate(b);
			if (site != null) {
				b.state.hasSite = true;
				b.state.cx = site[0];
				b.state.cy = site[1];
				b.state.cz = site[2];
				b.state.cells.clear();
				b.state.infiniteSite = Config.I.infinite;
				b.state.save();
				Compat.localMessage("[FarmBot] found untouched land at " + site[0] + " " + site[1] + " " + site[2] + ". farm farm farm.");
				return S.OK;
			}
			pickDirection(b);
			phase = Phase.TRAVEL;
			hops++;
		}
		// TRAVEL
		S s = b.nav.goTo(new Goal.XZ(target.getX(), target.getZ(), 6), true);
		if (s == S.OK || age % (20 * 90) == 0) {
			phase = Phase.ANALYZE;
			b.nav.reset();
		} else if (s == S.FAIL) {
			dirAngle += Math.PI / 2 + rnd.nextDouble();
			pickDirection(b);
		}
		return S.RUN;
	}

	private static long key(int cx, int cz) {
		return ((long) cx << 32) ^ (cz & 0xffffffffL);
	}

	private Info analyze(Bot b, int cx, int cz) {
		LevelChunk ch = Compat.chunk(b.lvl, cx, cz);
		if (ch == null) return null;
		int[] hs = new int[16];
		int water = 0, n = 0, plains = 0;
		int minH = Integer.MAX_VALUE;
		for (int lx = 1; lx < 16; lx += 4)
			for (int lz = 1; lz < 16; lz += 4) {
				int x = (cx << 4) + lx, z = (cz << 4) + lz;
				int gy = W.groundFeetY(x, z);
				hs[n++] = gy;
				var biome = b.lvl.getBiome(new BlockPos(x, gy, z));
				if (biome.is(Biomes.PLAINS) || biome.is(Biomes.SUNFLOWER_PLAINS)) plains++;
				minH = Math.min(minH, gy);
				if (W.isWater(W.st(x, gy - 1, z)) || W.isWater(W.st(x, gy, z))) water++;
			}
		boolean dirty = false;
		LevelChunkSection[] secs = ch.getSections();
		outer:
		for (int si = 0; si < secs.length; si++) {
			LevelChunkSection sec = secs[si];
			if (sec == null || sec.hasOnlyAir()) continue;
			int baseY = ch.getSectionYFromSectionIndex(si) << 4;
			if (baseY + 15 < minH - 12) continue;
			if (!sec.maybeHas(Guard::artificial)) continue;
			for (int y = 0; y < 16; y++)
				for (int z = 0; z < 16; z++)
					for (int x = 0; x < 16; x++) {
						BlockState s = sec.getBlockState(x, y, z);
						if (Guard.artificial(s)) {
							dirty = true;
							break outer;
						}
					}
		}
		return new Info(dirty, hs, water / 16.0, plains / 16.0);
	}

	private boolean nearSighting(Bot b, int x, int z) {
		int r = Bot.I.mc.getCurrentServer() != null ? Math.max(160, Config.I.avoidPlayerRadius) : Config.I.avoidPlayerRadius;
		for (long[] s : b.state.playerSightings) {
			long dx = s[0] - x, dz = s[1] - z;
			if (dx * dx + dz * dz < (long) r * r) return true;
		}
		return false;
	}

	/** Returns {x, feetY, z} of the best site centre, or null. */
	private int[] evaluate(Bot b) {
		// the longer we search, the less picky we get
		int K = hops > 8 ? Math.max(4, Config.I.siteChunks - 3) : hops > 4 ? Math.max(5, Config.I.siteChunks - 2) : Config.I.siteChunks;
		int M = hops > 6 ? Math.max(1, Config.I.marginChunks - 1) : Config.I.marginChunks;
		// on a server, keep well clear of other people's land (claims extend past their builds)
		if (b.mc.getCurrentServer() != null) M = Math.max(M, 4);
		double maxStd = hops > 6 ? 3.5 : 2.5;
		double minPlains = hops > 8 ? 0.7 : hops > 4 ? 0.8 : 0.88;
		int pcx = b.p.getBlockX() >> 4, pcz = b.p.getBlockZ() >> 4;
		int[] best = null;
		double bestScore = Double.MAX_VALUE;
		for (int ox = -12; ox <= 12; ox++)
			for (int oz = -12; oz <= 12; oz++) {
				int x0 = pcx + ox, z0 = pcz + oz;
				boolean ok = true;
				for (int x = x0 - M; x < x0 + K + M && ok; x++)
					for (int z = z0 - M; z < z0 + K + M && ok; z++) {
						Info i = CACHE.get(key(x, z));
						if (i == null || i.dirty) ok = false;
					}
				if (!ok) continue;
				List<Integer> all = new ArrayList<>();
				double water = 0, plains = 0;
				for (int x = x0; x < x0 + K; x++)
					for (int z = z0; z < z0 + K; z++) {
						Info i = CACHE.get(key(x, z));
						for (int h : i.heights) all.add(h);
						water += i.water;
						plains += i.plains;
					}
				water /= (K * K);
				plains /= (K * K);
				if (Config.I.infinite && plains < minPlains) continue;
				double mean = all.stream().mapToInt(Integer::intValue).average().orElse(0);
				double var = all.stream().mapToDouble(h -> (h - mean) * (h - mean)).average().orElse(0);
				double std = Math.sqrt(var);
				if (std > maxStd || water > 0.12) continue;
				int cx = (x0 << 4) + K * 8, cz = (z0 << 4) + K * 8;
				if (nearSighting(b, cx, cz)) continue;
				int[] sorted = all.stream().mapToInt(Integer::intValue).toArray();
				Arrays.sort(sorted);
				int median = sorted[sorted.length / 2];
				double dist = Math.sqrt(Math.pow(cx - b.p.getX(), 2) + Math.pow(cz - b.p.getZ(), 2));
				double score = std * 3 + water * 40 + dist / 64.0;
				if (score < bestScore) {
					bestScore = score;
					best = new int[]{cx, median, cz};
				}
			}
		return best;
	}

	private void pickDirection(Bot b) {
		if (Config.I.infinite) {
			// infinite mode: head for the biggest patch of plains we can see
			double sx = 0, sz = 0;
			int pcx = b.p.getBlockX() >> 4, pcz = b.p.getBlockZ() >> 4;
			int n = 0;
			for (Map.Entry<Long, Info> e : CACHE.entrySet()) {
				if (e.getValue().plains < 0.9 || e.getValue().dirty) continue;
				int dx = (int) (e.getKey() >> 32) - pcx, dz = (int) (long) e.getKey() - pcz;
				if (dx * dx + dz * dz > 14 * 14) continue;
				sx += dx;
				sz += dz;
				n++;
			}
			// plains nearby: head into the middle of them. otherwise keep exploring.
			if (n >= 6 && (sx * sx + sz * sz) > (double) n * n) {
				dirAngle = Math.atan2(sz, sx);
				target = new BlockPos((int) (b.p.getX() + sx / n * 16), 0, (int) (b.p.getZ() + sz / n * 16));
				return;
			}
		}
		if (Double.isNaN(dirAngle)) {
			// head away from the mess we can see
			double sx = 0, sz = 0;
			int pcx = b.p.getBlockX() >> 4, pcz = b.p.getBlockZ() >> 4;
			for (Map.Entry<Long, Info> e : CACHE.entrySet()) {
				if (!e.getValue().dirty) continue;
				int cx = (int) (e.getKey() >> 32), cz = (int) (long) e.getKey();
				sx += cx - pcx;
				sz += cz - pcz;
			}
			dirAngle = (sx == 0 && sz == 0) ? rnd.nextDouble() * Math.PI * 2 : Math.atan2(-sz, -sx);
		} else {
			dirAngle += (rnd.nextDouble() - 0.5) * 0.8;
		}
		// don't wander into oceans: of 8 headings near the current one, take the driest
		int pcx0 = b.p.getBlockX() >> 4, pcz0 = b.p.getBlockZ() >> 4;
		double bestA = dirAngle, bestScore = Double.MAX_VALUE;
		for (int k = 0; k < 8; k++) {
			double a = dirAngle + k * Math.PI / 4;
			double wet = 0;
			for (int r = 2; r <= 12; r++) {
				Info i = CACHE.get(key(pcx0 + (int) Math.round(Math.cos(a) * r), pcz0 + (int) Math.round(Math.sin(a) * r)));
				if (i != null) wet += i.water() + (Config.I.infinite ? (1 - i.plains()) * 0.3 : 0);
			}
			double score = wet * 10 + Math.min(k, 8 - k); // prefer keeping roughly the same heading
			if (score < bestScore) {
				bestScore = score;
				bestA = a;
			}
		}
		dirAngle = bestA;
		int step = Config.I.exploreStep;
		target = new BlockPos((int) (b.p.getX() + Math.cos(dirAngle) * step), 0, (int) (b.p.getZ() + Math.sin(dirAngle) * step));
	}
}
