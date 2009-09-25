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
package com.sun.max.tele.object;

import java.lang.reflect.*;
import java.util.*;

import com.sun.max.collect.*;
import com.sun.max.lang.*;
import com.sun.max.memory.*;
import com.sun.max.program.*;
import com.sun.max.tele.*;
import com.sun.max.tele.reference.*;
import com.sun.max.vm.actor.holder.*;
import com.sun.max.vm.actor.member.*;
import com.sun.max.vm.classfile.*;
import com.sun.max.vm.classfile.constant.*;
import com.sun.max.vm.code.*;
import com.sun.max.vm.compiler.builtin.*;
import com.sun.max.vm.compiler.c1x.*;
import com.sun.max.vm.compiler.target.*;
import com.sun.max.vm.jit.*;
import com.sun.max.vm.reference.*;
import com.sun.max.vm.runtime.*;
import com.sun.max.vm.thread.*;
import com.sun.max.vm.type.*;
import com.sun.max.vm.value.*;


/**
 * A singleton factory that manages the creation and maintenance of
 * instances of {@link TeleObject}, each of which is a
 * canonical surrogate for an object in the VM.
 * <br>
 * A {@link TeleObject} is intended to be cannonical, so the unique instance for each object can be
 * retrieved either by location or by OID.  Exceptions to this can occur because of GC:
 * <ul>
 * <li>An object in the VM can be released by the running application and "collected" by the
 * GC.  As soon as this is discovered, the {@TeleObject} that refers to it is marked "dead".
 * <li>During some phases of copying GC, there may be two instances that refer to what
 * is semantically the same object: one referring to the old copy and one referring to the new.</li>
 * <li>As soon as a duplication due to copying is discovered, the {@link TeleObject} that refers
 * to the old copy is marked "obsolete".  It is possible to discover the {@link TeleObject} that
 * refers to the newer copy of the object.</li>
 * <li>A {@link TeleObject} that is either "dead" or "obsolete" is removed from the maps
 *  and cannot be discovered, either by location or OID.</li>
 * </ul>
 *
 * @author Michael Van De Vanter
 */
public final class TeleObjectFactory extends AbstractTeleVMHolder{

    private static final int TRACE_VALUE = 2;

    private static TeleObjectFactory teleObjectFactory;

    /**
     * @return the singleton manager for instances of {@link TeleObject}.
     */
    public static TeleObjectFactory make(TeleVM teleVM) {
        if (teleObjectFactory == null) {
            teleObjectFactory = new TeleObjectFactory(teleVM);
        }
        return teleObjectFactory;
    }

    // TODO (mlvdv)  TeleObject weak references

    /**
     * Map: Reference to {@link Object}s in the VM --> canonical local {@link TeleObject} that represents the
     * object in the VM. Relies on References being canonical and GC-safe.
     */
    private  final GrowableMapping<Reference, TeleObject> referenceToTeleObject = HashMapping.createIdentityMapping();

    /**
     * Map: OID --> {@link TeleObject}.
     */
    private final GrowableMapping<Long, TeleObject> oidToTeleObject = HashMapping.createEqualityMapping();

    /**
     * Constructors for specific classes of tuple objects in the heap in the {@teleVM}.
     * The most specific class that matches a particular {@link TeleObject} will
     * be used, in an emulation of virtual method dispatch.
     */
    private final Map<Class, Constructor> classToTeleTupleObjectConstructor = new HashMap<Class, Constructor>();

