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
	 * I think this lines up with where Fabric does entrypoints?
	 */
	@Inject(method = "<init>", at = @At(value = "INVOKE", shift = At.Shift.BEFORE, ordinal = 0, target = "Ljava/lang/Thread;currentThread()Ljava/lang/Thread;"))
	private void onRun(CallbackInfo ci) {
		Entrypoints.runClientEntrypoints();
	}
}
