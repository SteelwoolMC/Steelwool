package io.github.steelwoolmc.steelwool.jartransform;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlWriter;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.github.steelwoolmc.steelwool.Constants;
import io.github.steelwoolmc.steelwool.jartransform.mappings.Mappings;
import io.github.steelwoolmc.steelwool.modloading.FabricModData;
import io.github.steelwoolmc.steelwool.modloading.ModCandidate;
import net.minecraftforge.fml.loading.FMLPaths;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

/**
 * Class for converting Fabric mod jars to mod jars that can be loaded by Forge
 */
public class FabricToForgeConverter {
	// TODO multithreading for jar conversion? I'm not sure how slow it'll end up being once we can actually handle large/many mods

	/**
	 * Given a list of {@link ModCandidate}s, convert the mod jars from Fabric to Forge and return a list of Forge jar paths
	 * @param modCandidates the ModCandidates to be transformed
	 * @param mappings the intermediary->TSRG mapping data
	 * @return a list of Forge jar paths
	 */
	public static List<Path> getConvertedJarPaths(List<ModCandidate> modCandidates, Mappings.SimpleMappingData mappings) {
		var remapper = new Mappings.SteelwoolRemapper(mappings);

		var modsOutputFolder = FMLPaths.getOrCreateGameRelativePath(Constants.MOD_CACHE_ROOT.resolve("mods"), Constants.MOD_ID+"/mods");

		// Delete all existing mod files
		// FIXME only do this for dev versions; we want caching for release - figure out how to do caching properly though
		try {
			Files.walk(modsOutputFolder).forEach(path -> {
				// Don't delete the root folder, just the contents
				if (path.equals(modsOutputFolder)) return;
				try {
					Files.deleteIfExists(path);
				} catch (IOException ignored) {}
			});
		} catch (IOException e) {
			e.printStackTrace();
		}

		var outputJars = new ArrayList<Path>();

		for (var candidate : modCandidates) {
			var outputPath = modsOutputFolder.resolve(candidate.path().getFileName());
			try {
				transformJar(candidate.path(), outputPath, mappings, remapper, candidate.metadata());
				outputJars.add(outputPath);
			} catch (IOException | URISyntaxException e) {
				throw new RuntimeException(String.format("Failed to transform mod jar for %s", candidate.metadata().id), e);
			}
		}
		return outputJars;
	}

	/**
	 * Transform a Fabric mod jar into a Forge mod jar
	 * @param inputPath the path of the fabric mod jar
	 * @param outputPath the path to create the forge mod jar
	 * @param mappings the intermediary->TSRG mapping data
	 * @param remapper the ASM remapper to be used for remapping mod classes
	 * @param fabricData the fabric mod metadata of the mod
	 */
	private static void transformJar(Path inputPath, Path outputPath, Mappings.SimpleMappingData mappings, Remapper remapper, FabricModData fabricData) throws URISyntaxException, IOException {
		URI originalJarUri = new URI("jar:"+inputPath.toUri());
		URI remappedJarUri = new URI("jar:"+outputPath.toUri());
		try(var oldFs = FileSystems.newFileSystem(originalJarUri, Map.of()); var newFs = FileSystems.newFileSystem(remappedJarUri, Map.of("create", "true"))) {
			var accessWidenerPath = fabricData.accessWidener != null ? oldFs.getPath(fabricData.accessWidener) : null;
			var accessWidenerOutputPath = newFs.getPath("/META-INF/accesstransformer.cfg");
			// Create META-INF immediately if it doesn't exist
			Files.createDirectories(newFs.getPath("/META-INF"));
			Files.walkFileTree(oldFs.getPath("/"), new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path oldFile, BasicFileAttributes attrs) throws IOException {
					var fileString = oldFile.toString();
					var newFile = newFs.getPath(fileString);

					Files.createDirectories(newFile.getParent());

					if (fileString.endsWith(".class")) {
						var classReader = new ClassReader(Files.readAllBytes(oldFile));
						var classWriter = new ClassWriter(classReader, 0);
						var classRemapper = new ClassRemapper(classWriter, remapper);

						classReader.accept(classRemapper, 0);

						byte[] data = classWriter.toByteArray();
						Files.write(newFile, data);
					} else if (fileString.endsWith("refmap.json")) {
						// TODO find refmap files from fabric json -> mixin configs -> refmaps, rather than using file names
						// TODO do we need to change the "named:intermediary" key in the "data" element? afaik only the "mappings" element is used anyway?
						remapRefmap(mappings, oldFile, newFile);
					} else if (accessWidenerPath != null && Files.isSameFile(accessWidenerPath, oldFile)) {
						convertAccessWidener(mappings, oldFile, accessWidenerOutputPath);
					} else {
						Files.copy(oldFile, newFile);
					}

					return FileVisitResult.CONTINUE;
				}
			});
			Files.write(newFs.getPath("/META-INF/mods.toml"), new TomlWriter().writeToString(generateForgeMetadata(fabricData)).getBytes());

