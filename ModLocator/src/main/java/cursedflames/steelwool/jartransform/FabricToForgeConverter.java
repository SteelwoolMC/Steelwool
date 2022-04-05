package cursedflames.steelwool.jartransform;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlWriter;
import cursedflames.steelwool.Constants;
import cursedflames.steelwool.jartransform.mappings.Mappings;
import cursedflames.steelwool.modloading.FabricModData;
import cursedflames.steelwool.modloading.ModCandidate;
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
import java.util.List;
import java.util.Map;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class FabricToForgeConverter {
	// TODO multithreading for jar conversion? I'm not sure how slow it'll end up being once we can actually handle large/many mods
	public static List<Path> getConvertedJarPaths(List<ModCandidate> modCandidates) {
		var mappings = Mappings.getSimpleMappingData();
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

	private static void transformJar(Path inputPath, Path outputPath, Mappings.SimpleMappingData mappings, Remapper remapper, FabricModData fabricData) throws URISyntaxException, IOException {
		URI originalJarUri = new URI("jar:"+inputPath.toUri());
		URI remappedJarUri = new URI("jar:"+outputPath.toUri());
		try(var oldFs = FileSystems.newFileSystem(originalJarUri, Map.of()); var newFs = FileSystems.newFileSystem(remappedJarUri, Map.of("create", "true"))) {
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

			var dummyModClassPackage = "cursedflames/steelwool/generated/" + fabricData.id;
			var dummyModClassPath = newFs.getPath(dummyModClassPackage + "/Mod.class");
			Files.createDirectories(dummyModClassPath.getParent());
			Files.write(dummyModClassPath, generateDummyModClass(dummyModClassPackage + "/Mod", fabricData.id));
		}
	}

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
		//      (manifest data? our manifest would be lost if someone puts SteelWool in a fat jar, but who's going to do that? it'd probably be fine)
		mainAttributes.putValue("Transformed-With-SteelWool", "0.0.0");

		// TODO handle sided mixins
		if (fabricData.mixins.size() > 0) {
			mainAttributes.putValue("MixinConfigs", fabricData.mixins.stream()
					.map(FabricModData.MixinConfig::config).collect(Collectors.joining(",")));
		}

		try(var stream = Files.newOutputStream(manifestPath)) {
			manifest.write(stream);
		}
	}

	private static final Pattern methodPattern = Pattern.compile("method_[0-9]+");
	private static final Pattern fieldPattern = Pattern.compile("field_[0-9]+");
	private static final Pattern classPattern = Pattern.compile("[$/\\w]+class_[0-9]+");

	private static void remapRefmap(Mappings.SimpleMappingData mappings, Path oldFile, Path newFile) throws IOException {
		// FIXME actually parse JSON - rewrite this entire refmap remapper
		var data = Files.readString(oldFile);
		while (true) {
			var matcher = methodPattern.matcher(data);
			if (!matcher.find()) break;
			var start = matcher.start();
			var end = matcher.end();
			var value = matcher.group();
			data = data.substring(0, start) + mappings.methods().get(value) + data.substring(end);
		}
		while (true) {
			var matcher = fieldPattern.matcher(data);
			if (!matcher.find()) break;
			var start = matcher.start();
			var end = matcher.end();
			var value = matcher.group();
			data = data.substring(0, start) + mappings.fields().get(value) + data.substring(end);
		}
		while (true) {
			var matcher = classPattern.matcher(data);
			if (!matcher.find()) break;
			var start = matcher.start();
			var end = matcher.end();
			var value = matcher.group();
			// Hack to deal with Lnet.foo.Bar; class references since the regex grabs the L as well
			if (value.startsWith("L")) {
				start++;
				value = value.substring(1);
			}
			data = data.substring(0, start) + mappings.classes().get(value) + data.substring(end);
		}
		Files.write(newFile, data.getBytes());
	}

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