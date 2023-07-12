package io.github.steelwoolmc.steelwool.mod.mixin.registryhack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// TODO we access-transform to make NamespacedWrapper public but it's still non-public during build?
@Mixin(targets = {"net.minecraftforge.registries.NamespacedWrapper"}, remap = false)
public class MixinNamespacedWrapper {
	/**
	 * @author CursedFlames
	 * @reason Fabric mods do not use the forge API for registration
	 */
	@Inject(method = "lock", cancellable = true, at = @At("HEAD"))
	public void onLock(CallbackInfo ci) {
		ci.cancel();
	}
}
