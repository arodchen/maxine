/*
 * Copyright (c) 2007 Sun Microsystems, Inc.  All rights reserved.
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
package com.sun.max.vm.compiler.eir.amd64;

import com.sun.max.annotate.*;
import com.sun.max.collect.*;
import com.sun.max.lang.*;
import com.sun.max.vm.collect.*;
import com.sun.max.vm.compiler.eir.*;
import com.sun.max.vm.type.*;

/**
 * @author Bernd Mathiske
 */
public abstract class AMD64EirGenerator extends EirGenerator<AMD64EirGeneratorScheme> {

    /**
     * Assigns an exception object into the result location defined by the {@linkplain EirABIsScheme#javaABI() Java ABI}.
     * which is where this compiler expects the exception to be when compiling a catch block.
     *
     * The {@link NEVER_INLINE} annotation guarantees that the assignment to the result location actually occurs.
     */
    @NEVER_INLINE
    public static Throwable assignExceptionToCatchParameterLocation(Throwable throwable) {
        return throwable;
    }

    public static void addFrameReferenceMap(PoolSet<EirVariable> liveVariables, WordWidth stackSlotWidth, ByteArrayBitMap map) {
        for (EirVariable variable : liveVariables) {
            if (variable.kind() == Kind.REFERENCE) {
                EirLocation location = variable.location();
                if (location.category() == EirLocationCategory.STACK_SLOT) {
                    final EirStackSlot stackSlot = (EirStackSlot) location;
                    if (stackSlot.purpose != EirStackSlot.Purpose.PARAMETER) {
                        final int stackSlotBitIndex = stackSlot.offset / stackSlotWidth.numberOfBytes;
                        map.set(stackSlotBitIndex);
                    }
                }
            }
        }
    }

    private final EirLocation eirCatchParameterLocation = eirABIsScheme().javaABI.getResultLocation(Kind.REFERENCE);

    public AMD64EirGenerator(AMD64EirGeneratorScheme eirGeneratorScheme) {
        super(eirGeneratorScheme);
    }

    @Override
    public EirLocation catchParameterLocation() {
        return eirCatchParameterLocation;
    }

}
