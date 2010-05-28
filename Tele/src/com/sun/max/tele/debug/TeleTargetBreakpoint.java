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

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

import com.sun.max.program.*;
import com.sun.max.tele.*;
import com.sun.max.tele.debug.BreakpointCondition.*;
import com.sun.max.tele.interpreter.*;
import com.sun.max.tele.method.*;
import com.sun.max.tele.method.CodeLocation.*;
import com.sun.max.unsafe.*;
import com.sun.max.vm.reference.*;
import com.sun.max.vm.tele.*;
import com.sun.max.vm.value.*;

/**
 * Target code breakpoints.
 *
 * @author Bernd Mathiske
 * @author Michael Van De Vanter
 * @author Doug Simon
 */
public abstract class TeleTargetBreakpoint extends TeleBreakpoint {

    protected final TargetBreakpointManager manager;

    /**
     * The original code from the target code in the VM that was present before
     * the breakpoint code was patched in.
     */
    protected final byte[] originalCodeAtBreakpoint;

    /**
     * Whether the breakpoint is actually active in the VM at the moment.
     */
    private boolean isActive;

    /**
     * Creates a target code breakpoint for a given address in the VM.
     *
     * @param teleVM the VM
     * @param manager the manager responsible for managing these breakpoints
     * @param codeLocation  the location at which the breakpoint is to be created, by address
     * @param originalCode the target code at {@code address} that will be overwritten by the breakpoint
     *            instruction. If this value is null, then the code will be read from {@code address}.
     * @param owner the bytecode breakpoint for which this is being created, null if none.
     * @param the kind of breakpoint
     */
    private TeleTargetBreakpoint(TeleVM teleVM, TargetBreakpointManager manager, CodeLocation codeLocation, byte[] originalCode, BreakpointKind kind, TeleBytecodeBreakpoint owner) {
        super(teleVM, codeLocation, kind, owner);
        this.manager = manager;
        this.originalCodeAtBreakpoint = originalCode == null ? teleVM.dataAccess().readFully(codeLocation.address(), manager.codeSize()) : originalCode;
    }

    public boolean isBytecodeBreakpoint() {
        return false;
    }

