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

import static com.sun.c1x.bytecode.Bytecodes.UnsignedComparisons.*;

import com.sun.c1x.*;
import com.sun.c1x.bytecode.*;
import com.sun.c1x.ci.*;
import com.sun.c1x.gen.*;
import com.sun.c1x.globalstub.*;
import com.sun.c1x.ir.*;
import com.sun.c1x.lir.*;
import com.sun.c1x.stub.*;
import com.sun.c1x.util.*;

/**
 * This class implements the X86-specific portion of the LIR generator.
 *
 * @author Thomas Wuerthinger
 * @author Ben L. Titzer
 */
public final class AMD64LIRGenerator extends LIRGenerator {

    private static final LIRLocation IDIV_IN = LIROperand.forRegister(CiKind.Int, AMD64.rax);
    private static final LIRLocation IDIV_OUT = LIROperand.forRegister(CiKind.Int, AMD64.rax);
    private static final LIRLocation IREM_OUT = LIROperand.forRegister(CiKind.Int, AMD64.rdx);
    private static final LIRLocation IDIV_TMP = LIROperand.forRegister(CiKind.Int, AMD64.rdx);

    private static final LIRLocation LDIV_IN = LIROperand.forRegister(CiKind.Long, AMD64.rax);
    private static final LIRLocation LDIV_OUT = LIROperand.forRegister(CiKind.Long, AMD64.rax);
    private static final LIRLocation LREM_OUT = LIROperand.forRegister(CiKind.Long, AMD64.rdx);
    private static final LIRLocation LDIV_TMP = LIROperand.forRegister(CiKind.Long, AMD64.rdx);

    private static final LIROperand LONG_0_64 = LIROperand.forRegister(CiKind.Long, AMD64.rax);

    private static final LIROperand LONG_1_64 = LIROperand.forRegister(CiKind.Long, AMD64.rbx);

    private static final LIRLocation SHIFT_COUNT_IN = LIROperand.forRegister(CiKind.Int, AMD64.rcx);
    protected static final LIRLocation ILLEGAL = LIROperand.IllegalLocation;

    public AMD64LIRGenerator(C1XCompilation compilation) {
        super(compilation);
        assert is32 || is64 : "unknown word size: " + compilation.target.arch.wordSize;
        assert is32 != is64 : "can't be both 32 and 64 bit";
    }

    @Override
    protected LIROperand exceptionPcOpr() {
        return ILLEGAL;
    }

    @Override
    protected boolean canStoreAsConstant(Value v, CiKind kind) {
        if (kind == CiKind.Short || kind == CiKind.Char) {
            // there is no immediate move of word values in asemblerI486.?pp
            return false;
        }
        return v instanceof Constant;
    }

    @Override
    protected boolean canInlineAsConstant(Value v) {
        if (v.kind == CiKind.Long) {
            return false;
        }
        return v.kind != CiKind.Object || v.isNullConstant();
    }

    @Override
    protected boolean canInlineAsConstant(LIRConstant c) {
        if (c.kind == CiKind.Long) {
            return false;
        }
        return c.kind != CiKind.Object || c.asObject() == null;
    }

    @Override
    protected LIROperand safepointPollRegister() {
        return ILLEGAL;
    }

    @Override
    protected LIRAddress genAddress(LIRLocation base, LIROperand index, int shift, int disp, CiKind kind) {
        assert base.isVariableOrRegister() : "must be";
        if (LIROperand.isConstant(index)) {
            return new LIRAddress(base, (((LIRConstant) index).asInt() << shift) + disp, kind);
        } else {
            assert index.isVariableOrRegister();
            return new LIRAddress(base, ((LIRLocation) index), LIRAddress.Scale.fromLog2(shift), disp, kind);
        }
    }

    @Override
    protected void genCmpMemInt(Condition condition, LIRLocation base, int disp, int c, LIRDebugInfo info) {
        lir.cmpMemInt(condition, base, disp, c, info);
    }

