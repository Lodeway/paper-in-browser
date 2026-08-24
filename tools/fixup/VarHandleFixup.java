import xyz.wagyourtail.jvmdg.shade.asm.*;
import xyz.wagyourtail.jvmdg.shade.asm.tree.*;
import xyz.wagyourtail.jvmdg.shade.asm.tree.analysis.*;

import java.io.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.zip.*;

// Post-pass over the downgraded jars: replace calls through JVMDowngrader's VarHandle emulation stub
// (J_L_I_VarHandle) with direct field/array access. The stub costs ~2.7us per access under CheerpJ
// (dispatch + owner checks + emulated Unsafe) vs ~30ns for a plain field access, and the chunk
// system's hot structures (MultiThreadedQueue, the concurrent hash tables, NewChunkHolder, ...) sit
// on it for every operation.
//
// Sound only because CheerpJ runs every Java thread cooperatively on one JS thread: threads can only
// switch at yield points (blocking calls, monitors, sleeps), never inside the straight-line helper
// methods generated here, so plain reads/writes — and non-atomic compare-and-set sequences — observe
// and preserve the same values the atomic originals would.
//
// Mechanics, per jar set (all jars are processed in one invocation so cross-jar targets resolve):
//  1. Classes whose constant pool mentions the stub are parsed; their <clinit> is pattern-matched for
//     handle creation (ConcurrentUtil.getVarHandle/getStaticVarHandle/getArrayHandle and the
//     MethodHandles stub equivalents) with constant arguments, giving handleField -> (kind, owner,
//     field, type). The target field is verified to exist; its real descriptor wins.
//  2. Every invokevirtual on the stub whose receiver provably is a mapped handle (a getstatic of the
//     handle field, or jvmdg's nest accessor for it — tracked with a dataflow analysis) is redirected
//     to an invokestatic helper with the same stack shape (stub receiver becomes an ignored first
//     argument, so existing stack frames stay valid and untouched methods are copied byte-identical).
//  3. Helpers are generated once per (handle, op) into the class that owns the target field (private
//     access works; nestmates share a package, so cross-class calls stay legal). Unprovable or
//     unsupported callsites are left on the stub.
public class VarHandleFixup {
    static final String STUB = "xyz/wagyourtail/jvmdg/j9/stub/java_base/J_L_I_VarHandle";
    static final String STUB_DESC = "L" + STUB + ";";
    static final String STUB_MH = "xyz/wagyourtail/jvmdg/j9/stub/java_base/J_L_I_MethodHandles";
    static final byte[] NEEDLE = "J_L_I_VarHandle".getBytes();

    static final int KIND_INSTANCE = 0, KIND_STATIC = 1, KIND_ARRAY = 2;

    static final class Handle {
        int kind;
        String owner;      // field owner (instance/static kinds)
        String field;
        Type fieldType;    // field type, or array class type for KIND_ARRAY
        int id;            // for unique helper names
    }

    // handle map key: declaringClass + "." + handleFieldName
    static final Map<String, Handle> handles = new HashMap<>();
    static final Map<String, byte[]> classBytes = new HashMap<>();      // internal name -> bytes
    static final Map<String, String> classJar = new HashMap<>();        // internal name -> jar path
    static final Map<String, ClassNode> parsed = new HashMap<>();       // internal name -> node
    static final Set<String> changed = new HashSet<>();
    static final Map<String, MethodNode> helperCache = new HashMap<>(); // ownerClass + "." + helperName
    static final Set<Handle> used = new HashSet<>();                    // handles with >=1 rewritten callsite
    static ClassLoader loader;
    static int nextId = 0, rewritten = 0, skipped = 0;

