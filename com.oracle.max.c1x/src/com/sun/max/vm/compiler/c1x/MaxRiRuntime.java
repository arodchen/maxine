/*
 * Copyright (c) 2009, 2011, Oracle and/or its affiliates. All rights reserved.
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
package com.sun.max.vm.compiler.c1x;

import static com.sun.cri.bytecode.Bytecodes.*;
import static com.sun.max.platform.Platform.*;
import static com.sun.max.vm.MaxineVM.*;
import static com.sun.max.vm.compiler.target.TargetMethod.Flavor.*;

import java.lang.reflect.*;
import java.util.*;

import com.sun.c1x.*;
import com.sun.c1x.util.*;
import com.sun.cri.ci.*;
import com.sun.cri.ci.CiTargetMethod.Call;
import com.sun.cri.ci.CiTargetMethod.DataPatch;
import com.sun.cri.ci.CiTargetMethod.Site;
import com.sun.cri.ri.*;
import com.sun.max.annotate.*;
import com.sun.max.platform.*;
import com.sun.max.unsafe.*;
import com.sun.max.vm.*;
import com.sun.max.vm.actor.*;
import com.sun.max.vm.actor.holder.*;
import com.sun.max.vm.actor.member.*;
import com.sun.max.vm.bytecode.*;
import com.sun.max.vm.compiler.*;
import com.sun.max.vm.compiler.target.*;
import com.sun.max.vm.hosted.*;
import com.sun.max.vm.runtime.*;
import com.sun.max.vm.value.*;

/**
 * The {@code MaxRiRuntime} class implements the runtime interface needed by C1X.
 * This includes access to runtime features such as class and method representations,
 * constant pools, as well as some compiler tuning.
 *
 * @author Ben L. Titzer
 */
public class MaxRiRuntime implements RiRuntime {

    private RiSnippets snippets;

    private static MaxRiRuntime instance = new MaxRiRuntime();
    
    public static MaxRiRuntime getInstance() {
    	return instance;
    }
    
    private MaxRiRuntime() {
    }

    /**
     * Gets the constant pool for a specified method.
     * @param method the compiler interface method
     * @return the compiler interface constant pool for the specified method
     */
    public RiConstantPool getConstantPool(RiMethod method) {
        return asClassMethodActor(method, "getConstantPool()").compilee().codeAttribute().cp;
    }

    /**
     * Gets the OSR frame for a particular method at a particular bytecode index.
     * @param method the compiler interface method
     * @param bci the bytecode index
     * @return the OSR frame
     */
    public RiOsrFrame getOsrFrame(RiMethod method, int bci) {
        throw FatalError.unimplemented();
    }

    /**
     * Checks whether the runtime requires inlining of the specified method.
     * @param method the method to inline
     * @return {@code true} if the method must be inlined; {@code false}
     * to allow the compiler to use its own heuristics
     */
    public boolean mustInline(RiMethod method) {
        if (!method.isResolved()) {
            return false;
        }

        ClassMethodActor methodActor = asClassMethodActor(method, "mustNotInline()");
        if (methodActor.isInline()) {
            return true;
        }

        // Remove the indirection through the "access$..." methods generated by the Java
        // source compiler for the purpose of implementing access to private fields between
        // in an inner-class relationship.
        if (MaxineVM.isHosted() && methodActor.isSynthetic() && methodActor.isStatic() && methodActor.name().startsWith("access$")) {
            return true;
        }

        return methodActor.isInline();
    }

    /**
     * Checks whether the runtime forbids inlining of the specified method.
     * @param method the method to inline
     * @return {@code true} if the runtime forbids inlining of the specified method;
     * {@code false} to allow the compiler to use its own heuristics
     */
    public boolean mustNotInline(RiMethod method) {
        if (!method.isResolved()) {
            return false;
        }
        final ClassMethodActor classMethodActor = asClassMethodActor(method, "mustNotInline()");
        return classMethodActor.codeAttribute() == null || classMethodActor.isNeverInline();
    }

    /**
     * Checks whether the runtime forbids compilation of the specified method.
     * @param method the method to compile
     * @return {@code true} if the runtime forbids compilation of the specified method;
     * {@code false} to allow the compiler to compile the method
     */
    public boolean mustNotCompile(RiMethod method) {
        return false;
    }

    static ClassMethodActor asClassMethodActor(RiMethod method, String operation) {
        if (method instanceof ClassMethodActor) {
            return (ClassMethodActor) method;
        }
        throw new CiUnresolvedException("invalid RiMethod instance: " + method.getClass());
    }

    public int basicObjectLockOffsetInBytes() {
        // Must not be called if the size of the lock object is 0.
        throw Util.shouldNotReachHere();
    }