    @Override
    protected void genCmpRegMem(Condition condition, LIROperand reg, LIRLocation base, int disp, CiKind kind, LIRDebugInfo info) {
        lir.cmpRegMem(condition, reg, new LIRAddress(base, disp, kind), info);
    }

    @Override
    protected boolean strengthReduceMultiply(LIROperand left, int c, LIROperand result, LIROperand tmp) {
        if (LIROperand.isLegal(tmp)) {
            if (Util.isPowerOf2(c + 1)) {
                lir.move(left, tmp);
                lir.shiftLeft(left, Util.log2(c + 1), left);
                lir.sub(left, tmp, result, null);
                return true;
            } else if (Util.isPowerOf2(c - 1)) {
                lir.move(left, tmp);
                lir.shiftLeft(left, Util.log2(c - 1), left);
                lir.add(left, tmp, result);
                return true;
            }
        }
        return false;
    }

    @Override
    public void visitNegateOp(NegateOp x) {
        LIRItem value = new LIRItem(x.x(), this);
        value.setDestroysRegister();
        value.loadItem();
        LIROperand reg = newVariable(x.kind);
        GlobalStub globalStub = null;
        if (x.kind == CiKind.Float) {
            globalStub = stubFor(GlobalStub.Id.fneg);
        } else if (x.kind == CiKind.Double) {
            globalStub = stubFor(GlobalStub.Id.dneg);
        }
        lir.negate(value.result(), reg, globalStub);
        setResult(x, reg);
    }

    public void visitArithmeticOpFPU(ArithmeticOp x) {
        LIRItem left = new LIRItem(x.x(), this);
        LIRItem right = new LIRItem(x.y(), this);
        assert !left.isStack() || !right.isStack() : "can't both be memory operands";
        boolean mustLoadBoth = (x.opcode() == Bytecodes.FREM || x.opcode() == Bytecodes.DREM);
        if (left.isRegister() || x.x().isConstant() || mustLoadBoth) {
            left.loadItem();
        }

        assert C1XOptions.SSEVersion >= 2;

        if (mustLoadBoth) {
            // frem and drem destroy also right operand, so move it to a new register
            right.setDestroysRegister();
            right.loadItem();
        } else if (right.isRegister()) {
            right.loadItem();
        }

        LIROperand reg;

        if (x.opcode() == Bytecodes.FREM) {
            reg = callRuntimeWithResult(CiRuntimeCall.ArithmeticFrem, null, left.result(), right.result());
        } else if (x.opcode() == Bytecodes.DREM) {
            reg = callRuntimeWithResult(CiRuntimeCall.ArithmeticDrem, null, left.result(), right.result());
        } else {
            reg = newVariable(x.kind);
            arithmeticOpFpu(x.opcode(), reg, left.result(), right.result(), ILLEGAL);
        }

        setResult(x, reg);
    }

