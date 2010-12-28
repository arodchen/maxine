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
package com.sun.c1x.target.amd64;

import static com.sun.cri.ci.CiKind.*;

import com.sun.c1x.*;
import com.sun.c1x.asm.*;
import com.sun.c1x.globalstub.*;
import com.sun.c1x.lir.*;
import com.sun.c1x.util.*;
import com.sun.cri.ci.*;
import com.sun.cri.ri.*;
import com.sun.cri.xir.*;

/**
 * This class implements the AMD64-specific portion of the macro assembler.
 *
 * @author Thomas Wuerthinger
 * @author Ben L. Titzer
 */
public class AMD64MacroAssembler extends AMD64Assembler {

    private final CiRegister rscratch1;

    public static class WithCompiler extends AMD64MacroAssembler {

        private final C1XCompiler compiler;

        public WithCompiler(C1XCompiler compiler, RiRegisterConfig registerConfig) {
            super(compiler.target, registerConfig);
            this.compiler = compiler;
        }

        @Override
        public GlobalStub lookupGlobalStub(XirTemplate template) {
            return compiler.lookupGlobalStub(template);
        }
    }

    public AMD64MacroAssembler(CiTarget target, RiRegisterConfig registerConfig) {
        super(target, registerConfig);
        this.rscratch1 = registerConfig.getScratchRegister();
    }

    /**
     * Must be overridden if compiling code that makes calls to global stubs.
     */
    public GlobalStub lookupGlobalStub(XirTemplate template) {
        throw new IllegalArgumentException("This assembler does not support compiling calls to global stubs");
    }

    public final int callGlobalStub(XirTemplate stub, LIRDebugInfo info, CiRegister result, CiValue... args) {
        assert args.length == stub.parameters.length;
        return callGlobalStubHelper(lookupGlobalStub(stub), stub.resultOperand.kind, info, result, args);
    }

    public final int callGlobalStub(GlobalStub stub, LIRDebugInfo info, CiRegister result, CiValue... args) {
        assert args.length == stub.argOffsets.length;
        return callGlobalStubHelper(stub, stub.resultKind, info, result, args);
    }

    private int callGlobalStubHelper(GlobalStub stub, CiKind resultKind, LIRDebugInfo info, CiRegister result, CiValue... args) {
        for (int i = 0; i < args.length; i++) {
            storeParameter(args[i], stub.argOffsets[i]);
        }

        int pos = directCall(stub.stubObject, info);

        if (result != CiRegister.None) {
            loadResult(result, stub.resultOffset, resultKind);
        }

        // Clear out parameters
        if (C1XOptions.GenAssertionCode) {
            for (int i = 0; i < args.length; i++) {
                movptr(new CiAddress(CiKind.Word, AMD64.RSP, stub.argOffsets[i]), 0);
            }
        }
        return pos;
    }

    void loadResult(CiRegister r, int offset, CiKind kind) {
        if (kind == CiKind.Int || kind == CiKind.Boolean) {
            movl(r, new CiAddress(CiKind.Int, AMD64.RSP, offset));
        } else if (kind == CiKind.Float) {
            movss(r, new CiAddress(CiKind.Float, AMD64.RSP, offset));
        } else if (kind == CiKind.Double) {
            movsd(r, new CiAddress(CiKind.Double, AMD64.RSP, offset));
        } else {
            movq(r, new CiAddress(CiKind.Word, AMD64.RSP, offset));
        }
    }

    void storeParameter(CiValue registerOrConstant, int offset) {
        CiKind k = registerOrConstant.kind;
        if (registerOrConstant.isConstant()) {
            CiConstant c = (CiConstant) registerOrConstant;
            if (c.kind == CiKind.Object) {
                movoop(new CiAddress(CiKind.Word, AMD64.RSP, offset), c);
            } else {
                movptr(new CiAddress(CiKind.Word, AMD64.RSP, offset), c.asInt());
            }
        } else if (registerOrConstant.isRegister()) {
            if (k.isFloat()) {
                movss(new CiAddress(CiKind.Float, AMD64.RSP, offset), registerOrConstant.asRegister());
            } else {
                movsd(new CiAddress(CiKind.Double, AMD64.RSP, offset), registerOrConstant.asRegister());
            }
        } else {
            Util.shouldNotReachHere();
        }
    }

