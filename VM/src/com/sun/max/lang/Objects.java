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
package com.sun.max.lang;

import java.lang.reflect.*;

import sun.misc.*;

import com.sun.max.program.*;
import com.sun.max.unsafe.*;
import com.sun.max.vm.*;
import com.sun.max.vm.heap.*;

/**
 * Basic generic utilities for objects.
 *
 * @author Bernd Mathiske
 */
public final class Objects {

    private Objects() {
    }

    private static final Unsafe _unsafe = (Unsafe) WithoutAccessCheck.getStaticField(Unsafe.class, "theUnsafe");

    /**
     * Copies the values of the instance fields in one object to another object.
     *
     * @param fromObject the object from which the field values are to be copied
     * @param toObject the object to which the field values are to be copied
     */
    public static void copy(Object fromObject, Object toObject) {
        assert fromObject.getClass() == toObject.getClass();
        Class c = fromObject.getClass();
        while (c != null) {
            for (Field field : c.getDeclaredFields()) {
                if ((field.getModifiers() & Modifier.STATIC) == 0) {
                    field.setAccessible(true);
                    try {
                        final Object value = field.get(fromObject);
                        field.set(toObject, value);
                    } catch (IllegalArgumentException illegalArgumentException) {
                        // This should never occur
                        throw ProgramError.unexpected(illegalArgumentException);
                    } catch (IllegalAccessException illegalAccessException) {
                        // This should never occur
                        throw ProgramError.unexpected(illegalAccessException);
                    }
                }
            }
            c = c.getSuperclass();
        }
    }

    public static <T> T clone(T object) {
        if (MaxineVM.isPrototyping()) {
            Throwable t;
            final Class<T> type = null;
            try {
                final Object result = WithoutAccessCheck.invokeVirtual(object, "clone", new Class[]{}, new Object[]{});
                return StaticLoophole.cast(type, result);
            } catch (InvocationTargetException invocationTargetException) {
                t = invocationTargetException.getTargetException();
            } catch (Throwable throwable1) {
                t = throwable1;
            }
            if (t instanceof CloneNotSupportedException) {
                try {
                    final Object result = _unsafe.allocateInstance(object.getClass());
                    copy(object, result);
                    return StaticLoophole.cast(type, result);
                } catch (Throwable throwable2) {
                    t = throwable2;
                }
            }
            throw ProgramError.unexpected(t);
        }
        return Heap.clone(object);
    }

    /**
     * Creates a new instance of a given class without calling any constructors. This call also ensures that {@code javaClass}
     * has been initialized.
     *
     * @param javaClass the class to construct an instance of
     * @return an uninitialized of {@code javaClass}
     * @throws InstantiationException if the instantiation fails for any of the reasons described
     *             {@linkplain InstantiationException here}
     */
    public static Object allocateInstance(Class<?> javaClass) throws InstantiationException {
        _unsafe.ensureClassInitialized(javaClass);
        return _unsafe.allocateInstance(javaClass);
    }

}
