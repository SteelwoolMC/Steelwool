package io.github.steelwoolmc.steelwool;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;

/**
 * Helper class containing various constants
 */
public class Constants {
	/** The mod's id */
	public static final String MOD_ID = "steelwool";
	// TODO SteelWool -> Steelwool everywhere
	/** The name of the mod */
	public static final String MOD_NAME = "SteelWool";
	/** The mod's logger */
	public static final Logger LOG = LogManager.getLogger(MOD_NAME);

	// TODO at some point we'll want to store data in the cache about what steelwool version was last used
	/** Game-directory-relative path for cached mod data (mappings, transformed mods, etc.) */
	public static final Path MOD_CACHE_ROOT = Path.of("." + MOD_ID);

	// TODO store and compare file hash if we cache the inner jar?
	/** The name of the inner jar nested within the main Steelwool jar file */
	public static final String INNER_JAR_NAME = "${inner_jar}";
}