    void movoop(CiRegister dst, CiConstant obj) {
        assert obj.kind == CiKind.Object;
        if (obj.isNull()) {
            xorq(dst, dst);
        } else {
            if (target.inlineObjects) {
                recordDataReferenceInCode(obj);
                mov64(dst, 0xDEADDEADDEADDEADL);
            } else {
                movq(dst, recordDataReferenceInCode(obj));
            }
        }
    }

    void movoop(CiAddress dst, CiConstant obj) {
        movoop(rscratch1, obj);
        movq(dst, rscratch1);
    }

    // src should NEVER be a real pointer. Use AddressLiteral for true pointers
    void movptr(CiAddress dst, long src) {
        mov64(rscratch1, src);
        movq(dst, rscratch1);
    }

    void pushptr(CiAddress src) {
        pushq(src);
    }

    void popptr(CiAddress src) {
        popq(src);
    }

    void xorptr(CiRegister dst, CiRegister src) {
        xorq(dst, src);
    }

    void xorptr(CiRegister dst, CiAddress src) {
        xorq(dst, src);
    }

    // 64 bit versions

    int correctedIdivq(CiRegister reg) {
        // Full implementation of Java ldiv and lrem; checks for special
        // case as described in JVM spec. : p.243 & p.271. The function
        // returns the (pc) offset of the idivl instruction - may be needed
        // for implicit exceptions.
        //
        // normal case special case
        //
        // input : X86Register.rax: dividend minLong
        // reg: divisor (may not be eax/edx) -1
        //
        // output: X86Register.rax: quotient (= X86Register.rax idiv reg) minLong
        // X86Register.rdx: remainder (= X86Register.rax irem reg) 0
        assert reg != AMD64.rax && reg != AMD64.rdx : "reg cannot be X86Register.rax or X86Register.rdx register";
        final long minLong = 0x8000000000000000L;
        Label normalCase = new Label();
        Label specialCase = new Label();

        // check for special case
        cmpq(AMD64.rax, recordDataReferenceInCode(CiConstant.forLong(minLong)));
        jcc(AMD64Assembler.ConditionFlag.notEqual, normalCase);
        xorl(AMD64.rdx, AMD64.rdx); // prepare X86Register.rdx for possible special case (where
        // remainder = 0)
        cmpq(reg, -1);
        jcc(AMD64Assembler.ConditionFlag.equal, specialCase);

        // handle normal case
        bind(normalCase);
        cdqq();
        int idivqOffset = codeBuffer.position();
        idivq(reg);

        // normal and special case exit
        bind(specialCase);

        return idivqOffset;
    }

    void decrementq(CiRegister reg, int value) {
        if (value == Integer.MIN_VALUE) {
            subq(reg, value);
            return;
        }
        if (value < 0) {
            incrementq(reg, -value);
            return;
        }
        if (value == 0) {
            return;
        }
        if (value == 1 && C1XOptions.UseIncDec) {
            decq(reg);
        } else {
            subq(reg, value);
        }
    }

    void incrementq(CiRegister reg, int value) {
        if (value == Integer.MIN_VALUE) {
            addq(reg, value);
            return;
        }
        if (value < 0) {
            decrementq(reg, -value);
            return;
        }
        if (value == 0) {
            return;
        }
        if (value == 1 && C1XOptions.UseIncDec) {
            incq(reg);
        } else {
            addq(reg, value);
        }
    }

    // These are mostly for initializing null
    void movptr(CiAddress dst, int src) {
        movslq(dst, src);
    }

    void stop(String msg) {
        if (C1XOptions.GenAssertionCode) {
            // TODO: pass a pointer to the message
            directCall(CiRuntimeCall.Debug, null);
            hlt();
        }
    }

    void andptr(CiRegister dst, int imm32) {
        andq(dst, imm32);
    }

    void c2bool(CiRegister x) {
        // implements x == 0 ? 0 : 1
        // note: must only look at least-significant byte of x
        // since C-style booleans are stored in one byte
        // only! (was bug)
        andl(x, 0xFF);
        setb(AMD64Assembler.ConditionFlag.notZero, x);
    }

    public final void cmp32(CiRegister src1, int imm) {
        cmpl(src1, imm);
    }

    public final void cmp32(CiRegister src1, CiAddress src2) {
        cmpl(src1, src2);
    }

