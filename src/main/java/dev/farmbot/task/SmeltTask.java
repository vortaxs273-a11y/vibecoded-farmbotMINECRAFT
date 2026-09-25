package dev.farmbot.task;

import dev.farmbot.Act;
import dev.farmbot.Bot;
import dev.farmbot.Compat;
import dev.farmbot.Inv;
import dev.farmbot.Recipes.Smelt;
import dev.farmbot.Res;
import dev.farmbot.W;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

import java.util.function.Predicate;

/** Loads a furnace (input + just enough fuel), waits next to it, collects the output. */
public final class SmeltTask extends Task {
	private final Smelt sm;
	private final int count;
	private enum Ph { FIND, PLACE, OPEN_LOAD, LOAD, WAIT, OPEN_TAKE, TAKE, PICKUP }
	private Ph ph = Ph.FIND;
	private BlockPos furnace;
	private boolean temp;
	private int wait, loaded, startOut;
	private final Do.Collector collector = new Do.Collector();

	public SmeltTask(Smelt sm, int count) {
		this.sm = sm;
		this.count = Math.max(1, Math.min(64, count));
	}

	@Override
	public String name() {
		return "smelting " + sm.out().name().toLowerCase() + " x" + count + (ph == Ph.WAIT ? " (waiting " + wait / 20 + "s)" : "");
	}

	@Override
	public int timeout() {
		return 20 * (count * 10 + 180);
	}

	@Override
	public S tick(Bot b) {
		switch (ph) {
			case FIND -> {
				startOut = Inv.count(sm.out());
				furnace = findFurnace(b);
				if (furnace != null) {
					ph = Ph.OPEN_LOAD;
					return S.RUN;
				}
				if (Inv.count(Res.FURNACE) == 0) return fail("no furnace");
				furnace = CraftTask.spotNear(b);
				if (furnace == null) return fail("nowhere to put a furnace");
				temp = true;
				ph = Ph.PLACE;
			}
			case PLACE -> {
				S s = Do.placeAt(b, furnace, Res.FURNACE.pred, st -> st.is(Blocks.FURNACE));
				if (s == S.FAIL) return fail("could not place furnace");
				if (s == S.OK) {
					b.state.ours.add(furnace.asLong());
					ph = Ph.OPEN_LOAD;
				}
			}
			case OPEN_LOAD, OPEN_TAKE -> {
				if (b.p.containerMenu instanceof AbstractFurnaceMenu) {
					ph = ph == Ph.OPEN_LOAD ? Ph.LOAD : Ph.TAKE;
					return S.RUN;
				}
				if (!W.st(furnace).is(Blocks.FURNACE)) return fail("furnace vanished");
				S s = Do.reach(b, furnace, true);
				if (s == S.FAIL) return fail("cannot reach furnace");
				if (s == S.OK) Act.open(furnace);
			}
			case LOAD -> {
				AbstractContainerMenu m = b.p.containerMenu;
				int cid = m.containerId;
				if (!m.getSlot(2).getItem().isEmpty()) Compat.quickMove(cid, 2);
				ItemStack in = m.getSlot(0).getItem();
				if (!in.isEmpty() && !sm.in().pred.test(in)) Compat.quickMove(cid, 0);
				int have = in.isEmpty() || !sm.in().pred.test(in) ? 0 : in.getCount();
				int toPut = Math.min(count - have, Inv.count(sm.in()));
				if (!put(m, sm.in().pred, 0, toPut)) return fail("no input");
				loaded = have + toPut;
				// fuel
				Predicate<ItemStack> fuel;
				double per;
				boolean smeltingLogs = sm.in() == Res.LOG;
				if (Inv.count(Res.COAL) * 8 >= loaded && !smeltingLogs) {
					fuel = Res.COAL.pred;
					per = 8;
				} else if (Inv.count(Res.PLANKS) * 1.5 >= loaded) {
					fuel = Res.PLANKS.pred;
					per = 1.5;
				} else if (Inv.count(Res.COAL) > 0) {
					fuel = Res.COAL.pred;
					per = 8;
				} else {
					fuel = Res.LOG.pred;
					per = 1.5;
				}
				int fuelNeeded = (int) Math.ceil(loaded / per);
				ItemStack f = m.getSlot(1).getItem();
				if (!f.isEmpty()) fuelNeeded = Math.max(0, fuelNeeded - f.getCount());
				if (fuelNeeded > 0 && !put(m, fuel, 1, fuelNeeded) && f.isEmpty()) {
					Compat.quickMove(cid, 0);
					return fail("no fuel");
				}
				b.p.closeContainer();
				ph = Ph.WAIT;
				wait = 0;
			}
			case WAIT -> {
				wait++;
				// keep an eye on the furnace so nobody walks off with it, but otherwise just stand here and farm-think
				if (!Act.inReach(furnace, Do.REACH)) Do.reach(b, furnace, true);
				else lookAround(b);
				if (wait > loaded * 200 + 40) ph = Ph.OPEN_TAKE;
			}
			case TAKE -> {
				AbstractContainerMenu m = b.p.containerMenu;
				Compat.quickMove(m.containerId, 2);
				boolean inputLeft = !m.getSlot(0).getItem().isEmpty();
				if (inputLeft && age < timeout() - 200) {
					b.p.closeContainer();
					ph = Ph.WAIT;
					wait = loaded * 200 - 200;
					return S.RUN;
				}
				if (inputLeft) Compat.quickMove(m.containerId, 0);
				if (!m.getSlot(1).getItem().isEmpty() && temp) Compat.quickMove(m.containerId, 1);
				b.p.closeContainer();
				if (temp) {
					ph = Ph.PICKUP;
					wait = 0;
					return S.RUN;
				}
				return Inv.count(sm.out()) > startOut ? S.OK : fail("nothing smelted");
			}
			case PICKUP -> {
				if (W.st(furnace).is(Blocks.FURNACE)) {
					S s = Do.breakAt(b, furnace);
					if (s == S.FAIL) return S.OK;
					return S.RUN;
				}
				b.state.ours.remove(furnace.asLong());
				S c = collector.tick(b, furnace, 4, Res.FURNACE.pred);
				if (c == S.OK || ++wait > 100) return S.OK;
			}
		}
		return S.RUN;
	}

