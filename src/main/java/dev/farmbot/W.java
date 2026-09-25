package dev.farmbot;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.shapes.VoxelShape;

/** World queries. Everything here is cheap and side-effect free. */
public final class W {
	private W() {}

	public static ClientLevel lvl() {
		return Compat.mc().level;
	}

	public static BlockState st(BlockPos p) {
		return lvl().getBlockState(p);
	}

	public static BlockState st(int x, int y, int z) {
		return lvl().getBlockState(new BlockPos(x, y, z));
	}

	public static boolean loaded(int x, int z) {
		return Compat.hasChunk(lvl(), x >> 4, z >> 4);
	}

	public static boolean loaded(BlockPos p) {
		return loaded(p.getX(), p.getZ());
	}

	public static boolean isLava(BlockState s) {
		return s.getFluidState().is(FluidTags.LAVA);
	}

	public static boolean isWater(BlockState s) {
		return s.getFluidState().is(FluidTags.WATER);
	}

	public static boolean isWaterSource(BlockState s) {
		return s.is(Blocks.WATER) && s.getFluidState().isSource();
	}

	public static boolean isLiquidBlock(BlockState s) {
		return s.getBlock() instanceof LiquidBlock;
	}

	public static boolean isFire(BlockState s) {
		return s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE);
	}

	/** Things you never want your body inside of. */
	public static boolean hurtsBody(BlockState s) {
		Block b = s.getBlock();
		return isLava(s) || isFire(s) || b == Blocks.SWEET_BERRY_BUSH || b == Blocks.COBWEB || b == Blocks.POWDER_SNOW
			|| b == Blocks.WITHER_ROSE || b == Blocks.CACTUS || b == Blocks.CAMPFIRE || b == Blocks.SOUL_CAMPFIRE
			|| b == Blocks.MAGMA_BLOCK || b == Blocks.POINTED_DRIPSTONE || b == Blocks.END_PORTAL || b == Blocks.NETHER_PORTAL;
	}

	/** Things you never want to stand on. */
	public static boolean badFloor(BlockState s) {
		Block b = s.getBlock();
		return b == Blocks.MAGMA_BLOCK || b == Blocks.CAMPFIRE || b == Blocks.SOUL_CAMPFIRE || b == Blocks.CACTUS
			|| b == Blocks.POINTED_DRIPSTONE || b == Blocks.POWDER_SNOW || isLava(s) || isFire(s) || b == Blocks.SCAFFOLDING;
	}

	public static boolean passable(BlockState s, BlockPos p) {
		if (hurtsBody(s)) return false;
		return s.getCollisionShape(lvl(), p).isEmpty();
	}

	public static boolean passable(BlockPos p) {
		return passable(st(p), p);
	}

	public static boolean solid(BlockPos p) {
		BlockState s = st(p);
		return !s.getCollisionShape(lvl(), p).isEmpty();
	}

	public static boolean standable(BlockState s, BlockPos floor) {
		if (badFloor(s)) return false;
		VoxelShape sh = s.getCollisionShape(lvl(), floor);
		if (sh.isEmpty()) return false;
		double top = sh.max(Direction.Axis.Y);
		return top > 0.4 && top <= 1.0;
	}

	public static boolean standable(BlockPos floor) {
		return standable(st(floor), floor);
	}

	public static boolean sturdyTop(BlockPos p) {
		return st(p).isFaceSturdy(lvl(), p, Direction.UP);
	}

	public static boolean lavaNear(BlockPos p) {
		if (isLava(st(p))) return true;
		for (Direction d : Direction.values()) if (isLava(st(p.relative(d)))) return true;
		return false;
	}

	public static boolean waterNear(BlockPos p) {
		for (Direction d : Direction.values()) {
			if (d == Direction.DOWN) continue;
			if (isWater(st(p.relative(d)))) return true;
		}
		return false;
	}

	public static boolean fallingAbove(BlockPos p) {
		return st(p.above()).getBlock() instanceof FallingBlock;
	}

	public static boolean isPlant(BlockState s) {
		if (s.isAir()) return false;
		if (s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS)) return false;
		if (isLiquidBlock(s)) return false;
		return s.is(BlockTags.REPLACEABLE) || s.is(BlockTags.FLOWERS) || s.is(BlockTags.SAPLINGS)
			|| s.is(Blocks.SHORT_GRASS) || s.is(Blocks.TALL_GRASS) || s.is(Blocks.FERN) || s.is(Blocks.LARGE_FERN)
			|| s.is(Blocks.DEAD_BUSH) || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.SNOW)
			|| s.is(Blocks.BROWN_MUSHROOM) || s.is(Blocks.RED_MUSHROOM) || s.is(Blocks.SUGAR_CANE);
	}

	public static boolean isTillable(BlockState s) {
		return s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT) || s.is(Blocks.DIRT_PATH);
	}

	public static boolean isMatureWheat(BlockState s) {
		return s.is(Blocks.WHEAT) && s.getBlock() instanceof CropBlock c && c.isMaxAge(s);
	}

	/** Feet-level Y of natural ground in a column, ignoring trees and plants. Integer.MIN_VALUE when unloaded. */
	public static int groundFeetY(int x, int z) {
		if (!loaded(x, z)) return Integer.MIN_VALUE;
		ClientLevel l = lvl();
		int y = l.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
		int min = Compat.minY(l);
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int i = 0; i < 48 && y > min; i++, y--) {
			BlockState s = l.getBlockState(m.set(x, y, z));
			if (s.isAir() || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || isPlant(s)) continue;
			return y + 1;
		}
		return y + 1;
	}

	public static double distSq(BlockPos a, double x, double y, double z) {
		double dx = a.getX() + 0.5 - x, dy = a.getY() + 0.5 - y, dz = a.getZ() + 0.5 - z;
		return dx * dx + dy * dy + dz * dz;
	}
}
