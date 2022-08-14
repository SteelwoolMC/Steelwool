package io.github.steelwoolmc.steelwool.modloading;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.steelwoolmc.steelwool.Constants;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.google.common.base.Preconditions.checkArgument;

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

	public static record Entrypoint(String adapter, String value) {}

	public static record MixinConfig(String config, Side environment) {}

	/** Whether this mod was loaded from a nested jar */
	public final boolean isNested;

	// Mandatory fields
	public final String id;
	public final String version;

	// Mod loading data
	public final Side environment;
	public final Map<String, List<Entrypoint>> entrypoints;
	public final List<String> nestedJars;
	public final List<MixinConfig> mixins;
	public final @Nullable String accessWidener;

	// Metadata
	public final String name;
	public final String description;

	public final List<String> authors;
	public final List<String> contributors;

	private FabricModData(boolean isNested, String id, String version, Side environment, Map<String, List<Entrypoint>> entrypoints, List<String> nestedJars,
						  List<MixinConfig> mixins, @Nullable String accessWidener, String name, String description, List<String> authors, List<String> contributors) {
		this.isNested = isNested;
		this.id = id;
		this.version = version;
		this.environment = environment;
		this.entrypoints = entrypoints;
		this.nestedJars = nestedJars;
		this.mixins = mixins;
		this.accessWidener = accessWidener;
		this.name = name;
		this.description = description;
		this.authors = authors;
		this.contributors = contributors;
	}

	private static String getStringOrDefault(JsonObject element, String key, String defaultValue) {
		var inner = element.getAsJsonPrimitive(key);
		if (inner == null) return defaultValue;
		// Error on wrong-type values, rather than ignoring them
		checkArgument(inner.isString(), "Expected value of type String for key %s", key);
		return inner.getAsString();
	}

	public static FabricModData readData(InputStream input, boolean isNested) {
		// We don't need 1:1 accuracy yet
		// eventually we'll *probably* want to mimic fabric-loader's approach to parsing
		// TODO just shade fabric-loader's parser instead?
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
		Map<String, List<Entrypoint>> entrypoints = new HashMap<>();
		var entrypointData = data.getAsJsonObject("entrypoints");
		if (entrypointData != null) {
			entrypointData.keySet().forEach(key -> {
				var outputArray = new ArrayList<Entrypoint>();
				var array = entrypointData.getAsJsonArray(key);
				array.forEach(entrypoint -> {
					if (entrypoint.isJsonObject()) {
						var adapter = getStringOrDefault((JsonObject) entrypoint, "adapter", "default");
						var value = getStringOrDefault((JsonObject) entrypoint, "value", null);
						if (value == null) {
							// TODO
							throw new RuntimeException();
						}
						outputArray.add(new Entrypoint(adapter, value));
					} else if (entrypoint.isJsonPrimitive() && entrypoint.getAsJsonPrimitive().isString()) {
						outputArray.add(new Entrypoint("default", entrypoint.getAsString()));
					} else {
						// TODO
						throw new RuntimeException();
					}
				});
				entrypoints.put(key, outputArray);
			});
		}
		List<String> nestedJars = new ArrayList<>(); // TODO nested jars
		var nestedJarData = data.getAsJsonArray("jars");
		if (nestedJarData != null) {
			nestedJarData.forEach(nestedJarEntry -> {
				if (nestedJarEntry.isJsonObject()) {
					var file = nestedJarEntry.getAsJsonObject().getAsJsonPrimitive("file");
					if (file.isString()) {
						nestedJars.add(file.getAsString());
					}
				}
			});
		}
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
		String accessWidener = getStringOrDefault(data, "accessWidener", "");
		if (accessWidener.isBlank()) accessWidener = null;

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
		return new FabricModData(isNested, id, version, environment, entrypoints, nestedJars, mixins, accessWidener, name, description, authors, contributors);
	}
}
