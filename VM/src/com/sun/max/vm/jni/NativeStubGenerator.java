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

import static com.sun.max.vm.classfile.constant.PoolConstantFactory.*;
import static com.sun.max.vm.classfile.constant.SymbolTable.*;

import com.sun.max.io.*;
import com.sun.max.program.*;
import com.sun.max.vm.*;
import com.sun.max.vm.actor.holder.*;
import com.sun.max.vm.actor.member.*;
import com.sun.max.vm.bytecode.graft.*;
import com.sun.max.vm.classfile.*;
import com.sun.max.vm.classfile.constant.*;
import com.sun.max.vm.thread.*;
import com.sun.max.vm.type.*;

/**
 * A utility class for generating bytecode that implements the transition
 * from Java to native code. Most of these transitions will made for calling a native function via JNI.
 * However, faster transitions to MaxineVM specific native code is also supported.
 * The steps performed by a generated stub are:
 * <p>
 * <ol>
 *   <li>Record the {@linkplain JniHandles#top() top} of {@linkplain VmThread#jniHandles() the current thread's JNI handle stack}.</li>
 *   <li>Push the pointer to the {@linkplain VmThread#currentJniEnvironmentPointer() current thread's native JNI environment data structure}.</li>
 *   <li>If the native method is static, {@linkplain JniHandles#createStackHandle(Object) handlize} and push the class reference
 *       otherwise handlize and push the receiver reference.</li>
 *   <li>Push the remaining parameters, handlizing non-null references before they are pushed.</li>
 *   <li>Save last Java frame info (stack, frame and instruction pointers) from thread local storage (TLS) to
 *       local variables and then update the TLS info to reflect the frame of the native stub.
 *   <li>Invoke the native function via a Maxine VM specific bytecode which also handles resolving the native function.
 *       The native function symbol is generated by {@linkplain Mangle mangling} the name and signature of the native method appropriately.</li>
 *   <li>Set the last Java instruction pointer in TLS to zero to indicate transition back into Java code.
 *   <li>If the native method returns a reference, {@linkplain JniHandle#unhand() unwrap} the returned handle.</li>
 *   <li>Restore the JNI frame as recorded in the first step.</li>
 *   <li>Throw any {@linkplain VmThread#throwPendingException() pending exception} (if any) for the current thread.</li>
 *   <li>Return the result to the caller.</li>
 * </ol>
 * <p>
 * TODO: The generated stubs need placeholders for platform specific functionality. For example,
 * on Intel systems, a JNI stub needs to (re)set the value of the %mxcsr register if the
 * compiler uses SSE instructions (see the reports for bugs <a href="http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=5003738">5003738</a>
 * and <a href="http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=5105765">5105765</a>).
 *
 * @author Doug Simon
 * @author Bernd Mathiske
 */
public final class NativeStubGenerator extends BytecodeAssembler {

    public NativeStubGenerator(ConstantPoolEditor constantPoolEditor, MethodActor classMethodActor) {
        super(constantPoolEditor);
        this.classMethodActor = classMethodActor;
        allocateParameters(classMethodActor.isStatic(), classMethodActor.descriptor());
        generateCode(classMethodActor.isCFunction(), classMethodActor.isStatic(), classMethodActor.holder(), classMethodActor.descriptor());
    }

    private final SeekableByteArrayOutputStream codeStream = new SeekableByteArrayOutputStream();
    private final MethodActor classMethodActor;

    @Override
    public void writeByte(byte b) {
        codeStream.write(b);
    }

    @Override
    protected void setWritePosition(int position) {
        codeStream.seek(position);
    }

    @Override
    public byte[] code() {
        fixup();
        return codeStream.toByteArray();
    }

    public CodeAttribute codeAttribute() {
        return new CodeAttribute(constantPool(),
                                 code(),
                                 (char) maxStack(),
                                 (char) maxLocals(),
                                 CodeAttribute.NO_EXCEPTION_HANDLER_TABLE,
                                 LineNumberTable.EMPTY,
                                 LocalVariableTable.EMPTY,
                                 null);
    }