    private TeleObjectFactory(TeleVM teleVM) {
        super(teleVM);
        Trace.begin(1, tracePrefix() + "initializing");
        final long startTimeMillis = System.currentTimeMillis();
        // Representation for all tuple objects not otherwise mentioned
        classToTeleTupleObjectConstructor.put(Object.class, getConstructor(TeleTupleObject.class));
        // Some common Java classes
        classToTeleTupleObjectConstructor.put(String.class, getConstructor(TeleString.class));
        classToTeleTupleObjectConstructor.put(Enum.class, getConstructor(TeleEnum.class));
        classToTeleTupleObjectConstructor.put(ClassLoader.class, getConstructor(TeleClassLoader.class));
        // Maxine Actors
        classToTeleTupleObjectConstructor.put(FieldActor.class, getConstructor(TeleFieldActor.class));
        classToTeleTupleObjectConstructor.put(VirtualMethodActor.class, getConstructor(TeleVirtualMethodActor.class));
        classToTeleTupleObjectConstructor.put(StaticMethodActor.class, getConstructor(TeleStaticMethodActor.class));
        classToTeleTupleObjectConstructor.put(InterfaceMethodActor.class, getConstructor(TeleInterfaceMethodActor.class));
        classToTeleTupleObjectConstructor.put(InterfaceActor.class, getConstructor(TeleInterfaceActor.class));
        classToTeleTupleObjectConstructor.put(VmThread.class, getConstructor(TeleVmThread.class));
        classToTeleTupleObjectConstructor.put(PrimitiveClassActor.class, getConstructor(TelePrimitiveClassActor.class));
        classToTeleTupleObjectConstructor.put(ArrayClassActor.class, getConstructor(TeleArrayClassActor.class));
        classToTeleTupleObjectConstructor.put(ReferenceClassActor.class, getConstructor(TeleReferenceClassActor.class));
        // Maxine code management
        classToTeleTupleObjectConstructor.put(JitTargetMethod.class, getConstructor(TeleJitTargetMethod.class));
        classToTeleTupleObjectConstructor.put(C1XTargetMethod.class, getConstructor(TeleC1XTargetMethod.class));
        classToTeleTupleObjectConstructor.put(OptimizedTargetMethod.class, getConstructor(TeleOptimizedTargetMethod.class));
        classToTeleTupleObjectConstructor.put(CodeRegion.class, getConstructor(TeleCodeRegion.class));
        classToTeleTupleObjectConstructor.put(CodeManager.class, getConstructor(TeleCodeManager.class));
        classToTeleTupleObjectConstructor.put(RuntimeMemoryRegion.class, getConstructor(TeleRuntimeMemoryRegion.class));
        // Other Maxine support
        classToTeleTupleObjectConstructor.put(Kind.class, getConstructor(TeleKind.class));
        classToTeleTupleObjectConstructor.put(ObjectReferenceValue.class, getConstructor(TeleObjectReferenceValue.class));
        classToTeleTupleObjectConstructor.put(Builtin.class, getConstructor(TeleBuiltin.class));
        // ConstantPool and PoolConstants
        classToTeleTupleObjectConstructor.put(ConstantPool.class, getConstructor(TeleConstantPool.class));
        classToTeleTupleObjectConstructor.put(CodeAttribute.class, getConstructor(TeleCodeAttribute.class));
        classToTeleTupleObjectConstructor.put(PoolConstant.class, getConstructor(TelePoolConstant.class));
        classToTeleTupleObjectConstructor.put(Utf8Constant.class, getConstructor(TeleUtf8Constant.class));
        classToTeleTupleObjectConstructor.put(StringConstant.class, getConstructor(TeleStringConstant.class));
        classToTeleTupleObjectConstructor.put(ClassConstant.Resolved.class, getConstructor(TeleClassConstant.Resolved.class));
        classToTeleTupleObjectConstructor.put(ClassConstant.Unresolved.class, getConstructor(TeleClassConstant.Unresolved.class));
        classToTeleTupleObjectConstructor.put(FieldRefConstant.Resolved.class, getConstructor(TeleFieldRefConstant.Resolved.class));
        classToTeleTupleObjectConstructor.put(FieldRefConstant.Unresolved.class, getConstructor(TeleFieldRefConstant.Unresolved.class));
        classToTeleTupleObjectConstructor.put(FieldRefConstant.UnresolvedIndices.class, getConstructor(TeleFieldRefConstant.UnresolvedIndices.class));
        classToTeleTupleObjectConstructor.put(ClassMethodRefConstant.Resolved.class, getConstructor(TeleClassMethodRefConstant.Resolved.class));
        classToTeleTupleObjectConstructor.put(ClassMethodRefConstant.Unresolved.class, getConstructor(TeleClassMethodRefConstant.Unresolved.class));
        classToTeleTupleObjectConstructor.put(ClassMethodRefConstant.UnresolvedIndices.class, getConstructor(TeleClassMethodRefConstant.UnresolvedIndices.class));
        classToTeleTupleObjectConstructor.put(InterfaceMethodRefConstant.Resolved.class, getConstructor(TeleInterfaceMethodRefConstant.Resolved.class));
        classToTeleTupleObjectConstructor.put(InterfaceMethodRefConstant.Unresolved.class, getConstructor(TeleInterfaceMethodRefConstant.Unresolved.class));
        classToTeleTupleObjectConstructor.put(InterfaceMethodRefConstant.UnresolvedIndices.class, getConstructor(TeleInterfaceMethodRefConstant.UnresolvedIndices.class));
        // Java language objects
        classToTeleTupleObjectConstructor.put(Class.class, getConstructor(TeleClass.class));
        classToTeleTupleObjectConstructor.put(Constructor.class, getConstructor(TeleConstructor.class));
        classToTeleTupleObjectConstructor.put(Field.class, getConstructor(TeleField.class));
        classToTeleTupleObjectConstructor.put(Method.class, getConstructor(TeleMethod.class));
        classToTeleTupleObjectConstructor.put(TypeDescriptor.class, getConstructor(TeleTypeDescriptor.class));
        classToTeleTupleObjectConstructor.put(SignatureDescriptor.class, getConstructor(TeleSignatureDescriptor.class));

        Trace.end(1, tracePrefix() + "initializing", startTimeMillis);
    }

