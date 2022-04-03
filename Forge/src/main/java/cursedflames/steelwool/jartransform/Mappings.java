package cursedflames.steelwool.jartransform;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import cursedflames.steelwool.Constants;
import net.fabricmc.tinyremapper.IMappingProvider;
import net.fabricmc.tinyremapper.TinyUtils;
import net.minecraftforge.fml.loading.FMLPaths;
import oshi.util.tuples.Pair;

import java.io.BufferedReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class Mappings {
	// TODO get game version instead of doing this
	private static final String TARGET_VERSION = "1.18.2";

	private static JsonElement readJson(URL url) throws IOException {
		try (var stream = url.openStream()) {
			return JsonParser.parseReader(new BufferedReader(new InputStreamReader(stream)));
		}
	}

	private static IMappingProvider mappings(Path file) {
		return TinyUtils.createTinyMappingProvider(file, "intermediary", "tsrg");
	}

	public static IMappingProvider getMappings() {
		// TODO check minecraft version of existing file, somehow - put the version in the filename maybe?
		//      maybe have a mappings folder and have files for each MC version
		var steelwoolFolder = FMLPaths.getOrCreateGameRelativePath(Constants.MOD_CACHE_ROOT, Constants.MOD_CACHE_ROOT.toString());
		var mappingFile = steelwoolFolder.resolve("intermediary_to_tsrg.tiny");
		if (mappingFile.toFile().exists()) {
			try {
				return mappings(mappingFile);
			} catch(RuntimeException e) {
				Constants.LOG.warn("Failed to load existing mappings file, regenerating it...");
			}
		}

		applyMojangClassNames(mappingFile);

		try {
			return mappings(mappingFile);
		} catch(RuntimeException e) {
			throw new RuntimeException("Failed to generate and load mappings file");
		}
	}

	private static void applyMojangClassNames(Path outputFile) {
		try {
			var url = new URL("https://launchermeta.mojang.com/mc/game/version_manifest.json");
			JsonElement data = readJson(url);
			var versions = data.getAsJsonObject().getAsJsonArray("versions");

			URL versionDataUrl = null;

			for (var version : versions) {
				if (version.getAsJsonObject().getAsJsonPrimitive("id").getAsString().equals(TARGET_VERSION)) {
					versionDataUrl = new URL(version.getAsJsonObject().getAsJsonPrimitive("url").getAsString());
					break;
				}
			}

			JsonElement versionData = readJson(versionDataUrl);

			var downloads = versionData.getAsJsonObject().getAsJsonObject("downloads");
			var clientMappingsUrl = new URL(downloads.getAsJsonObject("client_mappings").getAsJsonPrimitive("url").getAsString());
			var serverMappingsUrl = new URL(downloads.getAsJsonObject("server_mappings").getAsJsonPrimitive("url").getAsString());

			var clientMojangClassMappings = getMojangClassMappings(clientMappingsUrl);
			var serverMojangClassMappings = getMojangClassMappings(serverMappingsUrl);

			var mojangClassMappings = new HashMap<>(clientMojangClassMappings);
			serverMojangClassMappings.forEach((key, value) -> mojangClassMappings.merge(key, value, (oldValue, newValue) -> {assert oldValue.equals(newValue); return oldValue;}));

			try (var stream = Mappings.class.getResourceAsStream("intermediary_to_tsrg.tiny")) {
				var mappings = new BufferedReader(new InputStreamReader(stream))
						.lines()
						.map(line -> {
							if(!line.startsWith("c")) return line;
							var parts = line.split("\t");
							var mojangClassName = mojangClassMappings.get(parts[2]);
							assert mojangClassName != null;
							return String.format("%s\t%s\t%s", parts[0], parts[1], mojangClassName);
						})
						.collect(Collectors.joining("\n"));
				try(var writer = new FileWriter(outputFile.toFile(), false)) {
					writer.write(mappings);
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
			throw new RuntimeException("Mappings failed");
		}
	}

	private static Map<String, String> getMojangClassMappings(URL url) throws IOException {
		try (var stream = url.openStream()) {
			return new BufferedReader(new InputStreamReader(stream))
					.lines()
					// I think they indent using spaces, not tabs, but just in case
					.filter(line -> !line.startsWith("#") && !line.startsWith(" ") && !line.startsWith("\t"))
					.map(line -> {
						var terms = line.strip().replace(".", "/").split(" +");
						var obfName = terms[2].substring(0, terms[2].length()-1);
						var mojangName = terms[0];
						return new Pair<>(obfName, mojangName);
					})
					.collect(Collectors.toMap(Pair::getA, Pair::getB));
		}
	}
}
