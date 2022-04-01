package cursedflames.steelwool;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlWriter;
import cpw.mods.jarhandling.SecureJar;
import net.fabricmc.tinyremapper.FileSystemHandler;
import net.fabricmc.tinyremapper.IMappingProvider;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.ModDirTransformerDiscoverer;
import net.minecraftforge.fml.loading.StringUtils;
import net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileLocator;
import net.minecraftforge.forgespi.locating.IModFile;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import javax.annotation.Nullable;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class SteelwoolModLocator extends AbstractJarFileLocator {
	private final Path modFolder;

	public SteelwoolModLocator() {
		this.modFolder = FMLPaths.MODSDIR.get();
		Constants.LOG.info("SteelWool mod locator instantiated. Hi Forge :)");
	}

	@Override
	public String name() {
		return Constants.MOD_ID;
	}

	static record ModCandidate(Path path, FabricModData metadata) {}

	@Override
	public Stream<Path> scanCandidates() {
		Constants.LOG.info("SteelWool scanning for mods...");
		var excluded = ModDirTransformerDiscoverer.allExcluded();
		for (var e : excluded) {
			System.out.println("excluded = " + e);
		}

		List<Path> mods;

		try {
			mods = Files.list(this.modFolder)
					.filter(p -> !excluded.contains(p) && isValidJar(p))
					// .map(p -> getModCandidate(p))
					.filter(Objects::nonNull)
					// Use the same sorting as Forge, for consistency
					// TODO Fabric's sorting is much more complex; need to resort after finding Fabric mods
					//  - or maybe not? they shuffle in-dev to avoid order reliance
					.sorted(Comparator.comparing(path-> StringUtils.toLowerCase(path.getFileName().toString())))
					.collect(Collectors.toList());
		} catch (IOException e) {
			// TODO error handling
			e.printStackTrace();
			mods = List.of();
		}

		var modCandidates = new ArrayList<ModCandidate>();
		for (var mod : mods) {
			System.out.println("mod = " + mod);
			var cand = getModCandidate(mod);
			if (cand != null) {
				modCandidates.add(cand);
			}
		}

		Constants.LOG.info("Found {} fabric mod candidates", modCandidates.size());

		var mappings = Mappings.getMappings();
		var remapper = TinyRemapper.newRemapper().withMappings(mappings).build();

		var modsOutputFolder = FMLPaths.getOrCreateGameRelativePath(Path.of(Constants.MOD_ID+"/mods"), Constants.MOD_ID+"/mods");

		var outputJars = new ArrayList<Path>();

		for (var candidate : modCandidates) {
			var outputPath = modsOutputFolder.resolve(candidate.path.getFileName());
			try (var outputConsumer = new OutputConsumerPath.Builder(outputPath).build()) {
				// TODO use input tags for efficiency
				remapper.readInputs(candidate.path);
				remapper.apply(outputConsumer);
				remapper.finish();
			} catch (IOException e) {
				throw new RuntimeException();
			}
			try {
				URI originalJarUri = new URI("jar:"+candidate.path.toUri());
				URI remappedJarUri = new URI("jar:"+outputPath.toUri());
				try(var oldFs = FileSystemHandler.open(originalJarUri); var newFs = FileSystemHandler.open(remappedJarUri)) {
					Files.walkFileTree(oldFs.getPath("/"), new SimpleFileVisitor<>() {
						@Override
						public FileVisitResult visitFile(Path oldFile, BasicFileAttributes attrs) throws IOException {
							var newFile = newFs.getPath(oldFile.toString());
							if (Files.exists(newFile)) {
								return FileVisitResult.CONTINUE;
							}
							// TODO find refmap files from fabric json -> mixin configs -> refmaps, rather than using file names
							// TODO do we need to change the "named:intermediary" key in the "data" element? afaik only the "mappings" element is used anyway?
							if (oldFile.toString().endsWith("refmap.json")) {
								// TODO remap more efficiently
								var methodPattern = Pattern.compile("method_[0-9]+");
								var fieldPattern = Pattern.compile("field_[0-9]+");
								var classPattern = Pattern.compile("L[/\\w]+?class_[0-9]+;");

								var maps = getMaps(mappings);

								var data = Files.readString(oldFile);
								while (true) {
									var matcher = methodPattern.matcher(data);
									if (!matcher.find()) break;
									var start = matcher.start();
									var end = matcher.end();
									var value = matcher.group();
									data = data.substring(0, start) + maps.methodMap.get(value) + data.substring(end);
								}
								while (true) {
									var matcher = fieldPattern.matcher(data);
									if (!matcher.find()) break;
									var start = matcher.start();
									var end = matcher.end();
									var value = matcher.group();
									data = data.substring(0, start) + maps.fieldMap.get(value) + data.substring(end);
								}
								while (true) {
									var matcher = classPattern.matcher(data);
									if (!matcher.find()) break;
									var start = matcher.start();
									var end = matcher.end();
									var value = matcher.group();
									data = data.substring(0, start) + maps.classMap.get(value) + data.substring(end);
								}
								Files.write(newFile, data.getBytes());
								return FileVisitResult.CONTINUE;
							}
							Files.createDirectories(newFile.getParent());
							Files.copy(oldFile, newFile);
							return FileVisitResult.CONTINUE;
						}
					});
					Files.write(newFs.getPath("/META-INF/mods.toml"), new TomlWriter().writeToString(generateForgeMetadata(candidate.metadata)).getBytes());
					if (candidate.metadata.mixins.size() > 0) {
						List<String> manifestLines;
						// We already copied all files anyway, so just modify the one in the new jar directly
						var manifestPath = newFs.getPath("/META-INF/MANIFEST.MF");
						if (Files.exists(manifestPath)) {
							manifestLines = Files.readAllLines(manifestPath);
						} else {
							// FIXME we need to add some default manifest data here
							manifestLines = new ArrayList<>();
						}
						if (/*manifestLines.size() == 0 || */!manifestLines.get(manifestLines.size()-1).isEmpty()) {
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
								manifestLines.add(i, "MixinConfigs: " + candidate.metadata.mixins.stream()
										.map(FabricModData.MixinConfig::config).collect(Collectors.joining(",")));
//								manifestLines.add(i, "FMLModType: GAMELIBRARY");
								break;
							}
						}


						Files.writeString(manifestPath, String.join("\n", manifestLines));
					}
					var dummyModClassPackage = "cursedflames/steelwool/" + candidate.metadata.id;
					var dummyModClassPath = newFs.getPath(dummyModClassPackage + "/Mod.class");
					Files.createDirectories(dummyModClassPath.getParent());
					Files.write(dummyModClassPath, generateDummyModClass(dummyModClassPackage + "/Mod", candidate.metadata.id));
				}
				outputJars.add(outputPath);
			} catch (IOException | URISyntaxException e) {
				e.printStackTrace();
				throw new RuntimeException(e);
			}
		}

		return outputJars.stream();
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

	// We use the same initial jar-checking logic as fabric loader, for consistency
	private static boolean isValidJar(Path path) {
		if (!Files.isRegularFile(path)) return false;
		try {
			if (Files.isHidden(path)) return false;
		} catch (IOException e) {
			// TODO warning log message
			return false;
		}

		String fileName = path.getFileName().toString();

		return fileName.endsWith(".jar") && !fileName.startsWith(".");
	}

	/**
	 * Get a mod candidate for a jar path.
	 *
	 * Checks whether a mod jar contains a fabric mod, but no forge mod (to avoid double-loading of universal jars)
	 */
	// TODO some fabric mods may include a dummy mods.toml to warn forge users; we don't want to skip those
	private static @Nullable ModCandidate getModCandidate(Path path) {
		try (ZipFile zf = new ZipFile(path.toFile())) {
			ZipEntry forgeToml = zf.getEntry("META-INF/mods.toml");
			if (forgeToml != null) {
				System.out.println("FOUND FORGE TOML for path " + path);
				return null;
			}
			ZipEntry fabricJson = zf.getEntry("fabric.mod.json");
			if (fabricJson == null) return null;
			System.out.println("FOUND FABRIC MOD JSON for path " + path);

			FabricModData data;

			try (var is = zf.getInputStream(fabricJson)) {
				data = FabricModData.readData(is);
			}

			System.out.println("got fabric data!");
//			System.out.println(data.data.toString());

			if (data.environment != FabricModData.Side.BOTH && (data.environment == FabricModData.Side.CLIENT) != FMLEnvironment.dist.isClient()) {
				// We're on the wrong side for this mod; don't try to load it
				return null;
			}

			return new ModCandidate(path, data);

			// TODO nested jar handling - see https://github.com/FabricMC/fabric-loader/blob/ccacc836e96887c534e26731eba6bd04bc358a11/src/main/java/net/fabricmc/loader/impl/discovery/ModDiscoverer.java#L283-L345
		} catch (IOException e) {
			e.printStackTrace();
		}

		return null; //TODO
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
		constructor.visitMaxs(1, 1);
		cw.visitEnd();
		return cw.toByteArray();
	}

	@Override
	public void scanFile(IModFile modFile, Consumer<Path> pathConsumer) {
		final Function<Path, SecureJar.Status> status = p->modFile.getSecureJar().verifyPath(p);
		try (Stream<Path> files = Files.find(modFile.getSecureJar().getRootPath(), Integer.MAX_VALUE, (p, a) -> p.getNameCount() > 0 && p.getFileName().toString().endsWith(".class"))) {
			modFile.setSecurityStatus(files.peek(pathConsumer).map(status).reduce((s1, s2)-> SecureJar.Status.values()[Math.min(s1.ordinal(), s2.ordinal())]).orElse(SecureJar.Status.INVALID));
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@Override
	public void initArguments(Map<String, ?> arguments) {
		for (var key : arguments.keySet()) {
			System.out.println("key = " + key + ", value = " + arguments.get(key));
		}
	}

	@Override
	public boolean isValid(IModFile modFile) {
		// TODO validate any mods that we give to forge - forge's implementations of IModLocator seem to just `return true;`?
		return true;
	}
}