    /**
     * These methods may be called from a generated native stub.
     */
    private static final ClassMethodRefConstant currentJniEnvironmentPointer = createClassMethodConstant(VmThread.class, makeSymbol("currentJniEnvironmentPointer"));
    private static final ClassMethodRefConstant currentThread = createClassMethodConstant(VmThread.class, makeSymbol("current"));
    private static final ClassMethodRefConstant traceCurrentThreadPrefix = createClassMethodConstant(NativeStubGenerator.class, makeSymbol("traceCurrentThreadPrefix"));
    private static final ClassMethodRefConstant throwPendingException = createClassMethodConstant(VmThread.class, makeSymbol("throwPendingException"));
    private static final ClassMethodRefConstant createStackHandle = createClassMethodConstant(JniHandles.class, makeSymbol("createStackHandle"), Object.class);
    private static final ClassMethodRefConstant nullHandle = createClassMethodConstant(JniHandle.class, makeSymbol("zero"));
    private static final ClassMethodRefConstant unhandHandle = createClassMethodConstant(JniHandle.class, makeSymbol("unhand"));
    private static final ClassMethodRefConstant handles = createClassMethodConstant(VmThread.class, makeSymbol("jniHandles"));
    private static final ClassMethodRefConstant handlesTop = createClassMethodConstant(JniHandles.class, makeSymbol("top"));
    private static final ClassMethodRefConstant resetHandlesTop = createClassMethodConstant(JniHandles.class, makeSymbol("resetTop"), int.class);
    private static final ClassMethodRefConstant logPrintln_String = createClassMethodConstant(Log.class, makeSymbol("println"), String.class);
    private static final ClassMethodRefConstant logPrint_String = createClassMethodConstant(Log.class, makeSymbol("print"), String.class);
    private static final ClassMethodRefConstant traceJNI = createClassMethodConstant(ClassMethodActor.class, makeSymbol("traceJNI"));
    private static final StringConstant threadLabelPrefix = PoolConstantFactory.createStringConstant("[Thread \"");

    private void generateCode(boolean isCFunction, boolean isStatic, ClassActor holder, SignatureDescriptor signatureDescriptor) {
        final TypeDescriptor resultDescriptor = signatureDescriptor.resultDescriptor();
        final Kind resultKind = resultDescriptor.toKind();
        final StringBuilder nativeFunctionDescriptor = new StringBuilder("(");
        int nativeFunctionArgSlots = 0;
        final TypeDescriptor nativeResultDescriptor = resultKind == Kind.REFERENCE ? JavaTypeDescriptor.JNI_HANDLE : resultDescriptor;

        int jniHandles = 0;
        int top = 0;

        int currentThread = -1;

        int parameterLocalIndex = 0;
        if (!isCFunction) {

            // Cache current thread in a local variable
            invokestatic(NativeStubGenerator.currentThread, 0, 1);
            currentThread = allocateLocal(Kind.REFERENCE);
            astore(currentThread);

            verboseJniEntry();

            // Save current JNI frame.
            jniHandles = allocateLocal(Kind.REFERENCE);
            top = allocateLocal(Kind.INT);
            aload(currentThread);
            invokevirtual(handles, 1, 1);
            astore(jniHandles);
            aload(jniHandles);
            invokevirtual(handlesTop, 1, 1);
            istore(top);

            // Push the JNI environment variable
            invokestatic(currentJniEnvironmentPointer, 0, 1);

            final TypeDescriptor jniEnvDescriptor = currentJniEnvironmentPointer.signature(constantPool()).resultDescriptor();
            nativeFunctionDescriptor.append(jniEnvDescriptor);
            nativeFunctionArgSlots += jniEnvDescriptor.toKind().stackSlots();

            final TypeDescriptor stackHandleDescriptor = createStackHandle.signature(constantPool()).resultDescriptor();
            if (isStatic) {
                // Push the class for a static method
                ldc(createClassConstant(holder.toJava()));
            } else {
                // Push the receiver for a non-static method
                aload(parameterLocalIndex++);
            }
            invokestatic(createStackHandle, 1, 1);
            nativeFunctionDescriptor.append(stackHandleDescriptor);
            nativeFunctionArgSlots += stackHandleDescriptor.toKind().stackSlots();
        } else {
            assert isStatic;
        }

        // Push the remaining parameters, wrapping reference parameters in JNI handles
        for (int i = 0; i < signatureDescriptor.numberOfParameters(); i++) {
            final TypeDescriptor parameterDescriptor = signatureDescriptor.parameterDescriptorAt(i);
            TypeDescriptor nativeParameterDescriptor = parameterDescriptor;
            switch (parameterDescriptor.toKind().asEnum) {
                case BYTE:
                case BOOLEAN:
                case SHORT:
                case CHAR:
                case INT: {
                    iload(parameterLocalIndex);
                    break;
                }
                case FLOAT: {
                    fload(parameterLocalIndex);
                    break;
                }
                case LONG: {
                    lload(parameterLocalIndex);
                    ++parameterLocalIndex;
                    break;
                }
                case DOUBLE: {
                    dload(parameterLocalIndex);
                    ++parameterLocalIndex;
                    break;
                }
                case WORD: {
                    aload(parameterLocalIndex);
                    break;
                }
                case REFERENCE: {
                    assert !isCFunction;
                    final Label nullHandle = newLabel();
                    final Label join = newLabel();

                    // Pseudo-code: (arg == null ? JniHandle.zero() : JniHandle.createStackHandle(arg))
                    aload(parameterLocalIndex);
                    ifnull(nullHandle);
                    aload(parameterLocalIndex);
                    invokestatic(createStackHandle, 1, 1);
                    goto_(join);
                    decStack();
                    nullHandle.bind();
                    invokestatic(NativeStubGenerator.nullHandle, 0, 1);
                    join.bind();
                    nativeParameterDescriptor = JavaTypeDescriptor.JNI_HANDLE;
                    break;
                }
                case VOID: {
                    ProgramError.unexpected();
                }
            }
            nativeFunctionDescriptor.append(nativeParameterDescriptor);
            nativeFunctionArgSlots += nativeParameterDescriptor.toKind().stackSlots();
            ++parameterLocalIndex;
        }

        // Invoke the native function
        callnative(SignatureDescriptor.create(nativeFunctionDescriptor.append(')').append(nativeResultDescriptor).toString()), nativeFunctionArgSlots, nativeResultDescriptor.toKind().stackSlots());

        if (!isCFunction) {
            // Unwrap a reference result from its enclosing JNI handle. This must be done
            // *before* the JNI frame is restored.
            if (resultKind == Kind.REFERENCE) {
                invokevirtual(unhandHandle, 1, 1);
            }

            // Restore JNI frame.
            aload(jniHandles);
            iload(top);
            invokevirtual(resetHandlesTop, 2, 0);

            verboseJniExit();

            // throw (and clear) any pending exception
            aload(currentThread);
            invokevirtual(throwPendingException, 1, 0);
        }

        // Return result
        if (resultKind == Kind.REFERENCE) {
            assert !isCFunction;

            // Insert cast if return type is not java.lang.Object
            if (resultDescriptor != JavaTypeDescriptor.OBJECT) {
                checkcast(createClassConstant(resultDescriptor));
            }
        }

        return_(resultKind);
    }

