package io.github.steelwoolmc.steelwool.loaderapi;

import io.github.steelwoolmc.steelwool.jartransform.mappings.Mappings;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.ObjectShare;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.File;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * A reimplementation of FabricLoader's API, used to provide the API to Fabric mods running on Forge
 */
public class FabricLoaderImpl implements FabricLoader {
	public static FabricLoaderImpl INSTANCE;

	private final MappingResolverImpl mappingResolver;

	public FabricLoaderImpl(Mappings.SimpleMappingData mappingData) {
		if (INSTANCE != null) {
			throw new IllegalStateException("Attempted to instantiate FabricLoaderImpl multiple times. This shouldn't happen!");
		}
		mappingResolver = new MappingResolverImpl(mappingData);
		INSTANCE = this;
	}

	// TODO fabric's impl class is non-public
//	private final ObjectShare objectShare = new ObjectShareImpl();

	// TODO method stub
	@Override
	public <T> List<T> getEntrypoints(String key, Class<T> type) {
		return null;
	}

	// TODO method stub
	@Override
	public <T> List<EntrypointContainer<T>> getEntrypointContainers(String key, Class<T> type) {
		return null;
	}

	// TODO method stub
	@Override
	public ObjectShare getObjectShare() {
//		return objectShare;
		return null;
	}

	@Override
	public MappingResolver getMappingResolver() {
		return mappingResolver;
	}

	// TODO method stub
	@Override
	public Optional<ModContainer> getModContainer(String id) {
		return Optional.empty();
	}

	// TODO method stub
	@Override
	public Collection<ModContainer> getAllMods() {
		return Collections.EMPTY_SET;
	}

	@Override
	public boolean isModLoaded(String id) {
		return ModList.get().isLoaded(id);
	}

	@Override
	public boolean isDevelopmentEnvironment() {
		// TODO is this the correct way to handle this?
		return !FMLEnvironment.production;
	}

	@Override
	public EnvType getEnvironmentType() {
		return FMLEnvironment.dist.isDedicatedServer() ? EnvType.SERVER : EnvType.CLIENT;
	}

	// This is nullable AFAIK, and its use is discouraged anyway
	@Override
	public Object getGameInstance() {
		return null;
	}

	@Override
	public Path getGameDir() {
		return FMLPaths.GAMEDIR.get();
	}

	@Override
	public File getGameDirectory() {
		return getGameDir().toFile();
	}

	@Override
	public Path getConfigDir() {
		return FMLPaths.CONFIGDIR.get();
	}

	@Override
	public File getConfigDirectory() {
		return getConfigDir().toFile();
	}

	// TODO method stub
	@Override
	public String[] getLaunchArguments(boolean sanitize) {
		return new String[0];
	}
}
