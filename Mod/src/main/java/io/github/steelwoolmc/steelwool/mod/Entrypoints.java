package io.github.steelwoolmc.steelwool.mod;

import io.github.steelwoolmc.steelwool.Constants;
import io.github.steelwoolmc.steelwool.modloading.EntrypointsData;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.ModInitializer;

import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;

public class Entrypoints {
	public static void runClientEntrypoints() {
		Constants.LOG.info("Running common and client entrypoints!");

		var allEntrypoints = EntrypointsData.getEntrypoints();

		var mainEntrypoints = allEntrypoints.getOrDefault("main", List.of());
		var clientEntrypoints = allEntrypoints.getOrDefault("client", List.of());

		mainEntrypoints.stream().filter(entrypoint -> entrypoint.getAdapter().equals("default")).forEach(entrypoint -> {
			var init = createInitializer(entrypoint.getValue(), ModInitializer.class);
			init.onInitialize();
		});

		clientEntrypoints.stream().filter(entrypoint -> entrypoint.getAdapter().equals("default")).forEach(entrypoint -> {
			var init = createInitializer(entrypoint.getValue(), ClientModInitializer.class);
			init.onInitializeClient();
		});
	}

	public static void runServerEntrypoints() {
		Constants.LOG.info("Running common and server entrypoints!");

		var allEntrypoints = EntrypointsData.getEntrypoints();

		var mainEntrypoints = allEntrypoints.getOrDefault("main", List.of());
		var serverEntrypoints = allEntrypoints.getOrDefault("server", List.of());

		mainEntrypoints.stream().filter(entrypoint -> entrypoint.getAdapter().equals("default")).forEach(entrypoint -> {
			var init = createInitializer(entrypoint.getValue(), ModInitializer.class);
			init.onInitialize();
		});

		serverEntrypoints.stream().filter(entrypoint -> entrypoint.getAdapter().equals("default")).forEach(entrypoint -> {
			var init = createInitializer(entrypoint.getValue(), DedicatedServerModInitializer.class);
			init.onInitializeServer();
		});
	}

	// TODO actually test cases other than class
	@SuppressWarnings("unchecked")
	public static <T> T createInitializer(String entrypoint, Class<T> type) {
		var parts = entrypoint.split("::");

		checkArgument(parts.length <= 2, "Invalid entrypoint " + entrypoint);

		try {
			Class<?> c = Class.forName(parts[0]);
			if (parts.length == 1) {
				// Class entrypoint
				checkArgument(type.isAssignableFrom(c), "%s cannot be cast to %s", c.getName(), type.getName());
				return (T) c.getDeclaredConstructor().newInstance();
			} else {
				var possibleMethods = Arrays.stream(c.getDeclaredMethods()).filter(method -> method.getName().equals(parts[1])).collect(Collectors.toList());
				checkArgument(possibleMethods.size() < 2, "Ambiguous entrypoint; found multiple methods for %s", entrypoint);
				try {
					// Static field entrypoint
					// Not sure why this throws an exception instead of just being nullable?
					var field = c.getDeclaredField(parts[1]);
					checkArgument(possibleMethods.isEmpty(), "Ambiguous entrypoint; found both a method and a field for %s", entrypoint);
					checkArgument((field.getModifiers() & Modifier.STATIC) != 0, "Entrypoint field must be static");
					checkArgument(type.isAssignableFrom(field.getType()), field.getType().getName() + " cannot be cast to %s", type.getName());
					return (T) field.get(null);
				} catch (NoSuchFieldException ignored) {}
				checkArgument(possibleMethods.size() == 1, "No method or field found for %s", entrypoint);
				checkArgument(type.isInterface(), "Cannot use a method entrypoint for a non-interface type");
				// Method entrypoint
				var method = possibleMethods.get(0);

				var methodHandle = MethodHandles.lookup().unreflect(method);
				if ((method.getModifiers() & Modifier.STATIC) == 0) {
					var inst = c.getDeclaredConstructor().newInstance();
					methodHandle.bindTo(inst);
				}
				return MethodHandleProxies.asInterfaceInstance(type, methodHandle);
			}
		} catch (ClassNotFoundException | NoSuchMethodException | InvocationTargetException | InstantiationException | IllegalAccessException e) {
			throw new RuntimeException(e);
		}
	}
}
