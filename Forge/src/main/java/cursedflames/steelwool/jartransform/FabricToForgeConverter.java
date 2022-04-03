package cursedflames.steelwool.jartransform;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlWriter;
import cursedflames.steelwool.Constants;
import cursedflames.steelwool.jartransform.mappings.Mappings;
import cursedflames.steelwool.modloading.FabricModData;
import cursedflames.steelwool.modloading.ModCandidate;
import net.fabricmc.tinyremapper.IMappingProvider;
import net.minecraftforge.fml.loading.FMLPaths;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class FabricToForgeConverter {
	// TODO multithreading for jar conversion? I'm not sure how slow it'll end up being once we can actually handle large/many mods
	public static List<Path> getConvertedJarPaths(List<ModCandidate> modCandidates) {
		var mappings = Mappings.getSimpleMappingData();
//		var remapper = TinyRemapper.newRemapper()
//				.keepInputData(true)
//				.withMappings(mappings)
////				.resolveMissing(true) // what does this one even do?
//				.build();
		var remapper = new Mappings.SteelwoolRemapper(mappings);

		var modsOutputFolder = FMLPaths.getOrCreateGameRelativePath(Path.of("steelwool/mods"), Constants.MOD_ID+"/mods");

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

//		for (var candidate : modCandidates) {
//			var outputPath = modsOutputFolder.resolve(candidate.path().getFileName());
//			try (var outputConsumer = new OutputConsumerPath.Builder(outputPath).build()) {
//				var tag = remapper.createInputTag();
//				remapper.readInputs(tag, candidate.path());
//				remapper.apply(outputConsumer, tag);
//			} catch (IOException e) {
//				throw new RuntimeException();
//			}
//		}
//		remapper.finish();

		for (var candidate : modCandidates) {
			var outputPath = modsOutputFolder.resolve(candidate.path().getFileName());
			try {
				URI originalJarUri = new URI("jar:"+candidate.path().toUri());
				URI remappedJarUri = new URI("jar:"+outputPath.toUri());
				try(var oldFs = FileSystems.newFileSystem(originalJarUri, Map.of()); var newFs = FileSystems.newFileSystem(remappedJarUri, Map.of("create", "true"))) {
					Files.walkFileTree(oldFs.getPath("/"), new SimpleFileVisitor<>() {
						@Override
						public FileVisitResult visitFile(Path oldFile, BasicFileAttributes attrs) throws IOException {
							var fileString = oldFile.toString();
							var newFile = newFs.getPath(fileString);
//							if (Files.exists(newFile)) {
//								return FileVisitResult.CONTINUE;
//							}

							Files.createDirectories(newFile.getParent());

							if (fileString.endsWith(".class")) {
								var classReader = new ClassReader(Files.readAllBytes(oldFile));
								var classWriter = new ClassWriter(classReader, 0);
								var classRemapper = new ClassRemapper(classWriter, remapper);

								classReader.accept(classRemapper, 0);

								byte[] data = classWriter.toByteArray();
								Files.write(newFile, data);

								return FileVisitResult.CONTINUE;
							}
							// TODO find refmap files from fabric json -> mixin configs -> refmaps, rather than using file names
							// TODO do we need to change the "named:intermediary" key in the "data" element? afaik only the "mappings" element is used anyway?
							if (fileString.endsWith("refmap.json")) {
								remapRefmap(mappings, oldFile, newFile);
								return FileVisitResult.CONTINUE;
							}
							Files.copy(oldFile, newFile);
							return FileVisitResult.CONTINUE;
						}
					});
					Files.write(newFs.getPath("/META-INF/mods.toml"), new TomlWriter().writeToString(generateForgeMetadata(candidate.metadata())).getBytes());

					// maybe update the manifest while walking all files, instead of here? eh, probably better to do it here in case the file didn't exist in the old jar
					var manifestPath = newFs.getPath("/META-INF/MANIFEST.MF");
					updateManifest(manifestPath, candidate);

					var dummyModClassPackage = "cursedflames/steelwool/" + candidate.metadata().id;
					var dummyModClassPath = newFs.getPath(dummyModClassPackage + "/Mod.class");
					Files.createDirectories(dummyModClassPath.getParent());
					Files.write(dummyModClassPath, generateDummyModClass(dummyModClassPackage + "/Mod", candidate.metadata().id));
				}
				outputJars.add(outputPath);
			} catch (IOException | URISyntaxException e) {
				e.printStackTrace();
				throw new RuntimeException(e);
			}
		}
		return outputJars;
	}

	private static record Maps(HashMap<String, String> classMap, HashMap<String, String> methodMap, HashMap<String, String> fieldMap) {}

	private static Maps getMaps(IMappingProvider provider) {
		var classMap = new HashMap<String, String>();
		var methodMap = new HashMap<String, String>();
		var fieldMap = new HashMap<String, String>();
		IMappingProvider.MappingAcceptor acceptor = new IMappingProvider.MappingAcceptor() {
			@Override
			public void acceptClass(String srcName, String dstName) {
				classMap.put("L"+srcName+";", "L"+dstName+";");
			}

			@Override
			public void acceptMethod(IMappingProvider.Member method, String dstName) {
				methodMap.put(method.name, dstName);
			}

			@Override
			public void acceptMethodArg(IMappingProvider.Member method, int lvIndex, String dstName) {}

			@Override
			public void acceptMethodVar(IMappingProvider.Member method, int lvIndex, int startOpIdx, int asmIndex, String dstName) {}

			@Override
			public void acceptField(IMappingProvider.Member field, String dstName) {
				fieldMap.put(field.name, dstName);
			}
		};

		provider.load(acceptor);

		return new Maps(classMap, methodMap, fieldMap);
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

	private static void updateManifest(Path manifestPath, ModCandidate candidate) throws IOException {
		// FIXME proper manifest handling instead of this mess
		if (candidate.metadata().mixins.size() > 0) {
			List<String> manifestLines;
			// We already copied all files anyway, so just modify the one in the new jar directly
			if (Files.exists(manifestPath)) {
				manifestLines = Files.readAllLines(manifestPath);
			} else {
				// FIXME we need to add some default manifest data here
				manifestLines = new ArrayList<>();
			}
			if (manifestLines.size() == 0 || !manifestLines.get(manifestLines.size()-1).isEmpty()) {
				manifestLines.add("");
			}
			// Remove any existing MixinConfigs lines - TODO maybe we should just keep them?
			for (int i = 0; i < manifestLines.size(); i++) {
				var line = manifestLines.get(i);
				if (line.startsWith("MixinConfigs: ")/* || line.startsWith("FMLModType: ")*/) {
					manifestLines.remove(i);
					i--;
				}
				if (line.length() == 0) {
					// TODO handle sided mixins
					// FIXME will this break for excessively long lines?
					manifestLines.add(i, "MixinConfigs: " + candidate.metadata().mixins.stream()
							.map(FabricModData.MixinConfig::config).collect(Collectors.joining(",")));
//								manifestLines.add(i, "FMLModType: GAMELIBRARY");
					break;
				}
			}

			Files.writeString(manifestPath, String.join("\n", manifestLines));
		}
	}

	private static final Pattern methodPattern = Pattern.compile("method_[0-9]+");
	private static final Pattern fieldPattern = Pattern.compile("field_[0-9]+");
	private static final Pattern classPattern = Pattern.compile("[$/\\w]+class_[0-9]+");

	private static void remapRefmap(Mappings.SimpleMappingData mappings, Path oldFile, Path newFile) throws IOException {
		// FIXME actually parse JSON - rewrite this entire refmap remapper
//		var maps = getMaps(mappings);

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
