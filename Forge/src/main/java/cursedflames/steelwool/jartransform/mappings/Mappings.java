package cursedflames.steelwool.jartransform.mappings;

import com.google.gson.JsonElement;
import cursedflames.steelwool.Constants;
import cursedflames.steelwool.Utils;
import net.minecraftforge.fml.loading.FMLPaths;
import org.objectweb.asm.commons.Remapper;
import oshi.util.tuples.Pair;

import java.io.BufferedReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class Mappings {
	// TODO get game version instead of doing this
	private static final String TARGET_VERSION = "1.18.2";

	public static record SimpleMappingData(HashMap<String, String> classes, HashMap<String, String> methods, HashMap<String, String> fields) {}

	private static SimpleMappingData simpleMappings(Path file) throws IOException {
		var classes = new HashMap<String, String>();
		var methods = new HashMap<String, String>();
		var fields = new HashMap<String, String>();

		try (var reader = Files.newBufferedReader(file)) {
			reader
					.lines()
					.filter(line -> !line.isEmpty() && !line.startsWith("#"))
					.forEach(line -> {
						var terms = line.strip().split("\t");
						switch (terms[0]) {
							case "c" -> classes.put(terms[1], terms[2]);
							case "m" -> methods.put(terms[2], terms[3]);
							case "f" -> fields.put(terms[2], terms[3]);
						}
					});
		}

		return new SimpleMappingData(classes, methods, fields);
	}

	public static SimpleMappingData getSimpleMappingData() {
		// TODO check minecraft version of existing file, somehow - put the version in the filename maybe?
		//      maybe have a mappings folder and have files for each MC version
		var steelwoolFolder = FMLPaths.getOrCreateGameRelativePath(Constants.MOD_CACHE_ROOT, Constants.MOD_CACHE_ROOT.toString());
		var mappingFile = steelwoolFolder.resolve("intermediary_to_tsrg.tiny");
		if (mappingFile.toFile().exists()) {
			try {
				return simpleMappings(mappingFile);
			} catch(IOException e) {
				Constants.LOG.warn("Failed to load existing mappings file, regenerating it...");
			}
		}

		applyMojangClassNames(mappingFile);

		try {
			return simpleMappings(mappingFile);
		} catch(IOException e) {
			throw new RuntimeException("Failed to generate and load mappings file");
		}
	}

	private static void applyMojangClassNames(Path outputFile) {
		try {
			var url = new URL("https://launchermeta.mojang.com/mc/game/version_manifest.json");
			JsonElement data = Utils.readJson(url);
			var versions = data.getAsJsonObject().getAsJsonArray("versions");

			URL versionDataUrl = null;

			for (var version : versions) {
				if (version.getAsJsonObject().getAsJsonPrimitive("id").getAsString().equals(TARGET_VERSION)) {
					versionDataUrl = new URL(version.getAsJsonObject().getAsJsonPrimitive("url").getAsString());
					break;
				}
			}

			JsonElement versionData = Utils.readJson(versionDataUrl);

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
					.filter(line -> !line.isEmpty() && !line.startsWith("#") && !line.startsWith(" ") && !line.startsWith("\t"))
					.map(line -> {
						var terms = line.strip().replace(".", "/").split(" +");
						var obfName = terms[2].substring(0, terms[2].length()-1);
						var mojangName = terms[0];
						return new Pair<>(obfName, mojangName);
					})
					.collect(Collectors.toMap(Pair::getA, Pair::getB));
		}
	}

	public static class SteelwoolRemapper extends Remapper {
		private final SimpleMappingData mappings;

		public SteelwoolRemapper(SimpleMappingData mappings) {
			this.mappings = mappings;
		}

		@Override
		public String map(String typeName) {
			return mappings.classes.containsKey(typeName) ? mappings.classes.get(typeName) : super.map(typeName);
		}

		@Override
		public String mapMethodName(String owner, String name, String descriptor) {
			return mappings.methods.containsKey(name) ? mappings.methods.get(name) : super.mapMethodName(owner, name, descriptor);
		}

		@Override
		public String mapFieldName(String owner, String name, String descriptor) {
			return mappings.fields.containsKey(name) ? mappings.fields.get(name) : super.mapFieldName(owner, name, descriptor);
		}

		// Not sure if we need to handle any of these?
		@Override
		public String mapInnerClassName(String name, String ownerName, String innerName) {
			return super.mapInnerClassName(name, ownerName, innerName);
		}

		@Override
		public String mapRecordComponentName(String owner, String name, String descriptor) {
			return super.mapRecordComponentName(owner, name, descriptor);
		}

		@Override
		public String mapPackageName(String name) {
			return super.mapPackageName(name);
		}

		@Override
		public String mapMethodDesc(String methodDescriptor) {
			return super.mapMethodDesc(methodDescriptor);
		}

		@Override
		public Object mapValue(Object value) {
			return super.mapValue(value);
		}

		// I don't know what these do, but AutoRenamingTool from Forge doesn't do anything with them
		@Override public String mapModuleName(String name) { return super.mapModuleName(name); }
		@Override public String mapAnnotationAttributeName(String descriptor, String name) { return super.mapAnnotationAttributeName(descriptor, name); }
		@Override public String mapInvokeDynamicMethodName(String name, String descriptor) { return super.mapInvokeDynamicMethodName(name, descriptor); }
	}
}
