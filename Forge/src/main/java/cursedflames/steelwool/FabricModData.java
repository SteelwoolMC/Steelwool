package cursedflames.steelwool;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;

public class FabricModData {
	JsonElement data;

	private FabricModData(JsonElement data) {
		this.data = data;
	}


	public static FabricModData readData(InputStream input) {
		// We don't need 1:1 accuracy yet
		// eventually we'll *probably* want to mimic fabric-loader's approach to parsing
		var data = JsonParser.parseReader(new InputStreamReader(input));

		return new FabricModData(data);
	}
}