    private Constructor getConstructor(Class clazz) {
        return Classes.getDeclaredConstructor(clazz, TeleVM.class, Reference.class);
    }

    /**
     * Factory method for canonical {@link TeleObject} surrogate for heap objects in the VM.  Specific subclasses are
     * created for Maxine implementation objects of special interest, and for other objects for which special treatment
     * is desired.
     * <br>
     * Returns null for the distinguished zero {@link Reference}.
     * <br>
     * Care is taken to avoid I/O with the VM during synchronized
     * access to the canonicalization map.  There is a small exception
     * to this for {@link TeleTargetMethod}.
     *
     * @param reference non-null location of a Java object in the VM
     * @return canonical local surrogate for the object
     */
    public TeleObject make(Reference reference) {
        assert reference != null;
        if (reference.isZero()) {
            return null;
        }
        TeleObject teleObject = null;
        synchronized (referenceToTeleObject) {
            teleObject = referenceToTeleObject.get(reference);
        }
        if (teleObject != null) {
            return teleObject;
        }
        // Keep all the VM traffic outside of synchronization.
        if (!teleVM().isValidOrigin(reference.toOrigin())) {
            return null;
        }

        // Most important of the roles played by a {@link TeleObject} is to capture
        // the type of the object at the specified location.  This gets done empirically,
        // by examining the meta-information stored with the presumed object.
        // Because of the meta-circular design, this relies on analysis of meta-information
        // in the VM that is also stored as objects (notably hubs and class actors).  This
        // must be done carefully in order to avoid circularities, which is why the initial
        // investigation must be done using the lowest level memory reading primitives.

        // Location of the {@link Hub} in the VM that describes the layout of the presumed object.
        Reference hubReference;

        // Location of the {@link ClassActor} in the VM that describes the type of the presumed object.
        Reference classActorReference;

        // Local copy of the {@link ClassActor} in the VM that describes the type of the presumed object.
        // We presume to have loaded exactly the same classes as in the VM, so we can use this local
        // copy for a kind of reflective access to the structure of the presumed object.
        ClassActor classActor;

        try {
            // If the location in fact points to a well-formed object in the VM, we will be able to determine the
            // meta-information necessary to understanding how to access information in the object.
            hubReference = teleVM().wordToReference(teleVM().layoutScheme().generalLayout.readHubReferenceAsWord(reference));
            classActorReference = teleVM().fields().Hub_classActor.readReference(hubReference);
            classActor = teleVM().makeClassActor(classActorReference);
        } catch (InvalidReferenceException invalidReferenceException) {
            return null;
        }

        // Must check for the static tuple case first; it doesn't follow the usual rules
        final Reference hubhubReference = teleVM().wordToReference(teleVM().layoutScheme().generalLayout.readHubReferenceAsWord(hubReference));
        final Reference hubClassActorReference = teleVM().fields().Hub_classActor.readReference(hubhubReference);
        final ClassActor hubClassActor = teleVM().makeClassActor(hubClassActorReference);
        final Class hubJavaClass = hubClassActor.toJava();  // the class of this object's hub
        if (StaticHub.class.isAssignableFrom(hubJavaClass)) {
            //teleObject = new TeleStaticTuple(teleVM(), reference);       ?????????
            synchronized (referenceToTeleObject) {
                // Check map again, just in case there's a race
                teleObject = referenceToTeleObject.get(reference);
                if (teleObject == null) {
                    teleObject = new TeleStaticTuple(teleVM(), reference);
                }
            }
        } else if (classActor.isArrayClassActor()) {
            synchronized (referenceToTeleObject) {
                // Check map again, just in case there's a race
                teleObject = referenceToTeleObject.get(reference);
                if (teleObject == null) {
                    teleObject = new TeleArrayObject(teleVM(), reference, classActor.componentClassActor().kind, classActor.dynamicHub().specificLayout);
                }
            }
        } else if (classActor.isHybridClassActor()) {
            final Class javaClass = classActor.toJava();
            synchronized (referenceToTeleObject) {
                // Check map again, just in case there's a race
                teleObject = referenceToTeleObject.get(reference);
                if (teleObject == null) {
                    if (DynamicHub.class.isAssignableFrom(javaClass)) {
                        teleObject = new TeleDynamicHub(teleVM(), reference);
                    } else if (StaticHub.class.isAssignableFrom(javaClass)) {
                        teleObject = new TeleStaticHub(teleVM(), reference);
                    } else {
                        throw FatalError.unexpected("invalid hybrid implementation type");
                    }
                }
            }
        } else if (classActor.isTupleClassActor()) {
            synchronized (referenceToTeleObject) {
                // Check map again, just in case there's a race
                teleObject = referenceToTeleObject.get(reference);
                if (teleObject == null) {
                    final Constructor constructor = lookupTeleTupleObjectConstructor(classActor);
                    try {
                        teleObject = (TeleObject) constructor.newInstance(teleVM(), reference);
                    } catch (InstantiationException e) {
                        throw ProgramError.unexpected();
                    } catch (IllegalAccessException e) {
                        throw ProgramError.unexpected();
                    } catch (InvocationTargetException e) {
                        throw ProgramError.unexpected();
                    }
                }
            }
        } else {
            throw FatalError.unexpected("invalid object implementation type");
        }

        oidToTeleObject.put(teleObject.getOID(), teleObject);
        assert oidToTeleObject.containsKey(teleObject.getOID());

        referenceToTeleObject.put(reference, teleObject);
        return teleObject;
    }

