package io.github.steelwoolmc.mixintransmog;

import cpw.mods.modlauncher.LaunchPluginHandler;
import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.TransformationServiceDecorator;
import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import io.github.steelwoolmc.mixintransmog.instrumentationhack.InstrumentationHack;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static io.github.steelwoolmc.mixintransmog.Constants.Log;

public class MixinTransformationService implements ITransformationService {
	/**
	 * Remove the original mixin launch plugin
	 */
	private static void removeMixinLaunchPlugin() {
		try {
			var launcherLaunchPluginsField = Launcher.class.getDeclaredField("launchPlugins");
			launcherLaunchPluginsField.setAccessible(true);
			var launchPluginHandlerPluginsField = LaunchPluginHandler.class.getDeclaredField("plugins");
			launchPluginHandlerPluginsField.setAccessible(true);
			Map<String, ILaunchPluginService> plugins = (Map) launchPluginHandlerPluginsField.get(launcherLaunchPluginsField.get(Launcher.INSTANCE));
			plugins.remove("mixin");
			Log.debug("Removed the mixin launch plugin");
		} catch (NoSuchFieldException | IllegalAccessException e) {
			throw new RuntimeException(e);
		}
	}
	public MixinTransformationService() {
		Log.info("Mixin Transmogrifier is definitely up to no good...");
		InstrumentationHack.purgeDefaultMixin();
		removeMixinLaunchPlugin();
		Log.info("crimes against java were committed");
	}

	@Override
	public @NotNull String name() {
		return "mixin-transmogrifier";
	}

	@Override
	public void initialize(IEnvironment environment) {
		Log.debug("initialize called");
		// TODO this entire thing is temporary for debugging
		try {
			var transformationServicesHandlerField = Launcher.class.getDeclaredField("transformationServicesHandler");
			transformationServicesHandlerField.setAccessible(true);
			var transformationServicesHandler = transformationServicesHandlerField.get(Launcher.INSTANCE);
			var transformationServicesHandlerClass = transformationServicesHandler.getClass();
			var serviceLookupField = transformationServicesHandlerClass.getDeclaredField("serviceLookup");
			serviceLookupField.setAccessible(true);
			Map<String, TransformationServiceDecorator> serviceLookup = (Map) serviceLookupField.get(transformationServicesHandler);
			serviceLookup.forEach((key, value) -> {
				Log.debug(key);
				try {
					var serviceField = value.getClass().getDeclaredField("service");
					serviceField.setAccessible(true);
					var val = serviceField.get(value);
					Log.debug(val.getClass().getName());
				} catch (NoSuchFieldException | IllegalAccessException e) {
					throw new RuntimeException(e);
				}

			});
		} catch (NoSuchFieldException | IllegalAccessException e) {
			Log.error(e);
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
