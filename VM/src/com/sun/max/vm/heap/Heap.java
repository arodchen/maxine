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
package com.sun.max.vm.heap;

import static com.sun.max.vm.VMOptions.*;

import com.sun.max.annotate.*;
import com.sun.max.lang.*;
import com.sun.max.unsafe.*;
import com.sun.max.vm.*;
import com.sun.max.vm.actor.holder.*;
import com.sun.max.vm.code.*;
import com.sun.max.vm.debug.*;
import com.sun.max.vm.grip.*;
import com.sun.max.vm.layout.*;
import com.sun.max.vm.object.*;
import com.sun.max.vm.reference.*;
import com.sun.max.vm.runtime.*;
import com.sun.max.vm.thread.*;

/**
 * The dynamic Java object heap.
 *
 * @author Bernd Mathiske
 */
public final class Heap {

    private Heap() {
    }

    private static final Size MIN_HEAP_SIZE = Size.M.times(4); // To be adjusted
    /**
     * If initial size not specified, the maxSize / DEFAULT_INIT_HEAP_SIZE_RATIO
     */
    private static final int DEFAULT_INIT_HEAP_SIZE_RATIO = 2;

    private static final VMSizeOption maxHeapSizeOption = register(new VMSizeOption("-Xmx", Size.G, "The maximum heap size."), MaxineVM.Phase.PRISTINE);

    private static final VMSizeOption initialHeapSizeOption = register(new InitialHeapSizeOption(), MaxineVM.Phase.PRISTINE);

    static class InitialHeapSizeOption extends VMSizeOption {
        String invalidHeapSizeReason;

        @HOSTED_ONLY
        public InitialHeapSizeOption() {
            super("-Xms", maxHeapSizeOption.getValue().dividedBy(DEFAULT_INIT_HEAP_SIZE_RATIO), "The initial heap size.");
        }
        @Override
        public boolean check() {
            invalidHeapSizeReason = validateHeapSizing();
            return invalidHeapSizeReason == null;
        }

        @Override
        public void printErrorMessage() {
            Log.print(invalidHeapSizeReason);
        }
    }

    /**
     * A special exception thrown when a non-GC thread tries to perform a GC while holding
     * the {@linkplain VmThreadMap#ACTIVE GC lock}. There is a single, pre-allocated
     * {@linkplain #INSTANCE instance} of this object so that raising this exception
     * does not require any allocation.
     *
     * @author Doug Simon
     */
    public static final class HoldsGCLockError extends OutOfMemoryError {

        private HoldsGCLockError() {
        }

        public static final HoldsGCLockError INSTANCE = new HoldsGCLockError();
    }


    private static Size maxSize;
    private static Size initialSize;

    private static boolean heapSizingInputValidated = false;

    /**
     * Validate heap sizing inputs. This is common to any GC and can be done early on.
     *
     * @return
     */
    private static String validateHeapSizing() {
        if (heapSizingInputValidated) {
            return null;
        }
        Size max = maxHeapSizeOption.getValue();
        Size init = initialHeapSizeOption.getValue();
        if (maxHeapSizeOption.isPresent()) {
            if (max.lessThan(MIN_HEAP_SIZE)) {
                return "Heap too small";
            }
            if (initialHeapSizeOption.isPresent()) {
               if (max.lessThan(init)) {
                   return "Incompatible minimum and maximum heap sizes specified";
               }
               if (init.lessThan(MIN_HEAP_SIZE)) {
                   return "Too small initial heap";
               }
            } else {
                init = max;
            }
        } else if (initialHeapSizeOption.isPresent()) {
            if (init.lessThan(MIN_HEAP_SIZE)) {
                return "Heap too small";
            }
            max = init;
        }

        maxSize = max;
        initialSize = init;
        heapSizingInputValidated = true;
        return null;
    }

    // Note: Called via reflection from jvm.c
    public static long maxSizeLong() {
        return maxSize().toLong();
    }

    public static Size maxSize() {
        if (maxSize.isZero()) {
            validateHeapSizing();
        }
        return maxSize;
    }

    public static void setMaxSize(Size size) {
        maxSize = size;
    }

    public static Size initialSize() {
        if (initialSize.isZero()) {
            validateHeapSizing();
        }
        return initialSize;
    }

    public static void setInitialSize(Size size) {
        initialSize = size;
    }

    /**
     * Determines if information should be displayed about each garbage collection event.
     */
    public static boolean verbose() {
        return verboseOption.verboseGC || TraceGC || TraceRootScanning || Heap.traceGCTime();
    }

    /**
     * Set the verboseGC option (java.lang.management support).
     */
    public static void setVerbose(boolean value) {
        verboseOption.verboseGC = value;
    }

    private static boolean TraceAllocation;

