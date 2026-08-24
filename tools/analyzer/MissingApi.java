import xyz.wagyourtail.jvmdg.shade.asm.*;
import java.io.*; import java.util.*; import java.util.zip.*; import java.lang.reflect.*;

// Scans jars for references to JDK members and checks whether they exist in the running (Java 8) JDK.
public class MissingApi {
    static final Map<String, Set<String>> missing = new TreeMap<>();   // member -> referencing classes
    static final Map<String, Boolean> cache = new HashMap<>();

    public static void main(String[] args) throws Exception {
        for (String jar : args) scan(jar);
        for (Map.Entry<String, Set<String>> e : missing.entrySet())
            System.out.println(e.getValue().size() + "\t" + e.getKey() + "\t" + e.getValue().iterator().next() + (e.getValue().size() > 1 ? " (+" + (e.getValue().size()-1) + ")" : ""));
        System.err.println(missing.size() + " distinct missing members");
    }

    static void scan(String jar) throws IOException {
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(jar)))) {
            ZipEntry ze;
            while ((ze = zin.getNextEntry()) != null) {
                if (!ze.getName().endsWith(".class") || ze.getName().startsWith("META-INF/versions/")) continue;
                ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
                while ((n = zin.read(buf)) > 0) bo.write(buf, 0, n);
                final String cls = ze.getName();
                try {
                    new ClassReader(bo.toByteArray()).accept(new ClassVisitor(Opcodes.ASM9) {
                        public MethodVisitor visitMethod(int a, String name, String d, String s, String[] ex) {
                            return new MethodVisitor(Opcodes.ASM9) {
                                public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) { check(owner, name, desc, cls); }
                                public void visitFieldInsn(int op, String owner, String name, String desc) { check(owner, name, null, cls); }
                                public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... bsmArgs) {
                                    check(bsm.getOwner(), bsm.getName(), bsm.getDesc(), cls);
                                    for (Object o : bsmArgs) if (o instanceof Handle) { Handle h = (Handle) o; check(h.getOwner(), h.getName(), h.getTag() <= Opcodes.H_PUTSTATIC ? null : h.getDesc(), cls); }
                                }
                                public void visitTypeInsn(int op, String type) { checkClass(type, cls); }
                            };
                        }
                    }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                } catch (Throwable t) { System.err.println("skip " + cls + ": " + t); }
            }
        }
    }

    static boolean jdk(String owner) { return owner.startsWith("java/") || owner.startsWith("javax/") || owner.startsWith("jdk/") || owner.startsWith("sun/"); }

    static void checkClass(String type, String from) {
        if (type.startsWith("[")) return;
        if (!jdk(type)) return;
        if (!exists(type)) add(type + " (class)", from);
    }

    static Class<?> load(String owner) {
        try { return Class.forName(owner.replace('/', '.'), false, ClassLoader.getSystemClassLoader().getParent()); }
        catch (Throwable t) { try { return Class.forName(owner.replace('/', '.'), false, ClassLoader.getSystemClassLoader()); } catch (Throwable t2) { return null; } }
    }
    static boolean exists(String owner) { return load(owner) != null; }

    static void check(String owner, String name, String desc, String from) {
        if (owner.startsWith("[")) owner = "java/lang/Object";
        if (!jdk(owner)) return;
        String key = owner + "." + name + (desc == null ? "" : desc);
        Boolean ok = cache.get(key);
        if (ok == null) { ok = has(owner, name, desc); cache.put(key, ok); }
        if (!ok) add(key, from);
    }

    static boolean has(String owner, String name, String desc) {
        Class<?> c = load(owner);
        if (c == null) return false;
        if (desc == null) return findField(c, name);
        if (name.equals("<init>")) {
            for (Constructor<?> k : c.getDeclaredConstructors()) if (xyz.wagyourtail.jvmdg.shade.asm.Type.getConstructorDescriptor(k).equals(desc)) return true;
            return false;
        }
        return findMethod(c, name, desc, new HashSet<Class<?>>());
    }
    static boolean findField(Class<?> c, String name) {
        if (c == null) return false;
        for (Field f : c.getDeclaredFields()) if (f.getName().equals(name)) return true;
        for (Class<?> i : c.getInterfaces()) if (findField(i, name)) return true;
        return findField(c.getSuperclass(), name);
    }
    static boolean findMethod(Class<?> c, String name, String desc, Set<Class<?>> seen) {
        if (c == null || !seen.add(c)) return false;
        for (Method m : c.getDeclaredMethods()) if (m.getName().equals(name) && xyz.wagyourtail.jvmdg.shade.asm.Type.getMethodDescriptor(m).equals(desc)) return true;
        for (Class<?> i : c.getInterfaces()) if (findMethod(i, name, desc, seen)) return true;
        if (c.isInterface() && findMethod(Object.class, name, desc, seen)) return true;
        return findMethod(c.getSuperclass(), name, desc, seen);
    }
    static void add(String key, String from) { Set<String> s = missing.get(key); if (s == null) missing.put(key, s = new TreeSet<>()); s.add(from); }
}
