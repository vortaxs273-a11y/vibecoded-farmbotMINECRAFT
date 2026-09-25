package dev.farmbot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Every call into Minecraft internals that has historically moved around between versions lives here,
 * so a version bump means fixing one file instead of twenty.
 */
public final class Compat {
	private Compat() {}

	public static Minecraft mc() {
		return Minecraft.getInstance();
	}

	public static int selectedSlot(LocalPlayer p) {
		return p.getInventory().getSelectedSlot();
	}

	public static void setSelectedSlot(LocalPlayer p, int slot) {
		p.getInventory().setSelectedSlot(slot);
	}

	public static void click(int containerId, int slot, int button, ContainerInput type) {
		Minecraft mc = mc();
		mc.gameMode.handleContainerInput(containerId, slot, button, type, mc.player);
	}

	public static void pickup(int containerId, int slot, int button) {
		click(containerId, slot, button, ContainerInput.PICKUP);
	}

	public static void quickMove(int containerId, int slot) {
		click(containerId, slot, 0, ContainerInput.QUICK_MOVE);
	}

	public static void swap(int containerId, int slot, int hotbarOrOffhand) {
		click(containerId, slot, hotbarOrOffhand, ContainerInput.SWAP);
	}

	public static void throwStack(int containerId, int slot) {
		click(containerId, slot, 1, ContainerInput.THROW);
	}

	public static void useItemOn(BlockHitResult hit) {
		Minecraft mc = mc();
		mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
		mc.player.swing(InteractionHand.MAIN_HAND);
	}

	public static void useItem() {
		Minecraft mc = mc();
		mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
	}

	public static void attack(Entity e) {
		Minecraft mc = mc();
		mc.gameMode.attack(mc.player, e);
		mc.player.swing(InteractionHand.MAIN_HAND);
	}

	/** Raw dig packets. We do our own break timing so vanilla's "you let go of left click" logic can't abort us. */
	public static void dig(ServerboundPlayerActionPacket.Action action, BlockPos pos, Direction face) {
		Minecraft mc = mc();
		if (mc.getConnection() == null) return;
		mc.getConnection().send(new ServerboundPlayerActionPacket(action, pos, face, 0));
	}

	public static void swing() {
		mc().player.swing(InteractionHand.MAIN_HAND);
	}

	public static void sendChat(String msg) {
		Minecraft mc = mc();
		if (mc.getConnection() != null) mc.getConnection().sendChat(msg);
	}

	public static void sendCommand(String cmd) {
		Minecraft mc = mc();
		if (mc.getConnection() != null) mc.getConnection().sendCommand(cmd);
	}

	public static void localMessage(String msg) {
		Minecraft mc = mc();
		if (mc.player != null) mc.player.sendSystemMessage(Component.literal(msg));
	}

	public static void actionBar(String msg) {
		Minecraft mc = mc();
		mc.gui.setOverlayMessage(Component.literal(msg), false);
	}

	public static void disconnect(String reason) {
		Minecraft mc = mc();
		if (mc.getConnection() != null) mc.getConnection().getConnection().disconnect(Component.literal(reason));
	}

	public static void respawn(LocalPlayer p) {
		p.respawn();
		mc().setScreen(null);
	}

	public static boolean hasChunk(ClientLevel lvl, int cx, int cz) {
		return lvl.getChunkSource().hasChunk(cx, cz);
	}

	public static LevelChunk chunk(ClientLevel lvl, int cx, int cz) {
		return lvl.getChunkSource().getChunk(cx, cz, false);
	}

	public static int minY(ClientLevel lvl) {
		return lvl.getMinY();
	}

	public static int maxY(ClientLevel lvl) {
		return lvl.getMaxY();
	}

	public static double fallDistance(LocalPlayer p) {
		return p.fallDistance;
	}

	public static String blockId(Block b) {
		return BuiltInRegistries.BLOCK.getKey(b).getPath();
	}

	public static String serverKey() {
		Minecraft mc = mc();
		String key;
		if (mc.getSingleplayerServer() != null) {
			key = "sp_" + mc.getSingleplayerServer().getWorldData().getLevelName();
		} else if (mc.getCurrentServer() != null) {
			key = "mp_" + mc.getCurrentServer().ip;
		} else {
			key = "unknown";
		}
		if (mc.level != null) key += "_" + mc.level.dimension().toString();
		return key.replaceAll("[^A-Za-z0-9_.-]", "_");
	}
}
