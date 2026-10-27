import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.function.Consumer;
import org.objectweb.asm.*;

/** Applies personal launch preferences before the client opens windows or reads settings. */
final class GameLaunchAgent {
    private GameLaunchAgent() {}
    static void install(Instrumentation instrumentation) {
        System.getProperties().put("modaccel.launch.quiet", new Consumer<Object>() {
            public void accept(Object args) { QuietOptions.seedArgs((String[]) args); }
        });
        instrumentation.addTransformer(new ClassFileTransformer() {
            public byte[] transform(ClassLoader loader, String name, Class<?> type, ProtectionDomain domain, byte[] bytes) {
                try {
                    if (Boolean.getBoolean("modaccel.background")) {
                        byte[] background = BackgroundWindows.transform(name, bytes);
                        if (background != null) return background;
                    }
                    if (!Boolean.getBoolean("modaccel.quiet") || !"net/minecraft/client/main/Main".equals(name)) return null;
                    ClassReader reader = new ClassReader(bytes);
                    ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                    final int[] hooks = {0};
                    reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                        @Override public MethodVisitor visitMethod(int access, String method, String descriptor, String signature, String[] exceptions) {
                            MethodVisitor delegate = super.visitMethod(access, method, descriptor, signature, exceptions);
                            if (!method.equals("main") || !descriptor.equals("([Ljava/lang/String;)V") || (access & Opcodes.ACC_STATIC) == 0) return delegate;
                            hooks[0]++;
                            return new MethodVisitor(Opcodes.ASM9, delegate) {
                                @Override public void visitCode() {
                                    super.visitCode();
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", "()Ljava/util/Properties;", false);
                                    super.visitLdcInsn("modaccel.launch.quiet");
                                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                                    super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Consumer");
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Consumer", "accept", "(Ljava/lang/Object;)V", true);
                                }
                            };
                        }
                    }, 0);
                    if (hooks[0] != 1) throw new IllegalStateException("Minecraft client entry point is unsupported");
                    return writer.toByteArray();
                } catch (Throwable error) {
                    System.err.println("MODACCEL_BACKGROUND_ERROR " + error);
                    return new byte[0];
                }
            }
        });
    }
}
