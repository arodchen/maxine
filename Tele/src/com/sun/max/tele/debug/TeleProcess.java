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
package com.sun.max.tele.debug;

import static com.sun.max.tele.debug.ProcessState.*;

import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;

import com.sun.max.collect.*;
import com.sun.max.gui.*;
import com.sun.max.memory.*;
import com.sun.max.platform.*;
import com.sun.max.program.*;
import com.sun.max.tele.*;
import com.sun.max.tele.debug.TeleNativeThread.*;
import com.sun.max.tele.method.*;
import com.sun.max.tele.method.CodeLocation.*;
import com.sun.max.tele.object.*;
import com.sun.max.tele.page.*;
import com.sun.max.unsafe.*;
import com.sun.max.vm.thread.*;

/**
 * A model of the remote process in which the VM is running,
 * which includes access to the memory state and debugging
 * actions that control execution.
 *
 * @author Bernd Mathiske
 * @author Aritra Bandyopadhyay
 * @author Doug Simon
 * @author Michael Van De Vanter
 * @author Hannes Payer
 */
public abstract class TeleProcess extends AbstractTeleVMHolder implements TeleIO {

    private static final int TRACE_VALUE = 2;

    // Standard names for process control actions.
    private static final String RUN_TO_INSTRUCTION = "runToInstruction";
    private static final String TERMINATE = "terminate";
    private static final String PAUSE = "pause";
    private static final String RESUME = "resume";
    private static final String SINGLE_STEP = "singleStep";
    private static final String STEP_OVER = "stepOver";

    private static final Sequence<TeleNativeThread> EMPTY_THREAD_SEQUENCE = Sequence.Static.empty(TeleNativeThread.class);
    private static final Sequence<TeleBreakpointEvent> EMPTY_BREAKPOINTEVENT_SEQUENCE = Sequence.Static.empty(TeleBreakpointEvent.class);


    public static final String[] EMPTY_COMMAND_LINE_ARGUMENTS = {};

    /**
     * Allocates and initializes a buffer in native memory to hold a given set of command line arguments. De-allocating
     * the memory for the buffer is the responsibility of the caller.
     *
     * @param programFile the executable that will be copied into element 0 of the returned buffer
     * @param commandLineArguments
     * @return a native buffer than can be cast to the C type {@code char**} and used as the first argument to a C
     *         {@code main} function
     */
    protected static Pointer createCommandLineArgumentsBuffer(File programFile, String[] commandLineArguments) {
        final String[] strings = new String[commandLineArguments.length + 1];
        strings[0] = programFile.getAbsolutePath();
        System.arraycopy(commandLineArguments, 0, strings, 1, commandLineArguments.length);
        return CString.utf8ArrayFromStringArray(strings, true);
    }

    /**
     * A thread on which local VM execution requests are made, and on which follow-up actions are
     * performed when the VM process (or thread) stops.
     */
    private class RequestHandlingThread extends Thread {

        /**
         * The action to be performed when the process (or a thread in the process) stops as a result of the
         * currently executing process request.
         */
        private BlockingDeque<TeleEventRequest> requests = new LinkedBlockingDeque<TeleEventRequest>(10);

        RequestHandlingThread() {
            super("RequestHandlingThread");
            setDaemon(true);
        }

