package dev.farmbot;

import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Runs before every task, every tick. If anything is trying to kill the farmer, this takes the wheel.
 * Death, lava, fire, falls (water bucket clutch), drowning, suffocation, mobs, creepers, hunger, armor, last-resort logout.
 */
public final class Safety {
	public String what = "";
	public net.minecraft.core.BlockPos deathPos;
	public long deathTick;
	private boolean night;
	private BlockPos pickupWater;
	private int pickupTicks;
	private int eatTicks;
	private int lastFood;
	private int respawnDelay;
	private int fleeTicks;
	private Entity fleeFrom;

	public boolean tick(Bot b) {
		LocalPlayer p = b.p;

		// --- death: respawn, forget the current plan, walk home from wherever we spawned ---
		if (p.isDeadOrDying() || b.mc.screen instanceof DeathScreen) {
			what = "respawning";
			if (respawnDelay == 0) {
				deathPos = p.blockPosition();
				deathTick = b.tick;
			}
			if (++respawnDelay > 20) {
				respawnDelay = 0;
				Compat.respawn(p);
				b.state.deaths++;
				b.state.save();
				b.abortTask();
				eatTicks = 0;
				pickupWater = null;
				bunker = null;
			}
			return true;
		}
		respawnDelay = 0;

		List<LivingEntity> hostiles = hostiles(b, 16);
		LivingEntity nearest = hostiles.isEmpty() ? null : hostiles.get(0);
		double nd = nearest == null ? 99 : nearest.distanceTo(p);

		// --- lava ---
		if (p.isInLava()) {
			what = "lava!";
			if (Inv.has(s -> s.is(Items.WATER_BUCKET)) && Inv.select(s -> s.is(Items.WATER_BUCKET))) {
				Ctl.look(p, p.getYRot(), 90f);
				Compat.useItem();
				pickupWater = null;
			}
			escapeTo(b, p);
			Ctl.jump = true;
			return true;
		}

		// --- standing in fire / burning ---
		BlockPos feet = BlockPos.containing(p.getX(), p.getY() + 0.2, p.getZ());
		if (p.isOnFire() && !p.hasEffect(MobEffects.FIRE_RESISTANCE) && !p.isInWater()) {
			what = "on fire";
			if (W.isFire(W.st(feet))) {
				escapeTo(b, p);
				return true;
			}
			if (p.onGround() && Inv.has(s -> s.is(Items.WATER_BUCKET)) && W.solid(feet.below()) && W.st(feet).canBeReplaced()) {
				if (Inv.select(s -> s.is(Items.WATER_BUCKET))) {
					Ctl.lookAt(p, new Vec3(feet.getX() + 0.5, feet.getY() + 0.02, feet.getZ() + 0.5));
					Compat.useItem();
					pickupWater = feet;
					pickupTicks = 0;
				}
				return true;
			}
		}

		// --- falling: water bucket clutch ---
		if (!p.onGround() && !p.isInWater() && Compat.fallDistance(p) > 3.5 && p.getDeltaMovement().y < -0.4
			&& Inv.has(s -> s.is(Items.WATER_BUCKET))) {
			what = "falling (clutch)";
			BlockPos.MutableBlockPos g = feet.mutable();
			int i = 0;
			while (i++ < 10 && W.passable(g) && !W.isWater(W.st(g))) g.move(0, -1, 0);
			double dist = p.getY() - (g.getY() + 1);
			Ctl.look(p, p.getYRot(), 90f);
			if (Inv.select(s -> s.is(Items.WATER_BUCKET)) && dist < 3.2 && !W.isWater(W.st(g)) && W.solid(g)) {
				Compat.useItem();
				pickupWater = g.immutable().above();
				pickupTicks = 0;
			}
			return true;
		}

		// --- pick the clutch/extinguish water back up ---
		if (pickupWater != null) {
			if (++pickupTicks > 80 || !W.isWaterSource(W.st(pickupWater)) || !Inv.has(s -> s.is(Items.BUCKET))) {
				pickupWater = null;
			} else if (p.onGround() || p.isInWater()) {
				what = "picking water back up";
				Act.scoop(pickupWater);
				return true;
			}
		}

		// --- drowning ---
		if (p.isUnderWater() && p.getAirSupply() < 160) {
			what = "swimming up for air";
			Ctl.jump = true;
			Ctl.look(p, p.getYRot(), -60f);
			BlockPos head = BlockPos.containing(p.getEyePosition()).above();
			if (!W.passable(head) && !W.isWater(W.st(head)) && Guard.mayBreak(head, W.st(head))) b.breaker.tick(head);
			return true;
		}

		// --- suffocation ---
		if (p.isInWall()) {
			BlockPos eye = BlockPos.containing(p.getEyePosition());
			if (!W.passable(eye) && Guard.mayBreak(eye, W.st(eye))) {
				what = "suffocating";
				b.breaker.tick(eye);
				return true;
			}
		}

		// --- about to die: dig a bunker, seal it, heal, come back out ---
		// night only matters on the surface; underground it's always dark, so keep mining
		boolean dark = b.lvl.isDarkOutside() && b.lvl.canSeeSky(BlockPos.containing(p.getEyePosition()));
		boolean onFarm = b.state.hasSite && Farm.inBuiltCell(p.getBlockX(), p.getBlockZ());
		if (bunker != null || (p.getHealth() <= Config.I.panicHealth && (nd < 12 || p.hurtTime > 0) && !p.isInWater())
			|| (dark && !onFarm && !p.isInWater() && p.onGround())) {
			night = dark;
			if (bunker(b, p, nd)) return true;
		}

		// --- creepers: leave ---
		for (LivingEntity h : hostiles) {
			if (h.getType() == EntityType.CREEPER && h.distanceTo(p) < 6) {
				fleeFrom = h;
				fleeTicks = 30;
				break;
			}
		}
		if (fleeTicks > 0 && fleeFrom != null && fleeFrom.isAlive()) {
			fleeTicks--;
			what = "running from creeper";
			runFrom(p, fleeFrom.position());
			return true;
		}

		// --- fight ---
		if (nearest != null && nd < 3.4) {
			what = "fighting " + nearest.getType().getDescription().getString();
			int w = Inv.bestWeapon();
			if (w < 0 || Inv.selectIndex(w)) {
				Ctl.lookAt(p, nearest.getEyePosition().add(0, -0.3, 0));
				if (p.getAttackStrengthScale(0.5f) >= 0.95f) Compat.attack(nearest);
			}
			if (p.getHealth() < 8) runFrom(p, nearest.position());
			return true;
		}
		if (nearest != null && p.getHealth() < 8 && nd < 10) {
			what = "retreating";
			runFrom(p, nearest.position());
			return true;
		}

		// --- eat ---
		int food = p.getFoodData().getFoodLevel();
		boolean hungry = food <= Config.I.eatBelowHunger || (p.getHealth() < 14 && food < 18);
		if (eatTicks > 0 || (hungry && nd > 6)) {
			int slot = Inv.bestFood(20 - food);
			if (slot < 0 || food >= 20 || nd <= 4) {
				eatTicks = 0;
			} else {
				if (eatTicks == 0) lastFood = food;
				eatTicks++;
				what = "eating";
				if (Inv.selectIndex(slot)) {
					Ctl.look(p, p.getYRot(), -90f);
					Ctl.use = true;
				}
				if (food > lastFood || eatTicks > 60) eatTicks = 0;
				return true;
			}
		}

		// --- housekeeping: wear armor, shield in offhand ---
		if (b.tick % 40 == 0 && Inv.noScreenMenu()) housekeeping(p);

		what = "";
		return false;
	}