    public void visitArithmeticOpLong(ArithmeticOp x) {
        int opcode = x.opcode();
        if (opcode == Bytecodes.LDIV || opcode == Bytecodes.LREM) {
            // emit code for long division or modulus
            if (is64) {
                // emit inline 64-bit code
                LIRDebugInfo info = x.needsZeroCheck() ? stateFor(x) : null;
                LIROperand dividend = force(x.x(), LDIV_IN); // dividend must be in RAX
                LIROperand divisor = load(x.y());            // divisor can be in any (other) register

                if (C1XOptions.GenExplicitDiv0Checks && x.needsZeroCheck()) {
                    ThrowStub stub = new ThrowStub(stubFor(CiRuntimeCall.ThrowArithmeticException), info);
                    lir.cmp(Condition.EQ, divisor, LIROperand.forLong(0));
                    lir.branch(Condition.EQ, CiKind.Long, stub);
                    info = null;
                }
                LIROperand result = createResultVariable(x);
                LIROperand resultReg;
                if (opcode == Bytecodes.LREM) {
                    resultReg = LREM_OUT; // remainder result is produced in rdx
                    lir.lrem(dividend, divisor, resultReg, LDIV_TMP, info);
                } else {
                    resultReg = LDIV_OUT; // division result is produced in rax
                    lir.ldiv(dividend, divisor, resultReg, LDIV_TMP, info);
                }

                lir.move(resultReg, result);
            } else {
                // emit direct call into the runtime
                CiRuntimeCall runtimeCall = opcode == Bytecodes.LREM ? CiRuntimeCall.ArithmethicLrem : CiRuntimeCall.ArithmeticLdiv;
                LIRDebugInfo info = x.needsZeroCheck() ? stateFor(x) : null;
                setResult(x, callRuntimeWithResult(runtimeCall, info, x.x().operand(), x.y().operand()));
            }
        } else if (opcode == Bytecodes.LMUL) {
            LIRItem left = new LIRItem(x.x(), this);
            LIRItem right = new LIRItem(x.y(), this);

            // right register is destroyed by the long mul, so it must be
            // copied to a new register.
            right.setDestroysRegister();

            left.loadItem();
            right.loadItem();

            LIROperand reg = LONG_0_64;
            arithmeticOpLong(opcode, reg, left.result(), right.result(), null);
            LIROperand result = createResultVariable(x);
            lir.move(reg, result);
        } else {
            LIRItem left = new LIRItem(x.x(), this);
            LIRItem right = new LIRItem(x.y(), this);

            left.loadItem();
            // don't load constants to save register
            right.loadNonconstant();
            createResultVariable(x);
            arithmeticOpLong(opcode, x.operand(), left.result(), right.result(), null);
        }
    }

    public void visitArithmeticOpInt(ArithmeticOp x) {
        int opcode = x.opcode();
        if (opcode == Bytecodes.IDIV || opcode == Bytecodes.IREM) {
            // emit code for integer division or modulus

            LIRDebugInfo info = x.needsZeroCheck() ? stateFor(x) : null;
            LIROperand dividend = force(x.x(), IDIV_IN); // dividend must be in RAX
            LIROperand divisor = load(x.y());            // divisor can be in any (other) register

            if (C1XOptions.GenExplicitDiv0Checks && x.needsZeroCheck()) {
                ThrowStub stub = new ThrowStub(stubFor(CiRuntimeCall.ThrowArithmeticException), info);
                lir.cmp(Condition.EQ, divisor, LIROperand.forInt(0));
                lir.branch(Condition.EQ, CiKind.Int, stub);
                info = null;
            }
            LIROperand result = createResultVariable(x);
            LIROperand resultReg;
            if (opcode == Bytecodes.IREM) {
                resultReg = IREM_OUT; // remainder result is produced in rdx
                lir.irem(dividend, divisor, resultReg, IDIV_TMP, info);
            } else {
                resultReg = IDIV_OUT; // division result is produced in rax
                lir.idiv(dividend, divisor, resultReg, IDIV_TMP, info);
            }

            lir.move(resultReg, result);
        } else {
            // emit code for other integer operations

            LIRItem left = new LIRItem(x.x(), this);
            LIRItem right = new LIRItem(x.y(), this);
            LIRItem leftArg = left;
            LIRItem rightArg = right;
            if (x.isCommutative() && left.isStack() && right.isRegister()) {
                // swap them if left is real stack (or cached) and right is real register(not cached)
                leftArg = right;
                rightArg = left;
            }

            leftArg.loadItem();

            // do not need to load right, as we can handle stack and constants
            if (opcode == Bytecodes.IMUL) {
                // check if we can use shift instead
                boolean useConstant = false;
                boolean useTmp = false;
                if (LIROperand.isConstant(rightArg.result())) {
                    int iconst = rightArg.asInt();
                    if (iconst > 0) {
                        if (Util.isPowerOf2(iconst)) {
                            useConstant = true;
                        } else if (Util.isPowerOf2(iconst - 1) || Util.isPowerOf2(iconst + 1)) {
                            useConstant = true;
                            useTmp = true;
                        }
                    }
                }
                if (!useConstant) {
                    rightArg.loadItem();
                }
                LIROperand tmp = ILLEGAL;
                if (useTmp) {
                    tmp = newVariable(CiKind.Int);
                }
                createResultVariable(x);

                arithmeticOpInt(opcode, x.operand(), leftArg.result(), rightArg.result(), tmp);
            } else {
                createResultVariable(x);
                LIROperand tmp = ILLEGAL;
                arithmeticOpInt(opcode, x.operand(), leftArg.result(), rightArg.result(), tmp);
            }
        }
    }

