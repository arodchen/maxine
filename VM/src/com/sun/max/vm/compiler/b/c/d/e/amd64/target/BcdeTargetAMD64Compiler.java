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
package com.sun.max.vm.compiler.b.c.d.e.amd64.target;

import static com.sun.max.vm.compiler.CallEntryPoint.*;

import com.sun.max.annotate.*;
import com.sun.max.asm.*;
import com.sun.max.asm.amd64.*;
import com.sun.max.collect.*;
import com.sun.max.lang.*;
import com.sun.max.program.*;
import com.sun.max.unsafe.*;
import com.sun.max.vm.*;
import com.sun.max.vm.MaxineVM.*;
import com.sun.max.vm.actor.holder.*;
import com.sun.max.vm.actor.member.*;
import com.sun.max.vm.asm.amd64.*;
import com.sun.max.vm.classfile.constant.*;
import com.sun.max.vm.code.*;
import com.sun.max.vm.compiler.*;
import com.sun.max.vm.compiler.b.c.d.e.amd64.*;
import com.sun.max.vm.compiler.ir.*;
import com.sun.max.vm.compiler.snippet.*;
import com.sun.max.vm.compiler.target.*;
import com.sun.max.vm.runtime.*;
import com.sun.max.vm.runtime.amd64.*;
import com.sun.max.vm.stack.*;
import com.sun.max.vm.stack.StackFrameWalker.*;
import com.sun.max.vm.stack.amd64.*;
import com.sun.max.vm.thread.*;

/**
 * @author Bernd Mathiske
 */
public final class BcdeTargetAMD64Compiler extends BcdeAMD64Compiler implements TargetGeneratorScheme {

    private final AMD64EirToTargetTranslator _eirToTargetTranslator;

    protected AMD64EirToTargetTranslator createTargetTranslator() {
        return new AMD64EirToTargetTranslator(this);
    }

    public BcdeTargetAMD64Compiler(VMConfiguration vmConfiguration) {
        super(vmConfiguration);
        _eirToTargetTranslator = new AMD64EirToTargetTranslator(this);
    }

    public TargetGenerator targetGenerator() {
        return _eirToTargetTranslator;
    }

    @Override
    public IrGenerator irGenerator() {
        return _eirToTargetTranslator;
    }

    @Override
    public Sequence<IrGenerator> irGenerators() {
        return Sequence.Static.appended(super.irGenerators(), targetGenerator());
    }

    static final int RIP_CALL_INSTRUCTION_SIZE = 5;

    @INLINE
    private static void patchRipCallSite(Pointer callSite, Address calleeEntryPoint) {
        final int calleeOffset = calleeEntryPoint.minus(callSite.plus(RIP_CALL_INSTRUCTION_SIZE)).toInt();
        callSite.writeInt(1, calleeOffset);
    }

    /**
     * @see StaticTrampoline
     */
    @Override
    @NEVER_INLINE
    public void staticTrampoline() {
        final StaticTrampolineContext context = new StaticTrampolineContext();
        new VmStackFrameWalker(VmThread.current().vmThreadLocals()).inspect(VMRegister.getInstructionPointer(),
                                                      VMRegister.getCpuStackPointer(),
                                                      VMRegister.getCpuFramePointer(),
                                                      context);
        final Pointer callSite = context.instructionPointer().minus(RIP_CALL_INSTRUCTION_SIZE);
        final TargetMethod caller = Code.codePointerToTargetMethod(callSite);

        final ClassMethodActor callee = caller.callSiteToCallee(callSite);
        // Use the caller's abi to get the correct entry point.
        final Address calleeEntryPoint = CompilationScheme.Static.compile(callee, caller.abi().callEntryPoint(), CompilationDirective.DEFAULT);
        patchRipCallSite(callSite, calleeEntryPoint);

        // Make the trampoline's caller re-execute the now modified CALL instruction after we return from the trampoline:
        final Pointer stackPointer = context.stackPointer().minus(Word.size());
        stackPointer.setWord(callSite); // patch return address
    }

    @Override
    public Word createInitialVTableEntry(int vTableIndex, VirtualMethodActor dynamicMethodActor) {
        return  vmConfiguration().trampolineScheme().makeVirtualCallEntryPoint(vTableIndex);
    }

    @Override
    public Word createInitialITableEntry(int iIndex, VirtualMethodActor dynamicMethodActor) {
        return  vmConfiguration().trampolineScheme().makeInterfaceCallEntryPoint(iIndex);
    }