    /**
     * Determines if allocation should be traced.
     *
     * @returns {@code false} if the VM build level is not {@link BuildLevel#DEBUG}.
     */
    @INLINE
    public static boolean traceAllocation() {
        return MaxineVM.isDebug() && TraceAllocation;
    }

    /**
     * Modifies the value of the flag determining if allocation should be traced. This flag is ignored if the VM
     * {@linkplain VMConfiguration#buildLevel() build level} is not {@link BuildLevel#DEBUG}. This is typically provided
     * so that error situations can be reported without being confused by interleaving allocation traces.
     */
    public static void setTraceAllocation(boolean flag) {
        TraceAllocation = flag;
    }

    static {
        if (MaxineVM.isDebug()) {
            VMOptions.addFieldOption("-XX:", "TraceAllocation", Classes.getDeclaredField(Heap.class, "TraceAllocation"), "Trace heap allocation.");
        }
    }

    /**
     * Determines if all garbage collection activity should be traced.
     */
    @INLINE
    public static boolean traceGC() {
        return TraceGC && TraceGCSuppressionCount <= 0;
    }

    /**
     * Determines if the garbage collection phases should be traced.
     */
    @INLINE
    public static boolean traceGCPhases() {
        return (TraceGC || TraceGCPhases) && TraceGCSuppressionCount <= 0;
    }

    /**
     * Determines if garbage collection root scanning should be traced.
     */
    @INLINE
    public static boolean traceRootScanning() {
        return (TraceGC || TraceRootScanning) && TraceGCSuppressionCount <= 0;
    }

    /**
     * Determines if garbage collection timings should be collected and printed.
     */
    @INLINE
    public static boolean traceGCTime() {
        return (TraceGC || TimeGC) && TraceGCSuppressionCount <= 0;
    }

    /**
     * Disables -XX:-TraceGC, -XX:-TraceRootScanning and -XX:-TraceGCPhases if greater than 0.
     */
    public static int TraceGCSuppressionCount;

    private static boolean TraceGC;
    private static boolean TraceGCPhases;
    private static boolean TraceRootScanning;
    private static boolean TimeGC;
    private static boolean GCDisabled;

    static {
        VMOption timeOption = VMOptions.addFieldOption("-XX:", "TimeGC", Heap.class,
            "Time and print garbage collection activity.");

        VMOption traceGCPhasesOption = VMOptions.addFieldOption("-XX:", "TraceGCPhases", Heap.class,
            "Trace garbage collection phases.");

        VMOption traceRootScanningOption = VMOptions.addFieldOption("-XX:", "TraceRootScanning", Heap.class,
            "Trace garbage collection root scanning.");

        VMOption traceGCOption = VMOptions.addFieldOption("-XX:", "TraceGC", Heap.class,
            "Trace all garbage collection activity. Enabling this option also enables the " +
            traceRootScanningOption + ", " + traceGCPhasesOption + " and " + timeOption + " options.");

        VMOptions.addFieldOption("-XX:", "TraceGCSuppressionCount", Heap.class,
                        "Disable " + traceGCOption + ", " + traceRootScanningOption + " and " +
                        traceGCPhasesOption + " until the n'th GC");

        VMOptions.addFieldOption("-XX:", "DisableGC", Classes.getDeclaredField(Heap.class, "GCDisabled"), "Disable garbage collection.");
    }

    /**
     * Returns whether the "-XX:+DisableGC" option was specified.
     *
     * @return {@code true} if the user specified "-XX:+DisableGC" on the command line option; {@code false} otherwise
     */
    public static boolean gcDisabled() {
        return GCDisabled;
    }

    /**
     * Used by the Inspector to uniquely identify the special boot heap region.
     */
    @INSPECTED
    private static final String HEAP_BOOT_NAME = "Heap-Boot";

    @INSPECTED
    public static final BootHeapRegion bootHeapRegion = new BootHeapRegion(Address.zero(), Size.fromInt(Integer.MAX_VALUE), HEAP_BOOT_NAME);

    @UNSAFE
    @FOLD
    private static HeapScheme heapScheme() {
        return VMConfiguration.hostOrTarget().heapScheme();
    }

    @INLINE
    public static void disableAllocationForCurrentThread() {
        heapScheme().disableAllocationForCurrentThread();
    }

    @INLINE
    public static void enableAllocationForCurrentThread() {
        heapScheme().enableAllocationForCurrentThread();
    }

    /**
     * @see HeapScheme#isGcThread(Thread)
     */
    public static boolean isGcThread(Thread thread) {
        return heapScheme().isGcThread(thread);
    }

    public static void initializeAuxiliarySpace(Pointer primordialVmThreadLocals, Pointer auxiliarySpace) {
        heapScheme().initializeAuxiliarySpace(primordialVmThreadLocals, auxiliarySpace);
    }