			// maybe update the manifest while walking all files, instead of here? eh, probably better to do it here in case the file didn't exist in the old jar
			var manifestPath = newFs.getPath("/META-INF/MANIFEST.MF");
			updateManifest(manifestPath, fabricData);

			// Fabric allows for `-` in mod ids, which isn't allowed in java packages
			var escapedId = fabricData.id.replace("-", "_");

			var dummyModClassPackage = "io/github/steelwoolmc/steelwool/generated/" + escapedId;
			var dummyModClassPath = newFs.getPath(dummyModClassPackage + "/Mod.class");
			Files.createDirectories(dummyModClassPath.getParent());
			Files.write(dummyModClassPath, generateDummyModClass(dummyModClassPackage + "/Mod", fabricData.id));
		}
	}

	/**
	 * Generate the Forge mod metadata for a mod, from its Fabric mod metadata
	 * @param fabricData the fabric mod metadata
	 * @return the forge mod metadata, as a {@link Config}
	 */
	private static Config generateForgeMetadata(FabricModData fabricData) {
		// TODO do we care about side effects from always setting this and not resetting it afterwards?
		// why is this a global property anyway? seems kinda janky
		Config.setInsertionOrderPreserved(true);

		var config = Config.inMemory();
		// TODO do we need a separate language loader?
		// TODO how do we handle non-java fabric mods?
		config.set("modLoader", "javafml");
		// TODO don't hardcode this
		config.set("loaderVersion", "[40,)");
		// FIXME get actual license information before release
		config.set("license", "Unknown");

		var modEntry = Config.inMemory();
		modEntry.set("modId", fabricData.id);
		modEntry.set("version", fabricData.version);
		modEntry.set("displayName", fabricData.name);

		config.set("mods", List.of(modEntry));

		return config;
	}

	/**
	 * Modify the jar manifest data of a converted mod jar
	 *
	 * <p>Updates the mixin config path, and adds a marker to indicate that the jar was transformed by Steelwool</p>
	 * @param manifestPath the path of the manifest to modify
	 * @param fabricData the fabric mod metadata of the mod
	 */
	private static void updateManifest(Path manifestPath, FabricModData fabricData) throws IOException {
		Manifest manifest;

		if (Files.exists(manifestPath)) {
			try(var stream = Files.newInputStream(manifestPath)) {
				manifest = new Manifest(stream);
			}
		} else {
			// TODO do we need to add some default manifest data here?
			manifest = new Manifest();
		}

		var mainAttributes = manifest.getMainAttributes();
		// TODO figure out how to cleanly include/get version data at runtime so we can put the version here
		//      (manifest data? our manifest would be lost if someone puts Steelwool in a fat jar, but who's going to do that? it'd probably be fine)
		mainAttributes.putValue("Transformed-With-Steelwool", "0.0.0");

		// TODO handle sided mixins
		if (fabricData.mixins.size() > 0) {
			mainAttributes.putValue("MixinConfigs", fabricData.mixins.stream()
					.map(FabricModData.MixinConfig::config).collect(Collectors.joining(",")));
		}

		try(var stream = Files.newOutputStream(manifestPath)) {
			manifest.write(stream);
		}
	}

	/**
	 * Remap a Mixin refmap file from (named->intermediary) to (named->TSRG)
	 * @param mappings the intermediary->TSRG mapping data
	 * @param oldFile the path of the original refmap file
	 * @param newFile the path to create the remapped refmap file
	 */
	private static void remapRefmap(Mappings.SimpleMappingData mappings, Path oldFile, Path newFile) throws IOException {
		JsonObject jsonRoot;
		try (var reader = Files.newBufferedReader(oldFile)) {
			jsonRoot = JsonParser.parseReader(reader).getAsJsonObject();
		}

		remapRefmapData(mappings, jsonRoot.getAsJsonObject("mappings"));
		// TODO do we even need to remap the data? Does mixin just always use `mappings`?
		var data = jsonRoot.getAsJsonObject("data");
		// TODO do we want to change the key to say "searge" too?
		data.entrySet().forEach(entry -> remapRefmapData(mappings, entry.getValue().getAsJsonObject()));

		try (var writer = Files.newBufferedWriter(newFile)) {
			new Gson().toJson(jsonRoot, writer);
			// Not sure why we have to call this ourselves but whatever
			writer.flush();
		}
	}

	/**
	 * Remap the mapping data within a Mixin refmap, from (named->intermediary) to (named->TSRG)
	 * @param mappings the intermediary->TSRG mapping data
	 * @param data the refmap data to modify in-place
	 */
	private static void remapRefmapData(Mappings.SimpleMappingData mappings, JsonObject data) {
		data.entrySet().forEach(classEntry -> {
			if (classEntry.getValue().isJsonObject()) {
				classEntry.getValue().getAsJsonObject().entrySet().forEach(entry -> {
					var value = entry.getValue();
					if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
						entry.setValue(new JsonPrimitive(Mappings.naiveRemapString(mappings, value.getAsString())));
					}
				});
			}
		});
	}

	// TODO actually test this - really pretty much everything in this class needs actual tests written
	//      also probably want to split conversion logic for specific file types into their own classes

	/**
	 * Convert an access widener file to an equivalent access transformer file
	 * @param mappings the intermediary->TSRG mapping data
	 * @param oldFile the access widener file to read
	 * @param newFile the path to create the access transformer file
	 */
	private static void convertAccessWidener(Mappings.SimpleMappingData mappings, Path oldFile, Path newFile) throws IOException {
		try (var reader = Files.newBufferedReader(oldFile)) {
			var header = reader.readLine();
			var headerParts = header.split("\\s+");
			if (headerParts.length != 3 || !headerParts[0].equals("accessWidener")) {
				// TODO maybe add config options for parsing strictness (fail vs. warn)
				Constants.LOG.warn("Got invalid access widener header, converting it anyway!\n{}", header);
			} else if (!headerParts[1].equals("v1")) {
				Constants.LOG.warn("Got non-v1 access widener header, converting it as if it's v1 anyway!\n{}", header);
			// TODO is it `intermediary` or `named`? fabric-api has `named`
			} else if (!headerParts[2].equals("intermediary")) {
				Constants.LOG.warn("Got non-v1 access widener header, converting it anyway!\n{}", header);
			}

			// TODO option for whether or not to preserve comments?
			var accessTransformerData = reader.lines().map(line -> {
				String transformer;
				String comment;
				var index = line.indexOf("#");
				if (index >= 0) {
					transformer = line.substring(0, index);
					comment = line.substring(index);
				} else {
					transformer = line;
					comment = "";
				}

				transformer = transformer.strip();
				if (transformer.isEmpty()) {
					return comment;
				}

				return convertAccessWidenerLine(mappings, transformer) + comment;
			}).collect(Collectors.joining("\n"));
			accessTransformerData = "# Converted from an AccessWidener by Steelwool\n" + accessTransformerData;
			Files.write(newFile, accessTransformerData.getBytes());
		}
	}

	/**
	 * Convert a single access widener entry to an equivalent access transformer
	 * @param mappings the intermediary->TSRG mapping data
	 * @param transformer the access widener to be transformed
	 * @return the equivalent access transformer
	 */
	private static String convertAccessWidenerLine(Mappings.SimpleMappingData mappings, String transformer) {
		var parts = transformer.split("\\s+");
		var className = Mappings.naiveRemapString(mappings, parts[2]).replace("/", ".");
		// TODO error handling for invalid lines
		switch (parts[1]) {
			case "class" -> {
				switch (parts[0]) {
					case "accessible" -> {
						return String.format("public %s", className);
					}
					case "extendable" -> {
						return String.format("public-f %s", className);
					}
				}
			}
			case "method" -> {
				var methodName = Mappings.naiveRemapString(mappings, parts[3]);
				var methodDescriptor = Mappings.naiveRemapString(mappings, parts[4]);
				switch (parts[0]) {
					case "accessible" -> {
						// TODO do we care about adding final if the method is private?
						return String.format("public %s %s%s", className, methodName, methodDescriptor);
					}
					case "extendable" -> {
						// TODO do we care about making the method protected instead of public?
						return String.format("public-f %s %s%s", className, methodName, methodDescriptor);
					}
				}
			}
			case "field" -> {
				var fieldName = Mappings.naiveRemapString(mappings, parts[3]);
				var fieldDescriptor = Mappings.naiveRemapString(mappings, parts[4]);
				switch (parts[0]) {
					case "accessible" -> {
						return String.format("public %s %s", className, fieldName);
					}
					case "mutable" -> {
						// TODO do we care about making the field also public?
						return String.format("public-f %s %s", className, fieldName);
					}
				}
			}
		}
		Constants.LOG.warn("Failed to convert access widener line: {}", transformer);
		return "";
	}

	/**
	 * Generate a dummy mod class, as Forge requires mods to have a main class annotated with {@link net.minecraftforge.fml.common.Mod} in order to load them
	 * @param className the name of the class to be generated
	 * @param modid the id of the mod
	 * @return the generated dummy class bytecode as a byte array
	 */
	private static byte[] generateDummyModClass(String className, String modid) {
		var cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);
		// Add mod annotation
		var annotation = cw.visitAnnotation("Lnet/minecraftforge/fml/common/Mod;", true);
		annotation.visit("value", modid);

		// Define default no-args constructor
		var constructor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		constructor.visitCode();
		constructor.visitVarInsn(Opcodes.ALOAD, 0); // Load `this` onto stack
		constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false); // Call super constructor
		constructor.visitInsn(Opcodes.RETURN);
//		// Java gives a VerifyError without this line even though COMPUTE_FRAMES or COMPUTE_MAXS is supposed to do it automatically
		constructor.visitMaxs(1, 1);
		cw.visitEnd();
		return cw.toByteArray();
	}
}
