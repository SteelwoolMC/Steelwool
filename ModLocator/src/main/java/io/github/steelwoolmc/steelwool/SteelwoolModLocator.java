package io.github.steelwoolmc.steelwool;

import cpw.mods.jarhandling.JarMetadata;
import cpw.mods.jarhandling.SecureJar;
import io.github.steelwoolmc.steelwool.jartransform.FabricToForgeConverter;
import io.github.steelwoolmc.steelwool.jartransform.mappings.Mappings;
import io.github.steelwoolmc.steelwool.loaderapi.FabricLoaderImpl;
import io.github.steelwoolmc.steelwool.modloading.EntrypointsData;
import io.github.steelwoolmc.steelwool.modloading.FabricModData;
import io.github.steelwoolmc.steelwool.modloading.ModCandidate;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.ModDirTransformerDiscoverer;
import net.minecraftforge.fml.loading.StringUtils;
import net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileModLocator;
import net.minecraftforge.fml.loading.moddiscovery.ModFileParser;
import net.minecraftforge.forgespi.locating.IModFile;
import net.minecraftforge.forgespi.locating.IModLocator;
import net.minecraftforge.forgespi.locating.ModFileLoadingException;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * {@link IModLocator} implementation that finds Fabric mods in the mods directory, transforms them into Forge mods, and provides the transformed jars to Forge
 */
public class SteelwoolModLocator extends AbstractJarFileModLocator {
	private final Path modFolder;
	private final Path nestedJarFolder;
	private final EntrypointsData entrypoints = EntrypointsData.createInstance();

	private final Mappings.SimpleMappingData mappings;

	public SteelwoolModLocator() {
		this.modFolder = FMLPaths.MODSDIR.get();
		this.nestedJarFolder = FMLPaths.getOrCreateGameRelativePath(Constants.MOD_CACHE_ROOT.resolve("jij"));
		// Delete all existing JiJ files
		// FIXME only do this for dev versions; we want caching for release - figure out how to do caching properly though
		// FIXME don't do this *here*
		try {
			Files.walk(nestedJarFolder).forEach(path -> {
				// Don't delete the root folder, just the contents
				if (path.equals(nestedJarFolder)) return;
				try {
					Files.deleteIfExists(path);
				} catch (IOException ignored) {}
			});
		} catch (IOException e) {
			e.printStackTrace();
		}

		Constants.LOG.info("Steelwool mod locator instantiated. Hi Forge :)");

		mappings = Mappings.getSimpleMappingData();
		new FabricLoaderImpl(mappings);

		ModIdHack.makeForgeAcceptDashesInModids();
	}

	@Override
	public String name() {
		return Constants.MOD_ID;
	}

	@Override
	public Stream<Path> scanCandidates() {
		Constants.LOG.info("Steelwool scanning for mods...");
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

		// FIXME dependency resolution; the same dependency could be nested in multiple different jars
		// TODO make forge treat the nested jars as child mods? at least in the case of Gambeson
		var modCandidates = mods.stream().map(path -> getModCandidates(path, false)).flatMap(List::stream).collect(Collectors.toList());
		// FIXME we shouldn't be doing entrypoints here
		modCandidates.forEach(cand -> {
			cand.metadata().entrypoints.forEach((prototype, entrypoints) -> {
				entrypoints.forEach(entrypoint -> {
					this.entrypoints.addEntrypoint(prototype, entrypoint);
				});
			});
		});

		Constants.LOG.info("Found {} fabric mod candidates", modCandidates.size());

		var outputJars = FabricToForgeConverter.getConvertedJarPaths(modCandidates, mappings);
		// Add our own internal mod here so that it gets loaded
		outputJars.add(0, getInternalMod());
		return outputJars.stream();
	}

	// We use the same initial jar-checking logic as fabric loader, for consistency

	/**
	 * Check whether an arbitrary file path is a valid jar file path
	 * @param path the file path to check
	 * @return whether the file path is a valid jar file path
	 */
	private static boolean isValidJar(Path path) {
		if (!Files.isRegularFile(path)) return false;
		try {
			if (Files.isHidden(path)) return false;
		} catch (IOException e) {
			// TODO warning log message
			return false;
		}

		var fileName = path.getFileName().toString();

		return fileName.endsWith(".jar") && !fileName.startsWith(".");
	}