    public void patchCallSite(TargetMethod targetMethod, int callOffset, Word callEntryPoint) {
        final Pointer callSite = targetMethod.codeStart().plus(callOffset).asPointer();
        final AMD64Assembler assembler = new AMD64Assembler(callSite.toLong());
        final Label label = new Label();
        assembler.fixLabel(label, callEntryPoint.asAddress().toLong());
        assembler.call(label);
        try {
            final byte[] code = assembler.toByteArray();
            Bytes.copy(code, 0, targetMethod.code(), callOffset, code.length);
        } catch (AssemblyException assemblyException) {
            ProgramError.unexpected("patching call site failed", assemblyException);
        }
    }

    private static final byte RET = (byte) 0xC3;
    private static final byte RET2 = (byte) 0xC2;


    private static boolean walkAdapterFrame(StackFrameWalker stackFrameWalker, TargetMethod targetMethod, Purpose purpose, Object context, Pointer startOfAdapter, boolean isTopFrame) {
        final Pointer instructionPointer = stackFrameWalker.instructionPointer();
        final Pointer stackPointer = stackFrameWalker.stackPointer();
        final Pointer jitEntryPoint = JIT_ENTRY_POINT.in(targetMethod);
        final int adapterFrameSize = AMD64AdapterFrameGenerator.jitToOptimizingAdapterFrameSize(stackFrameWalker, startOfAdapter);
        Pointer callerFramePointer = stackFrameWalker.framePointer();

        Pointer ripPointer = stackPointer; // stack pointer at call entry point (where the RIP is).
        final byte firstInstructionByte = stackFrameWalker.readByte(instructionPointer, 0);
        if (!instructionPointer.equals(jitEntryPoint) && !instructionPointer.equals(startOfAdapter) && firstInstructionByte != RET2) {
            ripPointer = stackPointer.plus(adapterFrameSize);
            callerFramePointer = stackFrameWalker.readWord(ripPointer, -Word.size()).asPointer();
        }

        final Pointer callerInstructionPointer = stackFrameWalker.readWord(ripPointer, 0).asPointer();
        switch(purpose) {
            case EXCEPTION_HANDLING: {
                // cannot have an exception while in an adapter frame
                break;
            }
            case REFERENCE_MAP_PREPARING: {
                break;
            }
            case INSPECTING: {
                final StackFrameVisitor stackFrameVisitor = (StackFrameVisitor) context;
                final StackFrame stackFrame = new AMD64JitToOptimizedAdapterFrame(stackFrameWalker.calleeStackFrame(), targetMethod, instructionPointer, stackFrameWalker.framePointer(), stackPointer, adapterFrameSize);
                if (!stackFrameVisitor.visitFrame(stackFrame)) {
                    return false;
                }
                break;
            }
            default: {
                ProgramError.unknownCase();
            }
        }
        stackFrameWalker.advance(callerInstructionPointer, ripPointer.plus(Word.size() /* skip RIP */), callerFramePointer);
        return true;
    }


    /**
     * Determines if an execution point is in some adapter frame related code.
     *
     * @param inTopFrame specifies if the execution point is in the top frame on the stack
     * @param instructionPointer the address of the next instruction that will be executed
     * @param optimizedEntryPoint the address of the first instruction compiled by this compiler
     * @param startOfAdapter the address of the first instruction that sets up the adapter frame
     * @return true if the execution point denoted by the combination of {@code instructionPointer} and
     *         {@code inTopFrame} is in adapter frame related code
     */
    private static boolean inAdapterFrameCode(boolean inTopFrame, final Pointer instructionPointer, final Pointer optimizedEntryPoint, final Pointer startOfAdapter) {
        if (instructionPointer.lessThan(optimizedEntryPoint)) {
            return true;
        }
        if (inTopFrame) {
            return instructionPointer.greaterEqual(startOfAdapter);
        }
        // Since we are not in the top frame, instructionPointer is really the return instruction pointer of
        // a call. If it happens that the call is to a method that is never expected to return normally (e.g. a method that only exits by throwing an exception),
        // the call may well be the very last instruction in the method prior to the adapter frame code.
        // In this case, we're only in adapter frame code if the instructionPointer is greater than
        // the start of the adapter frame code.
        return instructionPointer.greaterThan(startOfAdapter);
    }

