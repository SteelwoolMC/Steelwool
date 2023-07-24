package io.github.steelwoolmc.steelwool.modloading;

import io.github.steelwoolmc.steelwool.Constants;
import net.fabricmc.loader.impl.metadata.EntrypointMetadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// TODO we should probably handle entrypoints in a completely different way
public class EntrypointsData {
	private final Map<String, List<EntrypointMetadata>> entrypoints;

	private static EntrypointsData instance;

	private EntrypointsData() {
		entrypoints = new HashMap<>();
	}

	public static EntrypointsData createInstance() {
		if (instance != null) {
			throw new UnsupportedOperationException("Attempted to create multiple instances of Entrypoints; this shouldn't happen");
		}
		instance = new EntrypointsData();
		return instance;
	}

	public void addEntrypoint(String prototype, EntrypointMetadata entrypoint) {
		entrypoints.computeIfAbsent(prototype, k -> new ArrayList<>()).add(entrypoint);
	}

	public static Map<String, List<EntrypointMetadata>> getEntrypoints() {
		if (instance == null) {
			Constants.LOG.warn("Steelwool entrypoints were not initialized. This shouldn't happen");
			return Map.of();
		}
		return Collections.unmodifiableMap(instance.entrypoints);
	}
}
