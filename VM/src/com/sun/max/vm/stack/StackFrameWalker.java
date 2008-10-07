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
package com.sun.max.vm.stack;

import static com.sun.max.vm.jni.JniFunctionWrapper.*;
import static com.sun.max.vm.stack.StackFrameWalker.Purpose.*;
import static com.sun.max.vm.thread.VmThreadLocal.*;

import com.sun.max.annotate.*;
import com.sun.max.collect.*;
import com.sun.max.unsafe.*;
import com.sun.max.vm.*;
import com.sun.max.vm.actor.member.*;
import com.sun.max.vm.code.*;
import com.sun.max.vm.compiler.*;
import com.sun.max.vm.compiler.snippet.NativeStubSnippet.*;
import com.sun.max.vm.compiler.target.*;
import com.sun.max.vm.debug.*;
import com.sun.max.vm.jni.*;
import com.sun.max.vm.runtime.*;
import com.sun.max.vm.thread.*;

/**
 * The mechanism for iterating over the frames in a thread's stack.
 *
 * @author Doug Simon
 * @author Laurent Daynes
 */
public abstract class StackFrameWalker {

    private CompilerScheme _compilerScheme;

    protected StackFrameWalker(CompilerScheme compilerScheme) {
        _compilerScheme = compilerScheme;
    }

    public enum Purpose {
        EXCEPTION_HANDLING(StackUnwindingContext.class),
        REFERENCE_MAP_PREPARING(StackReferenceMapPreparer.class),
        INSPECTING(StackFrameVisitor.class);

        private final Class _contextType;

        private Purpose(Class contextType) {
            _contextType = contextType;
        }

        /**
         * Determines if a given context object is of the type expected by this purpose.
         */
        public final boolean isValidContext(Object context) {
            return _contextType.isInstance(context);
        }
    }

    protected static class CallerFrameCollector implements StackFrameVisitor {
        int _count;
        StackFrame _result;
        @Override
        public boolean visitFrame(StackFrame frame) {
            if (_count++ == 2) {
                _result = frame;
                return false;
            }
            return true;
        }
    }

    private Purpose _purpose = null;
    private Pointer _stackPointer = Pointer.zero();
    private Pointer _framePointer;
    private Pointer _instructionPointer;
    private StackFrame _calleeStackFrame;

    private static final CriticalMethod MaxineVM_run = new CriticalMethod(MaxineVM.class, "run");
    private static final CriticalMethod VmThread_run = new CriticalMethod(VmThread.class, "run");

