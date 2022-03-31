package cursedflames.steelwool;

import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.ModDirTransformerDiscoverer;
import net.minecraftforge.fml.loading.StringUtils;
import net.minecraftforge.forgespi.locating.IModFile;
import net.minecraftforge.forgespi.locating.IModLocator;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class SteelwoolModLocator implements IModLocator {
	private final Path modFolder;

	public SteelwoolModLocator() {
		this.modFolder = FMLPaths.MODSDIR.get();
		Constants.LOG.info("SteelWool mod locator instantiated. Hi Forge :)");
	}

	@Override
	public String name() {
		return Constants.MOD_ID;
	}

	@Override
	public List<IModFile> scanMods() {
		Mappings.applyMojangClassNames();

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

		var modCandidates = new ArrayList<>();
		for (var mod : mods) {
			System.out.println("mod = " + mod);
			var cand = getModCandidate(mod);
			if (cand != null) {
				modCandidates.add(cand);
			}
		}

		Constants.LOG.info("Found {} fabric mod candidates", modCandidates.size());

		throw new UnsupportedOperationException();
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
			System.out.println(data.data.toString());

			return new ModCandidate(path, data);

			// FIXME check which side the fabric mod should load on; discard any that are on wrong side

			// TODO nested jar handling - see https://github.com/FabricMC/fabric-loader/blob/ccacc836e96887c534e26731eba6bd04bc358a11/src/main/java/net/fabricmc/loader/impl/discovery/ModDiscoverer.java#L283-L345
		} catch (IOException e) {
			e.printStackTrace();
		}

		return null; //TODO
	}

	static record ModCandidate(Path path, FabricModData metadata) {

	}

	@Override
	public void scanFile(IModFile modFile, Consumer<Path> pathConsumer) {

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
