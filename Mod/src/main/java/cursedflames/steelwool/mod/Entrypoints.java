package cursedflames.steelwool.mod;

import java.lang.reflect.InvocationTargetException;

public class Entrypoints {
	@SuppressWarnings("unchecked")
	public static <T> T createInitializer(String entrypoint, Class<T> type) {
		var parts = entrypoint.split("::");

		if (parts.length > 2) {
			throw new RuntimeException("Invalid entrypoint " + entrypoint);
		}

		try {
			Class<?> c = Class.forName(parts[0]);
			if (parts.length == 1) {
				return (T) c.getDeclaredConstructor().newInstance();
			} else {
				// TODO
				throw new UnsupportedOperationException();
			}
		} catch (ClassNotFoundException | NoSuchMethodException | InvocationTargetException | InstantiationException | IllegalAccessException e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		}

		// TODO
//		return null;
	}
}