    /**
     * Walks a thread's stack.
     * <p>
     * Note that this method does not explicitly {@linkplain #reset() reset} this stack walker. If this walk is for the
     * purpose of raising an exception, then the code that unwinds the stack to the exception handler frame is expected
     * to reset this walker. For all other purposes, the caller of this method must reset this walker.
     *
     * @param instructionPointer the instruction pointer of the code executing in the top frame
     * @param stackPointer a pointer denoting an ISA defined location in the top frame
     * @param framePointer a pointer denoting an ISA defined location in the top frame
     * @param purpose the reason this walk is being performed
     * @param context a purpose-specific object of a type {@linkplain Purpose#isValidContext(Object) compatible} with
     *            {@code purpose}
     */
    private void walk(Pointer instructionPointer, Pointer stackPointer, Pointer framePointer, Purpose purpose, Object context) {

        checkPurpose(purpose, context);

        _purpose = purpose;
        _instructionPointer = instructionPointer;
        _framePointer = framePointer;
        _stackPointer = stackPointer;
        boolean isTopFrame = true;
        boolean inNative = isThreadInNative();

        TargetMethod lastJavaCallee = null;
        Pointer lastJavaCalleeStackPointer = Pointer.zero();
        Pointer lastJavaCalleeFramePointer = Pointer.zero();

        while (!_stackPointer.isZero()) {
            final TargetMethod targetMethod = targetMethodFor(_instructionPointer);
            if (targetMethod != null && (!inNative || purpose == INSPECTING)) {
                // Java frame
                if (lastJavaCallee != null) {
                    if (lastJavaCallee.classMethodActor().isCFunction()) {
                        Debug.err.print("Caller of VM entry point (@C_FUNCTION method) \"");
                        Debug.err.print(lastJavaCallee.name());
                        Debug.err.print("\" is not native code: ");
                        Debug.err.print(targetMethod.name());
                        Debug.err.print(targetMethod.classMethodActor().descriptor().string());
                        Debug.err.print(" in ");
                        Debug.err.println(targetMethod.classMethodActor().holder().name().string());
                        FatalError.unexpected("Caller of a VM entry point (@C_FUNCTION method) must be native code");
                    }
                }

                final DynamicCompilerScheme compilerScheme = targetMethod.compilerScheme();
                // Record the last Java callee to be the current frame *before* the compiler scheme
                // updates the current frame during the call to walkJavaFrame()
                lastJavaCalleeStackPointer = _stackPointer;
                lastJavaCalleeFramePointer = _framePointer;
                lastJavaCallee = targetMethod;

                if (!compilerScheme.walkFrame(this, isTopFrame, targetMethod, purpose, context)) {
                    return;
                }

                final ClassMethodActor lastJavaCalleeMethodActor = lastJavaCallee.classMethodActor();
                if (lastJavaCalleeMethodActor.isCFunction()) {
                    if (purpose == INSPECTING && targetMethodFor(_instructionPointer) == null && runtimeStubFor(_instructionPointer) == null) {
                        // Native code frame
                        final StackFrameVisitor stackFrameVisitor = (StackFrameVisitor) context;
                        if (!stackFrameVisitor.visitFrame(new NativeStackFrame(_calleeStackFrame, _instructionPointer, _framePointer, _stackPointer))) {
                            return;
                        }
                    }
                    if (!advanceCFunctionFrame(purpose, lastJavaCallee, lastJavaCalleeStackPointer, lastJavaCalleeFramePointer, context)) {
                        if (isRunMethod(lastJavaCalleeMethodActor)) {
                            return;
                        }
                        FatalError.check(purpose == INSPECTING, "Could not unwind stack past Java method annotated with @C_FUNCTION");
                        return;
                    }
                    lastJavaCallee = null;
                }
            } else {
                final RuntimeStub stub = runtimeStubFor(_instructionPointer);
                if (stub != null && (!inNative || purpose == INSPECTING)) {
                    if (!stub.walkFrame(this, isTopFrame, purpose, context)) {
                        return;
                    }
                } else {
                    if (purpose == INSPECTING) {
                        final StackFrameVisitor stackFrameVisitor = (StackFrameVisitor) context;
                        if (!stackFrameVisitor.visitFrame(new NativeStackFrame(_calleeStackFrame, _instructionPointer, _framePointer, _stackPointer))) {
                            return;
                        }
                    }

                    if (inNative) {
                        inNative = false;
                        advanceFrameInNative(purpose);
                    } else {
                        if (stub != null) {
                            if (!stub.walkFrame(this, isTopFrame, purpose, context)) {
                                return;
                            }
                        } else {
                            if (lastJavaCallee == null) {
                                // This is the native thread start routine (i.e. VmThread.run())
                                return;
                            }

                            final ClassMethodActor lastJavaCalleeMethodActor = lastJavaCallee.classMethodActor();
                            if (lastJavaCalleeMethodActor.isCFunction()) {
                                if (!advanceCFunctionFrame(purpose, lastJavaCallee, lastJavaCalleeStackPointer, lastJavaCalleeFramePointer, context)) {
                                    if (lastJavaCalleeMethodActor.equals(MaxineVM_run) || lastJavaCalleeMethodActor.equals(VmThread_run)) {
                                        return;
                                    }
                                    FatalError.check(purpose == INSPECTING, "Could not unwind stack past Java method annotated with @C_FUNCTION");
                                    return;
                                }
                            } else {
                                Debug.err.print("Native code called/entered a Java method not annotated with @C_FUNCTION: ");
                                Debug.err.print(lastJavaCalleeMethodActor.name().string());
                                Debug.err.print(lastJavaCalleeMethodActor.descriptor().string());
                                Debug.err.print(" in ");
                                Debug.err.println(lastJavaCalleeMethodActor.holder().name().string());
                                FatalError.unexpected("Native code called/entered a Java method that is not a JNI function, a Java trap handler or a Java trap stub");
                            }
                        }
                    }
                }
                lastJavaCallee = null;
            }
            isTopFrame = false;
        }
    }

