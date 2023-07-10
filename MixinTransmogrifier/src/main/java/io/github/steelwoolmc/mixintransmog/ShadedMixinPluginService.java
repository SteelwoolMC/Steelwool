package io.github.steelwoolmc.mixintransmog;

import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;

import java.util.EnumSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.github.steelwoolmc.mixintransmog.Constants.Log;

/**
 * A launch plugin that transforms mod mixin classes to refer to shaded mixin rather than the original
 * (i.e. `org.spongepowered` -> `shadow.spongepowered`)
 */
public class ShadedMixinPluginService implements ILaunchPluginService {
	@Override
	public String name() {
		return "mixin-transmogrifier";
	}

	@Override
	public EnumSet<Phase> handlesClass(Type classType, boolean isEmpty) {
		return EnumSet.of(Phase.BEFORE);
	}

	@Override
	public boolean processClass(final Phase phase, ClassNode classNode, final Type classType, String reason) {
		if (phase != Phase.BEFORE) return false;
		var annotations = Stream.concat(
				classNode.visibleAnnotations != null ? classNode.visibleAnnotations.stream() : Stream.empty(),
				classNode.invisibleAnnotations != null ? classNode.invisibleAnnotations.stream() : Stream.empty()
		);
		if (annotations.noneMatch(annotation -> annotation.desc.equals("Lshadowignore/org/spongepowered/asm/mixin/Mixin;"))) {
			if (classNode.name.contains("Mixin")) {
				System.out.println(classNode.name);
				System.out.println(classNode.visibleAnnotations.stream().map(annotationNode -> annotationNode.desc).collect(Collectors.joining("\n")));
			}
			return false;
		}
		Log.debug("Processing mixin class: " + classNode.name);
		var remapper = new ClassRemapper(classNode, new Remapper() {
			@Override
			public String map(String internalName) {
				if (internalName.startsWith("shadowignore/org/spongepowered")) {
					return "org/spongepowered" + internalName.substring("shadowignore/org/spongepowered".length());
				}
				return super.map(internalName);
			}
		});
		classNode.accept(remapper);
		return true;
	}

	@Override
	public int processClassWithFlags(final Phase phase, ClassNode classNode, final Type classType, String reason) {
		// We only touch type names; no need to recompute anything - use SIMPLE_REWRITE instead of one of the recompute ones
		return processClass(phase, classNode, classType, reason) ? ComputeFlags.SIMPLE_REWRITE : ComputeFlags.NO_REWRITE;
	}
}
