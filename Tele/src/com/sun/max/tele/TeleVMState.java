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
package com.sun.max.tele;

import java.io.*;
import java.util.*;

import com.sun.max.collect.*;
import com.sun.max.tele.debug.*;

/**
 * Implements the immutable history of Maxine VM states during a debugging sessions.
 *
 * @author Michael Van De Vanter
 */
public final class TeleVMState implements MaxVMState {

    private static final Sequence<TeleNativeThread> EMPTY_THREAD_SEQUENCE = Sequence.Static.empty(TeleNativeThread.class);
    private static final Collection<TeleNativeThread> EMPTY_THREAD_COLLECTION = Collections.emptyList();

    public static final TeleVMState NONE = new TeleVMState(ProcessState.UNKNOWN,
        -1L,
        EMPTY_THREAD_COLLECTION,
        (TeleNativeThread) null,
        EMPTY_THREAD_SEQUENCE,
        EMPTY_THREAD_SEQUENCE,
        EMPTY_THREAD_SEQUENCE,
        (TeleWatchpointEvent) null,
        false,
        (TeleVMState) null);

    private final ProcessState processState;
    private final long serialID;
    private final long epoch;
    private final Sequence<MaxThread> threads;
    private final MaxThread singleStepThread;
    private final Sequence<MaxThread> threadsStarted;
    private final Sequence<MaxThread> threadsDied;
    private final Sequence<MaxThread> breakpointThreads;
    private final MaxWatchpointEvent maxWatchpointEvent;
    private final boolean isInGC;
    private final TeleVMState previous;

    /**
     * @param processState current state of the VM
     * @param epoch current process epoch counter
     * @param threads threads currently active in the VM
     * @param singleStepThread thread just single-stepped, null if none
     * @param threadsStarted threads created since the previous state
     * @param threadsDied threads died since the previous state
     * @param breakpointThreads threads currently at a breakpoint, empty if none
     * @param teleWatchpointEvent information about a thread currently at a memory watchpoint, null if none
     * @param isInGC is the VM, when paused, in a GC
     * @param previous previous state
     */
    public TeleVMState(ProcessState processState,
                    long epoch,
                    Collection<TeleNativeThread> threads,
                    TeleNativeThread singleStepThread,
                    Sequence<TeleNativeThread> threadsStarted,
                    Sequence<TeleNativeThread> threadsDied,
                    Sequence<TeleNativeThread> breakpointThreads,
                    TeleWatchpointEvent teleWatchpointEvent, boolean isInGC, TeleVMState previous) {
        final Sequence<MaxThread> emptyMaxThreadSequence = Sequence.Static.empty(MaxThread.class);
        this.processState = processState;
        this.serialID = previous == null ? 0 : previous.serialID() + 1;
        this.epoch = epoch;
        this.singleStepThread = singleStepThread;
        this.threadsStarted = threadsStarted.length() == 0 ? emptyMaxThreadSequence : new VectorSequence<MaxThread>(threadsStarted);
        this.threadsDied = threadsDied.length() == 0 ? emptyMaxThreadSequence : new VectorSequence<MaxThread>(threadsDied);
        this.breakpointThreads = breakpointThreads.length() == 0 ? emptyMaxThreadSequence : new VectorSequence<MaxThread>(breakpointThreads);
        this.maxWatchpointEvent = teleWatchpointEvent;
        this.isInGC = isInGC;
        this.previous = previous;

        // Compute the current active thread list.
        if (previous == null) {
            // First state transition in the history.
            this.threads = new VectorSequence<MaxThread>(threadsStarted);
        } else if (threadsStarted.length() + threadsDied.length() == 0)  {
            // No changes since predecessor; share the thread list.
            this.threads = previous.threads();
        } else {
            // There have been some thread changes; make a new (immutable) sequence for the new state
            this.threads = new VectorSequence<MaxThread>(threads);
        }
    }

    public ProcessState processState() {
        return processState;
    }

    public long serialID() {
        return serialID;
    }

    public long epoch() {
        return epoch;
    }

    public Sequence<MaxThread> threads() {
        return threads;
    }

    public MaxThread singleStepThread() {
        return singleStepThread;
    }

    public Sequence<MaxThread> threadsStarted() {
        return threadsStarted;
    }

    public  Sequence<MaxThread> threadsDied() {
        return threadsDied;
    }

    public Sequence<MaxThread> breakpointThreads() {
        return breakpointThreads;
    }

    public MaxWatchpointEvent watchpointEvent() {
        return maxWatchpointEvent;
    }

    public boolean isInGC() {
        return isInGC;
    }

    public MaxVMState previous() {
        return previous;
    }

    public boolean newerThan(MaxVMState maxVMState) {
        return maxVMState == null || serialID > maxVMState.serialID();
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder(50);
        sb.append(getClass().getSimpleName()).append("(");
        sb.append(Long.toString(serialID)).append(", ");
        sb.append(processState.toString()).append(", ");
        sb.append(Long.toString(epoch)).append(", ");
        sb.append(Boolean.toString(isInGC)).append(", ");
        sb.append("prev=");
        if (previous == null) {
            sb.append("null");
        } else {
            sb.append(previous.processState().toString());
        }
        sb.append(")");
        return sb.toString();
    }

    public void writeSummaryToStream(PrintStream printStream) {
        MaxVMState state = this;
        while (state != null) {
            final StringBuilder sb = new StringBuilder(100);
            sb.append(Long.toString(state.serialID())).append(":  ");
            sb.append("proc=(").append(state.processState().toString()).append(", ").append(Long.toString(state.epoch())).append(") ");
            sb.append("gc=").append(state.isInGC()).append(" ");
            printStream.println(sb.toString());
            if (state.singleStepThread() != null) {
                printStream.println("\tstep=" + state.singleStepThread().toShortString());
            }
            if (state.previous() != null && state.threads() == state.previous().threads()) {
                printStream.println("\tthreads active: <unchanged>");
            } else if (state.threads().length() == 0) {
                printStream.println("\tthreads active: <empty>");
            } else {
                printStream.println("\tthreads active:");
                for (MaxThread thread : state.threads()) {
                    printStream.println("\t\t" + thread.toShortString());
                }
            }
            if (state.threadsStarted().length() > 0) {
                printStream.println("\tthreads newly started:");
                for (MaxThread thread : state.threadsStarted()) {
                    printStream.println("\t\t" + thread.toShortString());
                }
            }
            if (state.threadsDied().length() > 0) {
                printStream.println("\tthreads newly died:");
                for (MaxThread thread : state.threadsDied()) {
                    printStream.println("\t\t" + thread.toShortString());
                }
            }
            if (state.breakpointThreads().length() > 0) {
                printStream.println("\tthreads at breakpoint");
                for (MaxThread thread : state.breakpointThreads()) {
                    printStream.println("\t\t" + thread.toShortString());
                }
            }
            if (state.watchpointEvent() != null) {
                printStream.println("\tthread at watchpoint=" + state.watchpointEvent());
            }
            state = state.previous();
        }
    }

}
