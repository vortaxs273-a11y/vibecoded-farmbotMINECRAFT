package dev.farmbot.task;

import dev.farmbot.Act;
import dev.farmbot.Bot;
import dev.farmbot.Compat;
import dev.farmbot.Farm;
import dev.farmbot.Inv;
import dev.farmbot.Recipes.Recipe;
import dev.farmbot.Res;
import dev.farmbot.W;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

/** Crafts by clicking items into the 2x2 inventory grid or a crafting table, exactly like a player would. */
public final class CraftTask extends Task {
	private final Recipe r;
	private final int times;
	private enum Ph { PREP, PLACE_TABLE, OPEN, FILL, WAIT, TAKE, CLEAN, PICKUP }
	private Ph ph = Ph.PREP;
	private BlockPos table;
	private boolean temp;
	private int wait;
	private final Do.Collector collector = new Do.Collector();

	public CraftTask(Recipe r, int times) {
		this.r = r;
		this.times = Math.max(1, Math.min(64, times));
	}

	@Override
	public String name() {
		return "crafting " + r.out().name().toLowerCase() + " x" + (times * r.count());
	}

	@Override
	public int timeout() {
		return 20 * 90;
	}

	@Override
	public S tick(Bot b) {
		switch (ph) {
			case PREP -> {
				if (!r.table()) {
					if (!Inv.noScreenMenu()) b.p.closeContainer();
					ph = Ph.FILL;
					return S.RUN;
				}
				table = findTable(b);
				if (table != null) {
					ph = Ph.OPEN;
					return S.RUN;
				}
				if (Inv.count(Res.CRAFTING_TABLE) == 0) return fail("no crafting table");
				table = spotNear(b);
				if (table == null) return fail("nowhere to put a table");
				temp = true;
				ph = Ph.PLACE_TABLE;
			}
			case PLACE_TABLE -> {
				S s = Do.placeAt(b, table, Res.CRAFTING_TABLE.pred, st -> st.is(Blocks.CRAFTING_TABLE));
				if (s == S.FAIL || age > 400 && s == S.RUN && !W.st(table).is(Blocks.CRAFTING_TABLE)) return fail("could not place table");
				if (s == S.OK) {
					b.state.ours.add(table.asLong());
					ph = Ph.OPEN;
				}
			}
			case OPEN -> {
				if (b.p.containerMenu instanceof CraftingMenu) {
					ph = Ph.FILL;
					return S.RUN;
				}
				if (!W.st(table).is(Blocks.CRAFTING_TABLE)) return fail("table vanished");
				S s = Do.reach(b, table, true);
				if (s == S.FAIL) return fail("cannot reach table");
				if (s == S.OK) Act.open(table);
			}
			case FILL -> {
				AbstractContainerMenu m = b.p.containerMenu;
				if (r.table() && !(m instanceof CraftingMenu)) {
					ph = Ph.OPEN;
					return S.RUN;
				}
				if (!fill(m)) {
					clean(m);
					return fail("missing ingredients");
				}
				ph = Ph.WAIT;
				wait = 0;
			}
			case WAIT -> {
				AbstractContainerMenu m = b.p.containerMenu;
				if (!m.getSlot(0).getItem().isEmpty()) {
					ph = Ph.TAKE;
				} else if (++wait > 60) {
					clean(m);
					return fail("recipe didn't match");
				}
			}
			case TAKE -> {
				AbstractContainerMenu m = b.p.containerMenu;
				Compat.quickMove(m.containerId, 0);
				ph = Ph.CLEAN;
				wait = 0;
			}
			case CLEAN -> {
				if (++wait < 3) return S.RUN;
				AbstractContainerMenu m = b.p.containerMenu;
				clean(m);
				if (m != b.p.inventoryMenu) b.p.closeContainer();
				if (temp) {
					ph = Ph.PICKUP;
					wait = 0;
					return S.RUN;
				}
				return S.OK;
			}
			case PICKUP -> {
				if (W.st(table).is(Blocks.CRAFTING_TABLE)) {
					S s = Do.breakAt(b, table);
					if (s == S.FAIL) return S.OK; // fine, leave it
					return S.RUN;
				}
				b.state.ours.remove(table.asLong());
				S c = collector.tick(b, table, 4, Res.CRAFTING_TABLE.pred);
				if (c == S.OK || ++wait > 100) return S.OK;
			}
		}
		return S.RUN;
	}