    public static void main(String[] args) throws Exception {
        List<URL> urls = new ArrayList<>();
        for (String jar : args) urls.add(new File(jar).toURI().toURL());
        loader = new URLClassLoader(urls.toArray(new URL[0]), VarHandleFixup.class.getClassLoader());

        // pass 0: index every class in every jar (bytes only)
        for (String jar : args) {
            try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(jar)))) {
                ZipEntry ze;
                while ((ze = zin.getNextEntry()) != null) {
                    if (!ze.getName().endsWith(".class")) continue;
                    byte[] data = readAll(zin);
                    String name = ze.getName().substring(0, ze.getName().length() - 6);
                    classBytes.put(name, data);
                    classJar.put(name, jar);
                }
            }
        }

        // pass 1: parse candidates, collect handle definitions from <clinit>
        for (Map.Entry<String, byte[]> e : classBytes.entrySet()) {
            if (!contains(e.getValue(), NEEDLE)) continue;
            ClassNode cn = node(e.getKey());
            for (MethodNode mn : cn.methods) {
                if (mn.name.equals("<clinit>")) collectHandles(cn, mn);
            }
        }
        System.out.println("[vh] mapped " + handles.size() + " VarHandle statics");

        // pass 2: rewrite callsites in every candidate class
        for (String name : new ArrayList<>(parsed.keySet())) {
            ClassNode cn = parsed.get(name);
            for (MethodNode mn : new ArrayList<>(cn.methods)) rewriteMethod(cn, mn); // helpers append to cn.methods
        }
        System.out.println("[vh] rewrote " + rewritten + " callsites, left " + skipped + " on the stub");

        // Plain reads can be cached by CheerpJ's JIT across a cooperative spin loop, so a thread
        // polling a flag another thread sets would never see the write. Volatile costs nothing
        // measurable under CheerpJ and forces the re-read, so every rewritten target field gets it.
        int marked = 0;
        for (Handle h : used) {
            if (h.kind == KIND_ARRAY) continue;
            ClassNode owner = node(h.owner);
            FieldNode f = owner == null ? null : field(owner, h.field);
            if (f == null || (f.access & (Opcodes.ACC_VOLATILE | Opcodes.ACC_FINAL)) != 0) continue;
            f.access |= Opcodes.ACC_VOLATILE;
            changed.add(h.owner);
            marked++;
        }
        System.out.println("[vh] marked " + marked + " target fields volatile");

        // pass 3: write back every jar that has changed classes
        for (String jar : args) {
            boolean dirty = false;
            for (String c : changed) if (jar.equals(classJar.get(c))) { dirty = true; break; }
            if (!dirty) continue;
            File in = new File(jar), tmp = new File(jar + ".tmp");
            try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(in)));
                 ZipOutputStream zout = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
                ZipEntry ze;
                while ((ze = zin.getNextEntry()) != null) {
                    byte[] data = readAll(zin);
                    String name = ze.getName().endsWith(".class") ? ze.getName().substring(0, ze.getName().length() - 6) : null;
                    if (name != null && changed.contains(name)) data = emit(parsed.get(name));
                    zout.putNextEntry(new ZipEntry(ze.getName()));
                    zout.write(data);
                    zout.closeEntry();
                }
            }
            if (!in.delete() || !tmp.renameTo(in)) throw new IOException("could not replace " + jar);
            System.out.println("[vh] " + new File(jar).getName() + " updated");
        }
    }

    static ClassNode node(String name) {
        ClassNode cn = parsed.get(name);
        if (cn == null) {
            byte[] b = classBytes.get(name);
            if (b == null) return null;
            cn = new ClassNode();
            new ClassReader(b).accept(cn, ClassReader.SKIP_FRAMES);
            parsed.put(name, cn);
        }
        return cn;
    }

    // frames are recomputed for changed classes; resolve hierarchy against the processed jar set
    static byte[] emit(ClassNode cn) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override protected ClassLoader getClassLoader() { return loader; }
        };
        cn.accept(cw);
        return cw.toByteArray();
    }

    // ---- handle collection ------------------------------------------------------------------------------

    static void collectHandles(ClassNode cn, MethodNode mn) {
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof FieldInsnNode)) continue;
            FieldInsnNode put = (FieldInsnNode) insn;
            if (put.getOpcode() != Opcodes.PUTSTATIC || !put.desc.equals(STUB_DESC)) continue;
            AbstractInsnNode prev = real(insn.getPrevious());
            if (!(prev instanceof MethodInsnNode)) continue;
            MethodInsnNode fac = (MethodInsnNode) prev;
            Handle h = matchFactory(fac);
            String key = put.owner + "." + put.name;
            if (h == null) { handles.remove(key); continue; } // unknown factory: never trust this field
            if (h.kind != KIND_ARRAY) {
                // the factory's type argument should match the real field; the real descriptor wins
                ClassNode ownerNode = node(h.owner);
                FieldNode target = ownerNode == null ? null : field(ownerNode, h.field);
                if (target == null) continue;
                h.fieldType = Type.getType(target.desc);
            }
            h.id = nextId++;
            if (handles.containsKey(key)) handles.remove(key); // assigned twice: ambiguous, drop
            else handles.put(key, h);
        }
    }

    static Handle matchFactory(MethodInsnNode fac) {
        boolean cu = fac.owner.equals("ca/spottedleaf/concurrentutil/util/ConcurrentUtil");
        boolean mh = fac.owner.equals(STUB_MH) || fac.owner.equals(STUB_MH + "$Lookup");
        if (!cu && !mh) return null;
        Handle h = new Handle();
        if (fac.name.equals("getVarHandle") || fac.name.equals("findVarHandle")
            || fac.name.equals("getStaticVarHandle") || fac.name.equals("findStaticVarHandle")) {
            h.kind = fac.name.contains("Static") ? KIND_STATIC : KIND_INSTANCE;
            // last three args are (ownerClass, name, typeClass) constants
            AbstractInsnNode a3 = real(fac.getPrevious());
            AbstractInsnNode a2 = a3 == null ? null : real(a3.getPrevious());
            AbstractInsnNode a1 = a2 == null ? null : real(a2.getPrevious());
            Type owner = constClass(a1);
            String name = constString(a2);
            Type type = constClass(a3);
            if (owner == null || name == null || type == null || owner.getSort() != Type.OBJECT) return null;
            h.owner = owner.getInternalName();
            h.field = name;
            h.fieldType = type;
            return h;
        }
        if (fac.name.equals("getArrayHandle") || fac.name.equals("arrayElementVarHandle")) {
            Type arr = constClass(real(fac.getPrevious()));
            if (arr == null || arr.getSort() != Type.ARRAY) return null;
            h.kind = KIND_ARRAY;
            h.fieldType = arr;
            return h;
        }
        return null;
    }

    static Type constClass(AbstractInsnNode insn) {
        if (insn instanceof LdcInsnNode && ((LdcInsnNode) insn).cst instanceof Type) return (Type) ((LdcInsnNode) insn).cst;
        if (insn instanceof FieldInsnNode) { // Integer.TYPE etc.
            FieldInsnNode f = (FieldInsnNode) insn;
            if (f.getOpcode() == Opcodes.GETSTATIC && f.name.equals("TYPE")) {
                switch (f.owner) {
                    case "java/lang/Boolean": return Type.BOOLEAN_TYPE;
                    case "java/lang/Byte": return Type.BYTE_TYPE;
                    case "java/lang/Character": return Type.CHAR_TYPE;
                    case "java/lang/Short": return Type.SHORT_TYPE;
                    case "java/lang/Integer": return Type.INT_TYPE;
                    case "java/lang/Long": return Type.LONG_TYPE;
                    case "java/lang/Float": return Type.FLOAT_TYPE;
                    case "java/lang/Double": return Type.DOUBLE_TYPE;
                }
            }
        }
        return null;
    }

    static String constString(AbstractInsnNode insn) {
        return insn instanceof LdcInsnNode && ((LdcInsnNode) insn).cst instanceof String ? (String) ((LdcInsnNode) insn).cst : null;
    }

    static AbstractInsnNode real(AbstractInsnNode insn) {
        while (insn != null && (insn instanceof LabelNode || insn instanceof LineNumberNode || insn instanceof FrameNode)) insn = insn.getPrevious();
        return insn;
    }

    static FieldNode field(ClassNode cn, String name) {
        for (FieldNode f : cn.fields) if (f.name.equals(name)) return f;
        return null;
    }

    // ---- callsite rewriting -----------------------------------------------------------------------------

    // Park/unpark handshakes stay on the stub: their spin-until-CAS loops sit across LockSupport.park
    // and rely on the stub's native call being a yield point in CheerpJ's cooperative scheduler — an
    // inline helper never yields, and the boot deadlocks (empirically: QueueExecutorRunnable hangs
    // Bootstrap). These run once per work batch, so the stub cost is irrelevant there.
    static final String[] PARK_HANDSHAKES = { "QueueExecutorRunnable", "$TickThreadRunner" };

    static void rewriteMethod(ClassNode cn, MethodNode mn) {
        for (String e : PARK_HANDSHAKES) if (cn.name.contains(e)) return;
        for (String e : EXCLUDE) if (!e.isEmpty() && cn.name.contains(e)) return;
        boolean any = false;
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode && insn.getOpcode() == Opcodes.INVOKEVIRTUAL
                && ((MethodInsnNode) insn).owner.equals(STUB)) { any = true; break; }
        }
        if (!any) return;

        Frame<SourceValue>[] frames;
        try {
            frames = new Analyzer<>(new SourceInterpreter()).analyze(cn.name, mn);
        } catch (AnalyzerException e) {
            System.out.println("[vh] analyze failed for " + cn.name + "." + mn.name + ": " + e);
            return;
        }

        AbstractInsnNode[] insns = mn.instructions.toArray();
        for (int i = 0; i < insns.length; i++) {
            if (!(insns[i] instanceof MethodInsnNode) || insns[i].getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
            MethodInsnNode call = (MethodInsnNode) insns[i];
            if (!call.owner.equals(STUB)) continue;
            Frame<SourceValue> frame = frames[i];
            if (frame == null) continue;
            Type[] argTypes = Type.getArgumentTypes(call.desc);
            int recvSlot = frame.getStackSize() - argTypes.length - 1;
            if (recvSlot < 0) { skipped++; continue; }
            Handle h = resolveReceiver(frame.getStack(recvSlot));
            if (h == null) { skipped++; continue; }
            String helperOwner = h.kind == KIND_ARRAY ? cn.name : h.owner;
            ClassNode helperClass = node(helperOwner);
            if (helperClass == null || !samePackage(cn.name, helperOwner)) { skipped++; continue; }
            MethodNode helper = helper(helperClass, h, call);
            if (helper == null) { skipped++; continue; }
            call.setOpcode(Opcodes.INVOKESTATIC);
            call.owner = helperOwner;
            call.name = helper.name;
            call.desc = "(" + STUB_DESC + call.desc.substring(1);
            call.itf = false;
            used.add(h);
            rewritten++;
            changed.add(cn.name);
        }
    }

    static Handle resolveReceiver(SourceValue v) {
        if (v == null || v.insns.size() != 1) return null;
        AbstractInsnNode src = v.insns.iterator().next();
        if (src instanceof FieldInsnNode && src.getOpcode() == Opcodes.GETSTATIC) {
            FieldInsnNode f = (FieldInsnNode) src;
            return handles.get(f.owner + "." + f.name);
        }
        if (src instanceof MethodInsnNode && src.getOpcode() == Opcodes.INVOKESTATIC) {
            // jvmdg nest accessor: jvmdowngrader$nest$<...>$get$<FIELD>
            MethodInsnNode m = (MethodInsnNode) src;
            int idx = m.name.lastIndexOf("$get$");
            if (idx > 0 && m.name.startsWith("jvmdowngrader$nest$") && m.desc.equals("()" + STUB_DESC)) {
                return handles.get(m.owner + "." + m.name.substring(idx + 5));
            }
        }
        return null;
    }

    static boolean samePackage(String a, String b) {
        int ia = a.lastIndexOf('/'), ib = b.lastIndexOf('/');
        return ia == ib && (ia < 0 || a.substring(0, ia).equals(b.substring(0, ib)));
    }

    // ---- helper generation ------------------------------------------------------------------------------

    static MethodNode helper(ClassNode into, Handle h, MethodInsnNode call) {
        String base = opBase(call.name);
        if (base == null || (OPS != null && !OPS.contains(base))) return null;
        String name = "labs$vh" + h.id + "$" + call.name;
        String cacheKey = into.name + "." + name;
        String wantDesc = "(" + STUB_DESC + call.desc.substring(1);
        MethodNode cached = helperCache.get(cacheKey);
        if (cached != null) return cached.desc.equals(wantDesc) ? cached : null;

        Type[] args = Type.getArgumentTypes(call.desc);
        Type ret = Type.getReturnType(call.desc);
        MethodNode mn = build(h, base, args, ret, name);
        if (mn == null) return null;
        into.methods.add(mn);
        changed.add(into.name);
        helperCache.put(cacheKey, mn);
        return mn;
    }

    // debugging aids: -Dvh.ops=get,set restricts which op families are rewritten (default: all);
    // -Dvh.exclude=A,B skips rewriting inside classes whose internal name contains A or B
    static final Set<String> OPS = System.getProperty("vh.ops") == null ? null
        : new HashSet<>(Arrays.asList(System.getProperty("vh.ops").split(",")));
    static final String[] EXCLUDE = System.getProperty("vh.exclude") == null ? new String[0]
        : System.getProperty("vh.exclude").split(",");

    static String opBase(String n) {
        if (n.startsWith("compareAndExchange")) return "cax";
        if (n.startsWith("compareAndSet") || n.startsWith("weakCompareAndSet")) return "cas";
        if (n.startsWith("getAndSet")) return "gas";
        if (n.startsWith("getAndAdd")) return "gaa";
        if (n.startsWith("getAndBitwiseOr")) return "gor";
        if (n.startsWith("set")) return "set";
        if (n.startsWith("get")) return "get";
        return null;
    }

    /** Builds the helper body, or returns null when the shape is unsupported (callsite stays on the stub). */
    static MethodNode build(Handle h, String base, Type[] args, Type ret, String name) {
        Type ft = h.kind == KIND_ARRAY ? h.fieldType.getElementType() : h.fieldType;
        if (ft.getSort() == Type.FLOAT || ft.getSort() == Type.DOUBLE) return null; // bitwise CAS semantics differ; not needed
        if (h.kind == KIND_ARRAY && h.fieldType.getDimensions() != 1) return null;

        // expected argument shape (after the ignored stub receiver)
        int fixed = h.kind == KIND_INSTANCE ? 1 : h.kind == KIND_ARRAY ? 2 : 0;
        int values = base.equals("get") ? 0 : (base.equals("cas") || base.equals("cax")) ? 2 : 1;
        if (args.length != fixed + values) return null;
        if (h.kind != KIND_STATIC && args[0].getSort() != Type.OBJECT) return null;
        if (h.kind == KIND_ARRAY && args[1].getSort() != Type.INT) return null;
        for (int i = fixed; i < args.length; i++) if (!compatible(args[i], ft)) return null;
        if (base.equals("get") || base.equals("cax") || base.equals("gas") || base.equals("gaa") || base.equals("gor")) {
            if (!compatible(ret, ft)) return null;
        } else if (base.equals("cas")) {
            if (ret.getSort() != Type.BOOLEAN) return null;
        } else if (!base.equals("set") || ret.getSort() != Type.VOID) {
            if (!base.equals("set")) return null;
            return null;
        }
        if (base.equals("gaa") && ft.getSort() != Type.INT && ft.getSort() != Type.LONG) return null;
        if (base.equals("gor") && ft.getSort() != Type.INT) return null;

        StringBuilder desc = new StringBuilder("(").append(STUB_DESC);
        for (Type a : args) desc.append(a.getDescriptor());
        desc.append(")").append(ret.getDescriptor());
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
            name, desc.toString(), null, null);
        InsnList c = mn.instructions;

        // local slots: 0 = stub, then args
        int[] slot = new int[args.length];
        int next = 1;
        for (int i = 0; i < args.length; i++) { slot[i] = next; next += args[i].getSize(); }
        int scratch = next;

        // loads the target value onto the stack (receiver/index come from arg slots)
        Runnable loadTarget;
        Runnable loadRef = null;
        if (h.kind == KIND_INSTANCE) {
            loadRef = () -> {
                c.add(new VarInsnNode(Opcodes.ALOAD, slot[0]));
                c.add(new TypeInsnNode(Opcodes.CHECKCAST, h.owner));
            };
            Runnable lr = loadRef;
            loadTarget = () -> { lr.run(); c.add(new FieldInsnNode(Opcodes.GETFIELD, h.owner, h.field, ft.getDescriptor())); };
        } else if (h.kind == KIND_ARRAY) {
            loadRef = () -> {
                c.add(new VarInsnNode(Opcodes.ALOAD, slot[0]));
                c.add(new TypeInsnNode(Opcodes.CHECKCAST, h.fieldType.getInternalName()));
                c.add(new VarInsnNode(Opcodes.ILOAD, slot[1]));
            };
            Runnable lr = loadRef;
            loadTarget = () -> { lr.run(); c.add(new InsnNode(ft.getOpcode(Opcodes.IALOAD))); };
        } else {
            loadTarget = () -> c.add(new FieldInsnNode(Opcodes.GETSTATIC, h.owner, h.field, ft.getDescriptor()));
        }
        // stores the value on top of the stack into the target; caller must have run loadRef first (non-static)
        Runnable storeTarget = () -> {
            if (h.kind == KIND_INSTANCE) c.add(new FieldInsnNode(Opcodes.PUTFIELD, h.owner, h.field, ft.getDescriptor()));
            else if (h.kind == KIND_ARRAY) c.add(new InsnNode(ft.getOpcode(Opcodes.IASTORE)));
            else c.add(new FieldInsnNode(Opcodes.PUTSTATIC, h.owner, h.field, ft.getDescriptor()));
        };
        // loads value argument i (absolute index into args), casting refs to the field type
        java.util.function.IntConsumer loadValue = (i) -> {
            c.add(new VarInsnNode(args[i].getOpcode(Opcodes.ILOAD), slot[i]));
            if (isRef(ft) && !ft.getInternalName().equals("java/lang/Object") && isRef(args[i])) {
                c.add(new TypeInsnNode(Opcodes.CHECKCAST, ft.getInternalName()));
            }
        };

        switch (base) {
            case "get": {
                loadTarget.run();
                c.add(new InsnNode(ret.getOpcode(Opcodes.IRETURN)));
                break;
            }
            case "set": {
                if (loadRef != null) loadRef.run();
                loadValue.accept(fixed);
                storeTarget.run();
                c.add(new InsnNode(Opcodes.RETURN));
                break;
            }
            case "gas": case "gaa": case "gor": {
                int old = scratch;
                loadTarget.run();
                c.add(new VarInsnNode(ft.getOpcode(Opcodes.ISTORE), old));
                if (loadRef != null) loadRef.run();
                if (base.equals("gas")) {
                    loadValue.accept(fixed);
                } else {
                    c.add(new VarInsnNode(ft.getOpcode(Opcodes.ILOAD), old));
                    loadValue.accept(fixed);
                    c.add(new InsnNode(ft.getOpcode(base.equals("gaa") ? Opcodes.IADD : Opcodes.IOR)));
                }
                storeTarget.run();
                c.add(new VarInsnNode(ft.getOpcode(Opcodes.ILOAD), old));
                c.add(new InsnNode(ret.getOpcode(Opcodes.IRETURN)));
                break;
            }
            case "cas": case "cax": {
                int old = scratch;
                LabelNode fail = new LabelNode();
                loadTarget.run();
                c.add(new VarInsnNode(ft.getOpcode(Opcodes.ISTORE), old));
                c.add(new VarInsnNode(ft.getOpcode(Opcodes.ILOAD), old));
                c.add(new VarInsnNode(args[fixed].getOpcode(Opcodes.ILOAD), slot[fixed])); // expected
                switch (ft.getSort()) {
                    case Type.LONG:
                        c.add(new InsnNode(Opcodes.LCMP));
                        c.add(new JumpInsnNode(Opcodes.IFNE, fail));
                        break;
                    case Type.OBJECT: case Type.ARRAY:
                        c.add(new JumpInsnNode(Opcodes.IF_ACMPNE, fail));
                        break;
                    default:
                        c.add(new JumpInsnNode(Opcodes.IF_ICMPNE, fail));
                }
                if (loadRef != null) loadRef.run();
                loadValue.accept(fixed + 1); // new value
                storeTarget.run();
                if (base.equals("cas")) {
                    c.add(new InsnNode(Opcodes.ICONST_1));
                    c.add(new InsnNode(Opcodes.IRETURN));
                    c.add(fail);
                    c.add(new InsnNode(Opcodes.ICONST_0));
                    c.add(new InsnNode(Opcodes.IRETURN));
                } else {
                    c.add(fail);
                    c.add(new VarInsnNode(ft.getOpcode(Opcodes.ILOAD), old));
                    c.add(new InsnNode(ret.getOpcode(Opcodes.IRETURN)));
                }
                break;
            }
            default:
                return null;
        }
        mn.maxStack = 8;
        mn.maxLocals = scratch + 2;
        return mn;
    }

    static boolean isRef(Type t) { return t.getSort() == Type.OBJECT || t.getSort() == Type.ARRAY; }

    /** callsite type vs field type: same primitive family, or both references (no boxing supported). */
    static boolean compatible(Type callsite, Type ft) {
        if (isRef(ft)) return isRef(callsite);
        switch (ft.getSort()) {
            case Type.BOOLEAN: case Type.BYTE: case Type.CHAR: case Type.SHORT: case Type.INT:
                switch (callsite.getSort()) {
                    case Type.BOOLEAN: case Type.BYTE: case Type.CHAR: case Type.SHORT: case Type.INT: return true;
                    default: return false;
                }
            default:
                return callsite.getSort() == ft.getSort();
        }
    }

    static boolean contains(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return true;
        }
        return false;
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }
}