        /**
         * Waits until the VM process has stopped after it has been issued an execution request.
         * Carry out any special processing needed in case of triggered watchpoint or breakpoint.
         * The request's post-execution action is then performed.
         * Finally, any threads waiting for an execution request to complete are notified.
         *
         * @param request the intended VM action assumed to be running in the VM.
         */
        private void waitUntilProcessStopped(TeleEventRequest request) {
            assert requestHandlingThread == Thread.currentThread();
            Trace.begin(TRACE_VALUE, tracePrefix() + "waiting for execution to stop: " + request);
            try {
                final AppendableSequence<TeleBreakpointEvent> teleBreakpointEvents = new LinkSequence<TeleBreakpointEvent>();
                TeleWatchpointEvent teleWatchpointEvent = null;

                // Keep resuming the process, after dealing with event handlers, as long as this is true.
                boolean resumeExecution = true;
                do {  // while resumeExecution = true
                    assert teleBreakpointEvents.isEmpty();
                    assert teleWatchpointEvent == null;

                    // There are five legitimate cases where execution should halt (i.e. not be resumed),
                    // although there may be more than one simultaneously .
                    // Case 1. The process has died
                    // Case 2. A thread was just single stepped
                    // Case 3. At least one thread is at a breakpoint that specifies that execution should halt
                    // Case 4. At least one thread is at a memory watchpoint that specifies that execution should halt
                    // Case 5. There is a "pause" request pending

                    // Check for the special case where we can't determine what caused the VM to stop
                    boolean eventCauseFound = false;

                    // Wait here for VM process to stop
                    ProcessState newState = waitUntilStopped();

                    // Case 1. The process has died; throw an exception.
                    if (newState != ProcessState.STOPPED) {
                        if (newState != ProcessState.TERMINATED) {
                            throw new ProcessTerminatedException("unexpected state [" + newState + "]");
                        }
                        throw new ProcessTerminatedException("normal exit");
                    }
                    Trace.line(TRACE_VALUE, tracePrefix() + "Execution stopped: " + request);

                    if (lastSingleStepThread != null) {
                        // Case 2. A thread was just single stepped; do not continue execution.
                        eventCauseFound = true;
                        resumeExecution = false;
                    }

                    // Read VM memory and update various bits of cached state about the VM state
                    teleVM().refresh(++epoch);
                    refreshThreads();
                    targetBreakpointFactory().setActiveAll(false);

                    // Look through all the threads to see which, if any, have events triggered that caused the stop
                    for (TeleNativeThread thread : threads()) {
                        switch(thread.state()) {
                            case BREAKPOINT:
                                eventCauseFound = true;
                                final TeleTargetBreakpoint breakpoint = thread.breakpoint();
                                if (breakpoint.handleTriggerEvent(thread)) {
                                    Trace.line(TRACE_VALUE, tracePrefix() + " stopping thread [id=" + thread.id() + "] after triggering breakpoint");
                                    // Case 3. At least one thread is at a breakpoint that specifies that execution should halt; record it and do not continue.
                                    // At a breakpoint where we should really stop; create a record
                                    teleBreakpointEvents.append(new TeleBreakpointEvent(breakpoint, thread));
                                    resumeExecution = false;
                                }
                                break;
                            case WATCHPOINT:
                                eventCauseFound = true;
                                final Address triggeredWatchpointAddress = Address.fromLong(readWatchpointAddress());
                                final TeleWatchpoint systemTeleWatchpoint = watchpointFactory.findSystemWatchpoint(triggeredWatchpointAddress);
                                if (systemTeleWatchpoint != null && systemTeleWatchpoint.handleTriggerEvent(thread)) {
                                    Trace.line(TRACE_VALUE, tracePrefix() + " stopping thread [id=" + thread.id() + "] after triggering system watchpoint");
                                    // Case 4. At least one thread is at a memory watchpoint that specifies that execution should halt; record it and do not continue.
                                    resumeExecution = false;
                                }
                                final TeleWatchpoint clientTeleWatchpoint = watchpointFactory.findClientWatchpointContaining(triggeredWatchpointAddress);
                                if (clientTeleWatchpoint != null && clientTeleWatchpoint.handleTriggerEvent(thread)) {
                                    Trace.line(TRACE_VALUE, tracePrefix() + " stopping thread [id=" + thread.id() + "] after triggering client watchpoint");
                                    // Case 4. At least one thread is at a memory watchpoint that specifies that execution should halt; record it and do not continue.
                                    final int triggeredWatchpointCode = readWatchpointAccessCode();
                                    teleWatchpointEvent = new TeleWatchpointEvent(clientTeleWatchpoint, thread, triggeredWatchpointAddress, triggeredWatchpointCode);
                                    resumeExecution = false;
                                }
                                break;
                            default:
                                // This thread not stopped at breakpoint or watchpoint
                                break;
                        }
                    }
                    if (pauseRequestPending) {
                        // Case 5. There is a "pause" request pending
                        // Whether or not the process has threads at breakpoints or watchpoints,
                        // we must not resume execution if a client-originated pause has been requested.
                        eventCauseFound = true;
                        resumeExecution = false;
                        pauseRequestPending = false;
                    }
                    ProgramError.check(eventCauseFound, "Process halted for no apparent cause");
                    if (resumeExecution) {
                        Trace.line(TRACE_VALUE, tracePrefix() + "Resuming execution after handling event triggers: " + request);
                        restoreBreakpointsAndResume(request.withClientBreakpoints);
                    }
                } while (resumeExecution);
                // Finished with these now
                targetBreakpointFactory().removeTransientBreakpoints();
                Trace.end(TRACE_VALUE, tracePrefix() + "waiting for execution to stop: " + request);
                Trace.begin(TRACE_VALUE, tracePrefix() + "firing execution post-request action: " + request);
                request.notifyProcessStopped();
                Trace.end(TRACE_VALUE, tracePrefix() + "firing execution post-request action: " + request);
                updateState(STOPPED, teleBreakpointEvents, teleWatchpointEvent);

            } catch (ProcessTerminatedException processTerminatedException) {
                Trace.line(TRACE_VALUE, tracePrefix() + "VM process terminated: " + processTerminatedException.getMessage());
                updateState(TERMINATED);
            } catch (Throwable throwable) {
                throwable.printStackTrace();
                ThrowableDialog.showLater(throwable, null, tracePrefix() + "Uncaught exception while processing " + request);
            } finally {
                Trace.begin(TRACE_VALUE, tracePrefix() + "notifying completion of request: " + request);
                request.notifyOfCompletion();
                Trace.end(TRACE_VALUE, tracePrefix() + "notifying completion of request: " + request);
            }
        }

        private String traceSuffix(boolean synchronous) {
            return " (" + (synchronous ? "synchronous" : "asynchronous") + ")";
        }

