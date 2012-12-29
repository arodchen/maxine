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

import static com.oracle.graal.amd64.AMD64.*;
import static com.oracle.max.vm.ext.graal.MaxGraal.unimplemented;

import java.io.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.code.CompilationResult.Mark;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.asm.*;
import com.oracle.graal.asm.amd64.*;
import com.oracle.graal.compiler.gen.*;
import com.oracle.graal.compiler.amd64.*;
import com.oracle.graal.compiler.target.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.asm.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.phases.*;
import com.sun.max.vm.actor.member.*;
import com.sun.max.vm.compiler.*;
import com.sun.max.vm.compiler.target.*;


public class MaxAMD64Backend extends Backend {

    public static final String MARK_PROLOGUE_PUSHED_RBP = "PROLOGUE_PUSHED_RBP";
    public static final String MARK_PROLOGUE_SAVED_RSP = "PROLOGUE_SAVED_RSP";
    public static final String MARK_PROLOGUE_SAVED_REGS = "PROLOGUE_SAVED_REGS";
    public static final String MARK_EPILOGUE_START = "EPILOGUE_START";
    public static final String MARK_EPILOGUE_INCD_RSP = "EPILOGUE_INCD_RSP";
    public static final String MARK_EPILOGUE_POPPED_RBP = "EPILOGUE_POPPED_RBP";

    protected static class MaxAMD64LIRGenerator extends AMD64LIRGenerator {
        public MaxAMD64LIRGenerator(StructuredGraph graph, CodeCacheProvider runtime, TargetDescription target, FrameMap frameMap, ResolvedJavaMethod method, LIR lir) {
            super(graph, runtime, target, frameMap, method, lir);
        }

        @Override
        public void visitSafepointNode(SafepointNode i) {
            // No safepoints yet.
        }

        @Override
        public void visitExceptionObject(ExceptionObjectNode i) {
            unimplemented("MaxAMD64LIRGenerator.visitExceptionObject");
        }

        @Override
        public void visitBreakpointNode(BreakpointNode i) {
            unimplemented("MaxAMD64LIRGenerator.visitBreakpointNode");
        }

    }

    protected static class MaxAMD64FrameContext implements FrameContext {

        private ClassMethodActor callee;

        @Override
        public void enter(TargetMethodAssembler tasm) {
            if (callee.isTemplate()) {
                return;
            }

            FrameMap frameMap = tasm.frameMap;
            int frameSize = frameMap.frameSize();

            AMD64MacroAssembler asm = (AMD64MacroAssembler) tasm.asm;

            AdapterGenerator generator = AdapterGenerator.forCallee(callee, CallEntryPoint.OPTIMIZED_ENTRY_POINT);
            if (generator != null) {
                ByteArrayOutputStream os = new ByteArrayOutputStream(8);
                generator.adapt(callee, os);
                byte[] bytes = os.toByteArray();
                for (byte b : bytes) {
                    asm.codeBuffer.emitByte(b & 0xFF);
                }
            }

            // PushFrame
            asm.decrementq(rsp, frameSize);
            CalleeSaveLayout csl = frameMap.registerConfig.getCalleeSaveLayout();
            if (csl != null && csl.size != 0) {
                int frameToCSA = frameMap.offsetToCalleeSaveArea();
                assert frameToCSA >= 0;
                asm.save(csl, frameToCSA);
            }

            if (!callee.isVmEntryPoint()) {
                int lastFramePage = frameSize / tasm.target.pageSize;
                // emit multiple stack bangs for methods with frames larger than a page
                for (int i = 0; i <= lastFramePage; i++) {
                    int offset = (i + GraalOptions.StackShadowPages) * tasm.target.pageSize;
                    // Deduct 'frameSize' to handle frames larger than the shadow
                    bangStackWithOffset(tasm, asm, offset - frameSize);
                }
            }

        }

        /**
         * @param offset the offset RSP at which to bang. Note that this offset is relative to RSP after RSP has been
         *            adjusted to allocated the frame for the method. It denotes an offset "down" the stack.
         *            For very large frames, this means that the offset may actually be negative (i.e. denoting
         *            a slot "up" the stack above RSP).
         */
        private static void bangStackWithOffset(TargetMethodAssembler tasm, AMD64MacroAssembler masm, int offset) {
            masm.movq(new Address(tasm.target.wordKind, rsp.asValue(), -offset), rax);
        }


        @Override
        public void leave(TargetMethodAssembler tasm) {
            if (callee.isTemplate()) {
                return;
            }

            int frameSize = tasm.frameMap.frameSize();
            AMD64MacroAssembler asm = (AMD64MacroAssembler) tasm.asm;

            // PopFrame
            CalleeSaveLayout csl = tasm.frameMap.registerConfig.getCalleeSaveLayout();

            if (csl != null && csl.size != 0) {
                tasm.targetMethod.setRegisterRestoreEpilogueOffset(asm.codeBuffer.position());
                // saved all registers, restore all registers
                int frameToCSA = tasm.frameMap.offsetToCalleeSaveArea();
                asm.restore(csl, frameToCSA);
            }

            asm.incrementq(rsp, frameSize);
        }

    }

    MaxAMD64Backend(CodeCacheProvider runtime, TargetDescription target) {
        super(runtime, target);
    }

    @Override
    public LIRGenerator newLIRGenerator(StructuredGraph graph, FrameMap frameMap, ResolvedJavaMethod method, LIR lir) {
        return new MaxAMD64LIRGenerator(graph, runtime(), target, frameMap, method, lir);
    }

    @Override
    public TargetMethodAssembler newAssembler(FrameMap frameMap, LIR lir) {
        AbstractAssembler masm = new AMD64MacroAssembler(target, frameMap.registerConfig);
        FrameContext frameContext = new MaxAMD64FrameContext();
        TargetMethodAssembler tasm = new TargetMethodAssembler(target, runtime(), frameMap, masm, frameContext, lir.stubs);
        tasm.setFrameSize(frameMap.frameSize());
        tasm.targetMethod.setCustomStackAreaOffset(frameMap.offsetToCustomArea());
        return tasm;
    }

    @Override
    public void emitCode(TargetMethodAssembler tasm, ResolvedJavaMethod method, LIR lir) {
        MaxAMD64FrameContext maxFrameContext = (MaxAMD64FrameContext) tasm.frameContext;
        maxFrameContext.callee = (ClassMethodActor) MaxResolvedJavaMethod.get(method);
        lir.emitCode(tasm);
    }

}