    public void visitArithmeticOpWord(ArithmeticOp x) {
        int opcode = x.opcode();
        if (opcode == Bytecodes.WDIV || opcode == Bytecodes.WREM || opcode == Bytecodes.WDIVI || opcode == Bytecodes.WREMI) {
            // emit code for long division or modulus
            if (is64) {
                // emit inline 64-bit code
                LIRDebugInfo info = x.needsZeroCheck() ? stateFor(x) : null;
                LIROperand dividend = force(x.x(), LDIV_IN); // dividend must be in RAX
                LIROperand divisor = load(x.y());            // divisor can be in any (other) register

                if (C1XOptions.GenExplicitDiv0Checks && x.needsZeroCheck()) {
                    ThrowStub stub = new ThrowStub(stubFor(CiRuntimeCall.ThrowArithmeticException), info);
                    lir.cmp(Condition.EQ, divisor, LIROperand.forLong(0));
                    lir.branch(Condition.EQ, CiKind.Long, stub);
                    info = null;
                }
                LIROperand result = createResultVariable(x);
                LIROperand resultReg;
                if (opcode == Bytecodes.WREM) {
                    resultReg = LREM_OUT; // remainder result is produced in rdx
                    lir.wrem(dividend, divisor, resultReg, LDIV_TMP, info);
                } else if (opcode == Bytecodes.WREMI) {
                    resultReg = LREM_OUT; // remainder result is produced in rdx
                    lir.wremi(dividend, divisor, resultReg, LDIV_TMP, info);
                } else if (opcode == Bytecodes.WDIV) {
                    resultReg = LDIV_OUT; // division result is produced in rax
                    lir.wdiv(dividend, divisor, resultReg, LDIV_TMP, info);
                } else {
                    assert opcode == Bytecodes.WDIVI;
                    resultReg = LDIV_OUT; // division result is produced in rax
                    lir.wdivi(dividend, divisor, resultReg, LDIV_TMP, info);
                }

                lir.move(resultReg, result);
            } else {
                // emit direct call into the runtime
                CiRuntimeCall runtimeCall = opcode == Bytecodes.LREM ? CiRuntimeCall.ArithmethicLrem : CiRuntimeCall.ArithmeticLdiv;
                LIRDebugInfo info = x.needsZeroCheck() ? stateFor(x) : null;
                setResult(x, callRuntimeWithResult(runtimeCall, info, x.x().operand(), x.y().operand()));
            }
        } else if (opcode == Bytecodes.LMUL) {
            LIRItem left = new LIRItem(x.x(), this);
            LIRItem right = new LIRItem(x.y(), this);

            // right register is destroyed by the long mul, so it must be
            // copied to a new register.
            right.setDestroysRegister();

            left.loadItem();
            right.loadItem();

            LIROperand reg = LONG_0_64;
            arithmeticOpLong(opcode, reg, left.result(), right.result(), null);
            LIROperand result = createResultVariable(x);
            lir.move(reg, result);
        } else {
            LIRItem left = new LIRItem(x.x(), this);
            LIRItem right = new LIRItem(x.y(), this);

            left.loadItem();
            // don't load constants to save register
            right.loadNonconstant();
            createResultVariable(x);
            arithmeticOpLong(opcode, x.operand(), left.result(), right.result(), null);
        }
    }