        /**
         * Accepts a tele process execution request and schedules it for execution on the
         * {@linkplain #requestHandlingThread() request handling thread}. The request is executed immediately if the
         * {@linkplain Thread#currentThread() current} thread is the request handling thread. Otherwise, it will be executed
         * once any pending request has completed.
         *
         * @param request an execution request to schedule
         * @param synchronous if this value is true or the {@linkplain Thread#currentThread() current thread} is the
         *            {@linkplain #requestHandlingThread() request handling thread}, this method will block until the
         *            request's post execution action has
         *            completed. Otherwise, this method returns after scheduling the request and notifying the request
         *            handling thread.
         */
        void scheduleRequest(TeleEventRequest request, boolean isSynchronous) {
            final Thread currentThread = Thread.currentThread();
            if (currentThread == this) {
                Trace.begin(TRACE_VALUE, tracePrefix() + "immediate execution request: " + traceSuffix(isSynchronous));
                execute(request, true);
                Trace.end(TRACE_VALUE, tracePrefix() + "immediate execution request: " + request + traceSuffix(isSynchronous));
            } else {
                try {
                    Trace.begin(TRACE_VALUE, tracePrefix() + "scheduled execution request: " + request + traceSuffix(isSynchronous));
                    requests.putFirst(request);
                    Trace.end(TRACE_VALUE, tracePrefix() + "scheduled execution request: " + request + traceSuffix(isSynchronous));
                } catch (InterruptedException interruptedException) {
                    ProgramWarning.message(tracePrefix() + "Could not schedule " + request + ": " + interruptedException);
                    return;
                }

                if (isSynchronous) {
                    Trace.begin(TRACE_VALUE, tracePrefix() + "waiting for synchronous request to complete: " + request);
                    request.waitUntilComplete();
                    Trace.end(TRACE_VALUE, tracePrefix() + "waiting for synchronous request to complete: " + request);
                }
            }
        }

        private void execute(TeleEventRequest request, boolean isNested) {
            if (!isNested && processState != STOPPED) {
                ProgramWarning.message(tracePrefix() + "Cannot execute \"" + request + "\" unless process state is " + STOPPED);
            } else {
                try {
                    lastSingleStepThread = null;
                    Trace.begin(TRACE_VALUE, tracePrefix() + "executing request: " + request);
                    updateState(RUNNING);
                    request.execute();
                    Trace.end(TRACE_VALUE, tracePrefix() + "executing request: " + request);
                } catch (OSExecutionRequestException executionRequestException) {
                    executionRequestException.printStackTrace();
                    return;
                }
                waitUntilProcessStopped(request);
            }
        }

        @Override
        public void run() {
            while (true) {
                try {
                    final TeleEventRequest request = requests.takeLast();
                    teleVM().lock();
                    Trace.begin(TRACE_VALUE, tracePrefix() + "handling execution request: " + request);
                    execute(request, false);
                    Trace.end(TRACE_VALUE, tracePrefix() + "handling execution request: " + request);
                } catch (InterruptedException interruptedException) {
                    ProgramWarning.message(tracePrefix() + "Could not take request from sceduling queue: " + interruptedException);
                } finally {
                    teleVM().unlock();
                }
            }
        }
    }

    @Override
    protected String  tracePrefix() {
        return "[TeleProcess: " + Thread.currentThread().getName() + "] ";
    }

    private final Platform platform;

    private final TeleTargetBreakpoint.Factory targetBreakpointFactory;

    private final int maximumWatchpointCount;

    /**
     * A factory for creating and managing memory watchpoints in the VM;
     * null if watchpoints are not supported on this platform.
     *
     * @see #watchpointsEnabled()
     */
    private final TeleWatchpoint.Factory watchpointFactory;

    private final RequestHandlingThread requestHandlingThread;

    /**
     *  The number of times that the VM's process has been run.
     */
    private long epoch;

    /**
     * The current state of the process.
     */
    private ProcessState processState;

    /**
     * All threads currently active in the process.
     */
    private SortedMap<Long, TeleNativeThread> handleToThreadMap = new TreeMap<Long, TeleNativeThread>();

    /**
     * Instruction pointers for all threads currently active in the process.
     */
    private Set<Long> instructionPointers = new HashSet<Long>();

    /**
     * The thread that was single-stepped in the most recent VM activation,
     * null if the most recent activation was not a single step.
     */
    private TeleNativeThread lastSingleStepThread;

    /**
     * Threads newly created since the previous execution request.
     */
    private final Set<TeleNativeThread> threadsStarted = new TreeSet<TeleNativeThread>();

    /**
     * Threads newly died since the previous execution request (may contain newly created threads).
     */
    private final Set<TeleNativeThread> threadsDied = new TreeSet<TeleNativeThread>();

    /**
     * Whether a client-initiated request to pause the process is pending.
     */
    private boolean pauseRequestPending = false;

    private int transportDebugLevel = 0;

