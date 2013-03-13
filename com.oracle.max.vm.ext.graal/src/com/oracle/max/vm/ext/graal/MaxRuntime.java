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
import static com.sun.max.vm.MaxineVM.*;

import java.lang.reflect.*;
import java.util.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.code.CodeUtil.RefMapFormatter;
import com.oracle.graal.api.code.RuntimeCallTarget.Descriptor;
import com.oracle.graal.api.code.CompilationResult.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.java.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.printer.*;
import com.oracle.graal.snippets.*;
import com.oracle.max.vm.ext.graal.snippets.*;
import com.sun.max.program.*;
import com.sun.max.vm.*;
import com.sun.max.vm.actor.holder.*;
import com.sun.max.vm.actor.member.*;



public class MaxRuntime implements GraalCodeCacheProvider {

    private final Map<Class< ? extends Node>, LoweringProvider> lowerings = new HashMap<>();

    MaxTargetDescription maxTargetDescription;

    MaxRuntime(MaxTargetDescription maxTargetDescription) {
        this.maxTargetDescription = maxTargetDescription;
    }

    @Override
    public InstalledCode addMethod(ResolvedJavaMethod method, CompilationResult compResult, CodeInfo[] info) {
        unimplemented("addMethod");
        return null;
    }

    @Override
    public int getSizeOfLockData() {
        unimplemented("getSizeOfLockData");
        return 0;
    }

    @Override
    public String disassemble(CodeInfo info, CompilationResult tm) {
        byte[] code = info.getCode();
        HexCodeFile hcf = new HexCodeFile(code, info.getStart(), maxTargetDescription.arch.getName(), maxTargetDescription.wordSize * 8);
        if (tm != null) {
            HexCodeFile.addAnnotations(hcf, tm.getAnnotations());
            addExceptionHandlersComment(tm, hcf);
            Register fp = RegisterMap.toGraal(vm().registerConfigs.standard.frame);
            RefMapFormatter slotFormatter = new RefMapFormatter(maxTargetDescription.arch, maxTargetDescription.wordSize, fp, 0);
            for (Safepoint safepoint : tm.getSafepoints()) {
                if (safepoint instanceof Call) {
                    Call call = (Call) safepoint;
                    if (call.debugInfo != null) {
                        hcf.addComment(call.pcOffset + call.size, CodeUtil.append(new StringBuilder(100), call.debugInfo, slotFormatter).toString());
                    }
                } else {
                    if (safepoint.debugInfo != null) {
                        hcf.addComment(safepoint.pcOffset, CodeUtil.append(new StringBuilder(100), safepoint.debugInfo, slotFormatter).toString());
                    }
                    addOperandComment(hcf, safepoint.pcOffset, "{safepoint}");
                }
            }
            for (DataPatch site : tm.getDataReferences()) {
                hcf.addOperandComment(site.pcOffset, "{" + site.constant + "}");
            }
        }
        return hcf.toEmbeddedString();
    }

    private static void addExceptionHandlersComment(CompilationResult tm, HexCodeFile hcf) {
        if (!tm.getExceptionHandlers().isEmpty()) {
            String nl = HexCodeFile.NEW_LINE;
            StringBuilder buf = new StringBuilder("------ Exception Handlers ------").append(nl);
            for (CompilationResult.ExceptionHandler e : tm.getExceptionHandlers()) {
                buf.append("    ").append(e.pcOffset).append(" -> ").append(e.handlerPos).append(nl);
                hcf.addComment(e.pcOffset, "[exception -> " + e.handlerPos + "]");
                hcf.addComment(e.handlerPos, "[exception handler for " + e.pcOffset + "]");
            }
            hcf.addComment(0, buf.toString());
        }
    }

    private static void addOperandComment(HexCodeFile hcf, int pos, String comment) {
        String oldValue = hcf.addOperandComment(pos, comment);
        assert oldValue == null : "multiple comments for operand of instruction at " + pos + ": " + comment + ", " + oldValue;
    }

    @Override
    public RegisterConfig lookupRegisterConfig() {
        // TODO this method used to take a ResolvedJavaMethod as an argument
        // Maxine has different register configs for VM_ENTRY_POINT methods and "standard" methods,
        // which will need to be adderess when Graal is nused for the boot image.
        //return MaxRegisterConfig.get(vm().registerConfigs.getRegisterConfig((ClassMethodActor) MaxJavaMethod.getRiMethod(method)));
        return MaxRegisterConfig.get(vm().registerConfigs.standard);
    }

    @Override
    public int getCustomStackAreaSize() {
        return 0;
    }

    @Override
    public int getMinimumOutgoingSize() {
        return 0;
    }

    @Override
    public RuntimeCallTarget lookupRuntimeCall(Descriptor descriptor) {
        return MaxRuntimeCallsMap.get(descriptor);
    }

    @Override
    public int encodeDeoptActionAndReason(DeoptimizationAction action, DeoptimizationReason reason) {
        unimplemented("encodeDeoptActionAndReason");
        return 0;
    }

    @Override
    public boolean needsDataPatch(Constant constant) {
        // TODO when might this be true?
        return false;
    }