    @Override
    public void visitArithmeticOp(ArithmeticOp x) {
        trySwap(x);

        if (x.kind.isWord()) {
            visitArithmeticOpWord(x);
            return;
        }

        assert x.x().kind == x.kind && x.y().kind == x.kind : "wrong parameter types";
        switch (x.kind) {
            case Float:
            case Double:
                visitArithmeticOpFPU(x);
                return;
            case Long:
                visitArithmeticOpLong(x);
                return;
            case Int:
                visitArithmeticOpInt(x);
                return;
        }
        throw Util.shouldNotReachHere();
    }

    @Override
    public void visitShiftOp(ShiftOp x) {
        // count must always be in rcx
        LIRItem value = new LIRItem(x.x(), this);
        LIRItem count = new LIRItem(x.y(), this);

        boolean mustLoadCount = !LIROperand.isConstant(count.result()) || x.kind == CiKind.Long;
        if (mustLoadCount) {
            // count for long must be in register
            count.loadItemForce(SHIFT_COUNT_IN);
        }

        value.loadItem();
        LIROperand reg = createResultVariable(x);

        shiftOp(x.opcode(), reg, value.result(), count.result(), ILLEGAL);
    }

    @Override
    public void visitLogicOp(LogicOp x) {
        trySwap(x);

        LIRItem left = new LIRItem(x.x(), this);
        LIRItem right = new LIRItem(x.y(), this);

        left.loadItem();
        right.loadNonconstant();
        LIROperand reg = createResultVariable(x);

        logicOp(x.opcode(), reg, left.result(), right.result());
    }

    private void trySwap(Op2 x) {
    }

    @Override
    public void visitCompareOp(CompareOp x) {
        LIRItem left = new LIRItem(x.x(), this);
        LIRItem right = new LIRItem(x.y(), this);
        if (x.x().kind.isLong()) {
            left.setDestroysRegister();
        }
        left.loadItem();
        right.loadItem();
        LIROperand reg = createResultVariable(x);

        if (x.x().kind.isFloat() || x.x().kind.isDouble()) {
            int code = x.opcode();
            lir.fcmp2int(left.result(), right.result(), reg, (code == Bytecodes.FCMPL || code == Bytecodes.DCMPL));
        } else if (x.x().kind.isLong()) {
            lir.lcmp2int(left.result(), right.result(), reg);
        } else {
            Util.unimplemented();
        }
    }

    @Override
    public void visitUnsignedCompareOp(UnsignedCompareOp x) {
        LIRItem left = new LIRItem(x.x(), this);
        LIRItem right = new LIRItem(x.y(), this);
        left.setDestroysRegister();  // are we sure? why? copied from visitCompareOp
        left.loadItem();
        right.loadItem();
        Condition condition = null;
        switch (x.op) {
            case BELOW_THAN  : condition = Condition.BT; break;
            case ABOVE_THAN  : condition = Condition.AT; break;
            case BELOW_EQUAL : condition = Condition.BE; break;
            case ABOVE_EQUAL : condition = Condition.AE; break;
            default:
                Util.unimplemented();
        }
        LIROperand result = createResultVariable(x);
        lir.cmp(condition, left.result(), right.result());
        lir.cmove(condition, LIROperand.forInt(1), LIROperand.forInt(0), result);
    }