    /**
     * @param teleVM the VM with which the Process will be associated
     * @param platform
     * @param initialState Initial state of the process at creation.
     */
    protected TeleProcess(TeleVM teleVM, Platform platform, ProcessState initialState) {
        super(teleVM);
        this.platform = platform;
        this.processState = initialState;
        epoch = 0;
        this.targetBreakpointFactory = new TeleTargetBreakpoint.Factory(teleVM);
        this.maximumWatchpointCount = platformWatchpointCount();
        this.watchpointFactory = watchpointsEnabled() ? new TeleWatchpoint.Factory(teleVM, this) : null;

        //Initiate the thread that continuously waits on the running process.
        this.requestHandlingThread = new RequestHandlingThread();
        if (initialState != ProcessState.UNKNOWN) {
            this.requestHandlingThread.start();
        }
    }

    /**
     * Initializes the state history for the process.  Must be called after process created, but before
     * any requests.
     */
    public final void initializeState() {
        teleVM().refresh(epoch);
        updateState(processState);
    }

    /**
     * Gets the singleton for creating and managing VM watchpoints; null if not enabled
     * on this platform.
     *
     * @return the creator/manager of watchpoints; null watchpoints not supported on platform.
     */
    public final TeleWatchpoint.Factory getWatchpointFactory() {
        return watchpointFactory;
    }

    /**
     * Causes VM execution of a single instruction on a specified thread.
     *
     * @param thread the thread to be executed.
     * @param isSynchronous wait until execution is complete to return?
     * @throws InvalidVMRequestException
     * @throws OSExecutionRequestException
     */
    public final void singleStepThread(final TeleNativeThread thread, boolean isSynchronous) throws InvalidVMRequestException, OSExecutionRequestException    {
        Trace.begin(TRACE_VALUE, tracePrefix() + SINGLE_STEP + " schedule");
        final TeleEventRequest request = new TeleEventRequest(SINGLE_STEP, thread, false) {
            @Override
            public void execute() throws OSExecutionRequestException {
                Trace.begin(TRACE_VALUE, tracePrefix() + SINGLE_STEP + " perform");
                updateWatchpointCaches();
                singleStep(thread, false);
                Trace.end(TRACE_VALUE, tracePrefix() + SINGLE_STEP + " perform");
            }
        };
        requestHandlingThread.scheduleRequest(request, isSynchronous);
        Trace.end(TRACE_VALUE, tracePrefix() + SINGLE_STEP + " schedule");
    }

    /**
     * Steps a single thread to the next instruction in the current method.  If the current
     * instruction is a call, then run until the call returns.
     * <br>
     * This is effected by first single stepping and then noticing if execution has arrived at the
     * next instruction (the simple case).  If not, then assume that the thread stepped into a
     * call, set a transient breakpoint, and resume.
     *
     * @param thread the thread to step
     * @param synchronous wait for execution to complete before returning?
     * @param withClientBreakpoints should client breakpoints be enabled during execution?
     * @throws InvalidVMRequestException
     * @throws OSExecutionRequestException
     */
    public final void stepOver(final TeleNativeThread thread, boolean synchronous, final boolean withClientBreakpoints) throws InvalidVMRequestException, OSExecutionRequestException {
        Trace.begin(TRACE_VALUE, STEP_OVER + " schedule");
        final TeleEventRequest request = new TeleEventRequest(STEP_OVER, thread, withClientBreakpoints) {

            private Pointer oldInstructionPointer;
            private Pointer oldReturnAddress;

            @Override
            public void execute() throws OSExecutionRequestException {
                Trace.begin(TRACE_VALUE, tracePrefix() + STEP_OVER + " perform");
                updateWatchpointCaches();
                oldInstructionPointer = thread.instructionPointer();
                oldReturnAddress = thread.getReturnLocation().address().asPointer();
                singleStep(thread, false);
                Trace.end(TRACE_VALUE, tracePrefix() + STEP_OVER + " perform");
            }

            @Override
            public void notifyProcessStopped() {
                final CodeLocation stepOutLocation = getStepoutLocation(thread, oldReturnAddress, oldInstructionPointer, thread.instructionPointer());
                if (stepOutLocation != null) {
                    try {
                        runToInstruction(stepOutLocation, true, withClientBreakpoints);
                    } catch (OSExecutionRequestException e) {
                        e.printStackTrace();
                    } catch (InvalidVMRequestException e) {
                        e.printStackTrace();
                    }
                }
            }
        };
        requestHandlingThread.scheduleRequest(request, synchronous);
        Trace.end(TRACE_VALUE, STEP_OVER + " schedule");
    }

