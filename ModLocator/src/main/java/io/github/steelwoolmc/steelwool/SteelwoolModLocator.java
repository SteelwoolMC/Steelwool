package io.github.steelwoolmc.steelwool;

import cpw.mods.jarhandling.JarMetadata;
import cpw.mods.jarhandling.SecureJar;
import io.github.steelwoolmc.steelwool.jartransform.FabricToForgeConverter;
import io.github.steelwoolmc.steelwool.modloading.EntrypointsData;
import io.github.steelwoolmc.steelwool.modloading.FabricModData;
import io.github.steelwoolmc.steelwool.modloading.ModCandidate;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.ModDirTransformerDiscoverer;
import net.minecraftforge.fml.loading.StringUtils;
import net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileLocator;
import net.minecraftforge.fml.loading.moddiscovery.ModFileParser;
import net.minecraftforge.forgespi.locating.IModFile;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

public class SteelwoolModLocator extends AbstractJarFileLocator {
	private final Path modFolder;
	private final EntrypointsData entrypoints = EntrypointsData.createInstance();

	public SteelwoolModLocator() {
		this.modFolder = FMLPaths.MODSDIR.get();
		Constants.LOG.info("SteelWool mod locator instantiated. Hi Forge :)");
		ModIdHack.makeForgeAcceptDashesInModids();
	}

	@Override
	public String name() {
		return Constants.MOD_ID;
	}

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
			var cand = getModCandidate(mod);
			if (cand != null) {
				modCandidates.add(cand);
				cand.metadata().entrypoints.forEach((prototype, entrypoints) -> {
					entrypoints.forEach(entrypoint -> {
						this.entrypoints.addEntrypoint(prototype, entrypoint);
					});
				});
			}
		}

		Constants.LOG.info("Found {} fabric mod candidates", modCandidates.size());

		var outputJars = FabricToForgeConverter.getConvertedJarPaths(modCandidates);
		// Add our own internal mod here so that it gets loaded
		outputJars.add(0, getInternalMod());
		return outputJars.stream();
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

		var fileName = path.getFileName().toString();

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
			var forgeToml = zf.getEntry("META-INF/mods.toml");
			if (forgeToml != null) {
				System.out.println("FOUND FORGE TOML for path " + path);
				return null;
			}
			var fabricJson = zf.getEntry("fabric.mod.json");
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

	/**
	 * Copied from parent logic, but constructs a {@link ModIdHack.WrappedModFile} instead, in order to replace {@code -} with {@code _} in mod ids
	 *
	 * Also removes the handling for non {@code mods.toml} jars, as all steelwool-generated jars will have a {@code mods.toml}.
	 */
	@Override
	protected Optional<IModFile> createMod(Path... path) {
		var mjm = ModIdHack.createModJarMetadata();
		var sj = SecureJar.from(
				Manifest::new,
				jar -> jar.findFile(MODS_TOML).isPresent() ? mjm : JarMetadata.from(jar, path),
				(root, p) -> true,
				path
		);

		IModFile mod;
		if (sj.findFile(MODS_TOML).isPresent()) {
			mod = new ModIdHack.WrappedModFile(sj, this, ModFileParser::modsTomlParser);
		} else {
			// We always generate jars with mods.toml currently, so the manifest FMLModType check isn't necessary
			// FIXME warning/error? probably only in dev builds.
			// TODO a way of determining whether we're in a dev build... the Constants class?
			return Optional.empty();
		}

		mjm.setModFile(mod);
		return Optional.of(mod);
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

	private static Path getInternalMod() {
		// TODO define steelwoolFolder statically somewhere? (Constants?)
		var steelwoolFolder = FMLPaths.getOrCreateGameRelativePath(Constants.MOD_CACHE_ROOT, Constants.MOD_CACHE_ROOT.toString());
		var innerJarPath = steelwoolFolder.resolve(Constants.INNER_JAR_NAME);
		// TODO caching
		try (var stream = SteelwoolModLocator.class.getResourceAsStream("../../" + Constants.INNER_JAR_NAME)) {
			Files.copy(stream, innerJarPath, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			e.printStackTrace();
			throw new RuntimeException("Failed to extract internal mod jar", e);
		}
		return innerJarPath;
	}
}