    @Override
    protected void genCompareAndSwap(Intrinsic x, CiKind kind) {
        assert x.numberOfArguments() == 4 : "wrong type";
        LIRItem obj = new LIRItem(x.argumentAt(0), this); // object
        LIRItem offset = new LIRItem(x.argumentAt(1), this); // offset of field
        LIRItem cmp = new LIRItem(x.argumentAt(2), this); // value to compare with field
        LIRItem val = new LIRItem(x.argumentAt(3), this); // replace field with val if matches cmp

        assert obj.value.kind.isObject() : "invalid type";

        assert cmp.value.kind == kind : "invalid type";
        assert val.value.kind == kind : "invalid type";

        // get address of field
        obj.loadItem();
        offset.loadNonconstant();

        if (kind.isObject()) {
            cmp.loadItemForce(LIROperand.forRegister(CiKind.Object, AMD64.rax));
            val.loadItem();
        } else if (kind.isInt()) {
            cmp.loadItemForce(LIROperand.forRegister(CiKind.Int, AMD64.rax));
            val.loadItem();
        } else if (kind.isLong()) {
            assert is64 : "32-bit not implemented";
            cmp.loadItemForce(LIROperand.forRegister(CiKind.Long, AMD64.rax));
            val.loadItemForce(LIROperand.forRegister(CiKind.Long, AMD64.rbx));
        } else {
            Util.shouldNotReachHere();
        }

        LIROperand addr = newVariable(CiKind.Word);
        lir.move(obj.result(), addr);
        lir.add(addr, offset.result(), addr);

        if (kind.isObject()) { // Write-barrier needed for Object fields.
            // Do the pre-write barrier : if any.
            preBarrier(addr, false, null);
        }

        LIROperand ill = ILLEGAL; // for convenience
        if (kind.isObject()) {
            lir.casObj(addr, cmp.result(), val.result(), ill, ill);
        } else if (kind.isInt()) {
            lir.casInt(addr, cmp.result(), val.result(), ill, ill);
        } else if (kind.isLong()) {
            lir.casLong(addr, cmp.result(), val.result(), ill, ill);
        } else {
            Util.shouldNotReachHere();
        }

        // generate conditional move of boolean result
        LIROperand result = createResultVariable(x);
        lir.cmove(Condition.EQ, LIROperand.forInt(1), LIROperand.forInt(0), result);
        if (kind.isObject()) { // Write-barrier needed for Object fields.
            // Seems to be precise
            postBarrier(addr, val.result());
        }
    }

    @Override
    protected void genMathIntrinsic(Intrinsic x) {
        assert x.numberOfArguments() == 1 : "wrong type";

        LIROperand calcInput = load(x.argumentAt(0));
        LIROperand calcResult = createResultVariable(x);

        switch (x.intrinsic()) {
            case java_lang_Math$abs:
                lir.abs(calcInput, calcResult, ILLEGAL);
                break;
            case java_lang_Math$sqrt:
                lir.sqrt(calcInput, calcResult, ILLEGAL);
                break;
            case java_lang_Math$sin:
                setResult(x, callRuntimeWithResult(CiRuntimeCall.ArithmeticSin, null, calcInput));
                break;
            case java_lang_Math$cos:
                setResult(x, callRuntimeWithResult(CiRuntimeCall.ArithmeticCos, null, calcInput));
                break;
            case java_lang_Math$tan:
                setResult(x, callRuntimeWithResult(CiRuntimeCall.ArithmeticTan, null, calcInput));
                break;
            case java_lang_Math$log:
                setResult(x, callRuntimeWithResult(CiRuntimeCall.ArithmeticLog, null, calcInput));
                break;
            case java_lang_Math$log10:
                setResult(x, callRuntimeWithResult(CiRuntimeCall.ArithmeticLog10, null, calcInput));
                break;
            default:
                Util.shouldNotReachHere();
        }
    }

    @Override
    public void visitConvert(Convert x) {
        assert C1XOptions.SSEVersion >= 2 : "no fpu stack";
        LIROperand input = load(x.value());
        LIROperand result = newVariable(x.kind);

        // arguments of lirConvert
        GlobalStub globalStub = null;
        switch (x.opcode()) {
            case Bytecodes.F2I: globalStub = stubFor(GlobalStub.Id.f2i); break;
            case Bytecodes.F2L: globalStub = stubFor(GlobalStub.Id.f2l); break;
            case Bytecodes.D2I: globalStub = stubFor(GlobalStub.Id.d2i); break;
            case Bytecodes.D2L: globalStub = stubFor(GlobalStub.Id.d2l); break;
        }
        lir.convert(x.opcode(), input, result, globalStub);
        setResult(x, result);
    }

