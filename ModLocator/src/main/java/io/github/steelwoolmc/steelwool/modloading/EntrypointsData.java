package io.github.steelwoolmc.steelwool.modloading;

import io.github.steelwoolmc.steelwool.Constants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EntrypointsData {
	private final Map<String, List<FabricModData.Entrypoint>> entrypoints;

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

	public void addEntrypoint(String prototype, FabricModData.Entrypoint entrypoint) {
		entrypoints.computeIfAbsent(prototype, k -> new ArrayList<>()).add(entrypoint);
	}

	public static Map<String, List<FabricModData.Entrypoint>> getEntrypoints() {
		if (instance == null) {
			Constants.LOG.warn("SteelWool entrypoints were not initialized. This shouldn't happen");
			return Map.of();
		}
		return Collections.unmodifiableMap(instance.entrypoints);
	}
}