    @Override
    public ResolvedJavaType lookupJavaType(Class< ? > clazz) {
        return MaxResolvedJavaType.get(ClassActor.fromJava(clazz));
    }

    @Override
    public ResolvedJavaMethod lookupJavaMethod(Method reflectionMethod) {
        return MaxResolvedJavaMethod.get(MethodActor.fromJava(reflectionMethod));
    }

    @Override
    public ResolvedJavaField lookupJavaField(Field reflectionField) {
        return MaxResolvedJavaField.get(FieldActor.fromJava(reflectionField));
    }

    @Override
    public ResolvedJavaType lookupJavaType(Constant constant) {
        if (constant.getKind() == Kind.Object) {
            Object o = constant.asObject();
            if (o != null) {
                // When running isHosted, StaticTuple is an issue as it is HOSTED_ONLY
                // so shows up as null, which causes an assertion failure later
                if (MaxineVM.isHosted()) {
                    if (o.getClass() == StaticTuple.class) {
                        return MaxResolvedJavaType.get(ClassActor.fromJava(Object.class));
                    }
                }
                return MaxResolvedJavaType.get(ClassActor.fromJava(o.getClass()));
            }
        }
        return null;
    }

    @Override
    public boolean constantEquals(Constant x, Constant y) {
        assert x.getKind() == Kind.Object && y.getKind() == Kind.Object;
        return x.asObject() == y.asObject();
    }

    @Override
    public ResolvedJavaMethod lookupJavaConstructor(Constructor reflectionConstructor) {
        unimplemented("lookupJavaConstructor");
        return null;
    }

    @Override
    public int lookupArrayLength(Constant array) {
        return Array.getLength(array.asObject());
    }

    @SuppressWarnings("unchecked")
    @Override
    public void lower(Node n, LoweringTool tool) {
        LoweringProvider lowering = lowerings.get(n.getClass());
        ProgramError.check(lowering != null, "missing lowering for node: " + n.getClass().getSimpleName());
        lowering.lower(n, tool);
    }

    static class MaxSnippetGraphBuilderConfiguration extends GraphBuilderConfiguration {

        boolean ignoreHostOnlyError;

        public MaxSnippetGraphBuilderConfiguration() {
            super(true, true);
        }

        @Override
        public boolean unresolvedIsError() {
            // This prevents an assertion error in GraphBuilderPhase when we return an unresolved field
            return false;
        }

    }

    public void init() {
        // Snippets cannot have optimistic assumptions.
        Assumptions assumptions = new Assumptions(false);
        SnippetInstaller installer = new SnippetInstaller(this, assumptions, maxTargetDescription);
        GraalIntrinsics.installIntrinsics(installer);
        MaxIntrinsics.initialize(this, maxTargetDescription);
        MaxRuntimeCallsMap.initialize(this);
        MaxSnippetGraphBuilderConfiguration maxSnippetGraphBuilderConfiguration = new MaxSnippetGraphBuilderConfiguration();
        MaxConstantPool.setGraphBuilderConfig(maxSnippetGraphBuilderConfiguration);
        SnippetInstaller maxInstaller = new MaxSnippetInstaller(this, assumptions, maxTargetDescription,
                        maxSnippetGraphBuilderConfiguration);
        maxInstaller.installSnippets(TestSnippets.class);
        maxInstaller.installSnippets(NewSnippets.class);
        maxInstaller.installSnippets(FieldSnippets.class);
        maxInstaller.installSnippets(ArraySnippets.class);
        maxInstaller.installSnippets(TypeSnippets.class);
        maxInstaller.installSnippets(MaxInvokeLowerings.class);
        maxInstaller.installSnippets(ArithmeticSnippets.class);
        MaxConstantPool.setGraphBuilderConfig(null);

        MaxUnsafeAccessLowerings.registerLowerings(lowerings);
        MaxInvokeLowerings.registerLowerings(VMConfiguration.activeConfig(), maxTargetDescription, this, assumptions, lowerings);
        NewSnippets.registerLowerings(VMConfiguration.activeConfig(), maxTargetDescription, this, assumptions, lowerings);
        FieldSnippets.registerLowerings(VMConfiguration.activeConfig(), maxTargetDescription, this, assumptions, lowerings);
        ArraySnippets.registerLowerings(VMConfiguration.activeConfig(), maxTargetDescription, this, assumptions, lowerings);
        TypeSnippets.registerLowerings(VMConfiguration.activeConfig(), maxTargetDescription, this, assumptions, lowerings);
        ArithmeticSnippets.registerLowerings(VMConfiguration.activeConfig(), maxTargetDescription, this, assumptions, lowerings);
        TestSnippets.registerLowerings(VMConfiguration.activeConfig(), maxTargetDescription, this, assumptions, lowerings);
    }

    @Override
    public Signature parseMethodDescriptor(String methodDescriptor) {
        MaxGraal.unimplemented("parseMethodDescriptor");
        return null;
    }

    @Override
    public TargetDescription getTarget() {
        return maxTargetDescription;
    }

    @Override
    public Constant readUnsafeConstant(Kind kind, Object base, long displacement) {
        // TODO
        MaxGraal.unimplemented("MaxRuntime.readUnsafeConstant");
        return null;
    }


}