	/** Our home table if nearby, otherwise any table we placed ourselves. */
	public static BlockPos findTable(Bot b) {
		BlockPos me = b.p.blockPosition();
		if (b.state.table != null) {
			BlockPos t = BlockPos.of(b.state.table);
			if (t.distSqr(me) < 24 * 24 && W.st(t).is(Blocks.CRAFTING_TABLE)) return t;
		}
		for (Long l : b.state.ours) {
			BlockPos t = BlockPos.of(l);
			if (t.distSqr(me) < 16 * 16 && W.st(t).is(Blocks.CRAFTING_TABLE)) return t;
		}
		return null;
	}

	/** An air block next to us with solid ground, outside the farm. */
	public static BlockPos spotNear(Bot b) {
		BlockPos f = dev.farmbot.path.Nav.feet(b.p);
		for (int r = 1; r <= 3; r++)
			for (int dx = -r; dx <= r; dx++)
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
					for (int dy = 0; dy <= 1; dy++) {
						BlockPos c = f.offset(dx, dy, dz);
						if (!W.st(c).canBeReplaced() || W.isWater(W.st(c))) continue;
						if (!W.sturdyTop(c.below())) continue;
						if (Farm.isFarmProtected(c) || Farm.isFarmProtected(c.below())) continue;
						return c;
					}
				}
		return null;
	}

	private boolean fill(AbstractContainerMenu m) {
		int cid = m.containerId;
		int w = r.table() ? 3 : 2;
		clean(m);
		for (int row = 0; row < r.rows().length; row++) {
			String line = r.rows()[row];
			for (int col = 0; col < line.length(); col++) {
				char ch = line.charAt(col);
				if (ch == ' ') continue;
				Res need = r.key().get(ch);
				int grid = 1 + row * w + col;
				int placed = 0;
				int guard = 0;
				while (placed < times && guard++ < 200) {
					ItemStack carried = m.getCarried();
					if (carried.isEmpty() || !need.pred.test(carried)) {
						if (!carried.isEmpty()) putBack(m);
						int src = source(m, need);
						if (src < 0) return false;
						Compat.pickup(cid, src, 0);
						continue;
					}
					Compat.pickup(cid, grid, 1);
					placed++;
				}
			}
		}
		if (!m.getCarried().isEmpty()) putBack(m);
		return true;
	}

	private static int source(AbstractContainerMenu m, Res need) {
		for (int k = 0; k < m.slots.size(); k++) {
			Slot s = m.slots.get(k);
			if (s.container != Inv.inv()) continue;
			if (s.getContainerSlot() >= Inv.SIZE) continue;
			ItemStack st = s.getItem();
			if (!st.isEmpty() && need.pred.test(st)) return k;
		}
		return -1;
	}

	private static void putBack(AbstractContainerMenu m) {
		ItemStack c = m.getCarried();
		int empty = -1;
		for (int k = 0; k < m.slots.size(); k++) {
			Slot s = m.slots.get(k);
			if (s.container != Inv.inv() || s.getContainerSlot() >= Inv.SIZE) continue;
			ItemStack st = s.getItem();
			if (!st.isEmpty() && ItemStack.isSameItemSameComponents(st, c) && st.getCount() < st.getMaxStackSize()) {
				Compat.pickup(m.containerId, k, 0);
				if (m.getCarried().isEmpty()) return;
			}
			if (st.isEmpty() && empty < 0) empty = k;
		}
		if (empty >= 0) Compat.pickup(m.containerId, empty, 0);
	}

	private void clean(AbstractContainerMenu m) {
		int n = r.table() ? 9 : 4;
		for (int g = 1; g <= n; g++) {
			if (g < m.slots.size() && !m.getSlot(g).getItem().isEmpty()) Compat.quickMove(m.containerId, g);
		}
		if (!m.getCarried().isEmpty()) putBack(m);
	}
}
