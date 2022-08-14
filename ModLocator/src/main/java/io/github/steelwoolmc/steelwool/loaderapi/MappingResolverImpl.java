package io.github.steelwoolmc.steelwool.loaderapi;

import io.github.steelwoolmc.steelwool.jartransform.mappings.Mappings;
import net.fabricmc.loader.api.MappingResolver;

import java.util.Collection;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;

// TODO can we use forge's mapping stuff to resolve mappings other than intermediary/srg?
public class MappingResolverImpl implements MappingResolver {
	// TODO move constants like this somewhere more sensible - Constants.class?
	private static final String INTERMEDIARY = "intermediary";
	private static final String SRG = "srg";
	// TODO we'll need to figure out how to support named once we implement dev env support
	private final Collection<String> namespaces = Set.of(INTERMEDIARY, SRG);

	private final Mappings.SimpleMappingData mappingData;

	public MappingResolverImpl(Mappings.SimpleMappingData mappingData) {
		this.mappingData = mappingData;
	}

	@Override
	public Collection<String> getNamespaces() {
		return namespaces;
	}

	@Override
	public String getCurrentRuntimeNamespace() {
		// TODO could this cause compat issues?
		return SRG;
	}

	@Override
	public String mapClassName(String namespace, String className) {
		checkArgument(namespaces.contains(namespace), "Unsupported namespace %s; supported values: %s", namespace, namespaces);
		if (namespace.equals(SRG)) return className;
		var replaced = className.replace(".", "/");
		return mappingData.classes().getOrDefault(replaced, replaced).replace("/", ".");
	}

	@Override
	public String unmapClassName(String targetNamespace, String className) {
		// TODO support unmapClassName
		throw new UnsupportedOperationException("MappingResolver.unmapClassName not currently supported by Steelwool");
	}

	@Override
	public String mapFieldName(String namespace, String owner, String name, String descriptor) {
		checkArgument(namespaces.contains(namespace), "Unsupported namespace %s; supported values: %s", namespace, namespaces);
		if (namespace.equals(SRG)) return name;
		return mappingData.fields().getOrDefault(name, name);
	}

	@Override
	public String mapMethodName(String namespace, String owner, String name, String descriptor) {
		checkArgument(namespaces.contains(namespace), "Unsupported namespace %s; supported values: %s", namespace, namespaces);
		if (namespace.equals(SRG)) return name;
		return mappingData.methods().getOrDefault(name, name);
	}
}
