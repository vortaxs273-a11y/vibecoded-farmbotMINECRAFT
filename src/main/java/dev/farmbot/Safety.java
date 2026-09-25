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
			if (++respawnDelay > 20) {
				respawnDelay = 0;
				Compat.respawn(p);
				b.state.deaths++;
				b.state.save();
				b.abortTask();
				eatTicks = 0;
				pickupWater = null;
			}
			return true;
		}
		respawnDelay = 0;

		List<LivingEntity> hostiles = hostiles(b, 16);
		LivingEntity nearest = hostiles.isEmpty() ? null : hostiles.get(0);
		double nd = nearest == null ? 99 : nearest.distanceTo(p);

		// --- last resort: log out rather than die ---
		if (Config.I.panicLogout && !Config.I.infinite && p.getHealth() <= Config.I.panicHealth && p.hurtTime > 0
			&& (nd < 10 || p.isInLava() || p.isOnFire() || Compat.fallDistance(p) > 6)) {
			what = "PANIC LOGOUT";
			FarmBotClient.LOG.warn("panic logout at {} hp", p.getHealth());
			b.running = false;
			Ctl.releaseAll(b.mc);
			b.state.save();
			Compat.disconnect("[FarmBot] panic logout at " + (int) p.getHealth() + " hp. farm farm farm </im_end/>");
			return true;
		}

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
