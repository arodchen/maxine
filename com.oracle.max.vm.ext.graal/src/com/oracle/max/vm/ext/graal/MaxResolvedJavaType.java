/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.oracle.max.vm.ext.graal;

import static com.oracle.max.vm.ext.graal.MaxGraal.unimplemented;

import java.lang.annotation.*;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;

import com.oracle.graal.api.meta.*;
import com.sun.cri.ci.*;
import com.sun.cri.ri.*;
import com.sun.max.vm.actor.holder.*;
import com.sun.max.vm.actor.member.*;


public class MaxResolvedJavaType extends MaxJavaType implements ResolvedJavaType {

    private static MaxResolvedJavaType objectType;

    protected MaxResolvedJavaType(RiResolvedType riResolvedType) {
        super(riResolvedType);
    }

    public static MaxResolvedJavaType get(RiResolvedType riResolvedType) {
        return (MaxResolvedJavaType) MaxJavaType.get(riResolvedType);
    }

    public static RiResolvedType getRiResolvedType(ResolvedJavaType resolvedJavaType) {
        return (RiResolvedType) MaxJavaType.getRiType(resolvedJavaType);
    }

    private static MaxResolvedJavaType getObject() {
        if (objectType == null) {
            objectType = MaxResolvedJavaType.get(ClassActor.fromJava(Object.class));
        }
        return objectType;
    }

    private RiResolvedType riResolvedType() {
        return (RiResolvedType) riType;
    }

    private static com.sun.cri.ri.RiType.Representation toCiRepresentation(Representation r) {
        // Checkstyle: stop
        switch (r) {
            case JavaClass: return com.sun.cri.ri.RiType.Representation.JavaClass;
            case ObjectHub: return com.sun.cri.ri.RiType.Representation.ObjectHub;
            default: return null;
        }
        // Checkstyle: resume
    }

    @Override
    public Constant getEncoding(Representation r) {
        CiConstant encoding = ((ClassActor) riResolvedType()).getEncoding(toCiRepresentation(r));
        return ConstantMap.toGraal(encoding);
    }

    @Override
    public boolean hasFinalizer() {
        return riResolvedType().hasFinalizer();
    }

    @Override
    public boolean hasFinalizableSubclass() {
        return riResolvedType().hasFinalizableSubclass();
    }

    @Override
    public boolean isInterface() {
        return riResolvedType().isInterface();
    }

    @Override
    public boolean isInstanceClass() {
        return riResolvedType().isInstanceClass();
    }

    @Override
    public boolean isArray() {
        return riResolvedType().isArrayClass();
    }

    @Override
    public boolean isPrimitive() {
        ClassActor ca = (ClassActor) riType;
        return ca.isPrimitiveClassActor();
    }

    @Override
    public int getModifiers() {
        return riResolvedType().accessFlags();
    }

    @Override
    public boolean isInitialized() {
        return riResolvedType().isInitialized();
    }

    @Override
    public void initialize() {
        unimplemented("MaxResolvedType.initialize");
    }

    @Override
    public boolean isAssignableFrom(ResolvedJavaType other) {
        return (MaxResolvedJavaType.getRiResolvedType(other)).isSubtypeOf(riResolvedType());
    }

    @Override
    public boolean isInstance(Constant obj) {
        ClassActor classActor = (ClassActor) riType;
        return classActor.isInstance(obj.asObject());
    }

    @Override
    public ResolvedJavaType asExactType() {
        return MaxResolvedJavaType.get(riResolvedType().exactType());
    }

    @Override
    public ResolvedJavaType getSuperclass() {
        return MaxResolvedJavaType.get(riResolvedType().superType());
    }

    @Override
    public ResolvedJavaType[] getInterfaces() {
        unimplemented("MaxResolvedType.getInterfaces");
        return null;
    }

    @Override
    public ResolvedJavaType findLeastCommonAncestor(ResolvedJavaType otherType) {
        if (otherType.isPrimitive()) {
            return null;
        } else {
            MaxResolvedJavaType t1 = this;
            MaxResolvedJavaType t2 = (MaxResolvedJavaType) otherType;
            while (true) {
                if (t1.isAssignableFrom(t2)) {
                    return t1;
                }
                if (t2.isAssignableFrom(t1)) {
                    return t2;
                }
                t1 = MaxResolvedJavaType.get(t1.riResolvedType().superType());
                t2 = MaxResolvedJavaType.get(t2.riResolvedType().superType());
            }
        }
    }

    @Override
    public ResolvedJavaType findUniqueConcreteSubtype() {
        RiResolvedType riResolvedType = riResolvedType().uniqueConcreteSubtype();
        if (riResolvedType == null) {
            return null;
        } else {
            return MaxResolvedJavaType.get(riResolvedType);
        }

    }

    @Override
    public ResolvedJavaType getArrayClass() {
        return (ResolvedJavaType) super.getArrayClass();
    }

