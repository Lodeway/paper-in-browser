/*
 * Configurate
 * Copyright (C) zml and Configurate contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.spongepowered.configurate.objectmapping;

// Replacement for configurate-core 4.2.0's RecordFieldDiscoverer, compiled with JDK 8
// and swapped into the downgraded configurate-core jar by scripts/build-jar.sh.
//
// Why: the upstream class detects records reflectively, by looking up
// Class.isRecord / Class.getRecordComponents / java.lang.reflect.RecordComponent
// through MethodHandles.Lookup at runtime. JVMDowngrader can only rewrite direct
// call sites, not name-based reflection, so on a Java 8 runtime every one of those
// lookups fails, all five handles stay null, and discover() opts out for every
// type. Record-backed config sections then fall through to ObjectFieldDiscoverer,
// which needs a zero-argument constructor that records do not have, and reading a
// config written by a previous boot dies with
// "Objects must have a zero-argument constructor to be able to create new instances".
//
// JVMDowngrader does keep the record metadata: downgraded records extend
// xyz.wagyourtail.jvmdg.j16.stub.java_base.J_L_Record, carry their component list
// in a xyz.wagyourtail.jvmdg.j16.RecordComponents class annotation, and the
// runtime exposes it through J_L_Class.isRecord / J_L_Class.getRecordComponents
// returning J_L_R_RecordComponent (which implements AnnotatedElement). The only
// change from upstream is the static initializer: it binds the five method
// handles to those stubs instead of the java.lang.Class / RecordComponent
// members that only exist on Java 16+. discover() is upstream's code unchanged
// (minus the checker-qual annotations, to avoid a compile-time dependency).

import static io.leangen.geantyref.GenericTypeReflector.erase;
import static io.leangen.geantyref.GenericTypeReflector.resolveExactType;

import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.util.Types;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import xyz.wagyourtail.jvmdg.j16.stub.java_base.J_L_Class;
import xyz.wagyourtail.jvmdg.j16.stub.java_base.J_L_R_RecordComponent;

/**
 * Discovers fields in J14+ {@code Record}s.
 */
final class RecordFieldDiscoverer implements FieldDiscoverer<Object[]> {

    static final RecordFieldDiscoverer INSTANCE = new RecordFieldDiscoverer();

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final MethodHandle CLASS_IS_RECORD;
    private static final MethodHandle CLASS_GET_RECORD_COMPONENTS;
    private static final MethodHandle RECORD_COMPONENT_GET_ANNOTATED_TYPE;
    private static final MethodHandle RECORD_COMPONENT_GET_NAME;
    private static final MethodHandle RECORD_COMPONENT_GET_ACCESSOR;

    static {
        MethodHandle classIsRecord = null;
        MethodHandle classGetRecordComponents = null;
        MethodHandle recordComponentGetAnnotatedType = null;
        MethodHandle recordComponentGetName = null;
        MethodHandle recordComponentGetAccessor = null;
        try {
            // Bind to the JVMDowngrader record stubs instead of the Java 16+ reflection
            // API. The stub methods on J_L_Class are static with the class as the first
            // parameter, so the handles have exactly the shapes discover() invokes them
            // with; the J_L_R_RecordComponent handles are adapted from the
            // AnnotatedElement call sites by MethodHandle.invoke's asType cast.
            classIsRecord = LOOKUP.findStatic(J_L_Class.class, "isRecord",
                    MethodType.methodType(boolean.class, Class.class));
            classGetRecordComponents = LOOKUP.findStatic(J_L_Class.class, "getRecordComponents",
                    MethodType.methodType(J_L_R_RecordComponent[].class, Class.class));
            recordComponentGetAnnotatedType = LOOKUP.findVirtual(J_L_R_RecordComponent.class, "getAnnotatedType",
                    MethodType.methodType(AnnotatedType.class));
            recordComponentGetAccessor = LOOKUP.findVirtual(J_L_R_RecordComponent.class, "getAccessor",
                    MethodType.methodType(Method.class));
            recordComponentGetName = LOOKUP.findVirtual(J_L_R_RecordComponent.class, "getName",
                    MethodType.methodType(String.class));
        } catch (final Exception ex) {
            // ignore, records stay undetected as upstream does when not on J14+
        }

        CLASS_IS_RECORD = classIsRecord;
        CLASS_GET_RECORD_COMPONENTS = classGetRecordComponents;
        RECORD_COMPONENT_GET_ANNOTATED_TYPE = recordComponentGetAnnotatedType;
        RECORD_COMPONENT_GET_NAME = recordComponentGetName;
        RECORD_COMPONENT_GET_ACCESSOR = recordComponentGetAccessor;
    }