    void cmpsd2int(CiRegister opr1, CiRegister opr2, CiRegister dst, boolean unorderedIsLess) {
        assert opr1.isFpu() && opr2.isFpu();
        ucomisd(opr1, opr2);

        Label l = new Label();
        if (unorderedIsLess) {
            movl(dst, -1);
            jcc(AMD64Assembler.ConditionFlag.parity, l);
            jcc(AMD64Assembler.ConditionFlag.below, l);
            movl(dst, 0);
            jcc(AMD64Assembler.ConditionFlag.equal, l);
            incrementl(dst, 1);
        } else { // unordered is greater
            movl(dst, 1);
            jcc(AMD64Assembler.ConditionFlag.parity, l);
            jcc(AMD64Assembler.ConditionFlag.above, l);
            movl(dst, 0);
            jcc(AMD64Assembler.ConditionFlag.equal, l);
            decrementl(dst, 1);
        }
        bind(l);
    }

    void cmpss2int(CiRegister opr1, CiRegister opr2, CiRegister dst, boolean unorderedIsLess) {
        assert opr1.isFpu();
        assert opr2.isFpu();
        ucomiss(opr1, opr2);

        Label l = new Label();
        if (unorderedIsLess) {
            movl(dst, -1);
            jcc(AMD64Assembler.ConditionFlag.parity, l);
            jcc(AMD64Assembler.ConditionFlag.below, l);
            movl(dst, 0);
            jcc(AMD64Assembler.ConditionFlag.equal, l);
            incrementl(dst, 1);
        } else { // unordered is greater
            movl(dst, 1);
            jcc(AMD64Assembler.ConditionFlag.parity, l);
            jcc(AMD64Assembler.ConditionFlag.above, l);
            movl(dst, 0);
            jcc(AMD64Assembler.ConditionFlag.equal, l);
            decrementl(dst, 1);
        }
        bind(l);
    }

    void cmpptr(CiRegister src1, CiRegister src2) {
        cmpq(src1, src2);
    }

    void cmpptr(CiRegister src1, CiAddress src2) {
        cmpq(src1, src2);
    }

    void cmpptr(CiRegister src1, int src2) {
        cmpq(src1, src2);
    }

    void cmpptr(CiAddress src1, int src2) {
        cmpq(src1, src2);
    }

    void decrementl(CiRegister reg, int value) {
        if (value == Integer.MIN_VALUE) {
            subl(reg, value);
            return;
        }
        if (value < 0) {
            incrementl(reg, -value);
            return;
        }
        if (value == 0) {
            return;
        }
        if (value == 1 && C1XOptions.UseIncDec) {
            decl(reg);
        } else {
            subl(reg, value);
        }
    }

    void decrementl(CiAddress dst, int value) {
        if (value == Integer.MIN_VALUE) {
            subl(dst, value);
            return;
        }
        if (value < 0) {
            incrementl(dst, -value);
            return;
        }
        if (value == 0) {
            return;
        }
        if (value == 1 && C1XOptions.UseIncDec) {
            decl(dst);
        } else {
            subl(dst, value);
        }
    }

    void incrementl(CiRegister reg, int value) {
        if (value == Integer.MIN_VALUE) {
            addl(reg, value);
            return;
        }
        if (value < 0) {
            decrementl(reg, -value);
            return;
        }
        if (value == 0) {
            return;
        }
        if (value == 1 && C1XOptions.UseIncDec) {
            incl(reg);
        } else {
            addl(reg, value);
        }
    }

    void incrementl(CiAddress dst, int value) {
        if (value == Integer.MIN_VALUE) {
            addl(dst, value);
            return;
        }
        if (value < 0) {
            decrementl(dst, -value);
            return;
        }
        if (value == 0) {
            return;
        }
        if (value == 1 && C1XOptions.UseIncDec) {
            incl(dst);
        } else {
            addl(dst, value);
        }
    }

    int loadSignedByte(CiRegister dst, CiAddress src) {
        int off = codeBuffer.position();
        movsxb(dst, src); // movsxb
        return off;
    }

    // Note: loadSignedShort used to be called loadSignedWord.
    // Although the 'w' in x86 opcodes refers to the term "word" in the assembler
    // manual : which means 16 bits : that usage is found nowhere in HotSpot code.
    // The term "word" in HotSpot means a 32- or 64-bit machine word.
    int loadSignedShort(CiRegister dst, CiAddress src) {
        // This is dubious to me since it seems safe to do a signed 16 => 64 bit
        // version but this is what 64bit has always done. This seems to imply
        // that users are only using 32bits worth.
        int off = codeBuffer.position();
        movswl(dst, src); // movsxw
        return off;
    }

