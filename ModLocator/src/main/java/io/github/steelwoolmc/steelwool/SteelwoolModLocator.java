package io.github.steelwoolmc.steelwool;

import cpw.mods.jarhandling.JarMetadata;
import cpw.mods.jarhandling.SecureJar;
import io.github.steelwoolmc.steelwool.jartransform.FabricToForgeConverter;
import io.github.steelwoolmc.steelwool.jartransform.mappings.Mappings;
import io.github.steelwoolmc.steelwool.modloading.EntrypointsData;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.discovery.DirectoryModCandidateFinder;
import net.fabricmc.loader.impl.discovery.ModCandidate;
import net.fabricmc.loader.impl.discovery.ModDiscoverer;
import net.fabricmc.loader.impl.discovery.ModResolutionException;
import net.fabricmc.loader.impl.discovery.ModResolver;
import net.fabricmc.loader.impl.launch.knot.Knot;
import net.fabricmc.loader.impl.metadata.DependencyOverrides;
import net.fabricmc.loader.impl.metadata.VersionOverrides;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.ModDirTransformerDiscoverer;
import net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileModLocator;
import net.minecraftforge.fml.loading.moddiscovery.ModFileParser;
import net.minecraftforge.forgespi.locating.IModFile;
import net.minecraftforge.forgespi.locating.IModLocator;
import net.minecraftforge.forgespi.locating.ModFileLoadingException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.github.steelwoolmc.steelwool.Constants.LOG;

/**
 * {@link IModLocator} implementation that finds Fabric mods in the mods directory, transforms them into Forge mods, and provides the transformed jars to Forge
 */
public class SteelwoolModLocator extends AbstractJarFileModLocator {
	private final Path modFolder;
	private final Path configFolder;
	private final Path nestedJarFolder;
	private final EntrypointsData entrypoints = EntrypointsData.createInstance();

	private final Mappings.SimpleMappingData mappings;

	public SteelwoolModLocator() {
		this.modFolder = FMLPaths.MODSDIR.get();
		this.configFolder = FMLPaths.CONFIGDIR.get();
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
		Constants.LOG.info("Initializing Knot");
		// TODO do we want to pass (some?) args?
		var knot = new Knot(FMLEnvironment.dist.isClient() ? EnvType.CLIENT : EnvType.SERVER);
		knot.init(new String[0]);
		Constants.LOG.info("Knot initialized");

		mappings = Mappings.getSimpleMappingData();

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

		List<ModCandidate> modCandidates;
		try {
			modCandidates = fabricLoader_resolveMods();
		} catch (ModResolutionException e) {
			throw new RuntimeException(e);
		}

		// FIXME we shouldn't be doing entrypoints here
		modCandidates.forEach(cand -> {
			cand.getMetadata().getEntrypointKeys().forEach(key -> {
				cand.getMetadata().getEntrypoints(key).forEach(entrypoint -> {
					entrypoints.addEntrypoint(key, entrypoint);
				});
			});
		});

		Constants.LOG.info("Found {} fabric mod candidates", modCandidates.size());

		var outputJars = FabricToForgeConverter.getConvertedJarPaths(modCandidates, mappings);
		// Add our own internal mod here so that it gets loaded
		outputJars.add(0, getInternalMod());
		return outputJars.stream();
	}

