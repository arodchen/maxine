/*
 * Copyright (c) 2009 Sun Microsystems, Inc.  All rights reserved.
 *
 * Sun Microsystems, Inc. has intellectual property rights relating to technology embodied in the product
 * that is described in this document. In particular, and without limitation, these intellectual property
 * rights may include one or more of the U.S. patents listed at http://www.sun.com/patents and one or
 * more additional patents or pending patent applications in the U.S. and in other countries.
 *
 * U.S. Government Rights - Commercial software. Government users are subject to the Sun
 * Microsystems, Inc. standard license agreement and applicable provisions of the FAR and its
 * supplements.
 *
 * Use is subject to license terms. Sun, Sun Microsystems, the Sun logo, Java and Solaris are trademarks or
 * registered trademarks of Sun Microsystems, Inc. in the U.S. and other countries. All SPARC trademarks
 * are used under license and are trademarks or registered trademarks of SPARC International, Inc. in the
 * U.S. and other countries.
 *
 * UNIX is a registered trademark in the U.S. and other countries, exclusively licensed through X/Open
 * Company, Ltd.
 */
package com.sun.c1x.lir;

import static com.sun.c1x.lir.LIROperand.*;

import java.io.*;
import java.util.*;

import com.sun.c1x.*;
import com.sun.c1x.asm.*;
import com.sun.c1x.ci.*;
import com.sun.c1x.debug.*;
import com.sun.c1x.ir.*;
import com.sun.c1x.lir.FrameMap.*;
import com.sun.c1x.lir.LIRCall.*;
import com.sun.c1x.ri.*;
import com.sun.c1x.stub.*;
import com.sun.c1x.util.*;
import com.sun.c1x.value.*;

/**
 * The {@code LIRAssembler} class definition.
 *
 * @author Marcelo Cintra
 * @author Thomas Wuerthinger
 * @author Ben L. Titzer
 */
public abstract class LIRAssembler {

    public final C1XCompilation compilation;
    public final AbstractAssembler asm;
    public final FrameMap frameMap;

    protected final List<LocalStub> localStubs;
    protected final List<SlowPath> xirSlowPath;
    protected final List<BlockBegin> branchTargetBlocks;

    private int lastDecodeStart;

    protected static class SlowPath {
        public final LIRXirInstruction instruction;
        public final Label[] labels;

        public SlowPath(LIRXirInstruction instruction, Label[] labels) {
            this.instruction = instruction;
            this.labels = labels;
        }
    }

    public LIRAssembler(C1XCompilation compilation) {
        this.compilation = compilation;
        this.asm = compilation.masm();
        this.frameMap = compilation.frameMap();
        this.localStubs = new ArrayList<LocalStub>();
        this.branchTargetBlocks = new ArrayList<BlockBegin>();
        this.xirSlowPath = new ArrayList<SlowPath>();
    }

    protected RiMethod method() {
        return compilation.method;
    }

    protected void addCodeStub(LocalStub stub) {
        assert stub != null;
        localStubs.add(stub);
    }

    protected void addSlowPath(SlowPath sp) {
        xirSlowPath.add(sp);
    }

    public void emitLocalStubs() {
        for (LocalStub s : localStubs) {
            emitCode(s);
            assert s.assertNoUnboundLabels();
        }

        for (SlowPath sp : xirSlowPath) {
            emitSlowPath(sp);
        }

        // No more code may be emitted after this point
    }

    protected int codePos() {
        return asm.codeBuffer.position();
    }

    public void emitExceptionEntries() {
        if (asm.exceptionInfoList.size() == 0) {
            return;
        }
        for (ExceptionInfo ilist : asm.exceptionInfoList) {
            List<ExceptionHandler> handlers = ilist.exceptionHandlers;

            for (ExceptionHandler handler : handlers) {
                assert handler.lirOpId() != -1 : "handler not processed by LinearScan";
                assert handler.entryCode() == null || handler.entryCode().instructionsList().get(handler.entryCode().instructionsList().size() - 1).code == LIROpcode.Branch : "last operation must be branch";

                if (handler.entryCodeOffset() == -1) {
                    // entry code not emitted yet
                    if (handler.entryCode() != null && handler.entryCode().instructionsList().size() > 1) {
                        handler.setEntryCodeOffset(codePos());
                        if (C1XOptions.CommentedAssembly) {
                            asm.blockComment("Exception adapter block");
                        }
                        emitLirList(handler.entryCode());
                    } else {
                        handler.setEntryCodeOffset(handler.entryBlock().exceptionHandlerPco());
                    }

                    assert handler.entryCodeOffset() != -1 : "must be set now";
                }
            }
        }
    }

