package cursedflames.steelwool;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// See: https://fabricmc.net/wiki/documentation:fabric_mod_json
public class FabricModData {
	public enum Side {
		CLIENT, SERVER, BOTH;

		public static Side fromString(String string) {
			if (string.isEmpty() || string.equals("*")) {
				return BOTH;
			}
			if (string.equalsIgnoreCase("client")) {
				return CLIENT;
			}
			if (string.equalsIgnoreCase("server")) {
				return SERVER;
			}
			// TODO
			throw new RuntimeException();
		}
	}

	public static record EntryPoint(String adapter, String value) {}

	public static record MixinConfig(String config, Side environment) {}

	// Mandatory fields
	public final String id;
	public final String version;

	// Mod loading data
	public final Side environment;
	public final Map<String, List<EntryPoint>> entrypoints;
	public final List<MixinConfig> mixins;

	// Metadata
	public final String name;
	public final String description;

	public final List<String> authors;
	public final List<String> contributors;

	private FabricModData(String id, String version, Side environment, Map<String, List<EntryPoint>> entrypoints, List<MixinConfig> mixins,
						  String name, String description, List<String> authors, List<String> contributors) {
		this.id = id;
		this.version = version;
		this.environment = environment;
		this.entrypoints = entrypoints;
		this.mixins = mixins;
		this.name = name;
		this.description = description;
		this.authors = authors;
		this.contributors = contributors;
	}

	private static String getStringOrDefault(JsonObject element, String key, String defaultValue) {
		var inner = element.getAsJsonPrimitive(key);
		if (inner == null) return defaultValue;
		// Error on wrong-type values, rather than ignoring them
		assert inner.isString();
		return inner.getAsString();
	}

	public static FabricModData readData(InputStream input) {
		// We don't need 1:1 accuracy yet
		// eventually we'll *probably* want to mimic fabric-loader's approach to parsing
		var data = JsonParser.parseReader(new InputStreamReader(input)).getAsJsonObject();

		var schemaVersionEntry = data.getAsJsonPrimitive("schemaVersion");
		var schemaVersion = schemaVersionEntry == null ? 0 : schemaVersionEntry.getAsInt();
		// TODO proper schema version support
		if (schemaVersion != 1) {
			Constants.LOG.warn("Parsing a fabric mod json with schema version {}, things may break", schemaVersion);
		}

		// Mandatory fields
		String id = getStringOrDefault(data, "id", null);
		String version = getStringOrDefault(data, "version", null);

		// Mod loading data
		Side environment = Side.fromString(getStringOrDefault(data, "environment", ""));
		Map<String, List<EntryPoint>> entrypoints = new HashMap<>();
		var entrypointData = data.getAsJsonObject("entrypoints");
		if (entrypointData != null) {
			entrypointData.keySet().forEach(key -> {
				var outputArray = new ArrayList<EntryPoint>();
				var array = entrypointData.getAsJsonArray(key);
				array.forEach(entrypoint -> {
					if (entrypoint.isJsonObject()) {
						var adapter = getStringOrDefault((JsonObject) entrypoint, "adapter", "default");
						var value = getStringOrDefault((JsonObject) entrypoint, "value", null);
						if (value == null) {
							// TODO
							throw new RuntimeException();
						}
						outputArray.add(new EntryPoint(adapter, value));
					} else if (entrypoint.isJsonPrimitive() && entrypoint.getAsJsonPrimitive().isString()) {
						outputArray.add(new EntryPoint("default", entrypoint.getAsString()));
					} else {
						// TODO
						throw new RuntimeException();
					}
				});
				entrypoints.put(key, outputArray);
			});
		}
//		List<NestedJar> nestedJars = new ArrayList<>(); // TODO nested jars
		// TODO language adapters?
		List<MixinConfig> mixins = new ArrayList<>();
		var mixinData = data.getAsJsonArray("mixins");
		if (mixinData != null) {
			mixinData.forEach(mixinEntry -> {
				if (mixinEntry.isJsonObject()) {
					var mixinEnvironment = Side.fromString(getStringOrDefault((JsonObject) mixinEntry, "environment", ""));
					var config = getStringOrDefault((JsonObject) mixinEntry, "config", null);
					if (config == null) {
						// TODO
						throw new RuntimeException();
					}
					mixins.add(new MixinConfig(config, mixinEnvironment));
				} else if (mixinEntry.isJsonPrimitive() && mixinEntry.getAsJsonPrimitive().isString()) {
					mixins.add(new MixinConfig(mixinEntry.getAsString(), Side.BOTH));
				} else {
					// TODO
					throw new RuntimeException();
				}
			});
		}

		// Dependencies
		// TODO

		// Metadata
		String name = getStringOrDefault(data, "name", null);
		String description = getStringOrDefault(data, "description", null);
		// TODO contact?
		// TODO read these
		List<String> authors = new ArrayList<>(); //TODO preserve author contact info too?
		List<String> contributors = new ArrayList<>();
		// TODO license stuff
		// TODO icon

		// TODO
		return new FabricModData(id, version, environment, entrypoints, mixins, name, description, authors, contributors);
	}
}
