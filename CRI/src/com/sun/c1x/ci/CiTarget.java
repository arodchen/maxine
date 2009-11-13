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
package com.sun.c1x.ci;

import com.sun.c1x.ri.RiRegisterConfig;


/**
 * This class represents the target machine for a compiler, including
 * the CPU architecture, the size of pointers and references, alignment
 * of stacks, caches, etc.
 *
 * @author Ben L. Titzer
 */
public class CiTarget {
    public final CiArchitecture arch;

    public final CiRegister[] registerReferenceMapOrder;
    public final CiRegister[] callerSavedRegisters;
    public final CiRegister.AllocationSet registerConfig;
    public final CiRegister stackRegister;
    public final CiRegister scratchRegister;
    public final RiRegisterConfig config;
    public final int pageSize;
    public final boolean isMP;

    public int referenceSize;
    public int stackAlignment;
    public int cacheAlignment;
    public int codeAlignment;
    public int heapAlignment;

    public CiTarget(CiArchitecture arch, RiRegisterConfig config, int pageSize, boolean isMP) {

        this.arch = arch;
        this.config = config;
        referenceSize = arch.wordSize;
        stackAlignment = arch.wordSize;
        cacheAlignment = arch.wordSize;
        heapAlignment = arch.wordSize;
        codeAlignment = 16;

        this.stackRegister = config.getStackPointerRegister();
        this.scratchRegister = config.getScratchRegister();

        this.callerSavedRegisters = config.getCallerSaveRegisters();
        this.registerReferenceMapOrder = config.getRegisterReferenceMapOrder();
        this.pageSize = pageSize;
        this.isMP = isMP;
        this.registerConfig = new CiRegister.AllocationSet(config.getAllocatableRegisters(), registerReferenceMapOrder);
    }

    /**
     * Gets the size in bytes of the specified basic type for this target.
     * @param basicType the basic type for which to get the size
     * @return the size in bytes of the basic type
     */
    public int sizeInBytes(CiKind basicType) {
        return basicType.sizeInBytes(referenceSize, arch.wordSize);
    }

    public boolean supportsSSE() {
        return true;
    }

    public boolean supports3DNOW() {
        return true;
    }

    public boolean supportsSSE2() {
        return true;
    }

    public boolean supportsLzcnt() {
        return true;
    }

    public boolean supportsCmov() {
        return true;
    }

    public boolean supportsMmx() {
        return true;
    }

    public boolean supportsSse42() {
        return false;
    }

    public boolean supportsMMX() {
        return true;
    }

    public boolean isIntel() {
        return false;
    }

    public boolean isAmd() {
        return true;
    }

    public boolean supportsPopcnt() {
        return true;
    }

    public boolean supportsSse41() {
        return false;
    }

    public boolean isP6() {
        return true;
    }

    public boolean supportsCx8() {
        return true;
    }
}