	private static void lookAround(Bot b) {
		if (b.tick % 60 == 0) dev.farmbot.Ctl.look(b.p, b.p.getYRot() + 30, 5);
	}

	/** Right-click `n` items one at a time into a slot. */
	private static boolean put(AbstractContainerMenu m, Predicate<ItemStack> pred, int slot, int n) {
		if (n <= 0) return true;
		int cid = m.containerId;
		int placed = 0, guard = 0;
		while (placed < n && guard++ < 200) {
			ItemStack carried = m.getCarried();
			if (carried.isEmpty() || !pred.test(carried)) {
				if (!carried.isEmpty()) return false;
				int src = -1;
				for (int k = 0; k < m.slots.size(); k++) {
					Slot s = m.slots.get(k);
					if (s.container == Inv.inv() && s.getContainerSlot() < Inv.SIZE && !s.getItem().isEmpty() && pred.test(s.getItem())) {
						src = k;
						break;
					}
				}
				if (src < 0) break;
				Compat.pickup(cid, src, 0);
				continue;
			}
			Compat.pickup(cid, slot, 1);
			placed++;
		}
		// put the rest back
		if (!m.getCarried().isEmpty()) {
			for (int k = 0; k < m.slots.size(); k++) {
				Slot s = m.slots.get(k);
				if (s.container == Inv.inv() && s.getContainerSlot() < Inv.SIZE && s.getItem().isEmpty()) {
					Compat.pickup(cid, k, 0);
					break;
				}
			}
		}
		return placed > 0;
	}

	public static BlockPos findFurnace(Bot b) {
		BlockPos me = b.p.blockPosition();
		if (b.state.furnace != null) {
			BlockPos f = BlockPos.of(b.state.furnace);
			if (f.distSqr(me) < 64 * 64 && W.st(f).is(Blocks.FURNACE)) return f;
		}
		for (Long l : b.state.ours) {
			BlockPos f = BlockPos.of(l);
			if (f.distSqr(me) < 16 * 16 && W.st(f).is(Blocks.FURNACE)) return f;
		}
		return null;
	}
}