    int loadUnsignedByte(CiRegister dst, CiAddress src) {
        // According to Intel Doc. AP-526 : "Zero-Extension of Short" : p.16 :
        // and "3.9 Partial Register Penalties" : p. 22.
        int off = codeBuffer.position();
        movzxb(dst, src); // movzxb
        return off;
    }

    // Note: loadUnsignedShort used to be called loadUnsignedWord.
    int loadUnsignedShort(CiRegister dst, CiAddress src) {
        // According to Intel Doc. AP-526, "Zero-Extension of Short", p.16,
        // and "3.9 Partial Register Penalties", p. 22).
        int off = codeBuffer.position();
        movzxl(dst, src); // movzxw
        return off;
    }

    @Override
    public void nullCheck(CiRegister reg) {
        // provoke OS null exception if reg = null by
        // accessing M[reg] w/o changing any (non-CC) registers
        // NOTE: cmpl is plenty here to provoke a segv
        testl(AMD64.rax, new CiAddress(CiKind.Word, reg.asValue(Word), 0));
        // Note: should probably use testl(X86Register.rax, new Address(reg, 0));
        // may be shorter code (however, this version of
        // testl needs to be implemented first)
    }

    void getThread(CiRegister javaThread) {
        // Platform-specific! Solaris / Windows / Linux
        Util.nonFatalUnimplemented();
    }

    void signExtendByte(CiRegister reg) {
        if (reg.isByte()) {
            movsxb(reg, reg); // movsxb
        } else {
            shll(reg, 24);
            sarl(reg, 24);
        }
    }

    void signExtendShort(CiRegister reg) {
        movsxw(reg, reg); // movsxw
    }

    void subptr(CiRegister dst, int imm32) {
        subq(dst, imm32);
    }

    // Support optimal SSE move instructions.
    void movflt(CiRegister dst, CiRegister src) {
        assert dst.isFpu() && src.isFpu();
        if (C1XOptions.UseXmmRegToRegMoveAll) {
            movaps(dst, src);
        } else {
            movss(dst, src);
        }
    }

    void movflt(CiRegister dst, CiAddress src) {
        assert dst.isFpu();
        movss(dst, src);
    }

    void movflt(CiAddress dst, CiRegister src) {
        assert src.isFpu();
        movss(dst, src);
    }

    void movdbl(CiRegister dst, CiRegister src) {
        assert dst.isFpu() && src.isFpu();
        if (C1XOptions.UseXmmRegToRegMoveAll) {
            movapd(dst, src);
        } else {
            movsd(dst, src);
        }
    }

    void movdbl(CiRegister dst, CiAddress src) {
        assert dst.isFpu();
        if (C1XOptions.UseXmmLoadAndClearUpper) {
            movsd(dst, src);
        } else {
            movlpd(dst, src);
        }
    }

    void xchgptr(CiRegister src1, CiRegister src2) {
        xchgq(src1, src2);
    }

    public void shouldNotReachHere() {
        stop("should not reach here");
    }

    public void enter(short imm16, byte imm8) {
        emitByte(0xC8);
        // appended:
        emitByte(imm16 & 0xff);
        imm16 >>= 8;
        emitByte(imm16 & 0xff);
        emitByte(imm8);
    }

    /**
     * Emit code to save a given set of callee save registers to the
     * {@linkplain CiCalleeSaveArea CSA} within the frame.
     * @param csa the description of the CSA
     * @param frameToCSA offset from the frame pointer to the CSA
     */
    public void save(CiCalleeSaveArea csa, int frameToCSA) {
        CiRegisterValue frame = frameRegister.asValue();
        for (CiRegister r : csa.registers) {
            int offset = csa.offsetOf(r);
            movq(new CiAddress(CiKind.Word, frame, frameToCSA + offset), r);
        }
    }

    public void restore(CiCalleeSaveArea csa, int frameToCSA) {
        CiRegisterValue frame = frameRegister.asValue();
        for (CiRegister r : csa.registers) {
            int offset = csa.offsetOf(r);
            movq(r, new CiAddress(CiKind.Word, frame, frameToCSA + offset));
        }
    }

    public void int3() {
        emitByte(0xCC);
    }
}