    private boolean isRunMethod(final ClassMethodActor lastJavaCalleeMethodActor) {
        return lastJavaCalleeMethodActor.equals(MaxineVM_run.classMethodActor()) || lastJavaCalleeMethodActor.equals(VmThread_run.classMethodActor());
    }

    /**
     * Advances this stack walker through the frame of a method annotated with {@link C_FUNCTION}.
     *
     * @param purpose the reason this walk is being performed
     * @param lastJavaCallee
     * @param lastJavaCalleeStackPointer
     * @param lastJavaCalleeFramePointer
     * @return
     */
    private boolean advanceCFunctionFrame(Purpose purpose, TargetMethod lastJavaCallee, Pointer lastJavaCalleeStackPointer, Pointer lastJavaCalleeFramePointer, Object context) {
        final ClassMethodActor lastJavaCalleeMethodActor = lastJavaCallee.classMethodActor();
        if (lastJavaCalleeMethodActor.isJniFunction()) {
            final Pointer namedVariablesBasePointer = _compilerScheme.namedVariablesBasePointer(lastJavaCalleeStackPointer, lastJavaCalleeFramePointer);
            final Word lastJavaCallerInstructionPointer = readWord(savedLastJavaCallerInstructionPointer().address(lastJavaCallee, namedVariablesBasePointer), 0);
            final Word lastJavaCallerStackPointer = readWord(savedLastJavaCallerStackPointer().address(lastJavaCallee, namedVariablesBasePointer), 0);
            final Word lastJavaCallerFramePointer = readWord(savedLastJavaCallerFramePointer().address(lastJavaCallee, namedVariablesBasePointer), 0);
            advance(getReturnInstructionPointerForNativeStub(lastJavaCallerInstructionPointer.asPointer(), true),
                    lastJavaCallerStackPointer,
                    lastJavaCallerFramePointer);
            return true;
        }
        if (lastJavaCalleeMethodActor.isSignalHandlerStub()) {
            final Pointer instructionPointer = readPointer(TRAP_INSTRUCTION_POINTER);
            final Pointer stackPointer = readPointer(TRAP_STACK_POINTER);
            final Pointer framePointer = readPointer(TRAP_FRAME_POINTER);
            if (purpose == EXCEPTION_HANDLING  && context instanceof StackUnwindingContext) {
                ((StackUnwindingContext) context).visitFrame(instructionPointer, stackPointer, framePointer);
            }
            advance(instructionPointer, stackPointer, framePointer);
            return true;
        }
        if (lastJavaCalleeMethodActor.isSignalHandler()) {
            // If the last frame was a Java trap handler, then the frame information needs to be loaded from the trap information
            // if it is available. This state can only occur when stack walking from the inspector.
            FatalError.check(purpose == INSPECTING, "Cannot stack walk when in a signal handler");
            final boolean trapHandlerHasRecordedTrapFrame = trapHandlerHasRecordedTrapFrame();
            if (trapHandlerHasRecordedTrapFrame) {
                advance(readPointer(TRAP_INSTRUCTION_POINTER),
                        readPointer(TRAP_STACK_POINTER),
                        readPointer(TRAP_FRAME_POINTER));
            }
            return true;
        }
        return false;
    }

    /**
     * Advances this walker past the first frame encountered when walking the stack of a thread that is executing
     * {@linkplain VmThread#isInNative() in native} code.
     */
    private void advanceFrameInNative(Purpose purpose) {
        Pointer lastJavaCallerInstructionPointer = readPointer(LAST_JAVA_CALLER_INSTRUCTION_POINTER);
        final Word lastJavaCallerStackPointer;
        final Word lastJavaCallerFramePointer;
        if (!lastJavaCallerInstructionPointer.isZero()) {
            lastJavaCallerStackPointer = readPointer(LAST_JAVA_CALLER_STACK_POINTER);
            lastJavaCallerFramePointer = readPointer(LAST_JAVA_CALLER_FRAME_POINTER);
        } else {
            FatalError.check(purpose == INSPECTING, "Thread cannot be 'in native' without having recorded the last Java caller in thread locals");
            // This code is currently only used by the inspector. The inspector might pause a thread when it is
            // in a C function. We use the LAST_JAVA_CALLER_INSTRUCTION_POINTER_FOR_C in a VM thread's locals
            // to display the Java stack
            lastJavaCallerInstructionPointer = readPointer(LAST_JAVA_CALLER_INSTRUCTION_POINTER_FOR_C);
            lastJavaCallerStackPointer = readPointer(LAST_JAVA_CALLER_STACK_POINTER_FOR_C);
            lastJavaCallerFramePointer = readPointer(LAST_JAVA_CALLER_FRAME_POINTER_FOR_C);

            FatalError.check(!lastJavaCallerInstructionPointer.isZero(), "Thread cannot be 'in native' without having recorded the last Java caller in thread locals");
        }
        advance(getReturnInstructionPointerForNativeStub(lastJavaCallerInstructionPointer, purpose != INSPECTING),
                lastJavaCallerStackPointer,
                lastJavaCallerFramePointer);
    }

