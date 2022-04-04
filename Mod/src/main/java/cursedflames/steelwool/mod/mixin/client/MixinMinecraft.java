package cursedflames.steelwool.mod.mixin.client;

import cursedflames.steelwool.Constants;
import cursedflames.steelwool.mod.Entrypoints;
import cursedflames.steelwool.modloading.EntrypointsData;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(Minecraft.class)
public class MixinMinecraft {
	/**
	 * run is called right after where fabric does its entrypoints, and is also after forge does modloading,
	 * so we handle entrypoints here
	 */
	@Inject(method = "run", at = @At("HEAD"))
	private void onRun(CallbackInfo ci) {
		Constants.LOG.info("Running common and client entrypoints!");

		// FIXME pay attention to adapters
		var allEntrypoints = EntrypointsData.getEntrypoints();

		var mainEntrypoints = allEntrypoints.getOrDefault("main", List.of());
		var clientEntrypoints = allEntrypoints.getOrDefault("client", List.of());

		mainEntrypoints.forEach(entrypoint -> {
			var init = Entrypoints.createInitializer(entrypoint.value(), ModInitializer.class);
			init.onInitialize();
		});

		clientEntrypoints.forEach(entrypoint -> {
			var init = Entrypoints.createInitializer(entrypoint.value(), ClientModInitializer.class);
			init.onInitializeClient();
		});
	}
}
