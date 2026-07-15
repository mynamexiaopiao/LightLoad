package com.lightload.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class RuntimePatchTransformer implements ClassFileTransformer {
    static final String ACCESS_TRANSFORMER_LIST =
            "net/minecraftforge/accesstransformer/parser/AccessTransformerList";
    static final String MIXIN_BYTECODE = "org/spongepowered/asm/util/Bytecode";
    static final String MIXIN_PROCESSOR = "org/spongepowered/asm/mixin/transformer/MixinProcessor";
    static final String RESOURCE_LOCATION = "net/minecraft/resources/ResourceLocation";
    static final String MOD_JAR_METADATA =
            "net/minecraftforge/fml/loading/moddiscovery/ModJarMetadata";
    static final String JDK_MODULE_RESOLVER = "java/lang/module/Resolver";
    private static final String HOOKS = "com/lightload/agent/RuntimeTransformationHooks";
    private static final String TYPE_DESCRIPTOR = "Lorg/objectweb/asm/Type;";
    private final Instrumentation instrumentation;
    private final Set<ModuleAccess> openedModules = ConcurrentHashMap.newKeySet();

    RuntimePatchTransformer(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (ACCESS_TRANSFORMER_LIST.equals(className)) {
            return transformAccessTransformer(module, classfileBuffer);
        }
        if (MIXIN_BYTECODE.equals(className)) {
            return transformMixinBytecode(module, classfileBuffer);
        }
        if (MIXIN_PROCESSOR.equals(className)
                && Boolean.parseBoolean(System.getProperty("lightload.runtimePatch.mixinTargetIndex.enabled", "true"))) {
            return transformMixinProcessor(module, classfileBuffer);
        }
        if (RESOURCE_LOCATION.equals(className)
                && Boolean.parseBoolean(System.getProperty("lightload.runtimePatch.oculusPath.enabled", "true"))) {
            return transformOculusResourceLocation(module, classfileBuffer);
        }
        if (MOD_JAR_METADATA.equals(className)) {
            return transformModJarMetadata(module, classfileBuffer);
        }
        if (JDK_MODULE_RESOLVER.equals(className)) {
            return transformModuleResolver(module, classfileBuffer);
        }
        return null;
    }

    private byte[] transformModJarMetadata(Module module, byte[] classfileBuffer) {
        try {
            openTargetModule(module, "net.minecraftforge.fml.loading.moddiscovery");
            ClassNode node = new ClassNode(Opcodes.ASM9);
            new ClassReader(classfileBuffer).accept(node, 0);
            MethodNode descriptor = find(node, "descriptor", "()Ljava/lang/module/ModuleDescriptor;");
            if (descriptor == null) {
                System.setProperty("lightload.runtimePatch.modulePackages", "unsupported-method");
                return null;
            }
            java.util.List<MethodInsnNode> packageCalls = java.util.Arrays.stream(descriptor.instructions.toArray())
                    .filter(MethodInsnNode.class::isInstance)
                    .map(MethodInsnNode.class::cast)
                    .filter(call -> call.name.equals("getPackages") && call.desc.equals("()Ljava/util/Set;"))
                    .toList();
            if (packageCalls.size() != 1) {
                System.setProperty("lightload.runtimePatch.modulePackages", "unsupported-call");
                return null;
            }
            MethodInsnNode call = packageCalls.get(0);
            descriptor.instructions.insertBefore(call, new VarInsnNode(Opcodes.ALOAD, 0));
            descriptor.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "modulePackages",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Set;", false));
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            System.setProperty("lightload.runtimePatch.modulePackageIndex", "instrumented");
            return writer.toByteArray();
        } catch (Throwable throwable) {
            System.setProperty("lightload.runtimePatch.modulePackageIndex", "failed");
            System.err.println("[Lightload Agent] Module package cache patch failed: " + throwable);
            return null;
        }
    }

    private byte[] transformModuleResolver(Module module, byte[] classfileBuffer) {
        try {
            openTargetModule(module, null);
            ClassNode node = new ClassNode(Opcodes.ASM9);
            new ClassReader(classfileBuffer).accept(node, 0);
            MethodNode makeGraph = find(node, "makeGraph", "(Ljava/lang/module/Configuration;)Ljava/util/Map;");
            if (makeGraph == null) {
                System.setProperty("lightload.runtimePatch.moduleClosure", "unsupported-method");
                return null;
            }
            TypeInsnNode closureStart = java.util.Arrays.stream(makeGraph.instructions.toArray())
                    .filter(TypeInsnNode.class::isInstance)
                    .map(TypeInsnNode.class::cast)
                    .filter(instruction -> instruction.getOpcode() == Opcodes.NEW
                            && "java/util/ArrayList".equals(instruction.desc))
                    .reduce((first, second) -> second).orElse(null);
            AbstractInsnNode returnLoad = makeGraph.instructions.getLast();
            while (returnLoad != null && returnLoad.getOpcode() < 0) returnLoad = returnLoad.getPrevious();
            if (returnLoad == null || returnLoad.getOpcode() != Opcodes.ARETURN) {
                System.setProperty("lightload.runtimePatch.moduleClosure", "unsupported-return");
                return null;
            }
            returnLoad = returnLoad.getPrevious();
            while (returnLoad != null && returnLoad.getOpcode() < 0) returnLoad = returnLoad.getPrevious();
            if (closureStart == null || !(returnLoad instanceof VarInsnNode load)
                    || load.getOpcode() != Opcodes.ALOAD) {
                System.setProperty("lightload.runtimePatch.moduleClosure", "unsupported-shape");
                return null;
            }
            int readsLocal = load.var;
            int transitiveLocal = readsLocal + 1;
            InsnList replacement = new InsnList();
            replacement.add(new VarInsnNode(Opcodes.ALOAD, readsLocal));
            replacement.add(new VarInsnNode(Opcodes.ALOAD, transitiveLocal));
            replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "completeTransitiveClosure",
                    "(Ljava/util/Map;Ljava/util/Map;)V", false));
            makeGraph.instructions.insertBefore(closureStart, replacement);
            AbstractInsnNode current = closureStart;
            while (current != returnLoad) {
                AbstractInsnNode next = current.getNext();
                makeGraph.instructions.remove(current);
                current = next;
            }
            makeGraph.tryCatchBlocks.removeIf(block -> !makeGraph.instructions.contains(block.start)
                    || !makeGraph.instructions.contains(block.end) || !makeGraph.instructions.contains(block.handler));
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            System.setProperty("lightload.runtimePatch.moduleClosure", "instrumented");
            return writer.toByteArray();
        } catch (Throwable throwable) {
            System.setProperty("lightload.runtimePatch.moduleClosure", "failed");
            System.err.println("[Lightload Agent] Module graph patch failed: " + throwable);
            return null;
        }
    }

    private byte[] transformAccessTransformer(Module module, byte[] classfileBuffer) {
        try {
            openTargetModule(module, null);
            ClassNode node = new ClassNode(Opcodes.ASM9);
            new ClassReader(classfileBuffer).accept(node, 0);
            String field = node.fields.stream()
                    .filter(candidate -> "Ljava/util/Map;".equals(candidate.desc))
                    .filter(candidate -> "accessTransformers".equals(candidate.name))
                    .map(candidate -> candidate.name)
                    .findFirst().orElse(null);
            MethodNode contains = find(node, "containsClassTarget", "(" + TYPE_DESCRIPTOR + ")Z");
            MethodNode get = find(node, "getTransformersForTarget", "(" + TYPE_DESCRIPTOR + ")Ljava/util/Map;");
            MethodNode load = find(node, "loadFromPath", "(Ljava/nio/file/Path;Ljava/lang/String;)V");
            if (field == null || contains == null || get == null || load == null) {
                System.setProperty("lightload.runtimeAgent.status", "unsupported-access-transformer");
                return null;
            }
            replaceContains(node.name, field, contains);
            replaceGet(node.name, field, get);
            invalidateAfterLoad(node.name, field, load);
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            System.setProperty("lightload.runtimeAgent.status", "access-transformer-indexed");
            return writer.toByteArray();
        } catch (Throwable throwable) {
            System.setProperty("lightload.runtimeAgent.status", "transform-failed");
            System.err.println("[Lightload Agent] Runtime patch failed; keeping Forge implementation: " + throwable);
            return null;
        }
    }

    private byte[] transformMixinBytecode(Module module, byte[] classfileBuffer) {
        try {
            openTargetModule(module, null);
            ClassNode node = new ClassNode(Opcodes.ASM9);
            new ClassReader(classfileBuffer).accept(node, 0);
            MethodNode method = find(node, "getOpcodeName", "(ILjava/lang/String;I)Ljava/lang/String;");
            if (method == null) {
                System.setProperty("lightload.runtimePatch.mixinOpcode", "unsupported");
                return null;
            }
            Object opcodeConstants = java.util.Arrays.stream(method.instructions.toArray())
                    .filter(LdcInsnNode.class::isInstance)
                    .map(LdcInsnNode.class::cast)
                    .map(instruction -> instruction.cst)
                    .filter(constant -> constant != null && String.valueOf(constant).endsWith("/Opcodes;"))
                    .findFirst().orElse(null);
            if (opcodeConstants == null) {
                System.setProperty("lightload.runtimePatch.mixinOpcode", "unsupported-constants");
                return null;
            }
            reset(method);
            method.instructions.add(new LdcInsnNode(opcodeConstants));
            method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "opcodeName",
                    "(Ljava/lang/Class;ILjava/lang/String;I)Ljava/lang/String;", false));
            method.instructions.add(new InsnNode(Opcodes.ARETURN));
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            System.setProperty("lightload.runtimePatch.mixinOpcode", "cached");
            return writer.toByteArray();
        } catch (Throwable throwable) {
            System.setProperty("lightload.runtimePatch.mixinOpcode", "failed");
            System.err.println("[Lightload Agent] Mixin opcode cache patch failed: " + throwable);
            return null;
        }
    }

    private byte[] transformMixinProcessor(Module module, byte[] classfileBuffer) {
        try {
            openTargetModule(module, "org.spongepowered.asm.mixin.transformer");
            ClassNode node = new ClassNode(Opcodes.ASM9);
            new ClassReader(classfileBuffer).accept(node, 0);
            MethodNode method = find(node, "applyMixins",
                    "(Lorg/spongepowered/asm/mixin/MixinEnvironment;Ljava/lang/String;"
                            + "Lorg/objectweb/asm/tree/ClassNode;)Z");
            String configsField = node.fields.stream()
                    .filter(field -> "configs".equals(field.name) && "Ljava/util/List;".equals(field.desc))
                    .map(field -> field.name)
                    .findFirst().orElse(null);
            if (method == null || configsField == null) {
                System.setProperty("lightload.runtimePatch.mixinTargets", "unsupported");
                return null;
            }
            java.util.List<MethodInsnNode> iterators = java.util.Arrays.stream(method.instructions.toArray())
                    .filter(MethodInsnNode.class::isInstance)
                    .map(MethodInsnNode.class::cast)
                    .filter(call -> call.getOpcode() == Opcodes.INVOKEINTERFACE
                            && "java/util/List".equals(call.owner)
                            && "iterator".equals(call.name)
                            && "()Ljava/util/Iterator;".equals(call.desc))
                    .filter(call -> previousField(call, node.name, configsField))
                    .toList();
            if (iterators.size() != 2) {
                System.setProperty("lightload.runtimePatch.mixinTargets", "unsupported-iterators");
                return null;
            }
            replaceConfigIterator(method, iterators.get(0), "packageConfigIterator");
            replaceConfigIterator(method, iterators.get(1), "targetConfigIterator");
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            System.setProperty("lightload.runtimePatch.mixinTargets", "indexed");
            return writer.toByteArray();
        } catch (Throwable throwable) {
            System.setProperty("lightload.runtimePatch.mixinTargets", "failed");
            System.err.println("[Lightload Agent] Mixin target index patch failed: " + throwable);
            return null;
        }
    }

    private byte[] transformOculusResourceLocation(Module module, byte[] classfileBuffer) {
        try {
            ClassNode node = new ClassNode(Opcodes.ASM9);
            new ClassReader(classfileBuffer).accept(node, 0);
            MethodNode validChar = findInjected(node, "(C)Z", "iris$allowInvalidPaths");
            MethodNode validPath = findInjected(node, "(Ljava/lang/String;)Z", "iris$blockDUMMY");
            if (validChar == null && validPath == null) {
                System.setProperty("lightload.runtimePatch.oculusPath", "not-present");
                return null;
            }
            if (validChar == null || validPath == null) {
                System.setProperty("lightload.runtimePatch.oculusPath", "unsupported");
                return null;
            }
            openTargetModule(module, null);
            replaceStaticBoolean(validChar, Opcodes.ILOAD, "oculusValidPathChar", "(C)Z");
            replaceStaticBoolean(validPath, Opcodes.ALOAD, "oculusValidPath", "(Ljava/lang/String;)Z");
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            System.setProperty("lightload.runtimePatch.oculusPath", "zero-allocation");
            return writer.toByteArray();
        } catch (Throwable throwable) {
            System.setProperty("lightload.runtimePatch.oculusPath", "failed");
            System.err.println("[Lightload Agent] Oculus path validation patch failed: " + throwable);
            return null;
        }
    }

    private void openTargetModule(Module module, String packageName) throws ClassNotFoundException {
        if (module == null || !module.isNamed() || !openedModules.add(new ModuleAccess(module, packageName))) return;
        Module hooks = Class.forName("com.lightload.agent.RuntimeTransformationHooks", false, null).getModule();
        Map<String, Set<Module>> opens = packageName == null ? Map.of() : Map.of(packageName, Set.of(hooks));
        instrumentation.redefineModule(module, Set.of(hooks), Map.of(), opens, Set.of(), Map.of());
    }

    private static boolean previousField(MethodInsnNode call, String owner, String field) {
        AbstractInsnNode previous = call.getPrevious();
        while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
        return previous instanceof FieldInsnNode access
                && access.getOpcode() == Opcodes.GETFIELD
                && owner.equals(access.owner)
                && field.equals(access.name);
    }

    private static void replaceConfigIterator(MethodNode method, MethodInsnNode original, String hook) {
        method.instructions.insertBefore(original, new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.set(original, new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, hook,
                "(Ljava/util/List;Ljava/lang/String;)Ljava/util/Iterator;", false));
    }

    private static void replaceStaticBoolean(MethodNode method, int loadOpcode, String hook, String descriptor) {
        reset(method);
        method.instructions.add(new VarInsnNode(loadOpcode, 0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, hook, descriptor, false));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
    }

    private static void replaceContains(String owner, String field, MethodNode method) {
        reset(method);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, owner, field, "Ljava/util/Map;"));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "containsClassTarget",
                "(Ljava/util/Map;Ljava/lang/Object;)Z", false));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
    }

    private static void replaceGet(String owner, String field, MethodNode method) {
        reset(method);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, owner, field, "Ljava/util/Map;"));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "getTransformersForTarget",
                "(Ljava/util/Map;Ljava/lang/Object;)Ljava/util/Map;", false));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
    }

    private static void invalidateAfterLoad(String owner, String field, MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.RETURN) continue;
            InsnList invalidate = new InsnList();
            invalidate.add(new VarInsnNode(Opcodes.ALOAD, 0));
            invalidate.add(new FieldInsnNode(Opcodes.GETFIELD, owner, field, "Ljava/util/Map;"));
            invalidate.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "invalidate",
                    "(Ljava/util/Map;)V", false));
            method.instructions.insertBefore(instruction, invalidate);
        }
    }

    private static void reset(MethodNode method) {
        method.instructions.clear();
        method.tryCatchBlocks.clear();
        method.localVariables = null;
    }

    private static MethodNode find(ClassNode node, String name, String descriptor) {
        return node.methods.stream()
                .filter(method -> method.name.equals(name) && method.desc.equals(descriptor))
                .findFirst().orElse(null);
    }

    private static MethodNode findInjected(ClassNode node, String descriptor, String handlerMarker) {
        return node.methods.stream()
                .filter(method -> method.desc.equals(descriptor))
                .filter(method -> java.util.Arrays.stream(method.instructions.toArray())
                        .filter(MethodInsnNode.class::isInstance)
                        .map(MethodInsnNode.class::cast)
                        .anyMatch(call -> call.owner.equals(node.name) && call.name.contains(handlerMarker)))
                .findFirst().orElse(null);
    }

    private record ModuleAccess(Module module, String packageName) {
    }
}