    public void emitCode(List<BlockBegin> hir) {
        if (C1XOptions.PrintLIR) {
            LIRList.printLIR(hir);
        }

        for (BlockBegin b : hir) {
            emitBlock(b);
        }

        assert checkNoUnboundLabels();
    }

    void emitBlock(BlockBegin block) {
        if (block.checkBlockFlag(BlockBegin.BlockFlag.BackwardBranchTarget)) {
            emitAlignment();
        }

        // if this block is the start of an exception handler, record the
        // PC offset of the first instruction for later construction of
        // the ExceptionHandlerTable
        if (block.checkBlockFlag(BlockBegin.BlockFlag.ExceptionEntry)) {
            block.setExceptionHandlerPco(codePos());
        }

        if (C1XOptions.PrintLIRWithAssembly) {
            // don't print Phi's
            InstructionPrinter ip = new InstructionPrinter(TTY.out(), false, compilation.target);
            ip.printBlock(block);
        }

        assert block.lir() != null : "must have LIR";
        if (C1XOptions.CommentedAssembly) {
            String st = String.format(" block B%d [%d, %d]", block.blockID, block.bci(), block.end().bci());
            asm.blockComment(st);
        }

        emitLirList(block.lir());
    }

    void emitLirList(LIRList list) {
        doPeephole(list);

        for (LIRInstruction op : list.instructionsList()) {
            if (C1XOptions.CommentedAssembly) {
                // Only print out branches
                if (op.code == LIROpcode.Branch) {
                    ByteArrayOutputStream st = new ByteArrayOutputStream();
                    LogStream ls = new LogStream(st);
                    op.printOn(ls);
                    ls.flush();
                    asm.blockComment(st.toString());
                }
            }
            if (C1XOptions.PrintLIRWithAssembly) {
                // print out the LIR operation followed by the resulting assembly
                op.printOn(TTY.out());
                TTY.println();
            }

            op.emitCode(this);

            if (C1XOptions.PrintLIRWithAssembly) {
                printAssembly(asm);
            }
        }
    }

    private void printAssembly(AbstractAssembler asm) {
        byte[] currentBytes = asm.codeBuffer.getData(lastDecodeStart, asm.codeBuffer.position());
        Util.printBytes("Code Part", currentBytes, C1XOptions.PrintAssemblyBytesPerLine);
        if (currentBytes.length > 0) {
            TTY.println(compilation.runtime.disassemble(currentBytes));
        }
        lastDecodeStart = asm.codeBuffer.position();
    }

    boolean checkNoUnboundLabels() {
        for (int i = 0; i < branchTargetBlocks.size() - 1; i++) {
            if (!branchTargetBlocks.get(i).label().isBound()) {
                TTY.println(String.format("label of block B%d is not bound", branchTargetBlocks.get(i).blockID));
                assert false : "unbound label";
            }
        }

        return true;
    }

    static FrameState stateBefore(Value ins) {
        if (ins instanceof Instruction) {
            return ((Instruction) ins).stateBefore();
        }
        return null;
    }

    void emitCall(LIRCall op) {
        verifyOopMap(op.info);

        switch (op.code) {
            case DirectCall:
                emitDirectCall(op.target, op.info);
                break;
            case IndirectCall:
                emitIndirectCall(op.target, op.info, op.lastArgument());
                break;
            case NativeCall:
                emitNativeCall(op.nativeFunction(), op.info);
                break;
            default:
                throw Util.shouldNotReachHere();
        }
    }

    void emitOpLabel(LIRLabel op) {
        asm.bind(op.label());
    }

    void emitOp1(LIROp1 op) {
        switch (op.code) {
            case Move:
                if (op.moveKind() == LIROp1.LIRMoveKind.Volatile) {
                    emitVolatileMove(op.operand(), op.result(), op.kind, op.info);
                } else {
                    moveOp(op.operand(), op.result(), op.kind, op.info, op.moveKind() == LIROp1.LIRMoveKind.Unaligned);
                }
                break;
            case Prefetchr:
                emitReadPrefetch(op.operand());
                break;
            case Prefetchw:
                emitReadPrefetch(op.operand());
                break;
            case Return:
                emitReturn(op.operand());
                break;
            case Safepoint:
                // TODO: emit a nop to separate debug info?
                emitSafepoint(op.operand(), op.info);
                break;
            case Branch:
                break;
            case Neg:
                emitNegate(op);
                break;
            case Leal:
                emitLeal(((LIRAddress) op.operand()), ((LIRLocation) op.result()));
                break;
            case NullCheck:
                asm.recordImplicitException(codePos(), op.info);
                assert op.operand().isRegister();
                asm.nullCheck(op.operand().asRegister());
                break;
            default:
                throw Util.shouldNotReachHere();
        }
    }