	// ------------------------------------------------------------------ bunker

	private BlockPos bunker; // original feet position = where the ceiling goes
	private int bunkerTicks;
	private static final java.util.function.Predicate<ItemStack> FILL = s -> Res.COBBLE.pred.test(s) || Res.DIRT.pred.test(s);

	/** Can we safely sink two blocks straight down from here? */
	private static boolean bunkerable(BlockPos f) {
		for (int dy = 1; dy <= 3; dy++) {
			BlockPos d = f.below(dy);
			var s = W.st(d);
			if (W.passable(d) || !Guard.mayBreak(d, s) || s.getDestroySpeed(W.lvl(), d) > 5 || s.getDestroySpeed(W.lvl(), d) < 0) return false;
			if (W.fallingAbove(d) && dy >= 2) return false;
			if (W.lavaNear(d)) return false;
		}
		if (!W.standable(f.below(4)) || W.lavaNear(f.below(4))) return false;
		// walls around the hole must hold
		for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
			for (int dy = 1; dy <= 3; dy++) if (!W.solid(f.below(dy).relative(d))) return false;
		}
		return true;
	}

	private boolean bunker(Bot b, LocalPlayer p, double nd) {
		BlockPos f = BlockPos.containing(p.getX(), p.getY() + 0.2, p.getZ());
		if (bunker == null) {
			if (!p.onGround() || !bunkerable(f)) {
				if (night && nd >= 12) return false; // can't dig in right here; keep working and try again next step
				// can't dig in here: get away and eat on the run
				if (nd < 12) {
					what = "low health: running";
					runFrom(p, hostiles(b, 16).isEmpty() ? p.position() : hostiles(b, 16).get(0).position());
					return true;
				}
				return false;
			}
			bunker = f.below(); // the roof: first ground block, surrounded by solid ground
			bunkerTicks = 0;
			FarmBotClient.LOG.info("[farmbot] low health ({}), digging in at {}", p.getHealth(), f);
		}
		bunkerTicks++;
		BlockPos floor = bunker.below(2); // feet level once dug in (3 below the surface)
		// 1. dig down
		if (f.getY() > floor.getY()) {
			what = "digging a bunker";
			BlockPos under = f.below();
			double hx = p.getX() - (f.getX() + 0.5), hz = p.getZ() - (f.getZ() + 0.5);
			if (hx * hx + hz * hz > 0.04) {
				Ctl.faceXZ(p, f.getX() + 0.5, f.getZ() + 0.5);
				Ctl.fwd = true;
				Ctl.sneak = true;
			}
			if (!W.passable(under)) b.breaker.tick(under);
			if (bunkerTicks > 20 * 30) bunker = null; // couldn't dig, give up on it
			return true;
		}
		// 2. seal the roof
		if (W.passable(bunker) || W.st(bunker).canBeReplaced()) {
			what = "sealing the bunker";
			if (Inv.count(FILL) == 0) {
				what = "hiding in a hole (no block for the roof)";
			} else if (Act.place(bunker, FILL)) b.state.ours.add(bunker.asLong());
			if (W.passable(bunker) && Inv.count(FILL) == 0 && !b.lvl.isDarkOutside() && p.getHealth() >= 16) {
				bunker = null;
				return false;
			}
			return true;
		}
		// 3. heal
		int food = p.getFoodData().getFoodLevel();
		boolean safeOutside = hostiles(b, 8).isEmpty();
		boolean stillNight = b.lvl.isDarkOutside() && bunker != null && b.lvl.canSeeSky(bunker.above());
		boolean canHeal = food >= 18 || Inv.foodValue() > 0; // natural regen needs a full-ish belly
		if (((p.getHealth() < 18 && canHeal) || !safeOutside || stillNight) && bunkerTicks < 20 * 60 * 15) {
			what = stillNight ? "sleeping in the bunker until morning" : "healing in the bunker (" + (int) p.getHealth() + " hp)";
			int slot = Inv.bestFood(20 - food);
			if (food < 20 && slot >= 0) {
				if (Inv.selectIndex(slot)) {
					Ctl.look(p, p.getYRot(), -90f);
					Ctl.use = true;
				}
			}
			return true;
		}
		// 4. out
		what = "leaving the bunker";
		if (!W.passable(bunker)) {
			b.breaker.tick(bunker);
			return true;
		}
		b.state.ours.remove(bunker.asLong());
		bunker = null;
		return false; // Baritone will climb out on the next path
	}

	private void housekeeping(LocalPlayer p) {
		int cid = p.inventoryMenu.containerId;
		for (int i = 0; i < Inv.SIZE; i++) {
			ItemStack s = Inv.get(i);
			if (s.isEmpty()) continue;
			EquipmentSlot slot = s.is(Items.IRON_HELMET) ? EquipmentSlot.HEAD : s.is(Items.IRON_CHESTPLATE) ? EquipmentSlot.CHEST
				: s.is(Items.IRON_LEGGINGS) ? EquipmentSlot.LEGS : s.is(Items.IRON_BOOTS) ? EquipmentSlot.FEET : null;
			if (slot != null && p.getItemBySlot(slot).isEmpty()) {
				Compat.quickMove(cid, Inv.menuSlot(p.inventoryMenu, i));
				return;
			}
			if (s.is(Items.SHIELD) && p.getOffhandItem().isEmpty()) {
				Compat.swap(cid, Inv.menuSlot(p.inventoryMenu, i), 40);
				return;
			}
		}
	}

	private static void runFrom(LocalPlayer p, Vec3 from) {
		Vec3 away = p.position().subtract(from);
		Vec3 target = p.position().add(away.normalize().scale(5));
		Ctl.faceXZ(p, target.x, target.z);
		Ctl.fwd = true;
		Ctl.sprint = p.getFoodData().getFoodLevel() > 6;
		if (p.horizontalCollision && p.onGround()) Ctl.jump = true;
		if (p.isInWater()) Ctl.jump = true;
	}

	/** Walk to the nearest block that isn't lava or fire. */
	private static void escapeTo(Bot b, LocalPlayer p) {
		BlockPos f = BlockPos.containing(p.getX(), p.getY() + 0.2, p.getZ());
		BlockPos best = null;
		double bd = Double.MAX_VALUE;
		for (int x = -4; x <= 4; x++)
			for (int y = -1; y <= 2; y++)
				for (int z = -4; z <= 4; z++) {
					BlockPos c = f.offset(x, y, z);
					if (!W.passable(c) || !W.passable(c.above()) || !W.standable(c.below())) continue;
					if (W.lavaNear(c) || W.isFire(W.st(c))) continue;
					double d = x * x + y * y + z * z;
					if (d < bd) {
						bd = d;
						best = c;
					}
				}
		if (best != null) {
			Ctl.faceXZ(p, best.getX() + 0.5, best.getZ() + 0.5);
			Ctl.fwd = true;
			Ctl.jump = true;
			Ctl.sprint = true;
		}
	}

	public static List<LivingEntity> hostiles(Bot b, double r) {
		LocalPlayer p = b.p;
		List<Entity> es = b.lvl.getEntities(p, p.getBoundingBox().inflate(r), e -> e instanceof LivingEntity le && le.isAlive()
			&& e.getType().getCategory() == MobCategory.MONSTER && e.getType() != EntityType.ENDERMAN
			&& e.getType() != EntityType.ZOMBIFIED_PIGLIN && e.getType() != EntityType.PIGLIN);
		es.sort((x, y) -> Double.compare(x.distanceToSqr(p), y.distanceToSqr(p)));
		@SuppressWarnings("unchecked")
		List<LivingEntity> out = (List<LivingEntity>) (List<?>) es;
		return out;
	}
}