    @Override
    public boolean walkFrame(StackFrameWalker stackFrameWalker, boolean isTopFrame, TargetMethod targetMethod, Purpose purpose, Object context) {
        final Pointer instructionPointer = stackFrameWalker.instructionPointer();
        final Pointer stackPointer = stackFrameWalker.stackPointer();
        final Pointer entryPoint;
        if (targetMethod.abi().callEntryPoint().equals(CallEntryPoint.C_ENTRY_POINT)) {
            // Simple case (no adapter)
            entryPoint = C_ENTRY_POINT.in(targetMethod);
        } else {
            // we may be in an adapter
            final Pointer jitEntryPoint = JIT_ENTRY_POINT.in(targetMethod);
            final Pointer optimizedEntryPoint = OPTIMIZED_ENTRY_POINT.in(targetMethod);
            final boolean hasAdapterFrame = !(jitEntryPoint.equals(optimizedEntryPoint));

            if (hasAdapterFrame) {
                final Pointer startOfAdapter = AMD64AdapterFrameGenerator.jitEntryPointJmpTarget(stackFrameWalker, targetMethod);
                if (inAdapterFrameCode(isTopFrame, instructionPointer, optimizedEntryPoint, startOfAdapter)) {
                    return walkAdapterFrame(stackFrameWalker, targetMethod, purpose, context, startOfAdapter, isTopFrame);
                }
            }
            entryPoint = optimizedEntryPoint;
        }

        final int frameSize;
        final Pointer ripPointer; // stack pointer at call entry point (where the RIP is).
        if (instructionPointer.equals(entryPoint) || stackFrameWalker.readByte(instructionPointer, 0) == RET) {
            // We are at the very first or the very last instruction to be executed.
            // In either case the stack pointer is unmodified wrt. the CALL that got us here.
            frameSize = 0;
            ripPointer = stackPointer;
        } else {
            // We are somewhere in the middle of this method.
            // The stack pointer has been bumped already to access locals and it has not been reset yet.
            frameSize = targetMethod.frameSize();
            ripPointer = stackPointer.plus(frameSize);
        }

        switch (purpose) {
            case REFERENCE_MAP_PREPARING: {
                // frame pointer == stack pointer
                final StackReferenceMapPreparer preparer = (StackReferenceMapPreparer) context;
                Pointer trapState = stackFrameWalker.trapState();
                if (!trapState.isZero()) {
                    FatalError.check(!targetMethod.classMethodActor().isTrapStub(), "Cannot have a trap in the trapStub");
                    final Safepoint safepoint = VMConfiguration.hostOrTarget().safepoint();
                    if (Trap.Number.isImplicitException(safepoint.getTrapNumber(trapState))) {
                        final Address catchAddress = targetMethod.throwAddressToCatchAddress(safepoint.getInstructionPointer(trapState));
                        if (catchAddress.isZero()) {
                            // An implicit exception occurred but not in the scope of a local exception handler.
                            // Thus, execution will not resume in this frame and hence no GC roots need to be scanned.
                            break;
                        }
                        // TODO: Get address of safepoint instruction at exception dispatcher site and scan
                        // the frame references based on its Java frame descriptor.
                        Problem.unimplemented("Cannot reliably find safepoint at exception dispatcher site yet.");
                    }
                } else {
                    if (targetMethod.classMethodActor().isTrapStub()) {
                        final Safepoint safepoint = VMConfiguration.hostOrTarget().safepoint();
                        trapState = AMD64Safepoint.getTrapStateFromRipPointer(ripPointer);
                        stackFrameWalker.setTrapState(trapState);
                        if (Trap.Number.isImplicitException(safepoint.getTrapNumber(trapState))) {
                            final Address catchAddress = targetMethod.throwAddressToCatchAddress(safepoint.getInstructionPointer(trapState));
                            if (catchAddress.isZero()) {
                                // An implicit exception occurred but not in the scope of a local exception handler.
                                // Thus, execution will not resume in this frame and hence no GC roots need to be scanned.
                            } else {
                                // TODO: Get address of safepoint instruction at exception dispatcher site and scan
                                // the register references based on its Java frame descriptor.
                                Problem.unimplemented("Cannot reliably find safepoint at exception dispatcher site yet.");
                                preparer.prepareRegisterReferenceMap(safepoint.getRegisterState(trapState), catchAddress.asPointer());
                            }
                        } else {
                            // Only scan with references in registers for a caller that did not trap due to an implicit exception.
                            // Find the register state and pass it to the preparer so that it can be covered with the appropriate reference map
                            final Pointer callerInstructionPointer = stackFrameWalker.readWord(ripPointer, 0).asPointer();
                            preparer.prepareRegisterReferenceMap(safepoint.getRegisterState(trapState), callerInstructionPointer);
                        }
                    }
                }
                if (!targetMethod.prepareFrameReferenceMap(preparer, instructionPointer, stackPointer, stackPointer)) {
                    return false;
                }
                break;
            }
            case EXCEPTION_HANDLING: {
                // if not at the top frame, subtract 1 to get an address that is _inside_ the call instruction of the caller
                final Address throwAddress = isTopFrame ? instructionPointer : instructionPointer.minus(1);
                final Address catchAddress = targetMethod.throwAddressToCatchAddress(throwAddress);
                if (!catchAddress.isZero()) {
                    final Throwable throwable = UnsafeLoophole.cast(StackUnwindingContext.class, context)._throwable;
                    if (!(throwable instanceof StackOverflowError) || VmThread.current().hasSufficentStackToReprotectGuardPage(stackPointer)) {
                        // Reset the stack walker
                        stackFrameWalker.reset();
                        // Completes the exception handling protocol (with respect to the garbage collector) initiated in Throw.raise()
                        Safepoint.enable();
                        unwind(throwable, catchAddress, stackPointer);
                    }
                }
                break;
            }
            case INSPECTING: {
                final StackFrameVisitor stackFrameVisitor = (StackFrameVisitor) context;
                if (!stackFrameVisitor.visitFrame(new AMD64JavaStackFrame(stackFrameWalker.calleeStackFrame(), targetMethod, instructionPointer, stackPointer, stackPointer))) {
                    return false;
                }
                break;
            }
        }

        final Pointer callerInstructionPointer = stackFrameWalker.readWord(ripPointer, 0).asPointer();
        final Pointer callerStackPointer = ripPointer.plus(Word.size()); // Skip RIP word
        final Pointer callerFramePointer;
        if (targetMethod.classMethodActor().isTrapStub()) {
            // framePointer is whatever was in the frame pointer register at the time of the trap
            final Pointer trapState = AMD64Safepoint.getTrapStateFromRipPointer(ripPointer);
            callerFramePointer = stackFrameWalker.readWord(trapState, AMD64GeneralRegister64.RBP.value() * Word.size()).asPointer();
        } else {
            // framePointer == stackPointer for this scheme.
            callerFramePointer = callerStackPointer;
        }
        stackFrameWalker.advance(callerInstructionPointer, callerStackPointer, callerFramePointer);
        return true;
    }