    /**
     * Resumes process to make it run until a given destination instruction is reached.
     * <br>
     * This is effected by creating a transient breakpoint and then performing an ordinary resume.
     *
     * @param codeLocation the destination instruction in compiled code
     * @param synchronous wait for completion before returning?
     * @param withClientBreakpoints enable client breakpoints during execution?
     * @throws OSExecutionRequestException
     * @throws InvalidVMRequestException
     */
    public final void runToInstruction(final CodeLocation codeLocation, final boolean synchronous, final boolean withClientBreakpoints) throws OSExecutionRequestException, InvalidVMRequestException {
        assert codeLocation instanceof MachineCodeLocation;
        final MachineCodeLocation compiledCodeLocation = (MachineCodeLocation) codeLocation;
        Trace.begin(TRACE_VALUE, tracePrefix() + RUN_TO_INSTRUCTION + " schedule");
        final TeleEventRequest request = new TeleEventRequest(RUN_TO_INSTRUCTION, null, withClientBreakpoints) {
            @Override
            public void execute() throws OSExecutionRequestException {
                Trace.begin(TRACE_VALUE, tracePrefix() + RUN_TO_INSTRUCTION + " perform");
                updateWatchpointCaches();
                // Create a temporary breakpoint if there is not already an enabled, non-persistent breakpoint for the target address:
                TeleTargetBreakpoint breakpoint = targetBreakpointFactory.findClientBreakpoint(compiledCodeLocation);
                if (breakpoint == null || !breakpoint.isEnabled()) {
                    try {
                        breakpoint = breakpointFactory().makeTransientTargetBreakpoint(compiledCodeLocation);
                    } catch (MaxVMBusyException e) {
                        ProgramError.unexpected("run to instruction should alwasy be executed inside VM lock on request handling thread");
                    }
                    breakpoint.setDescription("transient breakpoint for low-level run-to-instruction operation");
                }
                restoreBreakpointsAndResume(withClientBreakpoints);
                Trace.end(TRACE_VALUE, tracePrefix() + RUN_TO_INSTRUCTION + " perform");
            }
        };
        requestHandlingThread.scheduleRequest(request, synchronous);
        Trace.end(TRACE_VALUE, tracePrefix() + RUN_TO_INSTRUCTION + " schedule");
    }

    /**
     * Resumes process execution.
     *
     * @param synchronous wait for completion before returning?
     * @param withClientBreakpoints enable client breakpoints during execution?
     * @throws OSExecutionRequestException
     * @throws InvalidVMRequestException
     */
    public final void resume(final boolean synchronous, final boolean withClientBreakpoints) throws InvalidVMRequestException, OSExecutionRequestException {
        Trace.begin(TRACE_VALUE, tracePrefix() + RESUME + " schedule");
        final TeleEventRequest request = new TeleEventRequest(RESUME, null, withClientBreakpoints) {
            @Override
            public void execute() throws OSExecutionRequestException {
                Trace.begin(TRACE_VALUE, tracePrefix() + RESUME + " perform");
                updateWatchpointCaches();
                restoreBreakpointsAndResume(withClientBreakpoints);
                Trace.end(TRACE_VALUE, tracePrefix() + RESUME + " perform");
            }
        };
        requestHandlingThread.scheduleRequest(request, synchronous);
        Trace.end(TRACE_VALUE, tracePrefix() + RESUME + " schedule");
    }

    /**
     * Request that VM execution suspend as soon as possible. <br>
     * The suspended process may or may not have threads stopped
     * at breakpoints by the time execution stops completely.
     *
     * @throws InvalidVMRequestException
     * @throws OSExecutionRequestException
     */
    public final void pauseProcess() throws InvalidVMRequestException, OSExecutionRequestException {
        Trace.begin(TRACE_VALUE, tracePrefix() + PAUSE + " perform");
        if (processState != RUNNING) {
            throw new InvalidVMRequestException("Can only suspend a running tele process, not a tele process that is " + processState.toString().toLowerCase());
        }
        pauseRequestPending = true;
        suspend();
        Trace.end(TRACE_VALUE, tracePrefix() + PAUSE + " perform");
    }

    /**
     * Kill the VM process immediately.
     *
     * @throws InvalidVMRequestException
     * @throws OSExecutionRequestException
     */
    public final void terminateProcess() throws InvalidVMRequestException, OSExecutionRequestException {
        Trace.begin(TRACE_VALUE, tracePrefix() + TERMINATE + " perform");
        if (processState == TERMINATED) {
            throw new InvalidVMRequestException("Can only terminate a non-terminated tele process, not a tele process that is " + processState.toString().toLowerCase());
        }
        kill();
        Trace.end(TRACE_VALUE, tracePrefix() + TERMINATE + " perform");
    }

    /**
     * Gets the current process epoch: the number of requested execution steps of the process since it was created.
     * <br>
     * Note that this is different from the number of execution requests made by clients, since the process
     * may be run several times in the execution of such a request.
     *
     * @return the current process epoch
     */
    public final long epoch() {
        return epoch;
    }

    public final int pageSize() {
        return platform.pageSize;
    }

    public final int read(Address address, ByteBuffer buffer, int offset, int length) throws DataIOError {
        if (processState == TERMINATED) {
            throw new DataIOError(address, "Attempt to read the memory when the process is in state " + TERMINATED);
        }
        if (processState != STOPPED && processState != null && Thread.currentThread() != requestHandlingThread) {
        //    ProgramWarning.message("Reading from process memory while processed not stopped [thread: " + Thread.currentThread().getName() + "]");
        }
        DataIO.Static.checkRead(buffer, offset, length);
        final int bytesRead = read0(address, buffer, offset, length);
        if (bytesRead < 0) {
            throw new DataIOError(address);
        }
        return bytesRead;
    }

