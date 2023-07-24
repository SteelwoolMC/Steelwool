package io.github.steelwoolmc.steelwool.mod.mixin.registryhack;

import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = {"net.minecraftforge.registries.NamespacedWrapper"})
public class MixinNamespacedWrapper<T> {
	/**
	 * @author CursedFlames
	 * @reason Fabric mods do not use the forge API for registration
	 */
	@Inject(method = "lock", cancellable = true, at = @At("HEAD"), remap = false)
	public void onLock(CallbackInfo ci) {
		ci.cancel();
	}

	/**
	 * @author CursedFlames
	 * @reason hack registry freezing out of existence
	 */
	@Inject(method = "freeze", cancellable = true, at = @At("HEAD"))
	private void onFreeze(CallbackInfoReturnable<Registry<T>> cir) {
		cir.setReturnValue((MappedRegistry) (Object) this);
	}
}