    @INLINE
    public static Object createArray(DynamicHub hub, int length) {
        final Object array = heapScheme().createArray(hub, length);
        if (Heap.traceAllocation()) {
            traceCreateArray(hub, length, array);
        }
        return array;
    }

    @NEVER_INLINE
    public static void traceCreateArray(DynamicHub hub, int length, final Object array) {
        final boolean lockDisabledSafepoints = Log.lock();
        Log.printCurrentThread(false);
        Log.print(": Allocated array ");
        Log.print(hub.classActor.name.string);
        Log.print(" of length ");
        Log.print(length);
        Log.print(" at ");
        Log.print(Layout.originToCell(ObjectAccess.toOrigin(array)));
        Log.print(" [");
        Log.print(Layout.size(Reference.fromJava(array)).toInt());
        Log.println(" bytes]");
        Log.unlock(lockDisabledSafepoints);
    }

    @INLINE
    public static Object createTuple(Hub hub) {
        final Object object = heapScheme().createTuple(hub);
        if (Heap.traceAllocation()) {
            traceCreateTuple(hub, object);
        }
        return object;
    }

    @NEVER_INLINE
    public static void traceCreateTuple(Hub hub, final Object object) {
        final boolean lockDisabledSafepoints = Log.lock();
        Log.printCurrentThread(false);
        Log.print(": Allocated tuple ");
        Log.print(hub.classActor.name.string);
        Log.print(" at ");
        Log.print(Layout.originToCell(ObjectAccess.toOrigin(object)));
        Log.print(" [");
        Log.print(hub.tupleSize.toInt());
        Log.println(" bytes]");
        Log.unlock(lockDisabledSafepoints);
    }

    @INLINE
    public static Object createHybrid(DynamicHub hub) {
        final Object hybrid = heapScheme().createHybrid(hub);
        if (Heap.traceAllocation()) {
            traceCreateHybrid(hub, hybrid);
        }
        return hybrid;
    }

    @NEVER_INLINE
    private static void traceCreateHybrid(DynamicHub hub, final Object hybrid) {
        final boolean lockDisabledSafepoints = Log.lock();
        Log.printCurrentThread(false);
        Log.print(": Allocated hybrid ");
        Log.print(hub.classActor.name.string);
        Log.print(" at ");
        Log.print(Layout.originToCell(ObjectAccess.toOrigin(hybrid)));
        Log.print(" [");
        Log.print(hub.tupleSize.toInt());
        Log.println(" bytes]");
        Log.unlock(lockDisabledSafepoints);
    }

    @INLINE
    public static Hybrid expandHybrid(Hybrid hybrid, int length) {
        final Hybrid expandedHybrid = heapScheme().expandHybrid(hybrid, length);
        if (Heap.traceAllocation()) {
            traceExpandHybrid(hybrid, expandedHybrid);
        }
        return expandedHybrid;
    }

    @NEVER_INLINE
    private static void traceExpandHybrid(Hybrid hybrid, final Hybrid expandedHybrid) {
        final boolean lockDisabledSafepoints = Log.lock();
        Log.printCurrentThread(false);
        Log.print(": Allocated expanded hybrid ");
        final Hub hub = ObjectAccess.readHub(hybrid);
        Log.print(hub.classActor.name.string);
        Log.print(" at ");
        Log.print(Layout.originToCell(ObjectAccess.toOrigin(expandedHybrid)));
        Log.print(" [");
        Log.print(hub.tupleSize.toInt());
        Log.println(" bytes]");
        Log.unlock(lockDisabledSafepoints);
    }

    @INLINE
    public static Object clone(Object object) {
        final Object clone = heapScheme().clone(object);
        if (Heap.traceAllocation()) {
            traceClone(object, clone);
        }
        return clone;
    }

    @NEVER_INLINE
    private static void traceClone(Object object, final Object clone) {
        final boolean lockDisabledSafepoints = Log.lock();
        Log.printCurrentThread(false);
        Log.print(": Allocated cloned ");
        final Hub hub = ObjectAccess.readHub(object);
        Log.print(hub.classActor.name.string);
        Log.print(" at ");
        Log.print(Layout.originToCell(ObjectAccess.toOrigin(clone)));
        Log.print(" [");
        Log.print(hub.tupleSize.toInt());
        Log.println(" bytes]");
        Log.unlock(lockDisabledSafepoints);
    }

