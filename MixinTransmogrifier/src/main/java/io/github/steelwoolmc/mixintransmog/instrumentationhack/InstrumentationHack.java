package io.github.steelwoolmc.mixintransmog.instrumentationhack;

import io.github.steelwoolmc.mixintransmog.Constants;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.security.ProtectionDomain;
import java.util.function.Consumer;

import static io.github.steelwoolmc.mixintransmog.Constants.Log;

/**
 * Awful hacks using java Instrumentation to do things that we <i>really</i> weren't meant to do.
 */
public class InstrumentationHack {
	// TODO we probably want to un-attach instrumentation after we're done with it
	private static Instrumentation instrumentation = ByteBuddyAgent.install();

	static class ClassTransformer implements ClassFileTransformer {
		private final Class<?> targetClass;
		private final Consumer<MethodNode> methodHandler;
		public ClassTransformer(Class<?> targetClass, Consumer<MethodNode> methodHandler) {
			this.targetClass = targetClass;
			this.methodHandler = methodHandler;
		}

		public byte[] transform(
				ClassLoader loader,
				String className,
				Class<?> classBeingRedefined,
				ProtectionDomain protectionDomain,
				byte[] classfileBuffer
		) {
			if (!(classBeingRedefined == this.targetClass)) return classfileBuffer;
			var reader = new ClassReader(classfileBuffer);
			var node = new ClassNode();
			reader.accept(node, 0);
			for (var methodNode : node.methods) {
				this.methodHandler.accept(methodNode);
			}
			var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
			node.accept(writer);
			return writer.toByteArray();
		}
	}

	/**
	 * Transform the bytecode of a class that has already been loaded, using Instrumentation.
	 * @param clazz the class to transform
	 * @param transformer the transformer to apply to the class
	 */
	private static void retransformLoadedClass(Class<?> clazz, ClassTransformer transformer) {
		try {
			Log.debug("Retransforming loaded class {}", clazz.getName());
			instrumentation.addTransformer(transformer, true);
			instrumentation.retransformClasses(clazz);
			instrumentation.removeTransformer(transformer);
		} catch (UnmodifiableClassException | ClassFormatError e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Use awful hacky Instrumentation stuff to disable the default Mixin transformation service,
	 * so it doesn't get in the way of our own Mixin
	 */
	public static void purgeDefaultMixin() {
		try {
			// Overwriting just `MixinTransformationServiceAbstract.initialize` seems to be sufficient to disable it.
			// Note: `shadowignore.org.spongepowered` is used because shadow relocates fully qualified names in string literals
			var clazz = Class.forName("shadowignore.org.spongepowered.asm.launch.MixinTransformationServiceAbstract");
			Consumer<MethodNode> transform = methodNode -> {
				if (methodNode.name.equals("initialize")) {
					var insnList = new InsnList();
					// TODO we might want to use a logger instance instead of System.out here?
					//      previously tried to use Constants.Log but `Constants` gives a `NoClassDefFoundError` from here
					insnList.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
					insnList.add(new LdcInsnNode("Original mixin transformation service successfully crobbed by mixin-transmogrifier!"));
					insnList.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V"));

					insnList.add(new InsnNode(Opcodes.RETURN));
					methodNode.instructions = insnList;
				}
			};
			retransformLoadedClass(clazz, new ClassTransformer(clazz, transform));
		} catch (ClassNotFoundException e) {
			throw new RuntimeException(e);
		}
	}
}