    private Constructor lookupTeleTupleObjectConstructor(ClassActor classActor) {
        Class javaClass = classActor.toJava();
        while (javaClass != null) {
            final Constructor constructor = classToTeleTupleObjectConstructor.get(javaClass);
            if (constructor != null) {
                return constructor;
            }
            javaClass = javaClass.getSuperclass();
        }
        ProgramError.unexpected("TeleObjectFactory failed to find constructor for class" + classActor.toJava());
        return null;
    }

    /**
     * @return the {@link TeleObject} with specified OID.
     */
    public TeleObject lookupObject(long id) {
        return oidToTeleObject.get(id);
    }

    private int previousTeleObjectCount = 0;

    public void refresh(long processEpoch) {
        Trace.begin(TRACE_VALUE, tracePrefix() + "refreshing");
        final long startTimeMillis = System.currentTimeMillis();
        for (TeleObject teleObject : referenceToTeleObject.values()) {
            teleObject.refresh(processEpoch);
        }
        final int currentTeleObjectCount = referenceToTeleObject.length();
        final StringBuilder sb = new StringBuilder(100);
        sb.append(tracePrefix());
        sb.append("refreshing, count=").append(Integer.toString(currentTeleObjectCount));
        sb.append("  new=").append(Integer.toString(currentTeleObjectCount - previousTeleObjectCount));
        Trace.end(TRACE_VALUE, sb.toString(), startTimeMillis);
        previousTeleObjectCount = currentTeleObjectCount;
    }

}
