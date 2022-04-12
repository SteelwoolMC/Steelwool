package io.github.steelwoolmc.steelwool.mod.mixin.client;

import io.github.steelwoolmc.steelwool.mod.Entrypoints;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MixinMinecraft {
	/**
	 * run is called right after where fabric does its entrypoints, and is also after forge does modloading,
	 * so we handle entrypoints here
	 */
	@Inject(method = "run", at = @At("HEAD"))
	private void onRun(CallbackInfo ci) {
		Entrypoints.runClientEntrypoints();
	}
}