    private void checkPurpose(Purpose purpose, Object context) {
        if (!purpose.isValidContext(context)) {
            FatalError.unexpected("Invalid stack walk context");
        }

        //Debug.println(purpose.name());
        //Debug.print("--stackPointer");
        //Debug.print(_stackPointer);
        //Debug.print("--framePointer");
        //Debug.print(_framePointer);
        //Debug.print("--instructionPointer");
        //Debug.print(_instructionPointer);


        if (!_stackPointer.isZero()) {
            Debug.err.print("Stack walker already in use for ");
            Debug.err.println(_purpose.name());
            _stackPointer = Pointer.zero();
            _purpose = null;
            FatalError.unexpected("Stack walker already in use");
        }
    }

    /**
     * Gets the address of the instruction immediately after the call to the native function in a
     * {@linkplain NativeStubGenerator native stub}. That is, get the address of the instruction to which the native
     * code will return. There is a {@linkplain Safepoint#hard() hard} safepoint immediately following the native
     * function call and so this method returns the address of the first instruction implementing the hard safepoint.
     *
     * @param instructionPointer the instruction pointer in a native stub as saved by {@link NativeCallPrologue} or
     *            {@link NativeCallPrologueForC}
     * @param fatalIfNotFound specifies whether a {@linkplain FatalError fatal error} should be raised if no safepoint
     *            instruction can be found after {@code instructionPointer}. If this value is false and no safepoint
     *            instruction is found, then {@code instructionPointer} is returned.
     * @return the address of the first safepoint instruction after {@code instructionPointer}
     */
    private Pointer getReturnInstructionPointerForNativeStub(Pointer instructionPointer, boolean fatalIfNotFound) {
        final TargetMethod nativeStubTargetMethod = targetMethodFor(instructionPointer);
        //FatalError.check(nativeStubTargetMethod.classMethodActor().isNative(), "Instruction pointer not within a native stub");
        if (nativeStubTargetMethod != null) {
            final int firstSafepointIndex = nativeStubTargetMethod.numberOfDirectCalls() + nativeStubTargetMethod.numberOfIndirectCalls();
            final Pointer firstInstruction = nativeStubTargetMethod.codeStart();
            final int instructionPosition = instructionPointer.minus(firstInstruction).toInt();
        nextStop:
            for (int i = firstSafepointIndex; i < nativeStubTargetMethod.numberOfStopPositions(); i++) {
                final int stopPosition = nativeStubTargetMethod.stopPosition(i);
                if (stopPosition >= instructionPosition) {
                    final Pointer stopInstruction = firstInstruction.plus(stopPosition);
                    final byte[] safepointCode = VMConfiguration.target().safepoint().code();
                    for (int offset = 0; offset < safepointCode.length; ++offset) {
                        if (safepointCode[offset] != readByte(stopInstruction, offset)) {
                            break nextStop;
                        }
                    }
                    return stopInstruction;
                }
            }
        }
        if (fatalIfNotFound) {
            throw FatalError.unexpected("Could not find safepoint instruction in native stub after the native function call");
        }
        return instructionPointer;
    }