    public final int write(ByteBuffer buffer, int offset, int length, Address address) throws DataIOError, IndexOutOfBoundsException {
        if (processState == TERMINATED) {
            throw new DataIOError(address, "Attempt to write to memory when the process is in state " + TERMINATED);
        }
        if (processState != STOPPED && processState != null && Thread.currentThread() != requestHandlingThread) {
            //ProgramWarning.message("Writing to process memory while processed not stopped [thread: " + Thread.currentThread().getName() + "]");
            /* Uncomment to trace the culprit
            try {
                throw new Exception();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            */
        }
        DataIO.Static.checkWrite(buffer, offset, length);
        return write0(buffer, offset, length, address);
    }

    /**
     * @return the current state of the process
     */
    public final ProcessState processState() {
        return processState;
    }

    /**
     * Gets the set of all the threads in this process the last time it stopped.
     * The returned threads are sorted in ascending order of their {@linkplain TeleNativeThread#id() identifiers}.
     *
     * @return the threads in the process
     */
    public final IterableWithLength<TeleNativeThread> threads() {
        return Iterables.toIterableWithLength(handleToThreadMap.values());
    }

    /**
     * Thread-safe.
     *
     * @return whether watchpoints are supported on this platform.
     */
    public boolean watchpointsEnabled() {
        return maximumWatchpointCount > 0;
    }

    /**
     * @return factory for creation and management of target breakpoints in the process,
     */
    public final TeleTargetBreakpoint.Factory targetBreakpointFactory() {
        return targetBreakpointFactory;
    }

    /**
     * @return platform-specific limit on how many memory watchpoints can be
     * simultaneously active; 0 if memory watchpoints are not supported on the platform.
     */
    protected abstract int platformWatchpointCount();

    /**
     * @return tracing level of the underlying transportation
     * mechanism used for communication with this process.
     *
     * @see #setTransportDebugLevel(int)
     */
    public final int transportDebugLevel() {
        return transportDebugLevel;
    }

    /**
     * A subclass should override this method to set the tracing level of the underlying
     * transport mechanism used for communication with the target. The override should call
     * super.setTransportDebugLevel to cache the value here.
     * @param level new level
     */
    public void setTransportDebugLevel(int level) {
        transportDebugLevel = level;
    }

    /**
     * @return address to data i/ from process memory; platform-specific implementation.
     */
    public abstract DataAccess dataAccess();

    private void refreshThreads() {
        Trace.begin(TRACE_VALUE, tracePrefix() + "Refreshing remote threads:");
        final long startTimeMillis = System.currentTimeMillis();
        final AppendableSequence<TeleNativeThread> currentThreads = new ArrayListSequence<TeleNativeThread>(handleToThreadMap.size());
        gatherThreads(currentThreads);

        final SortedMap<Long, TeleNativeThread> newHandleToThreadMap = new TreeMap<Long, TeleNativeThread>();
        final Set<Long> newInstructionPointers = new HashSet<Long>();

        // List all previously live threads as possibly dead; remove the ones discovered to be current.
        threadsDied.addAll(handleToThreadMap.values());

        for (TeleNativeThread thread : currentThreads) {

            // Refresh the thread
            thread.refresh(epoch());

            newHandleToThreadMap.put(thread.localHandle(), thread);
            final TeleNativeThread oldThread = handleToThreadMap.get(thread.localHandle());
            if (oldThread != null) {
                if (oldThread != thread) {
                    threadsStarted.add(thread);
                } else {
                    threadsDied.remove(thread);
                    Trace.line(TRACE_VALUE, "    "  + thread);
                }
            } else {
                threadsStarted.add(thread);
                Trace.line(TRACE_VALUE, "    "  + thread + " STARTED");
            }
            final Pointer instructionPointer = thread.instructionPointer();
            if (!instructionPointer.isZero()) {
                newInstructionPointers.add(thread.instructionPointer().toLong());
            }
        }
        Trace.end(TRACE_VALUE, tracePrefix() + "Refreshing remote threads:", startTimeMillis);
        handleToThreadMap = newHandleToThreadMap;
        instructionPointers = newInstructionPointers;
    }

    /**
     * Gathers information about this process and posts a thread-safe record of the state change.
     *
     * @param newState the current state of this process
     * @param teleWatchpointEvent description of watchpoint trigger, if just happened.
     */
    private void updateState(ProcessState newState, Sequence<TeleBreakpointEvent> breakpointEvents, TeleWatchpointEvent teleWatchpointEvent) {
        processState = newState;
        if (newState == TERMINATED) {
            this.threadsDied.addAll(handleToThreadMap.values());
            handleToThreadMap.clear();
        }
        for (TeleNativeThread thread : this.threadsDied) {
            thread.setDead();
            Trace.line(TRACE_VALUE, tracePrefix() + "    "  + thread.toShortString() + " DEAD");
        }
        final Sequence<TeleNativeThread> threadsStarted =
            this.threadsStarted.isEmpty() ? EMPTY_THREAD_SEQUENCE : new ArrayListSequence<TeleNativeThread>(this.threadsStarted);
        final Sequence<TeleNativeThread> threadsDied =
            this.threadsDied.isEmpty() ? EMPTY_THREAD_SEQUENCE : new ArrayListSequence<TeleNativeThread>(this.threadsDied);
        this.threadsStarted.clear();
        this.threadsDied.clear();
        teleVM().notifyStateChange(processState, epoch, lastSingleStepThread, handleToThreadMap.values(), threadsStarted, threadsDied, breakpointEvents, teleWatchpointEvent);
    }

