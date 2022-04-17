package io.github.steelwoolmc.steelwool.loaderapi;

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
import java.util.List;
import java.util.Optional;

public class FabricLoaderImpl implements FabricLoader {
	public static final FabricLoaderImpl INSTANCE = new FabricLoaderImpl();

	// TODO fabric's impl class is non-public
//	private final ObjectShare objectShare = new ObjectShareImpl();

	@Override
	public <T> List<T> getEntrypoints(String key, Class<T> type) {
		return null;
	}

	@Override
	public <T> List<EntrypointContainer<T>> getEntrypointContainers(String key, Class<T> type) {
		return null;
	}

	@Override
	public ObjectShare getObjectShare() {
//		return objectShare;
		return null;
	}

	@Override
	public MappingResolver getMappingResolver() {
		return null;
	}

	@Override
	public Optional<ModContainer> getModContainer(String id) {
		return Optional.empty();
	}

	@Override
	public Collection<ModContainer> getAllMods() {
		return null;
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

	// TODO
	@Override
	public String[] getLaunchArguments(boolean sanitize) {
		return new String[0];
	}
}
