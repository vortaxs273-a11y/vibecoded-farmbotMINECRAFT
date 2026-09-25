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
	private int chestIdx;
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
				if (chest == null) return Inv.freeSlots() > 0 ? S.OK : fail("no chest to store in");
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
				// this chest must be full, try the next one
				chestIdx++;
				ph = Ph.GO;
			}
		}
		return S.RUN;
	}

	private boolean needsStoring() {
		for (Map.Entry<Res, Integer> e : KEEP.entrySet()) if (Inv.count(e.getKey()) > e.getValue() + 32) return true;
		return Inv.freeSlots() < 4 && (Inv.count(Res.BREAD) > 16 || Inv.count(Res.WHEAT) > 0 || Inv.count(Res.SEEDS) > 96);
	}

	private BlockPos chest(Bot b) {
		int n = 0;
		for (Long l : b.state.chests) {
			BlockPos p = BlockPos.of(l);
			if (!W.st(p).is(Blocks.CHEST)) continue;
			if (n++ >= chestIdx) return p;
		}
		return null;
	}
}