    private void updateState(ProcessState newState) {
        updateState(newState, EMPTY_BREAKPOINTEVENT_SEQUENCE, null);
    }

    /**
     * Given the code location before and after a single step, this method determines if the step represents a call
     * from one target method to another (or a recursive call from a target method to itself) and, if so, returns the
     * address of the next instruction that will be executed in the target method that is the origin of the step (i.e.
     * the return address of the call).
     *
     * @param thread the executing thread
     * @param oldReturnAddress the return address of the thread just before the single step
     * @param oldInstructionPointer the instruction pointer of the thread just before the single step
     * @param newInstructionPointer the instruction pointer of the thread just after the single step
     * @return if {@code oldInstructionPointer} and {@code newInstructionPointer} indicate two different target methods
     *         or a recursive call to the same target method, then the return location of the call is returned.
     *         Otherwise, null is returned, indicating that the step over is really just a single step.
     */
    private CodeLocation getStepoutLocation(TeleNativeThread thread, Pointer oldReturnAddress, Pointer oldInstructionPointer, Pointer newInstructionPointer) {
        if (newInstructionPointer.equals(oldReturnAddress)) {
            // Executed a return
            return null;
        }
        final TeleTargetMethod oldTeleTargetMethod = TeleTargetMethod.make(teleVM(), oldInstructionPointer);
        if (oldTeleTargetMethod == null) {
            // Stepped from native code:
            return null;
        }
        final TeleTargetMethod newTeleTargetMethod = TeleTargetMethod.make(teleVM(), newInstructionPointer);
        if (newTeleTargetMethod == null) {
            // Stepped into native code:
            return null;
        }
        if (oldTeleTargetMethod != newTeleTargetMethod || newTeleTargetMethod.callEntryPoint().equals(newInstructionPointer)) {
            // Stepped into a different target method or back into the entry of the same target method (i.e. a recursive call):
            return thread.getReturnLocation();
        }
        // Stepped over a normal, non-call instruction:
        return null;
    }

    /**
     * Re-activates breakpoints and resumes VM execution, first ensuring
     * that no threads are stuck at breakpoints.
     *
     * @param withClientBreakpoints should client-created breakpoints be activated?
     * @throws OSExecutionRequestException
     */
    private void restoreBreakpointsAndResume(boolean withClientBreakpoints) throws OSExecutionRequestException {
        for (TeleNativeThread thread : threads()) {
            thread.evadeBreakpoint();
        }
        if (withClientBreakpoints) {
            targetBreakpointFactory.setActiveAll(true);
        } else {
            targetBreakpointFactory.setActiveNonClient(true);
        }
        resume();
    }

    /**
     * Resumes this process, platform-specific implementation.
     *
     * @throws OSExecutionRequestException if there was some problem while resuming this process
     */
    protected abstract void resume() throws OSExecutionRequestException;

    /**
     * Suspends this process, platform-specific implementation.
     *
     * @throws OSExecutionRequestException if the request could not be performed
     */
    protected abstract void suspend() throws OSExecutionRequestException;

    /**
     * Single steps a given thread.
     *
     * @param thread the thread to single step
     * @param block specifies if the current thread should wait until the single step completes
     * @throws OSExecutionRequestException if there was a problem issuing the single step
     */
    protected final void singleStep(TeleNativeThread thread, boolean block) throws OSExecutionRequestException {
        if (!block) {
            lastSingleStepThread = thread;
        }
        if (!thread.singleStep()) {
            throw new OSExecutionRequestException("Error while single stepping thread " + thread);
        }
        if (block) {
            if (waitUntilStopped() != ProcessState.STOPPED) {
                throw new OSExecutionRequestException("Error while waiting for complete single stepping thread " + thread);
            }
        }
    }

    /**
     * Kills this process; platform-specific implementation.
     *
     * @throws OSExecutionRequestException
     */
    protected abstract void kill() throws OSExecutionRequestException;

    /**
     * Waits for this process to stop.
     *
     * @return true if the process stopped, false if there was an error
     */
    protected abstract ProcessState waitUntilStopped();

    protected abstract void gatherThreads(AppendableSequence<TeleNativeThread> threads);

    /**
     * Creates a native thread; platform-specific implementation.
     *
     * @param params description of thread to be created.
     * @return the new thread
     */
    protected abstract TeleNativeThread createTeleNativeThread(Params params);

