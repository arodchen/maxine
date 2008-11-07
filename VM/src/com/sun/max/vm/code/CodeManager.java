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
package com.sun.max.vm.code;

import com.sun.max.annotate.*;
import com.sun.max.collect.*;
import com.sun.max.lang.*;
import com.sun.max.memory.*;
import com.sun.max.program.*;
import com.sun.max.unsafe.*;
import com.sun.max.vm.actor.member.*;
import com.sun.max.vm.actor.member.MethodKey.*;
import com.sun.max.vm.compiler.target.*;
import com.sun.max.vm.heap.util.*;
import com.sun.max.vm.runtime.*;

/**
 * Target machine code cache management.
 *
 * All generated code is position independent as a whole, but target methods may contain direct call references between
 * each other and these must be within 32-bit offsets! Therefore all code regions must be within 32-bit offsets from
 * each other. Hence, we cannot simply malloc() them.
 *
 * @author Bernd Mathiske
 */
public abstract class CodeManager extends RuntimeMemoryRegion {

    /**
     * The default size of a runtime code region.
     */
    protected static final int RUNTIME_CODE_REGION_SIZE = Ints.M;

    /**
     * The default number of runtime code regions.
     */
    protected static final int NUMBER_OF_RUNTIME_CODE_REGIONS = 64;

    /**
     * The maximum number of virtual code regions.
     */
    protected static final int VIRTUAL_NUMBER_OF_RUNTIME_CODE_REGIONS = Integer.MAX_VALUE / RUNTIME_CODE_REGION_SIZE;

    /**
     * The maximum size of the code cache.
     */
    public static final int CODE_CACHE_SIZE = NUMBER_OF_RUNTIME_CODE_REGIONS * RUNTIME_CODE_REGION_SIZE;

    /**
     * An array of the code regions.
     */
    @INSPECTED
    protected final CodeRegion[] _runtimeCodeRegions;

    /**
     * Get the code region at the specified index.
     *
     * @param index the index into the code regions array
     * @return the code region at the specified index
     */
    protected CodeRegion getRuntimeCodeRegion(int index) {
        return _runtimeCodeRegions[index];
    }

    /**
     * Creates a code manager with the specified number of code regions.
     *
     * @param numberOfRuntimeCodeRegions the number of code regions that this code manager should allocate
     */
    CodeManager(int numberOfRuntimeCodeRegions) {
        super();
        _runtimeCodeRegions = new CodeRegion[numberOfRuntimeCodeRegions];
        for (int i = 0; i < numberOfRuntimeCodeRegions; i++) {
            _runtimeCodeRegions[i] = new CodeRegion("Code-" + i);
        }
    }

    /**
     * Initialize this code manager.
     */
    void initialize() {
    }

    /**
     * The current code region that is the default for allocating new target methods.
     */
    @INSPECTED
    private CodeRegion _currentCodeRegion = Code.bootCodeRegion();

    /**
     * Allocates a new code region with free space, if necessary.
     *
     * @return a new code region with free space
     */
    protected abstract CodeRegion makeFreeCodeRegion();

    /**
     * Allocates space for the specified target method, including its code and attached metadata.
     *
     * @param targetMethod the target method to allocate space for
     */
    synchronized void allocate(TargetMethod targetMethod) {
        if (!_currentCodeRegion.allocateTargetMethod(targetMethod)) {
            _currentCodeRegion = makeFreeCodeRegion();
            Code.registerMemoryRegion(_currentCodeRegion);
            if (!_currentCodeRegion.allocateTargetMethod(targetMethod)) {
                ProgramError.unexpected("could not allocate code");
            }
        }
        _methodKeyToTargetMethods.add(new MethodActorKey(targetMethod.classMethodActor()), targetMethod);
    }

