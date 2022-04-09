package cursedflames.steelwool;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import sun.misc.Unsafe;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;

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

	public static Unsafe getUnsafe() {
		return unsafe;
	}

	public static JsonElement readJson(URL url) throws IOException {
		try (var stream = url.openStream()) {
			return JsonParser.parseReader(new BufferedReader(new InputStreamReader(stream)));
		}
	}
}