    @Override
    public void initialize(MaxineVM.Phase phase) {
        super.initialize(phase);
        if (phase == Phase.PROTOTYPING) {
            _unwindMethod = ClassActor.fromJava(BcdeTargetAMD64Compiler.class).findLocalClassMethodActor(SymbolTable.makeSymbol("unwind"));
        }
    }

    private static ClassMethodActor _unwindMethod;

    private static int _unwindFrameSize = -1;

    private static int getUnwindFrameSize() {
        if (_unwindFrameSize == -1) {
            _unwindFrameSize = CompilationScheme.Static.getCurrentTargetMethod(_unwindMethod).frameSize();
        }
        return _unwindFrameSize;
    }

    /**
     * Unwinds a thread's stack to an exception handler.
     * <p>
     * The compiled version of this method must have it's own frame but the frame size must be known at image build
     * time. This is because this code manually adjusts the stack pointer.
     * <p>
     * The critical state of the registers before the RET instruction is:
     * <ul>
     * <li>RAX must hold the exception object</li>
     * <li>RSP must be one word less than the stack pointer of the handler frame that is the target of the unwinding</li>
     * <li>The value at [RSP] must be the address of the handler code</li>
     * </ul>
     *
     * @param throwable the exception object
     * @param catchAddress the address of the exception handler code
     * @param stackPointer the stack pointer denoting the frame of the handler to which the stack is unwound upon
     *            returning from this method
     */
    @NEVER_INLINE
    private static Throwable unwind(Throwable throwable, Address catchAddress, Pointer stackPointer) {
        // Push 'catchAddress' to the handler's stack frame and update RSP to point to the pushed value.
        // When the RET instruction is executed, the pushed 'catchAddress' will be popped from the stack
        // and the stack will be in the correct state for the handler.
        final Pointer returnAddressPointer = stackPointer.minus(Word.size());
        returnAddressPointer.setWord(catchAddress);
        if (_unwindFrameSize == -1) {
            _unwindFrameSize = getUnwindFrameSize();
        }
        VMRegister.setCpuStackPointer(returnAddressPointer.minus(_unwindFrameSize));

        // put the throwable in the return slot
        // NOTE: this is potentially dangerous, since this value must not be spilled onto the stack,
        // since the adjustment to the stack pointer above would cause the spill code to load the wrong value.
        return throwable;
    }

    @Override
    public Pointer namedVariablesBasePointer(Pointer stackPointer, Pointer framePointer) {
        return stackPointer;
    }

    @Override
    public void advance(StackFrameWalker stackFrameWalker, Word instructionPointer, Word stackPointer, Word framePointer) {
        // stack pointer = frame pointer for this scheme
        stackFrameWalker.advance(instructionPointer, stackPointer, stackPointer);
    }

    @Override
    public StackUnwindingContext makeStackUnwindingContext(Word stackPointer, Word framePointer, Throwable throwable) {
        return new StackUnwindingContext(throwable);
    }
}
