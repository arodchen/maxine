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
package com.sun.max.vm.actor.holder;

import static com.sun.max.vm.MaxineVM.*;
import static com.sun.max.vm.VMConfiguration.*;

import com.sun.max.annotate.*;
import com.sun.max.collect.*;
import com.sun.max.unsafe.*;
import com.sun.max.vm.*;
import com.sun.max.vm.actor.member.*;
import com.sun.max.vm.compiler.*;
import com.sun.max.vm.compiler.target.*;
import com.sun.max.vm.heap.*;
import com.sun.max.vm.jni.*;
import com.sun.max.vm.layout.*;

/**
 * @author Bernd Mathiske
 */
public final class DynamicHub extends Hub {

    DynamicHub(Size tupleSize, SpecificLayout specificLayout, ClassActor classActor, int[] superClassActorIds, Iterable<InterfaceActor> allInterfaceActors, int vTableLength, TupleReferenceMap referenceMap) {
        super(tupleSize, specificLayout, classActor, superClassActorIds, allInterfaceActors, vTableLength, referenceMap);
    }

    private void initializeMTable(int[] superClassActorIds, Iterable<InterfaceActor> allInterfaceActors, Mapping<MethodActor, VirtualMethodActor> methodLookup, int[] iToV) {
        // The first word of the iTable is where all unused mTable entries point:
        int iTableIndex = iTableStartIndex;
        // We set it to zero so it does not match any class actor's serial (they start at 1):
        setWord(iTableIndex, Address.zero());

        // Initialize all mTable entries by making them point to the first iTable slot:
        for (int mTableIndex = mTableStartIndex; mTableIndex < mTableStartIndex + mTableLength; mTableIndex++) {
            setInt(mTableIndex, iTableIndex);
        }
        iTableIndex++;

        // Reserve iTable space for interface method entries:
        for (InterfaceActor interfaceActor : allInterfaceActors) {
            final int mTableIndex = getMTableIndex(interfaceActor.id);
            setInt(mTableIndex, iTableIndex);
            assert getWord(iTableIndex).isZero();
            setWord(iTableIndex, Address.fromInt(interfaceActor.id));
            iTableIndex++;
            if (classActor.isReferenceClassActor()) {
                for (InterfaceMethodActor interfaceMethodActor : interfaceActor.localInterfaceMethodActors()) {
                    final VirtualMethodActor virtualMethodActor = methodLookup.get(interfaceMethodActor);
                    iToV[iTableIndex - iTableStartIndex] = virtualMethodActor.vTableIndex();
                    iTableIndex++;
                }
            }
        }
        for (int id : superClassActorIds) {
            if (id >= 0) {
                final int mTableIndex = getMTableIndex(id);
                setInt(mTableIndex, iTableIndex);
                assert getWord(iTableIndex).isZero();
                setWord(iTableIndex, Address.fromInt(id));
                iTableIndex++;
            } else {
                // ignore interface ids
            }
        }
    }

    DynamicHub expand(int[] superClassActorIds, Iterable<InterfaceActor> allInterfaceActors, Mapping<MethodActor, VirtualMethodActor> methodLookup, int[] iToV, TupleReferenceMap referenceMap) {
        final DynamicHub hub = (DynamicHub) expand();
        assert hub.mTableLength > 0;
        referenceMap.copyIntoHub(hub);
        hub.initializeMTable(superClassActorIds, allInterfaceActors, methodLookup, iToV);
        return hub;
    }

    void initializeVTable(VirtualMethodActor[] allVirtualMethodActors) {
        boolean compilerCreatesTargetMethods = compilerCreatesTargetMethods();
        for (int i = 0; i < allVirtualMethodActors.length; i++) {
            final VirtualMethodActor virtualMethodActor = allVirtualMethodActors[i];
            final int vTableIndex = firstWordIndex() + i;
            assert virtualMethodActor.vTableIndex() == vTableIndex;
            assert getWord(vTableIndex).isZero();
            Address vTableEntry;

            if (compilerCreatesTargetMethods) {
                vTableEntry = checkCompiled(virtualMethodActor);
                if (vTableEntry.isZero()) {
                    vTableEntry = vm().stubs.virtualTrampoline(vTableIndex);
                }
            } else {
                vTableEntry = MethodID.fromMethodActor(virtualMethodActor).asAddress();
            }
            setWord(vTableIndex, vTableEntry);
        }
    }

    private Address checkCompiled(VirtualMethodActor virtualMethodActor) {
        if (!MaxineVM.isHosted()) {
            final TargetMethod current = virtualMethodActor.currentTargetMethod();
            if (current != null) {
                return current.getEntryPoint(CallEntryPoint.VTABLE_ENTRY_POINT).asAddress();
            }
        }
        return Address.zero();
    }

    /**
     * Ensures all the vtable entries in this hub are resolved to compiled methods, not trampolines.
     */
    public void compileVTable() {
        VirtualMethodActor[] allVirtualMethodActors = classActor.allVirtualMethodActors();
        for (int i = 0; i < allVirtualMethodActors.length; i++) {
            final VirtualMethodActor virtualMethodActor = allVirtualMethodActors[i];
            final int vTableIndex = firstWordIndex() + i;
            assert virtualMethodActor.vTableIndex() == vTableIndex;
            Address vTableEntry = CallEntryPoint.VTABLE_ENTRY_POINT.in(vmConfig().compilationScheme().synchronousCompile(virtualMethodActor));
            setWord(vTableIndex, vTableEntry);
        }
    }

    void initializeITable(Iterable<InterfaceActor> allInterfaceActors, Mapping<MethodActor, VirtualMethodActor> methodLookup) {
        boolean compilerCreatesTargetMethods = compilerCreatesTargetMethods();
        if (classActor.isReferenceClassActor()) {
            for (InterfaceActor interfaceActor : allInterfaceActors) {
                final int interfaceIndex = getITableIndex(interfaceActor.id);
                for (InterfaceMethodActor interfaceMethodActor : interfaceActor.localInterfaceMethodActors()) {
                    final VirtualMethodActor virtualMethodActor = methodLookup.get(interfaceMethodActor);
                    final int iTableIndex = interfaceIndex + interfaceMethodActor.iIndexInInterface();
                    final int iIndex = iTableIndex - iTableStartIndex;
                    assert getWord(iTableIndex).isZero();
                    Address iTableEntry;
                    if (compilerCreatesTargetMethods) {
                        iTableEntry = checkCompiled(virtualMethodActor);
                        if (iTableEntry.isZero()) {
                            iTableEntry = vm().stubs.interfaceTrampoline(iIndex);
                        }
                    } else {
                        iTableEntry = MethodID.fromMethodActor(virtualMethodActor).asAddress();
                    }
                    setWord(iTableIndex, iTableEntry);
                }
            }
        }
    }

    /**
     * Determines whether or not the currently configured compiler compiles all the way down to target methods.
     *
     * TODO: Remove this once the notion of a compiler not being able to compile to target methods is obsolete.
     */
    @FOLD
    private static boolean compilerCreatesTargetMethods() {
        if (!isHosted()) {
            return true;
        }
        CPSCompiler compiler = CPSCompiler.Static.compiler();
        return compiler == null || compiler.compiledType() != null;
    }

    @Override
    public FieldActor findFieldActor(int offset) {
        return classActor.findInstanceFieldActor(offset);
    }

}