    public static boolean collectGarbage(Size requestedFreeSpace) {
        if (Thread.holdsLock(VmThreadMap.ACTIVE)) {
            // The GC requires this lock to proceed
            throw HoldsGCLockError.INSTANCE;
        }

        if (Heap.gcDisabled()) {
            Throw.stackDump("Out of memory and GC is disabled");
            MaxineVM.native_exit(1);
        }
        if (VmThread.isAttaching()) {
            Log.println("Cannot run GC on a thread still attaching to the VM");
            MaxineVM.native_exit(1);
        }
        final long k = Size.K.toLong();
        long beforeFree = 0L;
        long beforeUsed = 0L;
        if (verbose()) {
            beforeUsed = reportUsedSpace();
            beforeFree = reportFreeSpace();
            final boolean lockDisabledSafepoints = Log.lock();
            Log.print("--GC requested by thread ");
            Log.printCurrentThread(false);
            Log.print(" for ");
            Log.print(requestedFreeSpace.toLong());
            Log.println(" bytes --");
            Log.print("--Before GC   used: ");
            Log.print(beforeUsed / k);
            Log.print(" Kb, free: ");
            Log.print(beforeFree / k);
            Log.println(" Kb --");
            Log.unlock(lockDisabledSafepoints);
        }
        final boolean freedEnough = heapScheme().collectGarbage(requestedFreeSpace);
        if (verbose()) {
            final long afterUsed = reportUsedSpace();
            final long afterFree = reportFreeSpace();
            final long reclaimed = beforeUsed - afterUsed;
            final boolean lockDisabledSafepoints = Log.lock();
            Log.print("--GC requested by thread ");
            Log.printCurrentThread(false);
            Log.println(" done--");
            Log.print("--After GC   used: ");
            Log.print(afterUsed / k);
            Log.print("Kb, free: ");
            Log.print(afterFree / k);
            Log.print("Kb, reclaimed: ");
            Log.print(reclaimed / k);
            Log.println(" Kb --");
            if (freedEnough) {
                Log.println("--GC freed enough--");
            } else {
                Log.println("--GC did not free enough--");
            }
            Log.unlock(lockDisabledSafepoints);
        }
        return freedEnough;
    }

    // Note: Called via reflection from jvm.c
    public static long reportFreeSpace() {
        return heapScheme().reportFreeSpace().toLong();
    }

    // Note: Called via reflection from jvm.c
    public static long reportUsedSpace() {
        return heapScheme().reportUsedSpace().toLong();
    }

    // Note: Called via reflection from jvm.c
    public static long maxObjectInspectionAge() {
        return heapScheme().maxObjectInspectionAge();
    }

    public static void runFinalization() {
        heapScheme().runFinalization();
    }

    @INLINE
    public static boolean pin(Object object) {
        return heapScheme().pin(object);
    }

    @INLINE
    public static void unpin(Object object) {
        heapScheme().unpin(object);
    }

    @INLINE
    public static boolean isPinned(Object object) {
        return heapScheme().isPinned(object);
    }

    /**
     * Determines if the  heap scheme is initialized to the point where
     * {@link #collectGarbage(Size)} can safely be called.
     */
    public static boolean isInitialized() {
        return heapScheme().isInitialized();
    }

    public static void enableImmortalMemoryAllocation() {
        heapScheme().enableImmortalMemoryAllocation();
        if (ImmortalHeap.traceAllocation()) {
            Log.printCurrentThread(false);
            Log.println(": immortal heap allocation enabled");
        }
    }

    public static void disableImmortalMemoryAllocation() {
        heapScheme().disableImmortalMemoryAllocation();
        if (ImmortalHeap.traceAllocation()) {
            Log.printCurrentThread(false);
            Log.println(": immortal heap allocation disabled");
        }
    }

    /**
     * Currently, a number of memory regions containing object are treated as "permanent" root by the GC.
     * This method checks whether an address points into one of these regions.
     * @param address an address
     * @return true if the address points to one of the root regions of the heap.
     */
    public static boolean isInHeapRootRegion(Address address) {
        return bootHeapRegion.contains(address) || Code.contains(address) || ImmortalHeap.getImmortalHeap().contains(address);
    }

    public static boolean isValidGrip(Grip grip) {
        if (grip.isZero()) {
            return true;
        }
        Pointer origin = grip.toOrigin();
        if (!bootHeapRegion.contains(origin) && !heapScheme().contains(origin) && !Code.contains(origin) && !ImmortalHeap.getImmortalHeap().contains(origin)) {
            return false;
        }
        if (DebugHeap.isTagging()) {
            return DebugHeap.isValidNonnullGrip(grip);
        }
        return true;
    }

    public static void checkHeapSizeOptions() {
        Size initSize = initialSize();
        Size maxSize = maxSize();
        if (initSize.greaterThan(maxSize)) {
            Log.println("Incompatible minimum and maximum heap sizes specified");
            MaxineVM.native_exit(1);
        }
    }

}
