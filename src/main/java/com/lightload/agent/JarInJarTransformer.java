package com.lightload.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class JarInJarTransformer implements ClassFileTransformer {
    private static final String TARGET = "net/minecraftforge/fml/loading/moddiscovery/JarInJarDependencyLocator";
    private static final String HOOKS = "com/lightload/agent/EarlyJarInJarHooks";
    private static final String SCAN_DESCRIPTOR = "(Ljava/lang/Iterable;)Ljava/util/List;";
    private final Instrumentation instrumentation;

    JarInJarTransformer(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (!TARGET.equals(className)) return null;
        try {
            openTargetModule(module);
            String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(classfileBuffer));
            try {
                LightloadAgent.setTargetFingerprint(fingerprint);
            } catch (ClassNotFoundException unavailableOutsideAgentRuntime) {
                EarlyJarInJarHooks.setTargetFingerprint(fingerprint);
            }
            ClassNode node = new ClassNode(Opcodes.ASM9);
            new ClassReader(classfileBuffer).accept(node, 0);
            MethodNode scan = find(node.methods, "scanMods", SCAN_DESCRIPTOR);
            MethodNode load = node.methods.stream()
                    .filter(method -> method.name.equals("loadModFileFrom"))
                    .filter(method -> Type.getArgumentTypes(method.desc).length == 2)
                    .filter(method -> Type.getReturnType(method.desc).getClassName().equals("java.util.Optional"))
                    .findFirst().orElse(null);
            if (scan == null || load == null) {
                System.setProperty("lightload.agent.cache", "unsupported");
                System.err.println("[Lightload Agent] Forge Jar-in-Jar bytecode is unsupported; no transformation applied");
                return null;
            }
            scan.name = "lightload$scanModsOriginal";
            load.name = "lightload$loadModFileFromOriginal";
            node.methods.add(scanWrapper(node.name, scan.access, scan.exceptions));
            node.methods.add(loadWrapper(node.name, load));
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            System.out.println("[Lightload Agent] Forge Jar-in-Jar locator instrumented");
            return writer.toByteArray();
        } catch (Throwable throwable) {
            System.setProperty("lightload.agent.cache", "transform-failed");
            System.err.println("[Lightload Agent] Forge transformation failed; no transformation applied: " + throwable);
            return null;
        }
    }

    private void openTargetModule(Module module) {
        if (module == null || !module.isNamed()) return;
        Module agentModule;
        try {
            agentModule = LightloadAgent.hooksModule();
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("Bootstrap hooks are unavailable", exception);
        }
        instrumentation.redefineModule(module, Set.of(agentModule), Map.of(),
                Map.of("net.minecraftforge.fml.loading.moddiscovery", Set.of(agentModule)), Set.of(), Map.of());
    }

    private static MethodNode scanWrapper(String owner, int originalAccess, List<String> exceptions) {
        int access = originalAccess & ~(Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE);
        MethodNode method = new MethodNode(Opcodes.ASM9, access, "scanMods", SCAN_DESCRIPTOR, null,
                exceptions == null ? null : exceptions.toArray(String[]::new));
        LabelNode miss = new LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "tryLoad",
                "(Ljava/lang/Object;Ljava/lang/Iterable;)Ljava/lang/Object;", false));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new JumpInsnNode(Opcodes.IFNULL, miss));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/List"));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.instructions.add(miss);
        method.instructions.add(new FrameNode(Opcodes.F_APPEND, 1, new Object[]{"java/lang/Object"}, 0, null));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "lightload$scanModsOriginal",
                SCAN_DESCRIPTOR, false));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "recordResult",
                "(Ljava/lang/Object;Ljava/lang/Iterable;Ljava/util/List;)V", false));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        return method;
    }

    private static MethodNode loadWrapper(String owner, MethodNode original) {
        int access = original.access & ~(Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE);
        MethodNode method = new MethodNode(Opcodes.ASM9, access, "loadModFileFrom", original.desc,
                original.signature, original.exceptions == null ? null : original.exceptions.toArray(String[]::new));
        Type[] arguments = Type.getArgumentTypes(original.desc);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "lightload$loadModFileFromOriginal",
                original.desc, false));
        int resultLocal = 1 + arguments[0].getSize() + arguments[1].getSize();
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, resultLocal));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, resultLocal));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "recordLoaded",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", false));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, resultLocal));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        return method;
    }

    private static MethodNode find(List<MethodNode> methods, String name, String descriptor) {
        return methods.stream().filter(method -> method.name.equals(name) && method.desc.equals(descriptor))
                .findFirst().orElse(null);
    }
}
