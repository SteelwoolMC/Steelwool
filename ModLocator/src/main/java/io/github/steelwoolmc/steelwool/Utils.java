package io.github.steelwoolmc.steelwool;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import sun.misc.Unsafe;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;

/**
 * Class containing miscellaneous utils
 */
public class Utils {
	private static final Unsafe unsafe;

	static {
		try {
			var unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
			unsafeField.setAccessible(true);
			unsafe = (Unsafe) unsafeField.get(null);
		} catch (NoSuchFieldException | IllegalAccessException e) {
			throw new AssertionError("Failed to get instance of sun.misc.Unsafe", e);
		}
	}

	/**
	 * Get unsafe
	 * @return unsafe
	 */
	public static Unsafe getUnsafe() {
		return unsafe;
	}

	/**
	 * Read JSON data from a URL
	 * @param url the URL to read data from
	 * @return the parsed json data
	 */
	public static JsonElement readJson(URL url) throws IOException {
		try (var stream = url.openStream()) {
			return JsonParser.parseReader(new BufferedReader(new InputStreamReader(stream)));
		}
	}
}