	/**
	 * Copied from parent logic, but constructs a {@link ModIdHack.WrappedModFile} instead, in order to replace {@code -} with {@code _} in mod ids
	 * <p>
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

	// Based on FabricLoaderImpl.setup
	private List<ModCandidate> fabricLoader_resolveMods() throws ModResolutionException {
		boolean remapRegularMods = FabricLoaderImpl.INSTANCE.isDevelopmentEnvironment();
		VersionOverrides versionOverrides = new VersionOverrides();
		DependencyOverrides depOverrides = new DependencyOverrides(configFolder);

		// discover mods

		ModDiscoverer discoverer = new ModDiscoverer(versionOverrides, depOverrides);
		// TODO classpath and argument?
//		discoverer.addCandidateFinder(new ClasspathModCandidateFinder());
		discoverer.addCandidateFinder(new DirectoryModCandidateFinder(modFolder, remapRegularMods));
//		discoverer.addCandidateFinder(new ArgumentModCandidateFinder(remapRegularMods));

		Map<String, Set<ModCandidate>> envDisabledMods = new HashMap<>();
		var modCandidates = discoverer.discoverMods(FabricLoaderImpl.INSTANCE, envDisabledMods);

		LOG.info("Found {} mod candidates: {}", modCandidates.size(), modCandidates.stream().map(ModCandidate::getId).collect(Collectors.joining(", ")));

		// dump version and dependency overrides info

		// TODO logging
//		if (!versionOverrides.getAffectedModIds().isEmpty()) {
//			Log.info(LogCategory.GENERAL, "Versions overridden for %s", String.join(", ", versionOverrides.getAffectedModIds()));
//		}
//
//		if (!depOverrides.getAffectedModIds().isEmpty()) {
//			Log.info(LogCategory.GENERAL, "Dependencies overridden for %s", String.join(", ", depOverrides.getAffectedModIds()));
//		}

		// resolve mods

		modCandidates = ModResolver.resolve(modCandidates, FabricLoaderImpl.INSTANCE.getEnvironmentType(), envDisabledMods);

		// temporary hack to deal with mods JIJing parts of fabric-api
		modCandidates = modCandidates.stream().filter(c -> !fabricApiModIds.contains(c.getId())).collect(Collectors.toList());

		FabricLoaderImpl.INSTANCE.dumpModList(modCandidates);

		// TODO fabric-loader shuffles mod order in-dev unless system property DEBUG_DISABLE_MOD_SHUFFLE is set

		// add mods
		for (ModCandidate mod : modCandidates) {
			if (!mod.hasPath() && !mod.isBuiltin()) {
				try {
					mod.setPaths(Collections.singletonList(mod.copyToDir(nestedJarFolder, false)));
				} catch (IOException e) {
					throw new RuntimeException("Error extracting mod "+mod, e);
				}
			}
		}

		return modCandidates;
	}

	private static final List<String> fabricApiModIds = List.of(
			"fabric-api-base",
			"fabric-api-lookup-api-v1",
			"fabric-biome-api-v1",
			"fabric-block-api-v1",
			"fabric-blockrenderlayer-v1",
			"fabric-client-tags-api-v1",
			"fabric-command-api-v2",
			"fabric-content-registries-v0",
			"fabric-convention-tags-v1",
			"fabric-crash-report-info-v1",
			"fabric-data-generation-api-v1",
			"fabric-dimensions-v1",
			"fabric-entity-events-v1",
			"fabric-events-interaction-v0",
			"fabric-game-rule-api-v1",
			"fabric-gametest-api-v1",
			"fabric-item-api-v1",
			"fabric-item-group-api-v1",
			"fabric-key-binding-api-v1",
			"fabric-lifecycle-events-v1",
			"fabric-loot-api-v2",
			"fabric-message-api-v1",
			"fabric-mining-level-api-v1",
			"fabric-models-v0",
			"fabric-networking-api-v1",
			"fabric-object-builder-api-v1",
			"fabric-particles-v1",
			"fabric-recipe-api-v1",
			"fabric-registry-sync-v0",
			"fabric-renderer-api-v1",
			"fabric-renderer-indigo",
			"fabric-rendering-data-attachment-v1",
			"fabric-rendering-fluids-v1",
			"fabric-rendering-v1",
			"fabric-resource-conditions-api-v1",
			"fabric-resource-loader-v0",
			"fabric-screen-api-v1",
			"fabric-screen-handler-api-v1",
			"fabric-sound-api-v1",
			"fabric-transfer-api-v1",
			"fabric-transitive-access-wideners-v1"
	);
}
