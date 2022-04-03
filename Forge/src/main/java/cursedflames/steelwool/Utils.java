package cursedflames.steelwool;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;

public class Utils {
	public static JsonElement readJson(URL url) throws IOException {
		try (var stream = url.openStream()) {
			return JsonParser.parseReader(new BufferedReader(new InputStreamReader(stream)));
		}
	}
}
