package dev.farmbot.task;

import dev.farmbot.Act;
import dev.farmbot.Bot;
import dev.farmbot.Compat;
import dev.farmbot.Inv;
import dev.farmbot.Res;
import dev.farmbot.W;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.EnumMap;
import java.util.Map;

/** Toss junk, then haul surplus bread/wheat/seeds/materials into our chests at home. */
public final class StoreTask extends Task {
	private static final Map<Res, Integer> KEEP = new EnumMap<>(Res.class);

	static {
		KEEP.put(Res.BREAD, 16);
		KEEP.put(Res.WHEAT, 0);
		KEEP.put(Res.SEEDS, 96);
		KEEP.put(Res.COBBLE, 64);
		KEEP.put(Res.DIRT, 64);
		KEEP.put(Res.LOG, 16);
		KEEP.put(Res.PLANKS, 32);
		KEEP.put(Res.STICK, 16);
		KEEP.put(Res.COAL, 16);
		KEEP.put(Res.TORCH, 32);
		KEEP.put(Res.RAW_IRON, 0);
		KEEP.put(Res.IRON_INGOT, 16);
		KEEP.put(Res.GRAVEL, 2);
	}

	private enum Ph { TOSS, GO, DEPOSIT, NEXT }
	private Ph ph = Ph.TOSS;
	private BlockPos cur;
	private int wait;
	private boolean movedSomething;

	@Override
	public String name() {
		return "storing the harvest";
	}

	@Override
	public int timeout() {
		return 20 * 120;
	}

	/** Items worth carrying at all. Everything else gets dropped when space is tight. */
	public static boolean useful(ItemStack s) {
		if (s.isEmpty()) return true;
		for (Res r : Res.values()) if (r.pred.test(s)) return true;
		if (s.isDamageableItem()) return true;
		return Inv.safeFood(s) || s.is(Items.BEEF) || s.is(Items.PORKCHOP) || s.is(Items.MUTTON);
	}

	@Override
	public S tick(Bot b) {
		switch (ph) {
			case TOSS -> {
				if (!Inv.noScreenMenu()) b.p.closeContainer();
				int cid = b.p.inventoryMenu.containerId;
				for (int i = 0; i < Inv.SIZE; i++) {
					ItemStack s = Inv.get(i);
					if (!useful(s)) Compat.throwStack(cid, Inv.menuSlot(b.p.inventoryMenu, i));
				}
				ph = Ph.GO;
			}
			case GO -> {
				if (!needsStoring()) return S.OK;
				BlockPos chest = chest(b);
				if (chest == null) {
					// every chest is full: make room to work (drop the cheapest surplus), then the brain builds more chests
					makeRoom(b, 4);
					return fail("no chest to store in");
				}
				if (b.p.containerMenu instanceof ChestMenu) {
					ph = Ph.DEPOSIT;
					wait = 0;
					return S.RUN;
				}
				S s = Do.reach(b, chest, true);
				if (s == S.FAIL) return fail("cannot reach chest");
				if (s == S.OK) Act.open(chest);
				if (age > 20 * 60) return fail("chest won't open");
			}
			case DEPOSIT -> {
				if (++wait < 3) return S.RUN;
				AbstractContainerMenu m = b.p.containerMenu;
				if (!(m instanceof ChestMenu)) {
					ph = Ph.GO;
					return S.RUN;
				}
				Map<Res, Integer> keep = new EnumMap<>(KEEP);
				movedSomething = false;
				for (int k = 0; k < m.slots.size(); k++) {
					Slot sl = m.slots.get(k);
					if (sl.container != Inv.inv() || sl.getContainerSlot() >= Inv.SIZE) continue;
					ItemStack st = sl.getItem();
					if (st.isEmpty()) continue;
					for (Map.Entry<Res, Integer> e : keep.entrySet()) {
						if (!e.getKey().pred.test(st)) continue;
						if (e.getValue() >= st.getCount()) {
							e.setValue(e.getValue() - st.getCount());
						} else {
							Compat.quickMove(m.containerId, k);
							movedSomething = true;
							e.setValue(0);
						}
						break;
					}
				}
				ph = Ph.NEXT;
				wait = 0;
			}
			case NEXT -> {
				if (++wait < 4) return S.RUN;
				b.p.closeContainer();
				if (!needsStoring()) return S.OK;
				// this chest must be full: remember that, try the next one
				if (cur != null) b.state.fullChests.add(cur.asLong());
				b.state.save();
				ph = Ph.GO;
			}
		}
		return S.RUN;
	}

	/** Free up slots by dropping surplus, cheapest first (dirt, cobble, seeds... bread last). */
	private static void makeRoom(Bot b, int slots) {
		if (!Inv.noScreenMenu()) b.p.closeContainer();
		int cid = b.p.inventoryMenu.containerId;
		Res[] order = {Res.DIRT, Res.GRAVEL, Res.COBBLE, Res.SEEDS, Res.STICK, Res.WHEAT, Res.BREAD};
		for (Res r : order) {
			int keep = KEEP.getOrDefault(r, 0);
			int kept = 0;
			for (int i = 0; i < Inv.SIZE && Inv.freeSlots() < slots; i++) {
				ItemStack st = Inv.get(i);
				if (st.isEmpty() || !r.pred.test(st)) continue;
				if (kept + st.getCount() <= keep) {
					kept += st.getCount();
					continue;
				}
				Compat.throwStack(cid, Inv.menuSlot(b.p.inventoryMenu, i));
			}
		}
	}

	private boolean needsStoring() {
		for (Map.Entry<Res, Integer> e : KEEP.entrySet()) if (Inv.count(e.getKey()) > e.getValue() + 32) return true;
		return Inv.freeSlots() < 4 && (Inv.count(Res.BREAD) > 16 || Inv.count(Res.WHEAT) > 0 || Inv.count(Res.SEEDS) > 96);
	}

	private BlockPos chest(Bot b) {
		BlockPos me = b.p.blockPosition();
		BlockPos best = null;
		for (Long l : b.state.chests) {
			if (b.state.fullChests.contains(l)) continue;
			BlockPos p = BlockPos.of(l);
			if (W.loaded(p) && !W.st(p).is(Blocks.CHEST)) continue;
			if (best == null || p.distSqr(me) < best.distSqr(me)) best = p;
		}
		cur = best;
		return best;
	}
}
