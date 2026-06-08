package com.cowboy.multikeybinds.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds the user's "second key" assignments and exposes a fast reverse lookup
 * (extra key -> bindings) for the engine mixin.
 *
 * Source of truth at runtime: {@link #BINDS} (KeyMapping -> list of extra keys).
 * On disk: a tiny JSON map of bindingName -> [keyName].
 */
public final class MultiBindManager {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	// KeyMapping -> its extra keys. IdentityHashMap because KeyMapping equality
	// is by name and we want the actual object identity here.
	private static final Map<KeyMapping, List<InputConstants.Key>> BINDS = new IdentityHashMap<>();

	// extra key -> bindings that use it. Replaced wholesale on every change so
	// the engine can read it without locking.
	private static volatile Map<InputConstants.Key, List<KeyMapping>> REVERSE = Collections.emptyMap();

	// Raw saved data (bindingName -> [keyName]) read from disk before the key
	// mappings exist; resolved into BINDS once the game has started.
	private static Map<String, List<String>> raw = new HashMap<>();

	private MultiBindManager() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("cowboymkb.json");
	}

	/** Read the JSON file into {@link #raw}. Safe to call before key mappings load. */
	public static void load() {
		raw = new HashMap<>();
		Path path = file();
		if (!Files.exists(path)) {
			return;
		}
		try {
			String text = Files.readString(path);
			JsonElement root = JsonParser.parseString(text);
			if (root.isJsonObject()) {
				JsonObject obj = root.getAsJsonObject();
				for (String name : obj.keySet()) {
					List<String> keys = new ArrayList<>();
					obj.getAsJsonArray(name).forEach(e -> keys.add(e.getAsString()));
					raw.put(name, keys);
				}
			}
		} catch (Exception e) {
			System.err.println("[cowboymkb] Failed to read config: " + e.getMessage());
		}
	}

	/** Match saved binding names to the actual KeyMapping objects and build the reverse map. */
	public static void resolve(Options options) {
		BINDS.clear();
		Map<String, KeyMapping> byName = new HashMap<>();
		for (KeyMapping km : options.keyMappings) {
			byName.put(km.getName(), km);
		}
		for (Map.Entry<String, List<String>> e : raw.entrySet()) {
			KeyMapping km = byName.get(e.getKey());
			if (km == null) {
				continue;
			}
			List<InputConstants.Key> keys = new ArrayList<>();
			for (String keyName : e.getValue()) {
				try {
					InputConstants.Key key = InputConstants.getKey(keyName);
					if (key != null && key != InputConstants.UNKNOWN) {
						keys.add(key);
					}
				} catch (Exception ignored) {
					// skip unrecognised key names
				}
			}
			if (!keys.isEmpty()) {
				BINDS.put(km, keys);
			}
		}
		rebuildReverse();
	}

	/** Extra keys assigned to a binding (never null). */
	public static List<InputConstants.Key> getExtra(KeyMapping km) {
		return BINDS.getOrDefault(km, Collections.emptyList());
	}

	/** Set the single extra key for a binding, or pass null to clear it. */
	public static void setExtra(KeyMapping km, InputConstants.Key key) {
		if (key == null || key == InputConstants.UNKNOWN) {
			BINDS.remove(km);
		} else {
			List<InputConstants.Key> list = new ArrayList<>();
			list.add(key);
			BINDS.put(km, list);
		}
		rebuildReverse();
		save();
	}

	/** Fast path used by the engine mixin for every key/mouse event. */
	public static List<KeyMapping> bindingsForExtra(InputConstants.Key key) {
		return REVERSE.getOrDefault(key, Collections.emptyList());
	}

	private static void rebuildReverse() {
		Map<InputConstants.Key, List<KeyMapping>> next = new HashMap<>();
		for (Map.Entry<KeyMapping, List<InputConstants.Key>> e : BINDS.entrySet()) {
			for (InputConstants.Key key : e.getValue()) {
				next.computeIfAbsent(key, k -> new ArrayList<>()).add(e.getKey());
			}
		}
		REVERSE = next;
	}

	private static void save() {
		JsonObject obj = new JsonObject();
		for (Map.Entry<KeyMapping, List<InputConstants.Key>> e : BINDS.entrySet()) {
			com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
			for (InputConstants.Key key : e.getValue()) {
				arr.add(key.getName());
			}
			obj.add(e.getKey().getName(), arr);
		}
		// keep raw in sync so a re-resolve (e.g. resource reload) doesn't lose edits
		raw = new HashMap<>();
		for (Map.Entry<KeyMapping, List<InputConstants.Key>> e : BINDS.entrySet()) {
			List<String> names = new ArrayList<>();
			for (InputConstants.Key key : e.getValue()) {
				names.add(key.getName());
			}
			raw.put(e.getKey().getName(), names);
		}
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(obj));
		} catch (IOException ex) {
			System.err.println("[cowboymkb] Failed to write config: " + ex.getMessage());
		}
	}
}