    /**
     * Walks a thread's stack for the purpose of inspecting one or more frames on the stack. This method takes care of
     * {@linkplain #reset() resetting} this walker before returning.
     */
    public final void inspect(Pointer instructionPointer, Pointer stackPointer, Pointer framePointer, final StackFrameVisitor visitor) {
        // Wraps the visit operation to record the visited frame as the parent of the next frame to be visited.
        final StackFrameVisitor wrapper = new StackFrameVisitor() {
            public boolean visitFrame(StackFrame stackFrame) {
                if (_calleeStackFrame == null || !stackFrame.isSameFrame(_calleeStackFrame)) {
                    _calleeStackFrame = stackFrame;
                } else {
                    Debug.println("Same frame being visited twice: " + stackFrame);
                }
                return visitor.visitFrame(stackFrame);
            }
        };
        walk(instructionPointer, stackPointer, framePointer, INSPECTING, wrapper);
        _calleeStackFrame = null;
        reset();
    }

    /**
     * Walks a thread's stack for the purpose of raising an exception.
     */
    public final void unwind(Pointer instructionPointer, Pointer stackPointer, Pointer framePointer, Throwable throwable) {
        walk(instructionPointer, stackPointer, framePointer, EXCEPTION_HANDLING, _compilerScheme.makeStackUnwindingContext(stackPointer, framePointer, throwable));
    }

    /**
     * Walks a thread's stack for the purpose of preparing the reference map of a thread's stack. This method takes care of
     * {@linkplain #reset() resetting} this walker before returning.
     */
    public final void prepareReferenceMap(Pointer instructionPointer, Pointer stackPointer, Pointer framePointer, StackReferenceMapPreparer preparer) {
        walk(instructionPointer, stackPointer, framePointer, REFERENCE_MAP_PREPARING, preparer);
        reset();
    }

    /**
     * Terminates the current stack walk.
     */
    @INLINE
    public final void reset() {
        _stackPointer = Pointer.zero();
        _purpose = null;
    }

    /**
     * Gets the last stack frame {@linkplain StackFrameVisitor#visitFrame(StackFrame) visited} by this stack walker
     * while {@linkplain #inspect(Pointer, Pointer, Pointer, StackFrameVisitor) inspecting}. The returned frame is
     * the callee frame of the next frame to be visited.
     */
    public final StackFrame calleeStackFrame() {
        return _calleeStackFrame;
    }

    public abstract boolean trapHandlerHasRecordedTrapFrame();

    public abstract boolean isThreadInNative();

    public abstract TargetMethod targetMethodFor(Pointer instructionPointer);

    protected abstract RuntimeStub runtimeStubFor(Pointer instructionPointer);

    /**
     * Determines if this stack walker is currently in use. This is useful for detecting if an exception is being thrown as part of exception handling.
     */
    public final boolean isInUse() {
        return !_stackPointer.isZero();
    }

    @INLINE
    public final Pointer stackPointer() {
        return _stackPointer;
    }

    @INLINE
    public final void advance(Word instructionPointer, Word stackPointer, Word framePointer) {
        _instructionPointer = instructionPointer.asPointer();
        _stackPointer = stackPointer.asPointer();
        _framePointer = framePointer.asPointer();
    }

    /**
     * Advances this stack walker to the trap frame denoted by {@link VmThreadLocal#TRAP_INSTRUCTION_POINTER},
     * {@link VmThreadLocal#TRAP_FRAME_POINTER}, {@link VmThreadLocal#TRAP_STACK_POINTER}.
     */
    public final void advanceToTrapFrame() {
        advance(readPointer(TRAP_INSTRUCTION_POINTER),
                readPointer(TRAP_STACK_POINTER),
                readPointer(TRAP_FRAME_POINTER));
    }

    @INLINE
    public final Pointer framePointer() {
        return _framePointer;
    }

    @INLINE
    public final Pointer instructionPointer() {
        return _instructionPointer;
    }

    /**
     * Collects a sequence of stack frames, beginning a stack walk at the specified instruction pointer, stack pointer,
     * and frame pointer. This method will return all stack frames, including native frames, adapter frames, and
     * non-application visible stack frames. This method accepts an appendable sequence of stack frames
     *
     * @param stackFrames an appendable sequence of stack frames to collect the results; if <code>null</code>, this method
     * will create a new appendable sequence for collecting the result
     * @param instructionPointer the instruction pointer from which to begin the stack walk
     * @param stackPointer the stack pointer from which to begin the stack walk
     * @param framePointer the frame pointer from which to begin the stack walk
     * @return a sequence of all the stack frames, including native, adapter, and non-application visible stack frames,
     *         with the top frame as the first frame
     */
    public Sequence<StackFrame> frames(AppendableSequence<StackFrame> stackFrames, Pointer instructionPointer, Pointer stackPointer, Pointer framePointer) {
        final AppendableSequence<StackFrame> frames = stackFrames == null ? new LinkSequence<StackFrame>() : stackFrames;
        final StackFrameVisitor visitor = new StackFrameVisitor() {
            public boolean visitFrame(StackFrame stackFrame) {
                frames.append(stackFrame);
                return true;
            }
        };
        inspect(instructionPointer, stackPointer, framePointer, visitor);
        return frames;
    }

