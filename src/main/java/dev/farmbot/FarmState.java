package dev.farmbot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Everything the bot must remember across deaths, disconnects and restarts. One file per server+dimension. */
public final class FarmState {
	public static final int NONE = 0, BUILT = 2, SKIPPED = 3, STORAGE = 4;

	public boolean hasSite;
	/** site was picked by infinite mode (guaranteed plains) */
	public boolean infiniteSite;
	public int cx, cy, cz; // cy is the feet level of the farm surface; ground (farmland) is cy-1
	public Map<String, Integer> cells = new HashMap<>();
	public List<Long> chests = new ArrayList<>();
	/** extra plots turned into chest yards once home storage filled up, in claim order ("i,j") */
	public List<String> storageCells = new ArrayList<>();
	/** chest spots we couldn't build on */
	public Set<Long> badSpots = new HashSet<>();
	/** chests we've filled to the brim (skipped when storing) */
	public Set<Long> fullChests = new HashSet<>();
	public Long table, furnace;
	public boolean pool;
	/** we left something cooking in the home furnace */
	public boolean furnaceLoaded;
	public Set<Long> ours = new HashSet<>();
	public List<long[]> playerSightings = new ArrayList<>(); // x, z, time
	public int deaths;
	public long harvested;
	public long breadBaked;

	private transient Path file;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static FarmState load(String key) {
		Path dir = FabricLoader.getInstance().getConfigDir().resolve("farmbot");
		Path f = dir.resolve(key + ".json");
		FarmState s = null;
		try {
			if (Files.exists(f)) s = GSON.fromJson(Files.readString(f), FarmState.class);
		} catch (Exception e) {
			FarmBotClient.LOG.warn("farm state corrupted, starting fresh (farm farm)", e);
		}
		if (s == null) s = new FarmState();
		if (s.cells == null) s.cells = new HashMap<>();
		if (s.chests == null) s.chests = new ArrayList<>();
		if (s.ours == null) s.ours = new HashSet<>();
		if (s.storageCells == null) s.storageCells = new ArrayList<>();
		if (s.badSpots == null) s.badSpots = new HashSet<>();
		if (s.fullChests == null) s.fullChests = new HashSet<>();
		if (s.playerSightings == null) s.playerSightings = new ArrayList<>();
		s.file = f;
		return s;
	}

	public void save() {
		if (file == null) return;
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(this));
		} catch (IOException e) {
			FarmBotClient.LOG.warn("could not save farm state", e);
		}
	}

	public int cell(int i, int j) {
		return cells.getOrDefault(i + "," + j, NONE);
	}

	public void setCell(int i, int j, int status) {
		cells.put(i + "," + j, status);
		save();
	}

	public void reset() {
		hasSite = false;
		cells.clear();
		chests.clear();
		storageCells.clear();
		badSpots.clear();
		fullChests.clear();
		table = furnace = null;
		pool = false;
		ours.clear();
		save();
	}
}
