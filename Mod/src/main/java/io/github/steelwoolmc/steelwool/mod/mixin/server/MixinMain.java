package io.github.steelwoolmc.steelwool.mod.mixin.server;

import io.github.steelwoolmc.steelwool.mod.Entrypoints;
import net.minecraft.server.Main;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Main.class)
public class MixinMain {
	// TODO make sure that this still injects inside the `if (!optionset.has(optionspec1))`
	@Inject(method = "main", at = @At(value = "INVOKE", shift = At.Shift.AFTER, target = "Lnet/minecraftforge/server/loading/ServerModLoader;load()V"))
	private static void onMain(String[] args, CallbackInfo ci) {
		Entrypoints.runServerEntrypoints();
	}
}