    /**
     * @return address of this breakpoint in the VM.
     */
    private Address address() {
        return codeLocation().address();
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("Target breakpoint");
        sb.append("{0x").append(address().toHexString()).append(", ");
        sb.append(kind().toString()).append(", ");
        sb.append(isEnabled() ? "enabled" : "disabled");
        if (getDescription() != null) {
            sb.append(", \"").append(getDescription()).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    @Override
    public void remove() throws MaxVMBusyException {
        manager.removeNonTransientBreakpointAt(address());
    }

    /**
     * Determines if the target code in the VM is currently patched at this breakpoint's {@linkplain #address() address} with the
     * platform-dependent target instructions implementing a breakpoint.
     */
    boolean isActive() {
        return isActive;
    }

    /**
     * Sets the activation state of the breakpoint in the VM.
     *
     * @param active new activation state for the breakpoint
     */
    void setActive(boolean active) {
        if (active != isActive) {
            if (active) {
                // Patches the target code in the VM at this breakpoint's address with platform-dependent target instructions implementing a breakpoint.
                vm().dataAccess().writeBytes(address(), manager.code());
            } else {
                // Patches the target code in the VM at this breakpoint's address with the original code that was compiled at that address.
                vm().dataAccess().writeBytes(address(), originalCodeAtBreakpoint);
            }
            isActive = active;
        }
    }

    /**
     * A target breakpoint set explicitly by a client.
     * <br>
     * It will be visible to clients and can be explicitly enabled/disabled/removed by the client.
     */
    private static final class ClientTargetBreakpoint extends TeleTargetBreakpoint {

        private boolean enabled = true;
        private BreakpointCondition condition;

        /**
         * A client-created breakpoint for a given target code address, enabled by default.
         *
         * @param teleVM the VM
         * @param manager the manager that manages these breakpoints.
         * @param codeLocation the location at which the breakpoint is to be created, by address
         * @param originalCode the target code at {@code address} that will be overwritten by the breakpoint
         *            instruction. If this value is null, then the code will be read from {@code address}.
         */
        ClientTargetBreakpoint(TeleVM teleVM, TargetBreakpointManager manager, CodeLocation codeLocation, byte[] originalCode) {
            super(teleVM, manager, codeLocation, originalCode, BreakpointKind.CLIENT, null);
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public void setEnabled(boolean enabled) throws MaxVMBusyException {
            if (!vm().tryLock()) {
                throw new MaxVMBusyException();
            }
            try {
                this.enabled = enabled;
                manager.updateAfterBreakpointChanges(true);
            } finally {
                vm().unlock();
            }
        }

        @Override
        public BreakpointCondition getCondition() {
            return condition;
        }

        @Override
        public void setCondition(final String conditionDescriptor) throws ExpressionException, MaxVMBusyException {
            if (!vm().tryLock()) {
                throw new MaxVMBusyException();
            }
            try {
                this.condition = new BreakpointCondition(vm(), conditionDescriptor);
                setTriggerEventHandler(this.condition);
            } finally {
                vm().unlock();
            }
        }

    }

    /**
     * A target breakpoint set for internal use by the inspection's implementation.
     * <br>
     * It may or may not be visible to clients, but can be explicitly enabled/disabled/removed by the internal
     * service for which it was created.
     */
    private static final class SystemTargetBreakpoint extends TeleTargetBreakpoint {

        private boolean enabled = true;
        private BreakpointCondition condition;

        /**
        * A system-created breakpoint for a given target code address, enabled by default.
        * There is by default no special handling, but this can be changed by overriding
        * {@link #handleTriggerEvent(TeleNativeThread)}.
        *
        * @param codeLocation the location at which the breakpoint will be created, by address
        * @param originalCode the target code at {@code address} that will be overwritten by the breakpoint
        *            instruction. If this value is null, then the code will be read from {@code address}.
        */
        SystemTargetBreakpoint(TeleVM teleVM, TargetBreakpointManager manager, CodeLocation codeLocation, byte[] originalCode, TeleBytecodeBreakpoint owner) {
            super(teleVM, manager, codeLocation, originalCode, BreakpointKind.SYSTEM, owner);
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public void setEnabled(boolean enabled) throws MaxVMBusyException {
            if (!vm().tryLock()) {
                throw new MaxVMBusyException();
            }
            try {
                this.enabled = enabled;
            } finally {
                vm().unlock();
            }
        }

        @Override
        public BreakpointCondition getCondition() {
            return condition;
        }

        @Override
        public void setCondition(String conditionDescriptor) throws ExpressionException, MaxVMBusyException {
            if (!vm().tryLock()) {
                throw new MaxVMBusyException();
            }
            try {
                condition = new BreakpointCondition(vm(), conditionDescriptor);
                setTriggerEventHandler(condition);
            } finally {
                vm().unlock();
            }
        }

    }

    /**
     * A target breakpoint set by the process controller, intended to persist only for the
     * duration of a single execution cycle.  It is always enabled for the duration of its lifetime.
     * <br>
     * It should not be visible to clients, has no condition, and cannot be explicitly enabled/disabled.
     */
    private static final class TransientTargetBreakpoint extends TeleTargetBreakpoint {

        /**
         * A transient breakpoint for a given target code address.
         *
         * @param teleVM the VM
         * @param manager the manager for these breakpoints
         * @param codeLocation location containing the target code address at which the breakpoint is to be created
         * @param originalCode the target code at {@code address} that will be overwritten by the breakpoint
         *            instruction. If this value is null, then the code will be read from {@code address}.
         */
        TransientTargetBreakpoint(TeleVM teleVM, TargetBreakpointManager manager, CodeLocation codeLocation, byte[] originalCode) {
            super(teleVM, manager, codeLocation, originalCode, BreakpointKind.TRANSIENT, null);
        }

        @Override
        public boolean isEnabled() {
            // Transients are always enabled
            return true;
        }

        @Override
        public void setEnabled(boolean enabled) {
            ProgramError.unexpected("Can't enable/disable transient breakpoints");
        }

        @Override
        public BreakpointCondition getCondition() {
            // Transients do not have conditions.
            return null;
        }

        @Override
        public void setCondition(String conditionDescriptor) throws ExpressionException {
            ProgramError.unexpected("Transient breakpoints do not have conditions");
        }

    }

    public static final class TargetBreakpointManager extends AbstractTeleVMHolder {

        private final byte[] code;

        // The map implementations are not thread-safe; the manager must take care of that.
        private final Map<Long, ClientTargetBreakpoint> clientBreakpoints = new HashMap<Long, ClientTargetBreakpoint>();
        private final Map<Long, SystemTargetBreakpoint> systemBreakpoints = new HashMap<Long, SystemTargetBreakpoint>();
        private final Map<Long, TransientTargetBreakpoint> transientBreakpoints = new HashMap<Long, TransientTargetBreakpoint>();


        // Thread-safe, immutable versions of the client map. Will be read many, many more times than will change.
        private volatile List<ClientTargetBreakpoint> clientBreakpointsCache = Collections.emptyList();

        private List<MaxBreakpointListener> breakpointListeners = new CopyOnWriteArrayList<MaxBreakpointListener>();

        TargetBreakpointManager(TeleVM teleVM) {
            super(teleVM);
            this.code = TargetBreakpoint.createBreakpointCode(teleVM.vmConfiguration().platform().processorKind.instructionSet);
        }

        /**
         * Adds a listener for breakpoint changes.
         *
         * @param listener a breakpoint listener
         */
        void addListener(MaxBreakpointListener listener) {
            assert listener != null;
            breakpointListeners.add(listener);
        }

        /**
         * Removes a listener for breakpoint changes.
         *
         * @param listener a breakpoint listener
         */
        void removeListener(MaxBreakpointListener listener) {
            assert listener != null;
            breakpointListeners.remove(listener);
        }

        /**
         * Gets the bytes encoding the platform dependent instruction(s) representing a breakpoint.
         */
        private byte[] code() {
            return code.clone();
        }

        /**
         * Gets number of bytes that encode the platform dependent instruction(s) representing a breakpoint.
         */
        int codeSize() {
            return code.length;
        }

        /**
         * @return all the client-visible persistent target code breakpoints that currently exist
         * in the VM.  Modification safe against breakpoint removal.
         */
        synchronized List<ClientTargetBreakpoint> clientBreakpoints() {
            return clientBreakpointsCache;
        }

        /**
         * Gets a target code breakpoint set at a specified address in the VM.
         * <br>
         * If multiple breakpoints are set at {@code address}, then one is selected
         * according to the following preference:  a client breakpoint, if one exists,
         * otherwise a system breakpoint, if one exists, otherwise a transient breakpoint,
         * if one exists.
         *
         * @return the target code breakpoint a the specified address, if it exists, null otherwise.
         */
        synchronized TeleTargetBreakpoint getTargetBreakpointAt(Address address) {
            final ClientTargetBreakpoint clientBreakpoint = clientBreakpoints.get(address.toLong());
            if (clientBreakpoint != null) {
                return clientBreakpoint;
            }
            final SystemTargetBreakpoint systemBreakpoint = systemBreakpoints.get(address.toLong());
            if (systemBreakpoint != null) {
                return systemBreakpoint;
            }
            return transientBreakpoints.get(address.toLong());
        }

        public synchronized TeleTargetBreakpoint findClientBreakpoint(MachineCodeLocation compiledCodeLocation) {
            assert compiledCodeLocation.hasAddress();
            return clientBreakpoints.get(compiledCodeLocation.address().toLong());
        }

        /**
         * Return a client-visible target code breakpoint, creating a new one if none exists at that location.
         * <br>
         * Thread-safe (synchronizes on the VM lock)
         *
         * @param codeLocation location (with address) for the breakpoint
         * @return a possibly new target code breakpoint
         * @throws MaxVMBusyException
         */
        TeleTargetBreakpoint makeClientBreakpoint(CodeLocation codeLocation) throws MaxVMBusyException {
            assert codeLocation.hasAddress();
            if (!vm().tryLock()) {
                throw new MaxVMBusyException();
            }
            TeleTargetBreakpoint breakpoint;
            try {
                breakpoint = getTargetBreakpointAt(codeLocation.address());
                if (breakpoint == null || breakpoint.isTransient()) {
                    final ClientTargetBreakpoint clientBreakpoint = new ClientTargetBreakpoint(vm(), this, codeLocation, null);
                    final TeleTargetBreakpoint oldBreakpoint = clientBreakpoints.put(codeLocation.address().toLong(), clientBreakpoint);
                    assert oldBreakpoint == null;
                    breakpoint = clientBreakpoint;
                    updateAfterBreakpointChanges(true);
                }
            } finally {
                vm().unlock();
            }
            return breakpoint;
        }

        /**
         * Return a client-invisible target code breakpoint, creating a new one if none exists at that location.
         * <br>
         * Thread-safe (synchronizes on the VM lock)
         *
         * @param codeLocation location (with address) for the breakpoint
         * @return a possibly new target code breakpoint
         * @throws MaxVMBusyException
         */
        TeleTargetBreakpoint makeSystemBreakpoint(CodeLocation codeLocation, VMTriggerEventHandler handler, TeleBytecodeBreakpoint owner) throws MaxVMBusyException {
            assert codeLocation.hasAddress();
            final Address address = codeLocation.address();
            ProgramError.check(!address.isZero());
            if (!vm().tryLock()) {
                throw new MaxVMBusyException();
            }
            SystemTargetBreakpoint systemBreakpoint;
            try {
                systemBreakpoint = systemBreakpoints.get(address.toLong());
                // TODO (mlvdv) handle case where there is already a client breakpoint at this address.
                if (systemBreakpoint == null) {
                    systemBreakpoint = new SystemTargetBreakpoint(vm(), this, codeLocation, null, owner);
                    systemBreakpoint.setTriggerEventHandler(handler);
                    final SystemTargetBreakpoint oldBreakpoint = systemBreakpoints.put(address.toLong(), systemBreakpoint);
                    assert oldBreakpoint == null;
                    updateAfterBreakpointChanges(false);
                }
            } finally {
                vm().unlock();
            }
            return systemBreakpoint;
        }

        public TeleTargetBreakpoint makeSystemBreakpoint(CodeLocation codeLocation, VMTriggerEventHandler handler) throws MaxVMBusyException {
            return makeSystemBreakpoint(codeLocation, handler, null);
        }

        /**
         * Return a client-invisible transient breakpoint at a specified target code address in the VM, creating a new one first if needed.
         * <br>
         * Thread-safe (synchronized on the VM lock)
         *
         * @param codeLocation location (with address) for the breakpoint
         * @return a possibly new target code breakpoint
         * @throws MaxVMBusyException
         */
        TeleTargetBreakpoint makeTransientBreakpoint(CodeLocation codeLocation) throws MaxVMBusyException {
            assert codeLocation.hasAddress();
            final Address address = codeLocation.address();
            ProgramError.check(!address.isZero());
            if (!vm().tryLock()) {
                throw new MaxVMBusyException();
            }
            try {
                TeleTargetBreakpoint breakpoint = getTargetBreakpointAt(address);
                if (breakpoint == null || !breakpoint.isTransient()) {
                    final TransientTargetBreakpoint transientBreakpoint = new TransientTargetBreakpoint(vm(), this, codeLocation, null);
                    final TeleTargetBreakpoint oldBreakpoint = transientBreakpoints.put(address.toLong(), transientBreakpoint);
                    assert oldBreakpoint == null;
                    breakpoint = transientBreakpoint;
                    updateAfterBreakpointChanges(false);
                }
                return breakpoint;
            } finally {
                vm().unlock();
            }
        }

        private byte[] recoverOriginalCodeForBreakpoint(Address instructionPointer) {
            Value result = null;
            try {
                result = vm().teleMethods().TargetBreakpoint_findOriginalCode.interpret(LongValue.from(instructionPointer.toLong()));
            } catch (MaxVMBusyException maxVMBusyException) {
            } catch (TeleInterpreterException e) {
                throw ProgramError.unexpected(e);
            }
            if (result != null) {
                final Reference reference = result.asReference();
                if (!reference.isZero()) {
                    return (byte[]) reference.toJava();
                }
            }
            return null;
        }

        /**
         * Removes the client or system breakpoint, if it exists, at specified target code address in the VM.
         * <br>
         * Thread-safe; synchronizes on the VM lock
         *
         * @param address
         * @throws MaxVMBusyException
         */
        private void removeNonTransientBreakpointAt(Address address) throws MaxVMBusyException {
            if (!vm().tryLock()) {
                throw new MaxVMBusyException();
            }
            try {
                final long addressLong = address.toLong();
                if (clientBreakpoints.remove(addressLong) != null) {
                    updateAfterBreakpointChanges(true);
                } else {
                    if (systemBreakpoints.remove(addressLong) != null) {
                        updateAfterBreakpointChanges(false);
                    }
                }
            } finally {
                vm().unlock();
            }
        }

        /**
         * Sets the activation state of all target breakpoints in the VM.
         * <br>
         * Assumes VM lock held
         *
         * @param active new activation state for all breakpoints
         * @see TeleTargetBreakpoint#setActive(boolean)
         */
        void setActiveAll(boolean active) {
            assert vm().lockHeldByCurrentThread();
            for (TeleTargetBreakpoint breakpoint : clientBreakpoints.values()) {
                if (breakpoint.isEnabled()) {
                    breakpoint.setActive(active);
                }
            }
            for (TeleTargetBreakpoint breakpoint : systemBreakpoints.values()) {
                if (breakpoint.isEnabled()) {
                    breakpoint.setActive(active);
                }
            }
            for (TeleTargetBreakpoint breakpoint : transientBreakpoints.values()) {
                breakpoint.setActive(active);
            }
        }

        /**
         * Sets the activation state of all non-client target breakpoints in the VM.
        * <br>
         * Assumes VM lock held
         *
         * @param active new activation state for all breakpoints
         * @see TeleTargetBreakpoint#setActive(boolean)
         */
        void setActiveNonClient(boolean active) {
            assert vm().lockHeldByCurrentThread();
            for (TeleTargetBreakpoint breakpoint : systemBreakpoints.values()) {
                if (breakpoint.isEnabled()) {
                    breakpoint.setActive(active);
                }
            }
            for (TeleTargetBreakpoint breakpoint : transientBreakpoints.values()) {
                breakpoint.setActive(active);
            }
        }

        /**
         * Removes and clears all state associated with transient breakpoints.
         */
        void removeTransientBreakpoints() {
            assert vm().lockHeldByCurrentThread();
            transientBreakpoints.clear();
            updateAfterBreakpointChanges(false);
        }

        /**
         * Update immutable cache of breakpoint list and possibly notify listeners.
         *
         * @param announce whether to notify listeners
         */
        private void updateAfterBreakpointChanges(boolean announce) {
            clientBreakpointsCache = Collections.unmodifiableList(new ArrayList<ClientTargetBreakpoint>(clientBreakpoints.values()));
            if (announce) {
                for (final MaxBreakpointListener listener : breakpointListeners) {
                    listener.breakpointsChanged();
                }
            }
        }

        /**
         * Writes a description of every target breakpoint to the stream, including those usually not shown to clients,
         * with more detail than typically displayed.
         * <br>
         * Thread-safe
         *
         * @param printStream
         */
        void writeSummaryToStream(PrintStream printStream) {
            printStream.println("Target breakpoints :");
            for (ClientTargetBreakpoint targetBreakpoint : clientBreakpointsCache) {
                printStream.println("  " + targetBreakpoint + describeLocation(targetBreakpoint));
            }
            for (SystemTargetBreakpoint targetBreakpoint : systemBreakpoints.values()) {
                printStream.println("  " + targetBreakpoint + describeLocation(targetBreakpoint));
            }
            for (TransientTargetBreakpoint targetBreakpoint : transientBreakpoints.values()) {
                printStream.println("  " + targetBreakpoint + describeLocation(targetBreakpoint));
            }
        }

        private String describeLocation(TeleTargetBreakpoint teleTargetBreakpoint) {
            final MaxMachineCode maxMachineCode = vm().codeCache().findMachineCode(teleTargetBreakpoint.address());
            if (maxMachineCode != null) {
                return " in " + maxMachineCode.entityName();
            }
            return "";
        }
    }
}