    @Override
    public void visitBlockBegin(BlockBegin x) {
        // nothing to do for now
    }

    @Override
    public void visitIf(If x) {
        CiKind kind = x.x().kind;

        Condition cond = x.condition();

        LIRItem xitem = new LIRItem(x.x(), this);
        LIRItem yitem = new LIRItem(x.y(), this);
        LIRItem xin = xitem;
        LIRItem yin = yitem;

        if (kind.isLong()) {
            // for longs, only conditions "eql", "neq", "lss", "geq" are valid;
            // mirror for other conditions
            if (cond == Condition.GT || cond == Condition.LE) {
                cond = cond.mirror();
                xin = yitem;
                yin = xitem;
            }
            xin.setDestroysRegister();
        }
        xin.loadItem();
        if (kind.isLong() && LIROperand.isConstant(yin.result()) && yin.asLong() == 0 && (cond == Condition.EQ || cond == Condition.NE)) {
            // dont load item
        } else if (kind.isLong() || kind.isFloat() || kind.isDouble()) {
            // longs cannot handle constants at right side
            yin.loadItem();
        }

        // add safepoint before generating condition code so it can be recomputed
        if (x.isSafepoint()) {
            // increment backedge counter if needed
            incrementBackedgeCounter(stateFor(x, x.stateAfter()));

            lir.safepoint(ILLEGAL, stateFor(x, x.stateAfter()));
        }
        setNoResult(x);

        LIROperand left = xin.result();
        LIROperand right = yin.result();
        lir.cmp(cond, left, right);
        profileBranch(x, cond);
        moveToPhi(x.stateAfter());
        if (x.x().kind.isFloat() || x.x().kind.isDouble()) {
            lir.branch(cond, right.kind, x.trueSuccessor(), x.unorderedSuccessor());
        } else {
            lir.branch(cond, right.kind, x.trueSuccessor());
        }
        assert x.defaultSuccessor() == x.falseSuccessor() : "wrong destination above";
        lir.jump(x.defaultSuccessor());
    }

    @Override
    protected void genGetObjectUnsafe(LIRLocation dst, LIRLocation src, LIRLocation offset, CiKind kind, boolean isVolatile) {
        if (isVolatile && kind == CiKind.Long) {
            LIRAddress addr = new LIRAddress(src, offset, CiKind.Double);
            LIROperand tmp = newVariable(CiKind.Double);
            lir.load(addr, tmp, null);
            LIROperand spill = newVariable(CiKind.Long, VariableFlag.MustStartInMemory);
            lir.move(tmp, spill);
            lir.move(spill, dst);
        } else {
            LIRAddress addr = new LIRAddress(src, offset, kind);
            lir.load(addr, dst, null);
        }
    }

    @Override
    protected void genPutObjectUnsafe(LIRLocation src, LIRLocation offset, LIROperand data, CiKind kind, boolean isVolatile) {
        if (isVolatile && kind == CiKind.Long) {
            LIRAddress addr = new LIRAddress(src, offset, CiKind.Double);
            LIROperand tmp = newVariable(CiKind.Double);
            LIROperand spill = newVariable(CiKind.Double, VariableFlag.MustStartInMemory);
            lir.move(data, spill);
            lir.move(spill, tmp);
            lir.move(tmp, addr);
        } else {
            LIRAddress addr = new LIRAddress(src, offset, kind);
            boolean isObj = (kind == CiKind.Jsr || kind == CiKind.Object);
            if (isObj) {
                // Do the pre-write barrier, if any.
                preBarrier(addr, false, null);
                lir.move(data, addr);
                assert src.isVariableOrRegister() : "must be register";
                // Seems to be a precise address
                postBarrier(addr, data);
            } else {
                lir.move(data, addr);
            }
        }
    }

    @Override
    protected LIROperand osrBufferPointer() {
        return Util.nonFatalUnimplemented(null);
    }

}
