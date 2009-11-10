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
package com.sun.max.vm.jni;

import static com.sun.max.vm.stack.RawStackFrameVisitor.Util.*;

import java.lang.reflect.*;
import java.util.*;

import sun.reflect.*;

import com.sun.max.annotate.*;
import com.sun.max.collect.*;
import com.sun.max.platform.*;
import com.sun.max.program.*;
import com.sun.max.unsafe.*;
import com.sun.max.vm.actor.member.*;
import com.sun.max.vm.compiler.target.*;
import com.sun.max.vm.runtime.*;
import com.sun.max.vm.stack.*;
import com.sun.max.vm.thread.*;
import com.sun.max.vm.type.*;

/**
 * This class implements part of the Maxine version of the JVM_* interface which the VM presents to the  JDK's native code.
 * Some of the JVM interface is implemented directly in C in "jvm.c" and
 * some is implemented by upcalls to this Java code, which calls into the Maxine VM.
 *
 * @author Ben L. Titzer
 * @author Karthik M
 */
public class JVMFunctions {

    // Checkstyle: stop method name check

    public static Class[] GetClassContext() {
        // Use the stack walker to collect the frames:
        final StackFrameWalker stackFrameWalker = new VmStackFrameWalker(VmThread.current().vmThreadLocals());
        final Sequence<StackFrame> stackFrames = stackFrameWalker.frames(null, VMRegister.getInstructionPointer(), VMRegister.getCpuStackPointer(), VMRegister.getCpuFramePointer());

        // Collect method actors corresponding to frames:
        // N.B. In GuestVM there are no native frames, or JNI calls on the stack that need to be ignored, but we do not want a zero length result from the native frame at the base of the stack!
        final Sequence<ClassMethodActor> methodActors = StackFrameWalker.extractClassMethodActors(stackFrames, false, false, false, Platform.hostOrTarget().operatingSystem != OperatingSystem.GUESTVM);

        // Append the class of each method to the array:
        final List<Class> result = new ArrayList<Class>();
        for (ClassMethodActor methodActor : methodActors) {
            result.add(methodActor.holder().mirror());
        }
        return result.toArray(new Class[result.size()]);
    }

    private static final CriticalMethod javaLangReflectMethodInvoke = new CriticalMethod(Method.class, "invoke",
        SignatureDescriptor.create(Object.class, Object.class, Object[].class));

    private static class LatestUserDefinedLoaderVisitor implements RawStackFrameVisitor {
        ClassLoader result;
        public boolean visitFrame(TargetMethod targetMethod, Pointer instructionPointer, Pointer stackPointer, Pointer framePointer, int flags) {
            if (isTopFrame(flags) || isAdapter(flags) || targetMethod == null || targetMethod.classMethodActor() == javaLangReflectMethodInvoke.classMethodActor) {
                return true;
            }
            final ClassLoader cl = targetMethod.classMethodActor().holder().classLoader;
            if (cl != null && cl != BootClassLoader.BOOT_CLASS_LOADER) {
                result = cl;
                return false;
            }
            return true;
        }
    }

    public static ClassLoader LatestUserDefinedLoader() {
        LatestUserDefinedLoaderVisitor visitor = new LatestUserDefinedLoaderVisitor();
        new VmStackFrameWalker(VmThread.current().vmThreadLocals()).inspect(VMRegister.getInstructionPointer(),
            VMRegister.getCpuStackPointer(),
            VMRegister.getCpuFramePointer(),
            visitor);
        return visitor.result;
    }

    @NEVER_INLINE
    public static Class GetCallerClass(int depth) {
        return Reflection.getCallerClass(depth + 1);
    }

    public static String GetSystemPackage(String name) {
        ClasspathFile classpathFile = BootClassLoader.BOOT_CLASS_LOADER.classpath().classpathFileForPackage(name);
        if (classpathFile == null) {
            return null;
        }
        return classpathFile.classpathEntry.path();
    }

    public static String[] GetSystemPackages() {
        return null;
        //throw Problem.unimplemented();

    }

    public static void ArrayCopy(Object src, int srcPos, Object dest, int destPos, int length) {
        System.arraycopy(src, srcPos, dest, destPos, length);
    }

    // Checkstyle: resume method name check
}