    /**
     * Extracts a sequence of class method actors from a sequence of stack frames. It accepts a number of options that
     * indicate whether to include the top frame, adapter frames, native frames, and other frames that should not be
     * application visible.
     *
     * @param stackFrames an iterable list of stack frames
     * @param topFrame true if this method should include the ClassMethodActor of the top frame
     * @param adapterFrames true if adapter frames should be reported
     * @param invisibleFrames true if invisible frames should be reported
     * @return
     */
    public static Sequence<ClassMethodActor> extractClassMethodActors(Iterable<StackFrame> stackFrames, boolean topFrame, boolean adapterFrames, boolean invisibleFrames) {
        final AppendableSequence<ClassMethodActor> result = new LinkSequence<ClassMethodActor>();
        boolean top = true;
        for (StackFrame stackFrame : stackFrames) {
            if (top) {
                top = false;
                if (!topFrame) {
                    continue;
                }
            }
            if (stackFrame.isAdapter() && !adapterFrames) {
                continue;
            }
            final TargetMethod targetMethod = Code.codePointerToTargetMethod(stackFrame.instructionPointer());
            if (targetMethod == null) {
                // native frame
                continue;
            }
            final TargetJavaFrameDescriptor javaFrameDescriptor = targetMethod.getPrecedingJavaFrameDescriptor(stackFrame.instructionPointer());
            if (javaFrameDescriptor == null) {
                appendClassMethodActor(result, targetMethod.classMethodActor(), invisibleFrames);
            } else {
                appendInlinedFrameDescriptors(result, javaFrameDescriptor, invisibleFrames);
            }
        }
        return result;
    }

    private static void appendInlinedFrameDescriptors(AppendableSequence<ClassMethodActor> result, TargetJavaFrameDescriptor javaFrameDescriptor, boolean invisibleFrames) {
        // this recursive method appends inlined frame descriptors to the frame list (i.e. parent first)
        final TargetJavaFrameDescriptor parentFrameDescriptor = javaFrameDescriptor.parent();
        if (parentFrameDescriptor != null) {
            appendInlinedFrameDescriptors(result, parentFrameDescriptor, invisibleFrames);
        }
        appendClassMethodActor(result, javaFrameDescriptor.bytecodeLocation().classMethodActor(), invisibleFrames);
    }

    private static void appendClassMethodActor(final AppendableSequence<ClassMethodActor> result, final ClassMethodActor classMethodActor, boolean invisibleFrames) {
        if (classMethodActor.isApplicationVisible() || invisibleFrames) {
            result.append(classMethodActor);
        }
    }

    public abstract Word readWord(Address address, int offset);
    public abstract byte readByte(Address address, int offset);
    public abstract int readInt(Address address, int offset);

    public abstract Word readFramelessCallAddressRegister(TargetABI targetABI);

    public abstract Word readWord(VmThreadLocal local);
    public Pointer readPointer(VmThreadLocal local) {
        return readWord(local).asPointer();
    }

    /**
     * Updates the stack walker's frame and stack pointers with those specified by the target ABI (use the ABI stack and frame pointers).
     * This may be necessary when initiating stack walking: by default the stack frame walker uses the stack and frame pointers defined by the CPU.
     * This is incorrect when the ABI pointers differs from the CPU pointers (like it is the case with some JIT implementation, currently).
     * @param targetABI
     */
    public abstract void useABI(TargetABI targetABI);

    @NEVER_INLINE
    public StackFrame getCallerFrame() {
        final CallerFrameCollector visitor = new CallerFrameCollector();
        walk(VMRegister.getInstructionPointer(), VMRegister.getCpuStackPointer(), VMRegister.getCpuFramePointer(), INSPECTING, visitor);
        return visitor._result;
    }
}
