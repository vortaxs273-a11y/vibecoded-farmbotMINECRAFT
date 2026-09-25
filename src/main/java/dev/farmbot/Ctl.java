package dev.farmbot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Virtual keyboard. Tasks set the flags every tick; {@link #apply} pushes them into the real key mappings. */
public final class Ctl {
	public static boolean fwd, back, left, right, jump, sneak, sprint, use;

	private Ctl() {}

	public static void reset() {
		fwd = back = left = right = jump = sneak = sprint = use = false;
	}

	public static void apply(Minecraft mc) {
		Options o = mc.options;
		o.keyUp.setDown(fwd);
		o.keyDown.setDown(back);
		o.keyLeft.setDown(left);
		o.keyRight.setDown(right);
		o.keyJump.setDown(jump);
		o.keyShift.setDown(sneak);
		o.keySprint.setDown(sprint);
		o.keyUse.setDown(use);
	}

	public static void releaseAll(Minecraft mc) {
		reset();
		apply(mc);
	}

	public static void look(LocalPlayer p, float yaw, float pitch) {
		p.setYRot(yaw);
		p.setXRot(Mth.clamp(pitch, -90f, 90f));
		p.setYHeadRot(yaw);
	}

	public static void lookAt(LocalPlayer p, Vec3 target) {
		Vec3 eye = p.getEyePosition();
		double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
		double h = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90f;
		float pitch = (float) -(Mth.atan2(dy, h) * (180.0 / Math.PI));
		look(p, yaw, pitch);
	}

	/** Turn to face a point horizontally, keeping the eyes level-ish (for walking). */
	public static void faceXZ(LocalPlayer p, double x, double z) {
		double dx = x - p.getX(), dz = z - p.getZ();
		float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90f;
		look(p, yaw, 10f);
	}
}