    /**
     * Allocates space in this code region for a runtime stub.
     * Note that this method will allocate a byte array of the specified size and inline it in the code
     * region to preserve the invariant that the code region can be scanned linearly as a collection of objects.
     *
     * @param stub an object describing the size of the runtime stub (i.e. the size in bytes to allocate). If
     *            allocation is successful, the address of the memory chunk allocated (i.e. the address of the first
     *            element of the internally allocated byte array) will be accessible through the
     *            {@link MemoryRegion#start()} method of this object.
     * @return true if space was successfully allocated for the runtime stub
     */
    synchronized boolean allocateRuntimeStub(RuntimeStub stub) {
        if (!_currentCodeRegion.allocateRuntimeStub(stub)) {
            _currentCodeRegion = makeFreeCodeRegion();
            Code.registerMemoryRegion(_currentCodeRegion);
            if (!_currentCodeRegion.allocateRuntimeStub(stub)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Looks up the runtime code region in which the specified code pointer lies. This lookup
     * does not include the boot code region.
     *
     * @param codePointer the code pointer
     * @return a reference to the code region that contains the specified code pointer, if one exists; {@code null} if
     *         the code pointer lies outside of all runtime code regions
     */
    private CodeRegion codePointerToRuntimeCodeRegion(Address codePointer) {
        if (!contains(codePointer)) {
            return null;
        }
        final int index = codePointer.minus(start()).dividedBy(RUNTIME_CODE_REGION_SIZE).toInt();
        final CodeRegion codeRegion = _runtimeCodeRegions[index];
        if (codeRegion == null) {
            return null;
        }
        return codeRegion;
    }

    /**
     * Looks up the code region in which the specified code pointer lies. This lookup includes
     * the boot code region.
     *
     * @param codePointer the code pointer
     * @return a reference to the code region that contains the specified code pointer, if one exists; {@code null} if
     *         the code pointer lies outside of all code regions
     */
    CodeRegion codePointerToCodeRegion(Address codePointer) {
        if (Code.bootCodeRegion().contains(codePointer)) {
            return Code.bootCodeRegion();
        }
        return codePointerToRuntimeCodeRegion(codePointer);
    }

    /**
     * Looks up the target method that contains the specified code pointer.
     *
     * @param codePointer the code pointer to lookup
     * @return the target method that contains the specified code pointer, if it exists; {@code null}
     * if no target method contains the specified code pointer
     */
    TargetMethod codePointerToTargetMethod(Address codePointer) {
        final CodeRegion codeRegion = codePointerToCodeRegion(codePointer);
        if (codeRegion != null) {
            return codeRegion.findTargetMethod(codePointer);
        }
        return null;
    }

    /**
     * Looks up the runtime stub that contains the specified code pointer.
     *
     * @param codePointer the code pointer to lookup
     * @return the runtime stub that contains the specified code pointer, if it exists;
     *         {@code null} otherwise
     */
    RuntimeStub codePointerToRuntimeStub(Address codePointer) {
        final CodeRegion codeRegion = codePointerToCodeRegion(codePointer);
        if (codeRegion != null) {
            return codeRegion.findRuntimeStub(codePointer);
        }
        return null;
    }

    /**
     * A mapping from method keys to target methods.
     */
    private final ArrayBag<MethodKey, TargetMethod> _methodKeyToTargetMethods = new ArrayBag<MethodKey, TargetMethod>(TargetMethod.class, ArrayBag.MapType.HASHED);

    /**
     * Finds any target methods that match the specified method key.
     *
     * @param methodKey the method key to lookup
     * @return an array of target methods that match the specified method key
     */
    synchronized TargetMethod[] methodKeyToTargetMethods(MethodKey methodKey) {
        return _methodKeyToTargetMethods.get(methodKey);
    }

    /**
     * TODO: clean this up.
     */
    void visitCells(Visitor cellVisitor, Action action, RuntimeMemoryRegion from, RuntimeMemoryRegion to) {
        CellVisitorImpl.linearVisitAllCells((BeltWayCellVisitor) cellVisitor, action, Code.bootCodeRegion(), from, to);
        for (CodeRegion codeRegion : _runtimeCodeRegions) {
            if (codeRegion != null) {
                CellVisitorImpl.linearVisitAllCells((BeltWayCellVisitor) cellVisitor, action, codeRegion, from, to);
            }
        }
    }

    /**
     * Visit the cells in all the code regions in this code manager.
     *
     * @param cellVisitor the visitor to call back for each cell in each region
     */
    void visitCells(CellVisitor cellVisitor) {
        Code.bootCodeRegion().visitCells(cellVisitor);
        for (CodeRegion codeRegion : _runtimeCodeRegions) {
            if (codeRegion != null) {
                codeRegion.visitCells(cellVisitor);
            }
        }
    }

    public Size getSize() {
        Size size = Size.zero();
        for (int i = 0; i < _runtimeCodeRegions.length; i++) {
            size = size.plus(_runtimeCodeRegions[i].size());
        }
        return size;
    }
}