    public int sizeOfBasicObjectLock() {
        // locks are not placed on the stack
        return 0;
    }

    public int codeOffset() {
        return CallEntryPoint.OPTIMIZED_ENTRY_POINT.offset();
    }

    @Override
    public String disassemble(RiMethod method) {
        ClassMethodActor classMethodActor = asClassMethodActor(method, "disassemble()");
        return classMethodActor.format("%f %R %H.%n(%P)") + String.format("%n%s", CodeAttributePrinter.toString(classMethodActor.codeAttribute()));
    }

    public String disassemble(byte[] code, long address) {
        final Platform platform = Platform.platform();
        CiHexCodeFile hcf = new CiHexCodeFile(code, address, platform.isa.name(), platform.wordWidth().numberOfBits);
        if (isHosted()) {
            return new HexCodeFileDis(false).process(hcf, null);
        }
        return hcf.toEmbeddedString();
    }

    @Override
    public String disassemble(final CiTargetMethod targetMethod) {
        byte[] code = Arrays.copyOf(targetMethod.targetCode(), targetMethod.targetCodeSize());
        final Platform platform = Platform.platform();

        CiHexCodeFile hcf = new CiHexCodeFile(code, 0L, platform.isa.name(), platform.wordWidth().numberOfBits);
        CiUtil.addAnnotations(hcf, targetMethod.annotations());
        int spillSlotSize = target().spillSlotSize;
        CiArchitecture arch = target().arch;
        for (Call site : targetMethod.directCalls) {
            if (site.debugInfo() != null) {
                hcf.addComment(site.pcOffset, CiUtil.append(new StringBuilder(100), site.debugInfo, arch, spillSlotSize).toString());
            }
            hcf.addOperandComment(site.pcOffset, calleeString(site));
        }
        for (Call site : targetMethod.indirectCalls) {
            if (site.debugInfo() != null) {
                hcf.addComment(site.pcOffset, CiUtil.append(new StringBuilder(100), site.debugInfo, arch, spillSlotSize).toString());
            }
            hcf.addOperandComment(site.pcOffset, calleeString(site));
        }
        for (Site site : targetMethod.safepoints) {
            if (site.debugInfo() != null) {
                hcf.addComment(site.pcOffset, CiUtil.append(new StringBuilder(100), site.debugInfo(), arch, spillSlotSize).toString());
            }
            hcf.addOperandComment(site.pcOffset, "{safepoint}");
        }
        for (DataPatch site : targetMethod.dataReferences) {
            hcf.addOperandComment(site.pcOffset, "{" + site.constant + "}");
        }

        if (isHosted()) {
            return new HexCodeFileDis(false).process(hcf, null);
        }
        return hcf.toEmbeddedString();
    }

    private static String calleeString(Call call) {
        if (call.runtimeCall != null) {
            return "{" + call.runtimeCall.name() + "}";
        } else if (call.symbol != null) {
            return "{" + call.symbol + "}";
        } else if (call.globalStubID != null) {
            return "{" + call.globalStubID + "}";
        } else if (call.method != null) {
            return "{" + call.method + "}";
        } else {
            return "{<template_call>}";
        }
    }

    static class CachedInvocation {
        public CachedInvocation(Value[] args) {
            this.args = args;
        }
        final Value[] args;
        CiConstant result;
    }

    /**
     * Cache to speed up compile-time folding. This works as an invocation of a {@linkplain FOLD foldable}
     * method is guaranteed to be idempotent with respect its arguments.
     */
    private final HashMap<MethodActor, CachedInvocation> cache = new HashMap<MethodActor, CachedInvocation>();

