import org.objectweb.asm.*;

/** Keeps windows and physical cursor operations inside the controlled client out of the user's way. */
final class BackgroundWindows {
    private BackgroundWindows() {}

    static byte[] transform(String name, byte[] bytes) {
        if (name == null || (!name.equals("org/lwjgl/glfw/GLFW") && !name.equals("org/lwjgl/sdl/SDLVideo") && !name.equals("org/lwjgl/sdl/SDLMouse"))) return null;
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        final int[] hooks = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String method, String desc, String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, method, desc, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override public void visitCode() {
                        super.visitCode();
                        if (name.equals("org/lwjgl/glfw/GLFW") && method.equals("nglfwCreateWindow") && desc.equals("(IIJJJ)J")) {
                            hooks[0] |= 1;
                            super.visitLdcInsn(0x00020004); super.visitInsn(Opcodes.ICONST_0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, name, "glfwWindowHint", "(II)V", false);
                        }
                        if (name.equals("org/lwjgl/sdl/SDLVideo") && method.equals("nSDL_CreateWindow") && desc.equals("(JIIJ)J")) {
                            hooks[0] |= 1;
                            super.visitVarInsn(Opcodes.LLOAD, 4); super.visitLdcInsn(8L); super.visitInsn(Opcodes.LOR); super.visitVarInsn(Opcodes.LSTORE, 4);
                        }
                        if (name.equals("org/lwjgl/glfw/GLFW") && method.equals("glfwSetInputMode") && desc.equals("(JII)V")) {
                            hooks[0] |= 8;
                            Label otherMode = new Label();
                            super.visitVarInsn(Opcodes.ILOAD, 2); super.visitLdcInsn(0x00033001);
                            super.visitJumpInsn(Opcodes.IF_ICMPNE, otherMode); super.visitInsn(Opcodes.RETURN);
                            super.visitLabel(otherMode); super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                        }
                    }
                    @Override public void visitMethodInsn(int opcode, String owner, String called, String descriptor, boolean itf) {
                        if (owner.equals("org/lwjgl/system/JNI")) {
                            if (name.equals("org/lwjgl/glfw/GLFW") && (method.equals("glfwShowWindow") || method.equals("glfwFocusWindow")) && descriptor.equals("(JJ)V")) {
                                hooks[0] |= method.equals("glfwShowWindow") ? 2 : 4;
                                super.visitInsn(Opcodes.POP2); super.visitInsn(Opcodes.POP2); return;
                            }
                            if (name.equals("org/lwjgl/glfw/GLFW") && method.equals("glfwSetCursorPos") && descriptor.equals("(JDDJ)V")) {
                                hooks[0] |= 16;
                                for (int i = 0; i < 4; i++) super.visitInsn(Opcodes.POP2); return;
                            }
                            if (name.equals("org/lwjgl/sdl/SDLVideo") && method.equals("SDL_ShowWindow") && descriptor.equals("(JJ)Z")) {
                                hooks[0] |= 2;
                                super.visitInsn(Opcodes.POP2); super.visitInsn(Opcodes.POP2); super.visitInsn(Opcodes.ICONST_1); return;
                            }
                            if (name.equals("org/lwjgl/sdl/SDLVideo") && method.equals("SDL_RaiseWindow") && descriptor.equals("(JJ)Z")) {
                                hooks[0] |= 16;
                                super.visitInsn(Opcodes.POP2); super.visitInsn(Opcodes.POP2); super.visitInsn(Opcodes.ICONST_1); return;
                            }
                            if ((name.equals("org/lwjgl/sdl/SDLVideo") && (method.equals("SDL_SetWindowMouseGrab") || method.equals("SDL_SetWindowKeyboardGrab")) || name.equals("org/lwjgl/sdl/SDLMouse") && method.equals("SDL_SetWindowRelativeMouseMode")) && descriptor.equals("(JZJ)Z")) {
                                hooks[0] |= name.equals("org/lwjgl/sdl/SDLMouse") ? 1 : method.equals("SDL_SetWindowMouseGrab") ? 4 : 8;
                                super.visitInsn(Opcodes.POP2); super.visitInsn(Opcodes.POP); super.visitInsn(Opcodes.POP2); super.visitInsn(Opcodes.ICONST_1); return;
                            }
                            if (name.equals("org/lwjgl/sdl/SDLMouse") && method.equals("SDL_WarpMouseInWindow") && descriptor.equals("(JFFJ)V")) {
                                hooks[0] |= 2;
                                super.visitInsn(Opcodes.POP2); super.visitInsn(Opcodes.POP2); super.visitInsn(Opcodes.POP2); return;
                            }
                            if (name.equals("org/lwjgl/sdl/SDLMouse") && method.equals("SDL_WarpMouseGlobal") && descriptor.equals("(FFJ)Z")) {
                                hooks[0] |= 4;
                                super.visitInsn(Opcodes.POP2); super.visitInsn(Opcodes.POP2); super.visitInsn(Opcodes.ICONST_1); return;
                            }
                        }
                        super.visitMethodInsn(opcode, owner, called, descriptor, itf);
                    }
                };
            }
        }, 0);
        int expected = name.equals("org/lwjgl/glfw/GLFW") ? 31 : name.equals("org/lwjgl/sdl/SDLVideo") ? 31 : 7;
        if (hooks[0] != expected) throw new IllegalStateException("Unsupported " + name + " window controls (found " + hooks[0] + ", expected " + expected + ")");
        return writer.toByteArray();
    }
}
