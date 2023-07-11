package io.github.steelwoolmc.steelwool;

import cpw.mods.jarhandling.SecureJar;
import net.minecraftforge.fml.loading.moddiscovery.ModFile;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;
import net.minecraftforge.fml.loading.moddiscovery.ModInfo;
import net.minecraftforge.fml.loading.moddiscovery.ModJarMetadata;
import net.minecraftforge.forgespi.language.IConfigurable;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.forgespi.locating.IModFile;
import net.minecraftforge.forgespi.locating.IModLocator;
import net.minecraftforge.forgespi.locating.ModFileFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Class containing several methods and classes to help allow mod ids containing {@code -}, which are valid on Fabric but not Forge
 */
public class ModIdHack {
	// used for reflection
	private static final Field modFileInfoField;
	private static final Constructor<ModJarMetadata> modJarMetadataConstructor;

	/**
	 * The new pattern that will be used for mod id validation by Forge.
	 * <p>
	 * This pattern is identical to the original one, except with {@code -} accepted alongside {@code a-z0-9_}
	 */
	private static final Pattern NEW_MODID_PATTERN = Pattern.compile("^[a-z][a-z0-9_-]{1,63}$");

	static {
		try {
			modFileInfoField = ModFile.class.getDeclaredField("modFileInfo");
			modFileInfoField.setAccessible(true);
		} catch (NoSuchFieldException e) {
			throw new AssertionError("Failed to get field modFileInfo on ModFile.class", e);
		}
		try {
			modJarMetadataConstructor = ModJarMetadata.class.getDeclaredConstructor();
			modJarMetadataConstructor.setAccessible(true);
		} catch (NoSuchMethodException e) {
			throw new AssertionError("Failed to get constructor on ModJarMetadata.class", e);
		}
	}

	/** Use Unsafe to replace the mod id pattern in {@link ModInfo}. */
	static void makeForgeAcceptDashesInModids() {
		try {
			// use Class.forName to force the static initializer to run first,
			// so that it doesn't run after us and overwrite the value we set
			// - Unsafe.ensureClassInitialized() is deprecated
			try { Class.forName("net.minecraftforge.fml.loading.moddiscovery.ModInfo"); } catch (ClassNotFoundException ignored) {}

			var unsafe = Utils.getUnsafe();
			var field = ModInfo.class.getDeclaredField("VALID_MODID");
			field.setAccessible(true);

			var staticFieldBase = unsafe.staticFieldBase(field);
			var staticFieldOffset = unsafe.staticFieldOffset(field);
			unsafe.putObject(staticFieldBase, staticFieldOffset, NEW_MODID_PATTERN);

			Constants.LOG.info("Forge should now accept dashes in mod ids.");
		} catch (NoSuchFieldException e) {
			throw new AssertionError("Failed to force Forge to accept mod ids with dashes", e);
		}
	}

	/** Helper method to create instances of {@link ModJarMetadata}, as its constructor is non-public. */
	static ModJarMetadata createModJarMetadata() {
		try {
			return modJarMetadataConstructor.newInstance();
		} catch (InvocationTargetException | InstantiationException | IllegalAccessException e) {
			throw new RuntimeException("Failed to instantiate ModJarMetadata", e);
		}
	}

	/**
	 * Wrapper around {@link ModFile} to replace its {@link ModFileInfo} with {@link WrappedModFileInfo}
	 */
	static class WrappedModFile extends ModFile {
		public WrappedModFile(SecureJar jar, IModLocator locator, ModFileFactory.ModFileInfoParser parser) {
			super(jar, locator, parser);
		}

		@Override
		public boolean identifyMods() {
			var result = super.identifyMods();
			// Wrap the existing IModFileInfo (that is set in `super.identifyMods()`)
			// so that moduleName doesn't return invalid module names containing `-`
			try {
				IModFileInfo modFileInfo = (IModFileInfo) modFileInfoField.get(this);

				IModFileInfo newFileInfo;
				if (modFileInfo instanceof ModFileInfo) {
					newFileInfo = new WrappedModFileInfo(((ModFileInfo) modFileInfo).getFile(), modFileInfo.getConfig(), (a)->{}, modFileInfo.requiredLanguageLoaders());
				} else {
					// TODO does this ever actually happen?
					//      - if it does it could cause unexpected behaviour like ATs not applying
					newFileInfo = new WrappedIModFileInfo(modFileInfo);
				}

				modFileInfoField.set(this, newFileInfo);
			} catch (IllegalAccessException e) {
				throw new RuntimeException("Failed to wrap ModFile's ModFileInfo", e);
			}
			return result;
		}
	}

	/**
	 * Wrapper around {@link ModFileInfo} to prevent invalid module names by replacing any {@code -} with {@code _}
	 */
	private static class WrappedModFileInfo extends ModFileInfo {
		private WrappedModFileInfo(final ModFile file, final IConfigurable config, Consumer<IModFileInfo> configFileConsumer, final List<LanguageSpec> languageSpecs) {
			super(file, config, configFileConsumer, languageSpecs);
		}

		@Override public String moduleName() { return super.moduleName().replace("-", "_"); }
	}

	/**
	 * Wrapper around {@link IModFileInfo} to prevent invalid module names by replacing any {@code -} with {@code _}
	 */
	private record WrappedIModFileInfo(IModFileInfo inner) implements IModFileInfo {
		@Override public List<IModInfo> getMods() { return inner.getMods(); }

		@Override public List<IModFileInfo.LanguageSpec> requiredLanguageLoaders() { return inner.requiredLanguageLoaders(); }

		@Override public boolean showAsResourcePack() { return inner.showAsResourcePack(); }

		@Override public Map<String, Object> getFileProperties() { return inner.getFileProperties(); }

		@Override public String getLicense() { return inner.getLicense(); }

		@Override public String moduleName() { return inner.moduleName().replace("-", "_"); }

		@Override public String versionString() { return inner.versionString(); }

		@Override public List<String> usesServices() { return inner.usesServices(); }

		@Override public IModFile getFile() { return inner.getFile(); }

		@Override public IConfigurable getConfig() { return inner.getConfig(); }
	}
}