    @Override
    public CiConstant invoke(RiMethod method, CiMethodInvokeArguments args) {
        if (C1XOptions.CanonicalizeFoldableMethods && method.isResolved()) {
            MethodActor methodActor = (MethodActor) method;
            if (Actor.isDeclaredFoldable(methodActor.flags())) {
                Value[] values;
                int length = methodActor.descriptor().argumentCount(!methodActor.isStatic());
                if (length == 0) {
                    values = Value.NONE;
                } else {
                    values = new Value[length];
                    for (int i = 0; i < length; ++i) {
                        CiConstant arg = args.nextArg();
                        if (arg == null) {
                            return null;
                        }
                        Value value;
                        // Checkstyle: stop
                        switch (arg.kind) {
                            case Boolean: value = BooleanValue.from(arg.asBoolean()); break;
                            case Byte:    value = ByteValue.from((byte) arg.asInt()); break;
                            case Char:    value = CharValue.from((char) arg.asInt()); break;
                            case Double:  value = DoubleValue.from(arg.asDouble()); break;
                            case Float:   value = FloatValue.from(arg.asFloat()); break;
                            case Int:     value = IntValue.from(arg.asInt()); break;
                            case Long:    value = LongValue.from(arg.asLong()); break;
                            case Object:  value = ReferenceValue.from(arg.asObject()); break;
                            case Short:   value = ShortValue.from((short) arg.asInt()); break;
                            case Word:    value = WordValue.from(Address.fromLong(arg.asLong())); break;
                            default: throw new IllegalArgumentException();
                        }
                        // Checkstyle: resume
                        values[i] = value;
                    }
                }

                CachedInvocation cachedInvocation = null;
                if (!isHosted()) {
                    synchronized (cache) {
                        cachedInvocation = cache.get(methodActor);
                        if (cachedInvocation != null) {
                            if (Arrays.equals(values, cachedInvocation.args)) {
                                return cachedInvocation.result;
                            }
                        } else {
                            cachedInvocation = new CachedInvocation(values);
                            cache.put(methodActor, cachedInvocation);
                        }
                    }
                }

                try {
                    // attempt to invoke the method
                    CiConstant result = methodActor.invoke(values).asCiConstant();
                    // set the result of this instruction to be the result of invocation
                    C1XMetrics.MethodsFolded++;

                    if (!isHosted()) {
                        cachedInvocation.result = result;
                    }

                    return result;
                    // note that for void, we will have a void constant with value null
                } catch (IllegalAccessException e) {
                    // folding failed; too bad
                } catch (InvocationTargetException e) {
                    // folding failed; too bad
                } catch (ExceptionInInitializerError e) {
                    // folding failed; too bad
                }
                return null;
            }
        }
        return null;
    }

    public CiConstant foldWordOperation(int opcode, CiMethodInvokeArguments args) {
        CiConstant x = args.nextArg();
        CiConstant y = args.nextArg();
        switch (opcode) {
            case WDIV:
                return CiConstant.forWord(Address.fromLong(x.asLong()).dividedBy(Address.fromLong(y.asLong())).toLong());
            case WDIVI:
                return CiConstant.forWord(Address.fromLong(x.asLong()).dividedBy(y.asInt()).toLong());
            case WREM:
                return CiConstant.forWord(Address.fromLong(x.asLong()).remainder(Address.fromLong(y.asLong())).toLong());
            case WREMI:
                return CiConstant.forInt(Address.fromLong(x.asLong()).remainder(y.asInt()));
        }
        return null;
    }

    public Object registerGlobalStub(CiTargetMethod ciTargetMethod, String name) {
        return new C1XTargetMethod(GlobalStub, name, ciTargetMethod);
    }

    public RiType getRiType(Class<?> javaClass) {
        return ClassActor.fromJava(javaClass);
    }

    public RiType asRiType(CiKind kind) {
        return getRiType(kind.toJavaClass());
    }

    public RiType getTypeOf(CiConstant constant) {
        if (constant.kind.isObject()) {
            Object o = constant.asObject();
            if (o != null) {
                return ClassActor.fromJava(o.getClass());
            }
        }
        return null;
    }

    public Object asJavaObject(CiConstant c) {
        return c.asObject();
    }

    public Class<?> asJavaClass(CiConstant c) {
        Object o = c.asObject();
        if (o instanceof Class) {
            return (Class) o;
        }
        return null;
    }

    public boolean isExceptionType(RiType type) {
        return type.isSubtypeOf(getRiType(Throwable.class));
    }

    public RiMethod getRiMethod(Method method) {
        return MethodActor.fromJava(method);
    }

    public RiMethod getRiMethod(Constructor< ? > constructor) {
        return MethodActor.fromJavaConstructor(constructor);
    }

    public RiField getRiField(Field field) {
        return FieldActor.fromJava(field);
    }

    public RiSnippets getSnippets() {
        if (snippets == null) {
            snippets = new MaxRiSnippets(this);
        }
        return snippets;
    }

    public boolean areConstantObjectsEqual(CiConstant x, CiConstant y) {
        assert x.kind.isObject() && y.kind.isObject();
        return x.asObject() == y.asObject();
    }

    public RiRegisterConfig getRegisterConfig(RiMethod method) {
        return vm().registerConfigs.getRegisterConfig((ClassMethodActor) method);
    }

    @Override
    public int getCustomStackAreaSize() {
        return 0;
    }

    @Override
    public boolean supportsArrayIntrinsics() {
        return false;
    }

    @Override
    public int getArrayLength(CiConstant array) {
        return Array.getLength(array.asObject());
    }
}
