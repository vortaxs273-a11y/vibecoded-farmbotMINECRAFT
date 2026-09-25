package dev.farmbot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/** config/farmbot/config.json */
public final class Config {
	public static Config I = new Config();

	/** /farmbot infinite: plains only, huge site, expand forever, never log out, auto-start on join. */
	public boolean infinite = false;
	/** Start farming automatically every time you join a world. */
	public boolean autoStart = false;
	/** Site size in chunks (square). 6 = 96x96 blocks of pure farm. */
	public int siteChunks = 6;
	/** Clean chunk margin required around the site (no player-made blocks). */
	public int marginChunks = 2;
	/** How many rings of 9x9 cells to build around home. 5 = 11x11 cells = 99x99 blocks. */
	public int maxRings = 5;
	/** Blocks to walk per exploration hop when no untouched land is visible. */
	public int exploreStep = 160;
	/** Stay this far away from anywhere another player has been seen. */
	public int avoidPlayerRadius = 64;

	public boolean chatReplies = true;
	public int proximityRadius = 6;
	public int chatCooldownSeconds = 8;

	/** Disconnect as a last resort when about to die. */
	public boolean panicLogout = true;
	public float panicHealth = 5f;
	public int eatBelowHunger = 14;

	/** Baritone legitMine: only mine ores it has actually seen (branch mines at legitMineY). No x-ray tunnels. */
	public boolean legitMining = true;
	public int legitMineY = 16;

	public boolean craftArmor = true;
	public boolean placeTorches = true;

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("farmbot").resolve("config.json");
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static void load() {
		try {
			Path p = path();
			if (Files.exists(p)) {
				Config c = GSON.fromJson(Files.readString(p), Config.class);
				if (c != null) I = c;
			}
		} catch (Exception e) {
			FarmBotClient.LOG.warn("bad farmbot config, using defaults", e);
		}
		save();
	}

	public static void save() {
		try {
			Path p = path();
			Files.createDirectories(p.getParent());
			Files.writeString(p, GSON.toJson(I));
		} catch (Exception e) {
			FarmBotClient.LOG.warn("could not save farmbot config", e);
		}
	}
}
