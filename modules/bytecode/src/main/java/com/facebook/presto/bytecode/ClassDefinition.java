/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.presto.bytecode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.objectweb.asm.ClassVisitor;

import static com.facebook.presto.bytecode.Access.BRIDGE;
import static com.facebook.presto.bytecode.Access.INTERFACE;
import static com.facebook.presto.bytecode.Access.STATIC;
import static com.facebook.presto.bytecode.Access.SYNTHETIC;
import static com.facebook.presto.bytecode.Access.a;
import static com.facebook.presto.bytecode.Access.toAccessModifier;
import static java.util.Objects.requireNonNull;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.V11;

public class ClassDefinition {
    private final EnumSet<Access> access;
    private final ParameterizedType type;
    private final ParameterizedType superClass;
    private final List<ParameterizedType> interfaces = new ArrayList<>();
    private final List<AnnotationDefinition> annotations = new ArrayList<>();
    private final List<FieldDefinition> fields = new ArrayList<>();
    private final List<MethodDefinition> methods = new ArrayList<>();
    private final MethodDefinition classInitializer;
    private String source;
    private String debug;

    public ClassDefinition(
        EnumSet<Access> access,
        String name,
        ParameterizedType superClass,
        ParameterizedType... interfaces) {
        this(access, new ParameterizedType(name), superClass, interfaces);
    }

    public ClassDefinition(
        EnumSet<Access> access,
        ParameterizedType type,
        ParameterizedType superClass,
        ParameterizedType... interfaces) {
        requireNonNull(access, "access is null");
        requireNonNull(type, "type is null");
        requireNonNull(superClass, "superClass is null");
        requireNonNull(interfaces, "interfaces is null");

        this.access = access;
        this.type = type;
        this.superClass = superClass;
        this.interfaces.addAll(List.of(interfaces));

        classInitializer = new MethodDefinition(this, a(STATIC), "<clinit>", ParameterizedType.type(void.class), List.of());
    }

    public Set<Access> getAccess() {
        return Set.copyOf(access);
    }

    public String getName() {
        return type.getClassName();
    }

    public ParameterizedType getType() {
        return type;
    }

    public ParameterizedType getSuperClass() {
        return superClass;
    }

    public String getSource() {
        return source;
    }

    public List<ParameterizedType> getInterfaces() {
        return List.copyOf(interfaces);
    }

    public List<AnnotationDefinition> getAnnotations() {
        return List.copyOf(annotations);
    }

    public List<FieldDefinition> getFields() {
        return List.copyOf(fields);
    }

    public List<MethodDefinition> getMethods() {
        return List.copyOf(methods);
    }

    public boolean isInterface() {
        return access.contains(INTERFACE);
    }

    public void visit(ClassVisitor visitor) {
        // Generic signature if super class or any interface is generic
        String signature = null;
        if (superClass.isGeneric() || interfaces.stream().anyMatch(ParameterizedType::isGeneric)) {
            signature = genericClassSignature(superClass, interfaces);
        }

        String[] interfaces = new String[this.interfaces.size()];
        for (int i = 0; i < interfaces.length; i++) {
            interfaces[i] = this.interfaces.get(i).getClassName();
        }
        int accessModifier = toAccessModifier(access);
        visitor.visit(V11, isInterface() ? accessModifier : accessModifier | ACC_SUPER, type.getClassName(), signature, superClass.getClassName(), interfaces);

        // visit source
        if (source != null) {
            visitor.visitSource(source, debug);
        }

        // visit annotations
        for (AnnotationDefinition annotation : annotations) {
            annotation.visitClassAnnotation(visitor);
        }

        // visit fields
        for (FieldDefinition field : fields) {
            field.visit(visitor);
        }

        // visit clinit method
        if (!isInterface()) {
            classInitializer.visit(visitor, true);
        }

        // visit methods
        for (MethodDefinition method : methods) {
            method.visit(visitor);
        }

        // done
        visitor.visitEnd();
    }

    public AnnotationDefinition declareAnnotation(Class<?> type) {
        AnnotationDefinition annotationDefinition = new AnnotationDefinition(type);
        annotations.add(annotationDefinition);
        return annotationDefinition;
    }