    private RecordFieldDiscoverer() {
    }

    /**
     * Get data from a {@code record}.
     *
     * <p>These classes are quite a bit more limited than ordinary classes,
     * so we don't have to worry about traversing hierarchy.</p>.
     *
     * @param target containing record
     * @return an instance factory if this class is a record
     */
    @Override
    public <V> InstanceFactory<Object[]> discover(final AnnotatedType target,
            final FieldCollector<Object[], V> collector) throws SerializationException {
        if (CLASS_IS_RECORD != null && CLASS_GET_RECORD_COMPONENTS != null && RECORD_COMPONENT_GET_ANNOTATED_TYPE != null
                && RECORD_COMPONENT_GET_NAME != null && RECORD_COMPONENT_GET_ACCESSOR != null) {
            final Class<?> clazz = erase(target.getType());
            try {
                if ((boolean) CLASS_IS_RECORD.invoke(clazz)) { // clazz.isRecord()
                    final AnnotatedElement[] recordComponents =
                            (AnnotatedElement[]) CLASS_GET_RECORD_COMPONENTS.invoke(clazz); // clazz.getRecordComponents()
                    final Class<?>[] constructorParams = new Class<?>[recordComponents.length];
                    for (int i = 0, recordComponentsLength = recordComponents.length; i < recordComponentsLength; i++) {
                        // each component is itself annotatable, plus attached backing field and accessor method, so we have to get them all
                        final AnnotatedElement component = recordComponents[i];
                        final Method accessor = (Method) RECORD_COMPONENT_GET_ACCESSOR.invoke(component); // component.getAccessor()
                        accessor.setAccessible(true);

                        final String name = (String) RECORD_COMPONENT_GET_NAME.invoke(component); // component.getName()
                        final AnnotatedType genericType = (AnnotatedType) RECORD_COMPONENT_GET_ANNOTATED_TYPE.invoke(component); // .getAnnotatedType
                        constructorParams[i] = erase(genericType.getType()); // to add to the canonical constructor

                        final Field backingField = clazz.getDeclaredField(name);
                        backingField.setAccessible(true);

                        // Then we put everything together: resolve the type, calculate annotations, and submit a field
                        final AnnotatedType resolvedType = resolveExactType(genericType, target);
                        final AnnotatedElement annotationContainer = Types.combinedAnnotations(component, backingField, accessor);
                        final int targetIdx = i;
                        collector.accept(name, resolvedType, annotationContainer,
                            (intermediate, el, implicitSupplier) -> {
                                if (el != null) {
                                    intermediate[targetIdx] = el;
                                } else {
                                    intermediate[targetIdx] = implicitSupplier.get();
                                }
                            }, accessor::invoke);
                    }

                    // canonical constructor, which we'll use to make new instances
                    final Constructor<?> clazzConstructor = clazz.getDeclaredConstructor(constructorParams);
                    clazzConstructor.setAccessible(true);

                    return new InstanceFactory<Object[]>() {
                        @Override
                        public Object[] begin() {
                            return new Object[recordComponents.length];
                        }

                        @Override
                        public Object complete(final Object[] intermediate) throws SerializationException {
                            // Primitive values cannot be null, but we must pass a value for every parameter.
                            for (int i = 0, length = intermediate.length; i < length; ++i) {
                                if (intermediate[i] == null && constructorParams[i].isPrimitive()) {
                                    intermediate[i] = Types.defaultValue(constructorParams[i]);
                                }
                            }

                            try {
                                return clazzConstructor.newInstance(intermediate);
                            } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
                                throw new SerializationException(target.getType(), e);
                            }
                        }

                        @Override
                        public boolean canCreateInstances() {
                            return true;
                        }
                    };
                }
            } catch (final SerializationException ex) {
                throw ex;
            } catch (final Throwable ex) {
                // suppress, we just won't handle as a record
            }
        }
        return null;
    }

}