	/**
	 * Get a mod candidate for a jar path.
	 *
	 * <p>Checks whether a mod jar contains a fabric mod, but no forge mod (to avoid double-loading of universal jars)</p>
	 */
	// TODO some fabric mods may include a dummy mods.toml to warn forge users; we don't want to skip those
	// FIXME this needs to be reworked to handle nested jars properly
	private List<ModCandidate> getModCandidates(Path path, boolean isNested) {
		try (ZipFile zf = new ZipFile(path.toFile())) {
			var forgeToml = zf.getEntry("META-INF/mods.toml");
			if (forgeToml != null) {
				System.out.println("FOUND FORGE TOML for path " + path);
				return List.of();
			}
			var fabricJson = zf.getEntry("fabric.mod.json");
			if (fabricJson == null) return List.of();
			System.out.println("FOUND FABRIC MOD JSON for path " + path);

			FabricModData data;

			try (var is = new BufferedInputStream(zf.getInputStream(fabricJson))) {
				data = FabricModData.readData(is, isNested);
			}

			System.out.println("got fabric data!");
//			System.out.println(data.data.toString());

			if (data.environment != FabricModData.Side.BOTH && (data.environment == FabricModData.Side.CLIENT) != FMLEnvironment.dist.isClient()) {
				// We're on the wrong side for this mod; don't try to load it
				// TODO should nested jars be loaded in this case? probably not?
				return List.of();
			}

			// TODO can we use streams instead of lists? caused issues when I tried due to zip files closing before lambda evaluation
			var list = new ArrayList<ModCandidate>();
			list.add(new ModCandidate(path, data));

			list.addAll(data.nestedJars.stream().map(nestedJarPath -> {
				try (var is = new BufferedInputStream(zf.getInputStream(zf.getEntry(nestedJarPath)))) {
					var outputPath = nestedJarFolder.resolve(Path.of(nestedJarPath).getFileName());
					// FIXME this could cause issues with duplicate nested mods overwriting each other
					//       - how does fabric-loader avoid extracting the jars?
					if (!Files.exists(outputPath))
						Files.copy(is, outputPath);
					else {
						Constants.LOG.warn("duplicate extracted nested jar {}", outputPath);
					}
					return getModCandidates(outputPath, true);
				} catch (IOException e) {
					// TODO better error handling
					throw new RuntimeException("Error while handling nested jar", e);
				}
			}).flatMap(List::stream).collect(Collectors.toList()));
			return list;
		} catch (IOException e) {
			e.printStackTrace();
		}

		return List.of(); //TODO
	}

	/**
	 * Copied from parent logic, but constructs a {@link ModIdHack.WrappedModFile} instead, in order to replace {@code -} with {@code _} in mod ids
	 *
	 * Also removes the handling for non {@code mods.toml} jars, as all steelwool-generated jars will have a {@code mods.toml}.
	 */
	@Override
	protected IModLocator.ModFileOrException createMod(Path... path) {
		var mjm = ModIdHack.createModJarMetadata();
		// TODO using our own metadata supplier might allow us to keep fabric.mod.json instead of translating beforehand?
		var sj = SecureJar.from(
				Manifest::new,
				jar-> jar.moduleDataProvider().findFile(MODS_TOML).isPresent() ? mjm : JarMetadata.from(jar, path),
				(root, p) -> true,
				path
		);

		IModFile mod;
		if (sj.moduleDataProvider().findFile(MODS_TOML).isPresent()) {
			mod = new ModIdHack.WrappedModFile(sj, this, ModFileParser::modsTomlParser);
		} else {
			// We always generate jars with mods.toml currently, so the manifest FMLModType check isn't necessary
			// FIXME warning/error? probably only in dev builds.
			// TODO a way of determining whether we're in a dev build... the Constants class?
			return new ModFileOrException(null, new ModFileLoadingException("Unknown"));
		}

		mjm.setModFile(mod);
		return new ModFileOrException(mod, null);
	}

	@Override
	public void initArguments(Map<String, ?> arguments) {
		for (var key : arguments.keySet()) {
			System.out.println("key = " + key + ", value = " + arguments.get(key));
		}
	}

	/**
	 * Extract the internal Steelwool mod jar nested within the main jar
	 * @return the path of the extracted internal mod jar
	 */
	private static Path getInternalMod() {
		// TODO define steelwoolFolder statically somewhere? (Constants?)
		var steelwoolFolder = FMLPaths.getOrCreateGameRelativePath(Constants.MOD_CACHE_ROOT);
		var innerJarPath = steelwoolFolder.resolve(Constants.INNER_JAR_NAME);
		// TODO caching
		try (var stream = SteelwoolModLocator.class.getResourceAsStream("../../../../" + Constants.INNER_JAR_NAME)) {
			Files.copy(stream, innerJarPath, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			e.printStackTrace();
			throw new RuntimeException("Failed to extract internal mod jar", e);
		}
		return innerJarPath;
	}
}
