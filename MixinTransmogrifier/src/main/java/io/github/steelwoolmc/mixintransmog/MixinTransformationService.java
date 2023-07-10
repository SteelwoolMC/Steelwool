package io.github.steelwoolmc.mixintransmog;

import cpw.mods.modlauncher.LaunchPluginHandler;
import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import io.github.steelwoolmc.mixintransmog.instrumentationhack.InstrumentationHack;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.launch.MixinLaunchPlugin;
import org.spongepowered.asm.launch.MixinLaunchPluginLegacy;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static io.github.steelwoolmc.mixintransmog.Constants.Log;

public class MixinTransformationService implements ITransformationService {
	/**
	 * Replace the original mixin launch plugin
	 */
	private static void replaceMixinLaunchPlugin() {
		try {
			// This is a hack to get our shaded mixin to behave properly, as otherwise it tries to uses the thread classloader and then fails to load things
			// TODO what are the consequences of this? seems like it could potentially have rather bad unintended consequences
			var classLoader = MixinTransformationService.class.getClassLoader();
			Thread.currentThread().setContextClassLoader(classLoader);

			// Use reflection to get the loaded launch plugins
			var launcherLaunchPluginsField = Launcher.class.getDeclaredField("launchPlugins");
			launcherLaunchPluginsField.setAccessible(true);
			var launchPluginHandlerPluginsField = LaunchPluginHandler.class.getDeclaredField("plugins");
			launchPluginHandlerPluginsField.setAccessible(true);
			Map<String, ILaunchPluginService> plugins = (Map) launchPluginHandlerPluginsField.get(launcherLaunchPluginsField.get(Launcher.INSTANCE));

			// Replace original mixin with our mixin
			plugins.put("mixin", new MixinLaunchPlugin());
			Log.debug("Replaced the mixin launch plugin");

			// This shouldn't be necessary, but for some reason our launch plugin service doesn't seem to get loaded properly, so we load it manually
			plugins.computeIfAbsent("mixin-transmogrifier", key -> new ShadedMixinPluginService());
		} catch (NoSuchFieldException | IllegalAccessException e) {
			throw new RuntimeException(e);
		}
	}

	public MixinTransformationService() {
		Log.info("Mixin Transmogrifier is definitely up to no good...");
		InstrumentationHack.purgeDefaultMixin();
		replaceMixinLaunchPlugin();
		Log.info("crimes against java were committed");
	}

	@Override
	public @NotNull String name() {
		return "mixin-transmogrifier";
	}

	@Override
	public void initialize(IEnvironment environment) {
		try {
			Log.debug("initialize called");

			var mixinBootstrapStartMethod = MixinBootstrap.class.getDeclaredMethod("start");
			mixinBootstrapStartMethod.setAccessible(true);

			Optional<ILaunchPluginService> plugin = environment.findLaunchPlugin("mixin");
			if (plugin.isEmpty()) {
				throw new Error("Mixin Launch Plugin Service could not be located");
			}
			ILaunchPluginService launchPlugin = plugin.get();
			if (!(launchPlugin instanceof MixinLaunchPluginLegacy)) {
				throw new Error("Mixin Launch Plugin Service is present but not compatible");
			}

			var mixinPluginInitMethod = MixinLaunchPluginLegacy.class.getDeclaredMethod("init", IEnvironment.class, List.class);
			mixinPluginInitMethod.setAccessible(true);

			// The actual init invocations
			mixinBootstrapStartMethod.invoke(null);
			mixinPluginInitMethod.invoke(launchPlugin, environment, List.of());
		} catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public void onLoad(IEnvironment env, Set<String> otherServices) {
		Log.debug("onLoad called");
		Log.debug(otherServices.stream().collect(Collectors.joining(", ")));
	}

	@Override
	public @NotNull List<ITransformer> transformers() {
		return List.of();
	}
}
