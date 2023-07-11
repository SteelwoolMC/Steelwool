package io.github.steelwoolmc.steelwool.jartransform.mappings;

import com.google.gson.JsonElement;
import io.github.steelwoolmc.steelwool.Constants;
import io.github.steelwoolmc.steelwool.Utils;
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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Helper class for handling mapping between intermediary and TSRG
 *
 * <p>Note that we include intermediary->TSRG data in the mod jar, however TSRG uses official Mojang class names at runtime;
 * to comply with the license we have to download the official mappings at runtime on first launch and remap the class name mapping data.</p>
 */
public class Mappings {
	// TODO get game version instead of doing this
	private static final String TARGET_VERSION = "1.20.1";

	/**
	 * Record containing class, method, and field mappings from intermediary to TSRG
	 * <b>Important:</b> we do not keep track of what classes own each method/field; we are relying on method/field names being unique across all classes
	 * (which should be the case for both intermediary and TSRG)
	 * @param classes map from intermediary class names to TSRG class names
	 * @param methods map from intermediary method names to TSRG method names
	 * @param fields map from intermediary field names to TSRG field names
	 */
	public record SimpleMappingData(HashMap<String, String> classes, HashMap<String, String> methods, HashMap<String, String> fields) {}

	/**
	 * Load {@link SimpleMappingData} from a tiny-format intermediary->TSRG mapping file
	 * @param file the path of the mapping file to load
	 * @return the loaded mapping data
	 */
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

	/**
	 * Get the intermediary->TSRG mapping data for the current minecraft version, downloading and generating it if necessary.
	 * @return the mapping data for the current minecraft version
	 */
	public static SimpleMappingData getSimpleMappingData() {
		// TODO check minecraft version of existing file, somehow - put the version in the filename maybe?
		//      maybe have a mappings folder and have files for each MC version
		var steelwoolFolder = FMLPaths.getOrCreateGameRelativePath(Constants.MOD_CACHE_ROOT);
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

	// TODO what do we do if the user doesn't have an internet connection and we don't have mapping data?
	//      probably want to fail more gracefully instead of crashing
	/**
	 * Download the official Mojang classnames and apply them to the intermediary->TSRG data embedded in the mod jar
	 * @param outputFile the file to write the remapped mapping data to
	 */
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

	/**
	 * Download the official Mojang mappings, and extract the class name data
	 * @param url the URL to download the mapping data from
	 * @return a map of obfuscated class names to official class names
	 */
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

	/**
	 * ASM remapper class for remapping mod classes from intermediary to TSRG
	 */
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

	private static final Pattern methodPattern = Pattern.compile("method_[0-9]+");
	private static final Pattern fieldPattern = Pattern.compile("field_[0-9]+");
	private static final Pattern classPattern = Pattern.compile("^[$/\\w]+class_[0-9]+$");
	private static final Pattern classDescriptorPattern = Pattern.compile("(?<=L)[$/\\w]+?class_[0-9]+(?=;)");

	/**
	 * Naively remap an arbitrary string containing methods/fields/classes from intermediary to TSRG
	 * - this makes several assumptions about the format of the input (TODO document these assumptions)
	 * @param mappings the intermediary->TSRG mapping data
	 * @param input the string to be remapped
	 * @return the remapped string, or the original string if it could not be remapped
	 */
	// TODO this has huge room for optimization
	public static String naiveRemapString(Mappings.SimpleMappingData mappings, String input) {
		// If the string is exactly an intermediary class name we remap it
		if (classPattern.matcher(input).find()) {
			var mapped = mappings.classes().get(input);
			return mapped != null ? mapped : input;
		}

		// Otherwise we remap any methods/fields or class descriptors found in the string
		while (true) {
			var matcher = methodPattern.matcher(input);
			if (!matcher.find()) break;
			var start = matcher.start();
			var end = matcher.end();
			var value = matcher.group();
			input = input.substring(0, start) + mappings.methods().get(value) + input.substring(end);
		}
		while (true) {
			var matcher = fieldPattern.matcher(input);
			if (!matcher.find()) break;
			var start = matcher.start();
			var end = matcher.end();
			var value = matcher.group();
			input = input.substring(0, start) + mappings.fields().get(value) + input.substring(end);
		}
		while (true) {
			var matcher = classDescriptorPattern.matcher(input);
			if (!matcher.find()) break;
			var start = matcher.start();
			var end = matcher.end();
			var value = matcher.group();
			input = input.substring(0, start) + mappings.classes().get(value) + input.substring(end);
		}
		return input;
	}
}