    public void emitOp0(LIROp0 op) {
        switch (op.code) {
            case Label:
                throw Util.shouldNotReachHere();
            case StdEntry:
                // init offsets
                emitPrologue();
                asm.verifiedEntry();
                buildFrame();
                break;
            case OsrEntry:
                emitOsrEntry();
                break;
            case Membar:
                emitMembar();
                break;
            case MembarAcquire:
                emitMembarAcquire();
                break;
            case MembarRelease:
                emitMembarRelease();
                break;
            case ReadPC:
                emitReadPC(op.result());
                break;
            default:
                throw Util.shouldNotReachHere();
        }
    }

    protected void emitOp2(LIROp2 op) {
        switch (op.code) {
            case Cmp:
                if (op.info != null) {
                    assert isAddress(op.opr1()) || isAddress(op.opr2()) : "shouldn't be codeemitinfo for non-address operands";
                    //NullPointerExceptionStub stub = new NullPointerExceptionStub(pcOffset, cinfo);
                    //emitCodeStub(stub);
                    asm.recordImplicitException(codePos(), op.info);
                }
                emitCompare(op.condition(), op.opr1(), op.opr2(), op);
                break;

            case Cmpl2i:
            case Cmpfd2i:
            case Ucmpfd2i:
                emitCompareFloatInt(op.code, op.opr1(), op.opr2(), op.result(), op);
                break;

            case Cmove:
                emitConditionalMove(op.condition(), op.opr1(), op.opr2(), op.result());
                break;

            case Shl:
            case Shr:
            case Ushr:
                if (isConstant(op.opr2())) {
                    emitShiftOp(op.code, op.opr1(), ((LIRConstant) op.opr2()).asInt(), op.result());
                } else {
                    emitShiftOp(op.code, op.opr1(), op.opr2(), op.result(), op.tmp());
                }
                break;

            case Add:
            case Sub:
            case Mul:
            case Div:
            case Rem:
                emitArithOp(op.code, op.opr1(), op.opr2(), op.result(), op.info);
                break;

            case Abs:
            case Sqrt:
            case Sin:
            case Tan:
            case Cos:
            case Log:
            case Log10:
                emitIntrinsicOp(op.code, op.opr1(), op.opr2(), op.result(), op);
                break;

            case LogicAnd:
            case LogicOr:
            case LogicXor:
                emitLogicOp(op.code, op.opr1(), op.opr2(), op.result());
                break;

            case Throw:
            case Unwind:
                emitThrow(op.opr1(), op.opr2(), op.info, op.code == LIROpcode.Unwind);
                break;

            default:
                throw Util.shouldNotReachHere();
        }
    }

    void buildFrame() {
        asm.buildFrame(initialFrameSizeInBytes());
    }

    public void moveOp(LIROperand src, LIROperand dest, CiKind kind, LIRDebugInfo info, boolean unaligned) {
        if (src.isRegister()) {
            if (dest.isRegister()) {
                assert info == null : "no patching and info allowed here";
                reg2reg(src, dest);
            } else if (dest.isStack()) {
                assert info == null : "no patching and info allowed here";
                reg2stack(src, dest, kind);
            } else if (isAddress(dest)) {
                reg2mem(src, dest, kind, info, unaligned);
            } else {
                throw Util.shouldNotReachHere();
            }

        } else if (src.isStack()) {
            assert info == null : "no patching and info allowed here";
            if (dest.isRegister()) {
                stack2reg(src, dest, kind);
            } else if (dest.isStack()) {
                stack2stack(src, dest, kind);
            } else {
                throw Util.shouldNotReachHere();
            }

        } else if (isConstant(src)) {
            if (dest.isRegister()) {
                const2reg(src, dest, info); // patching is possible
            } else if (dest.isStack()) {
                assert info == null : "no patching and info allowed here";
                const2stack(src, dest);
            } else if (isAddress(dest)) {
                const2mem(src, dest, kind, info);
            } else {
                throw Util.shouldNotReachHere();
            }

        } else if (isAddress(src)) {
            if (dest.isStack()) {
                assert info == null && !unaligned;
                mem2stack(src, dest, kind);
            } else if (isAddress(dest)) {
                assert info == null && !unaligned;
                mem2mem(src, dest, kind);
            } else {
                mem2reg(src, dest, kind, info, unaligned);
            }

        } else {
            throw Util.shouldNotReachHere();
        }
    }

