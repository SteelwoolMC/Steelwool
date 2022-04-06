package cursedflames.steelwool.mod;

import cursedflames.steelwool.Constants;
import cursedflames.steelwool.modloading.EntrypointsData;
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

public class Entrypoints {
	public static void runClientEntrypoints() {
		Constants.LOG.info("Running common and client entrypoints!");

		var allEntrypoints = EntrypointsData.getEntrypoints();

		var mainEntrypoints = allEntrypoints.getOrDefault("main", List.of());
		var clientEntrypoints = allEntrypoints.getOrDefault("client", List.of());

		mainEntrypoints.stream().filter(entrypoint -> entrypoint.adapter().equals("default")).forEach(entrypoint -> {
			var init = createInitializer(entrypoint.value(), ModInitializer.class);
			init.onInitialize();
		});

		clientEntrypoints.stream().filter(entrypoint -> entrypoint.adapter().equals("default")).forEach(entrypoint -> {
			var init = createInitializer(entrypoint.value(), ClientModInitializer.class);
			init.onInitializeClient();
		});
	}

	public static void runServerEntrypoints() {
		Constants.LOG.info("Running common and server entrypoints!");

		var allEntrypoints = EntrypointsData.getEntrypoints();

		var mainEntrypoints = allEntrypoints.getOrDefault("main", List.of());
		var serverEntrypoints = allEntrypoints.getOrDefault("server", List.of());

		mainEntrypoints.stream().filter(entrypoint -> entrypoint.adapter().equals("default")).forEach(entrypoint -> {
			var init = createInitializer(entrypoint.value(), ModInitializer.class);
			init.onInitialize();
		});

		serverEntrypoints.stream().filter(entrypoint -> entrypoint.adapter().equals("default")).forEach(entrypoint -> {
			var init = createInitializer(entrypoint.value(), DedicatedServerModInitializer.class);
			init.onInitializeServer();
		});
	}

	// TODO actually test cases other than class
	@SuppressWarnings("unchecked")
	public static <T> T createInitializer(String entrypoint, Class<T> type) {
		var parts = entrypoint.split("::");

		assert parts.length <= 2 : "Invalid entrypoint " + entrypoint;

		try {
			Class<?> c = Class.forName(parts[0]);
			if (parts.length == 1) {
				// Class entrypoint
				assert type.isAssignableFrom(c) : c.getName() + " cannot be cast to " + type.getName();
				return (T) c.getDeclaredConstructor().newInstance();
			} else {
				var possibleMethods = Arrays.stream(c.getDeclaredMethods()).filter(method -> method.getName().equals(parts[1])).collect(Collectors.toList());
				assert possibleMethods.size() < 2 : "Ambiguous entrypoint; found multiple methods for " + entrypoint;
				try {
					// Static field entrypoint
					// Not sure why this throws an exception instead of just being nullable?
					var field = c.getDeclaredField(parts[1]);
					assert possibleMethods.isEmpty() : "Ambiguous entrypoint; found both a method and a field for " + entrypoint;
					assert (field.getModifiers() & Modifier.STATIC) != 0 : "Entrypoint field must be static";
					assert type.isAssignableFrom(field.getType()) : field.getType().getName() + " cannot be cast to " + type.getName();
					return (T) field.get(null);
				} catch (NoSuchFieldException ignored) {}
				assert possibleMethods.size() == 1 : "No method or field found for " + entrypoint;
				assert type.isInterface() : "Cannot use a method entrypoint for a non-interface type";
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
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}
}