    public AnnotationDefinition declareAnnotation(ParameterizedType type) {
        AnnotationDefinition annotationDefinition = new AnnotationDefinition(type);
        annotations.add(annotationDefinition);
        return annotationDefinition;
    }

    public FieldDefinition declareField(EnumSet<Access> access, String name, Class<?> type) {
        FieldDefinition fieldDefinition = new FieldDefinition(this, access, name, type);
        fields.add(fieldDefinition);
        return fieldDefinition;
    }

    public ClassDefinition addField(EnumSet<Access> access, String name, Class<?> type) {
        declareField(access, name, type);
        return this;
    }

    public FieldDefinition declareField(EnumSet<Access> access, String name, ParameterizedType type) {
        FieldDefinition fieldDefinition = new FieldDefinition(this, access, name, type);
        fields.add(fieldDefinition);
        return fieldDefinition;
    }

    public ClassDefinition addField(EnumSet<Access> access, String name, ParameterizedType type) {
        declareField(access, name, type);
        return this;
    }

    public ClassDefinition addField(FieldDefinition field) {
        fields.add(field);
        return this;
    }

    public MethodDefinition getClassInitializer() {
        if (isInterface()) {
            throw new IllegalAccessError("Interface does not have class initializer");
        }
        return classInitializer;
    }

    public MethodDefinition declareConstructor(
        EnumSet<Access> access,
        Parameter... parameters) {
        return declareMethod(access, "<init>", ParameterizedType.type(void.class), List.of(parameters));
    }

    public MethodDefinition declareConstructor(
        EnumSet<Access> access,
        Collection<Parameter> parameters) {
        return declareMethod(access, "<init>", ParameterizedType.type(void.class), List.copyOf(parameters));
    }

    public ClassDefinition declareDefaultConstructor(EnumSet<Access> access) {
        MethodDefinition constructor = declareConstructor(access);
        constructor
            .getBody()
            .append(constructor.getThis())
            .invokeConstructor(superClass)
            .ret();
        return this;
    }

    public ClassDefinition addMethod(MethodDefinition method) {
        methods.add(method);
        return this;
    }

    public ClassDefinition visitSource(String source, String debug) {
        this.source = source;
        this.debug = debug;
        return this;
    }

    public MethodDefinition declareMethod(
        EnumSet<Access> access,
        String name,
        ParameterizedType returnType,
        Parameter... parameters) {
        return declareMethod(access, name, returnType, List.of(parameters));
    }

    public MethodDefinition declareMethod(
        EnumSet<Access> access,
        String name,
        ParameterizedType returnType,
        Collection<Parameter> parameters
    ) {
        MethodDefinition methodDefinition = new MethodDefinition(this, access, name, returnType, parameters);

        EnumSet<Access> bridgeAccess = EnumSet.of(SYNTHETIC, BRIDGE);

        for (MethodDefinition method : methods) {
            if (name.equals(method.getName()) && method.getParameterTypes().equals(methodDefinition.getParameterTypes())) {
                boolean curBridge = method.getAccess().containsAll(bridgeAccess);
                boolean newBridge = methodDefinition.getAccess().containsAll(bridgeAccess);

                if ((!curBridge && !newBridge) || method.getReturnType().equals(methodDefinition.getReturnType()))
                    throw new IllegalArgumentException("Method with same name and signature already exists: " + name);
            }
        }
        methods.add(methodDefinition);
        return methodDefinition;
    }

    public static String genericClassSignature(
        ParameterizedType classType,
        ParameterizedType... interfaceTypes) {

        return Stream.concat(Stream.of(classType), Stream.of(interfaceTypes))
            .map(ParameterizedType::toString).collect(Collectors.joining(""));
    }

    public static String genericClassSignature(
        ParameterizedType classType,
        List<ParameterizedType> interfaceTypes) {

        return Stream.concat(Stream.of(classType), interfaceTypes.stream())
            .map(ParameterizedType::toString).collect(Collectors.joining(""));
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("ClassDefinition");
        sb.append("{access=").append(access);
        sb.append(", type=").append(type);
        sb.append('}');
        return sb.toString();
    }
}