    /**
     * Generates the code to trace a call to a native function from a native stub.
     */
    private void verboseJniEntry() {
        if (MaxineVM.isHosted()) {
            // Stubs generated while prototyping need to test the "-verbose" VM program argument
            invokestatic(traceJNI, 0, 1);
            final Label noTracing = newLabel();
            ifeq(noTracing);
            traceJniEntry();
            noTracing.bind();
        } else {
            if (JniNativeInterface.verbose()) {
                traceJniEntry();
            }
        }
    }

    private void traceJniEntry() {
        invokestatic(traceCurrentThreadPrefix, 0, 0);
        ldc(PoolConstantFactory.createStringConstant("\" --> JNI: " + classMethodActor.format("%H.%n(%P)") + "]"));
        invokestatic(logPrintln_String, 1, 0);
    }

    /**
     * Generates the code to trace a return to a native stub from a native function.
     */
    private void verboseJniExit() {
        if (MaxineVM.isHosted()) {
            // Stubs generated while prototyping need to test the "-verbose" VM program argument
            invokestatic(traceJNI, 0, 1);
            final Label notVerbose = newLabel();
            ifeq(notVerbose);
            traceJniExit();
            notVerbose.bind();
        } else {
            if (JniNativeInterface.verbose()) {
                traceJniExit();
            }
        }
    }

    private void traceJniExit() {
        invokestatic(traceCurrentThreadPrefix, 0, 0);
        ldc(PoolConstantFactory.createStringConstant("\" <-- JNI: " + classMethodActor.format("%H.%n(%P)") + "]"));
        invokestatic(logPrintln_String, 1, 0);
    }

    private static void traceCurrentThreadPrefix() {
        Log.print("[Thread \"");
        Log.print(VmThread.current().getName());
    }
}
