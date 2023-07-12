package io.github.steelwoolmc.steelwool.mod.mixin.registryhack;

import net.minecraftforge.registries.ForgeRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ForgeRegistry.class, remap = false)
public class MixinForgeRegistry {
	/**
	 * @author CursedFlames
	 * @reason Fabric mods do not use the forge API for registration
	 */
	@Inject(method = "freeze", cancellable = true, at = @At("HEAD"))
	private void onFreeze(CallbackInfo ci) {
		ci.cancel();
	}
}