    @Override
    public ResolvedJavaType getComponentType() {
        return (ResolvedJavaType) super.getComponentType();
    }

    @Override
    public ResolvedJavaMethod resolveMethod(ResolvedJavaMethod method) {
        MaxResolvedJavaMethod maxMethod = (MaxResolvedJavaMethod) method;
        if (maxMethod.riMethod instanceof InterfaceMethodActor) {
            ClassActor cma = (ClassActor) riResolvedType();
            if (cma.allVirtualMethodActors().length == 0) {
                // implementation not known
                return null;
            }
        }
        return MaxResolvedJavaMethod.get(riResolvedType().resolveMethodImpl((RiResolvedMethod) maxMethod.riMethod));
    }

    @Override
    public ResolvedJavaMethod findUniqueConcreteMethod(ResolvedJavaMethod method) {
        RiResolvedMethod riMethod = riResolvedType().uniqueConcreteMethod(MaxResolvedJavaMethod.getRiResolvedMethod(method));
        if (riMethod == null) {
            return null;
        } else {
            return MaxResolvedJavaMethod.get(riMethod);
        }

    }

    @Override
    public ResolvedJavaField[] getInstanceFields(boolean includeSuperclasses) {
        RiResolvedType riResolvedType = riResolvedType();
        ResolvedJavaField[] superClassJavaFields = null;
        if (includeSuperclasses) {
            RiResolvedType superType = riResolvedType.superType();
            if (superType != null) {
                superClassJavaFields = MaxResolvedJavaType.get(superType).getInstanceFields(true);
            }
        }
        RiResolvedField[] fields = riResolvedType.declaredFields();
        ResolvedJavaField[] javaFields = new ResolvedJavaField[fields.length + (superClassJavaFields == null ? 0 : superClassJavaFields.length)];
        int x = 0;
        if (superClassJavaFields != null) {
            x = superClassJavaFields.length;
            System.arraycopy(superClassJavaFields, 0, javaFields, 0, x);
        }
        for (int i = 0; i < fields.length; i++) {
            javaFields[x + i] = MaxResolvedJavaField.get(fields[i]);
        }
        return javaFields;
    }

    @Override
    public <T extends Annotation> T getAnnotation(Class<T> annotationClass) {
        unimplemented("MaxResolvedType.getAnnotation");
        return null;
    }

    @Override
    public ResolvedJavaField findInstanceFieldWithOffset(long offset) {
        ClassActor ca = (ClassActor) riType;
        // TODO I don't understand yet how this can happen but evidently UnsafeLoad nodes
        // can show up with MaxUnsafeCastNodes whose nonNull field is true and the node type is a Word type.
        // This causes a search for a field, which, of course, does not exist. Returning null is a workaround.
        if (MaxWordTypeRewriterPhase.isWord(this)) {
            return null;
        }
        return MaxResolvedJavaField.get(ca.findInstanceFieldActor((int) offset));
    }

    @Override
    public String toString() {
        return riType.toString();
    }

    @Override
    public String getSourceFileName() {
        ClassActor ca = (ClassActor) riType;
        return ca.sourceFileName;
    }

    @Override
    public URL getClassFilePath() {
        return null;
    }

    @Override
    public boolean isLocal() {
        return ((ClassActor) riType).toJava().isLocalClass();
    }

    @Override
    public boolean isMember() {
        return ((ClassActor) riType).toJava().isMemberClass();
    }

    @Override
    public ResolvedJavaType getEnclosingType() {
        Class<?> enclosingClass = ((ClassActor) riType).toJava().getEnclosingClass();
        if (enclosingClass == null) {
            return null;
        }
        return MaxResolvedJavaType.get(ClassActor.fromJava(enclosingClass));
    }

    @Override
    public ResolvedJavaMethod[] getDeclaredConstructors() {
        VirtualMethodActor[] v = ((ClassActor) riType).localVirtualMethodActors();
        ArrayList<ResolvedJavaMethod> list = new ArrayList<>();
        int count = 0;
        for (int i = 0; i < v.length; i++) {
            if (v[i].isConstructor()) {
                list.add(MaxResolvedJavaMethod.get(v[i]));
                count++;
            }
        }
        return list.toArray(new ResolvedJavaMethod[count]);
    }

    @Override
    public ResolvedJavaMethod[] getDeclaredMethods() {
        MethodActor[] v = ((ClassActor) riType).getLocalMethodActorsArray();
        ResolvedJavaMethod[] result = new ResolvedJavaMethod[v.length];
        for (int i = 0; i < v.length; i++) {
            result[i] = MaxResolvedJavaMethod.get(v[i]);
        }
        return result;
    }

    @Override
    public Constant newArray(int length) {
        return Constant.forObject(Array.newInstance(((ClassActor) riType).toJava(), length));
    }

}