    public void verifyOopMap(LIRDebugInfo info) {
        // TODO: verify oops
    }

    protected abstract int initialFrameSizeInBytes();

    protected abstract void doPeephole(LIRList list);

    protected abstract void emitSlowPath(SlowPath sp);

    protected abstract void emitCode(LocalStub s);

    protected abstract void emitAlignment();

    protected abstract void emitLeal(LIRAddress inOpr, LIRLocation resultOpr);

    protected abstract void emitNegate(LIROp1 negate);

    protected abstract void emitReadPC(LIROperand resultOpr);

    protected abstract void emitStackAllocate(StackBlock stackBlock, LIROperand resultOpr);

    protected abstract void emitSafepoint(LIROperand inOpr, LIRDebugInfo info);

    protected abstract void emitReturn(LIROperand inOpr);

    protected abstract void emitReadPrefetch(LIROperand inOpr);

    protected abstract void emitVolatileMove(LIROperand inOpr, LIROperand result, CiKind kind, LIRDebugInfo info);

    protected abstract void emitPrologue();

    protected abstract void emitThrow(LIROperand inOpr1, LIROperand inOpr2, LIRDebugInfo info, boolean unwind);

    protected abstract void emitLogicOp(LIROpcode code, LIROperand inOpr1, LIROperand inOpr2, LIROperand resultOpr);

    protected abstract void emitIntrinsicOp(LIROpcode code, LIROperand inOpr1, LIROperand inOpr2, LIROperand resultOpr, LIROp2 op);

    protected abstract void emitArithOp(LIROpcode code, LIROperand inOpr1, LIROperand inOpr2, LIROperand resultOpr, LIRDebugInfo info);

    protected abstract void emitShiftOp(LIROpcode code, LIROperand inOpr1, LIROperand inOpr2, LIROperand resultOpr, LIROperand tmpOpr);

    protected abstract void emitShiftOp(LIROpcode code, LIROperand inOpr1, int asJint, LIROperand resultOpr);

    protected abstract void emitConditionalMove(Condition condition, LIROperand inOpr1, LIROperand inOpr2, LIROperand resultOpr);

    protected abstract void emitCompareFloatInt(LIROpcode code, LIROperand inOpr1, LIROperand inOpr2, LIROperand resultOpr, LIROp2 op);

    protected abstract void emitCompare(Condition condition, LIROperand inOpr1, LIROperand inOpr2, LIROp2 op);

    protected abstract void emitBranch(LIRBranch branch);

    protected abstract void emitConvert(LIRConvert convert);

    protected abstract void emitLIROp2(LIROp2 op2);

    protected abstract void emitOp3(LIROp3 op3);

    protected abstract void emitCompareAndSwap(LIRCompareAndSwap compareAndSwap);

    protected abstract void emitXir(LIRXirInstruction xirInstruction);

    protected abstract void emitRuntimeCall(CiRuntimeCall l, LIRDebugInfo info);

    protected abstract void emitIndirectCall(Object target, LIRDebugInfo info, LIROperand operand);

    protected abstract void emitDirectCall(Object target, LIRDebugInfo info);

    protected abstract void emitNativeCall(NativeFunction nativeFunction, LIRDebugInfo info);

    protected abstract void emitCallAlignment(LIROpcode code);

    protected abstract void emitMembarRelease();

    protected abstract void emitMembarAcquire();

    protected abstract void emitMembar();

    protected abstract void emitOsrEntry();

    protected abstract void reg2stack(LIROperand src, LIROperand dest, CiKind kind);

    protected abstract void reg2mem(LIROperand src, LIROperand dest, CiKind kind, LIRDebugInfo info, boolean unaligned);

    protected abstract void mem2reg(LIROperand src, LIROperand dest, CiKind kind, LIRDebugInfo info, boolean unaligned);

    protected abstract void const2mem(LIROperand src, LIROperand dest, CiKind kind, LIRDebugInfo info);

    protected abstract void const2stack(LIROperand src, LIROperand dest);

    protected abstract void const2reg(LIROperand src, LIROperand dest, LIRDebugInfo info);

    protected abstract void mem2stack(LIROperand src, LIROperand dest, CiKind kind);

    protected abstract void mem2mem(LIROperand src, LIROperand dest, CiKind kind);

    protected abstract void stack2stack(LIROperand src, LIROperand dest, CiKind kind);

    protected abstract void stack2reg(LIROperand src, LIROperand dest, CiKind kind);

    protected abstract void reg2reg(LIROperand src, LIROperand dest);

}