    /**
     * Callback from JNI: creates new thread object or updates existing thread object with same thread ID.
     *
     * @param threads the sequence being used to collect the threads
     * @param id the {@link VmThread#id() id} of the thread. If {@code id > 0}, then this thread corresponds to a
     *            {@link VmThread Java thread}. If {@code id == 0}, then this is the primordial thread. Otherwise, this
     *            is a native thread or a Java thread that has not yet executed past the point in
     *            {@link VmThread#run()} where it is added to the active thread list.
     * @param localHandle the platform-specific process control library handle to this thread
     * @param handle the native thread library {@linkplain TeleNativeThread#handle() handle} to this thread
     * @param state
     * @param instructionPointer the current value of the instruction pointer
     * @param stackBase the lowest known address of the stack
     * @param stackSize the size of the stack in bytes
     * @param tlb the thread locals region of the thread
     * @param tlbSize the size of the thread locals region
     * @param tlaSize the size of a thread locals area
     */
    public final void jniGatherThread(AppendableSequence<TeleNativeThread> threads,
                    int id,
                    long localHandle,
                    long handle,
                    int state,
                    long instructionPointer,
                    long stackBase,
                    long stackSize,
                    long tlb,
                    int tlbSize,
                    int tlaSize) {
        assert state >= 0 && state < MaxThreadState.VALUES.length() : state;
        TeleNativeThread thread = handleToThreadMap.get(localHandle);

        final MemoryRegion stackRegion = new FixedMemoryRegion(Address.fromLong(stackBase), Size.fromLong(stackSize), "stack region");
        MemoryRegion threadLocalsRegion =
            (tlb == 0) ? null :  new FixedMemoryRegion(Address.fromLong(tlb), Size.fromLong(tlbSize), "thread locals region");

        Params params = new Params();
        params.id = id;
        params.localHandle = localHandle;
        params.handle = handle;
        params.stackRegion = stackRegion;
        params.threadLocalsRegion = threadLocalsRegion;

        if (thread == null) {
            thread = createTeleNativeThread(params);
        } else {
            // Handle the cases where a thread was added/removed from the global thread list since the last epoch
            if (id > 0) {
                if (thread.id() != id) {
                    // This is a Java thread that added from the global thread list since the last epoch.
                    thread = createTeleNativeThread(params);
                }
            } else {
                if (thread.id() != id) {
                    assert thread.isJava() : thread.id() + " != " + id + ": " + thread + ", params=" + params;
                    // This is a Java thread that removed from the global thread list since the last epoch
                    thread = createTeleNativeThread(params);
                }
            }
        }

        thread.updateAfterGather(MaxThreadState.VALUES.get(state), Pointer.fromLong(instructionPointer), threadLocalsRegion, tlaSize);
        threads.append(thread);
    }

    /**
     * Reads bytes from process memory, platform-specific implementation.
     *
     * Precondition:
     * {@code buffer != null && offset >= 0 && offset < buffer.capacity() && length >= 0 && offset + length <= buffer.capacity()}
     *
     * @param src the address from which reading should start
     * @param dst the buffer into which the bytes are read
     * @param dstOffset the offset in {@code dst} at which the bytes are read
     * @param length the maximum number of bytes to be read
     * @return the number of bytes read into {@code dst}
     *
     * @throws DataIOError if some IO error occurs
     * @throws IndexOutOfBoundsException if {@code offset} is negative, {@code length} is negative, or
     *             {@code length > buffer.limit() - offset}
     * @see #read(Address, ByteBuffer, int, int)
     */
    protected abstract int read0(Address address, ByteBuffer buffer, int offset, int length);


    /**
     * Writes bytes to process memory, platform-specific implementation.
     *
     * Precondition:
     * {@code buffer != null && offset >= 0 && offset < buffer.capacity() && length >= 0 && offset + length <= buffer.capacity()}
     *
     * @param src the buffer from which the bytes are written
     * @param srcOffset the offset in {@code src} from which the bytes are written
     * @param length the maximum number of bytes to be written
     * @param dst the address at which writing should start
     * @return the number of bytes written to {@code dst}
     *
     * @throws DataIOError if some IO error occurs
     * @throws IndexOutOfBoundsException if {@code srcOffset} is negative, {@code length} is negative, or
     *             {@code length > src.limit() - srcOffset}
     * @see #write(ByteBuffer, int, int, Address)
     */
    protected abstract int write0(ByteBuffer buffer, int offset, int length, Address address);

    /**
     * Activates a watchpoint in the native process, according to the specified
     * watchpoint configuration.  All watchpoints are by default <strong>after</strong>
     * watchpoints; they are intended to trigger after the specified event has taken place.
     *
     * @param teleWatchpoint specifications for a watchpoint, assumed to be currently inactive.
     * @return whether the activation succeeded.
     */
    protected boolean activateWatchpoint(TeleWatchpoint teleWatchpoint) {
        return false;
    }

    /**
     * Deactivate a watchpoint in the native process.
     * <br>
     * Deactivation depends on the location specified in the watchpoint;
     *
     * @param teleWatchpoint a watchpoint that is currently activated, and which still
     * specifies the same address.
     * @return whether the deactivation succeeded.
     */
    protected boolean deactivateWatchpoint(TeleWatchpoint teleWatchpoint) {
        return false;
    }

    protected long readWatchpointAddress() {
        return 0;
    }

    protected int readWatchpointAccessCode() {
        return 0;
    }

    private void updateWatchpointCaches() {
        if (watchpointFactory != null) {
            watchpointFactory.updateWatchpointMemoryCaches();
        }
    }
}
