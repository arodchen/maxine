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
package com.sun.c1x.stub;

import com.sun.c1x.debug.LogStream;
import com.sun.c1x.lir.CodeEmitInfo;
import com.sun.c1x.lir.LIRAssembler;
import com.sun.c1x.lir.LIROperand;
import com.sun.c1x.lir.LIRVisitState;

/**
 * @author Marcelo Cintra
 * @author Thomas Wuerthinger
 *
 */
public class RangeCheckStub extends CodeStub {

    private CodeEmitInfo info;
    private LIROperand index;
    private boolean throwIndexOutOfBoundsException;

    public RangeCheckStub(CodeEmitInfo rangeCheckInfo, LIROperand index) {
        this(rangeCheckInfo, index, false);
    }

    public RangeCheckStub(CodeEmitInfo info, LIROperand index, boolean throwIndexOutOfBoundsException) {
        this.info = info;
        this.index = index;
        this.throwIndexOutOfBoundsException = throwIndexOutOfBoundsException;
    }

    @Override
    public void emitCode(LIRAssembler e) {
        // TODO Auto-generated method stub

    }

    @Override
    public CodeEmitInfo info() {
        return info;
    }

    /**
     * Gets the throwIndexOutOfBoundsException of this class.
     *
     * @return the throwIndexOutOfBoundsException
     */
    public boolean isThrowIndexOutOfBoundsException() {
        return throwIndexOutOfBoundsException;
    }

    @Override
    public boolean isExceptionThrowStub() {
        return true;
    }

    public boolean isRangeCheckStub() {
        return true;
    }

    @Override
    public void visit(LIRVisitState visitor) {
        visitor.doSlowCase(info);
        visitor.doInput(index);
    }

    @Override
    public void printName(LogStream out) {
        out.print("RangeCheckStub");

    }
}
