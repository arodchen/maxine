/*
 * Copyright (c) 2009 Sun Microsystems, Inc.  All rights reserved.
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
package com.sun.max.vm.compiler.c1x;

import java.io.*;
import java.util.*;

import com.sun.c1x.ci.*;
import com.sun.c1x.ci.CiTargetMethod.*;
import com.sun.c1x.ri.*;
import com.sun.max.annotate.*;
import com.sun.max.collect.*;
import com.sun.max.io.*;
import com.sun.max.lang.*;
import com.sun.max.platform.*;
import com.sun.max.unsafe.*;
import com.sun.max.vm.*;
import com.sun.max.vm.actor.holder.*;
import com.sun.max.vm.actor.member.*;
import com.sun.max.vm.code.*;
import com.sun.max.vm.collect.*;
import com.sun.max.vm.compiler.*;
import com.sun.max.vm.compiler.target.*;
import com.sun.max.vm.runtime.*;
import com.sun.max.vm.stack.*;
import com.sun.max.vm.stack.JavaStackFrameLayout.*;

/**
 * This class implements a {@link TargetMethod target method} for
 * the Maxine VM that represents a compiled method generated by C1X.
 *
 * @author Ben L. Titzer
 * @author Thomas Wuerthinger
 */
public class C1XTargetMethod extends TargetMethod {

    private static final int RJMP = 0xe9;

    /**
     * An array of pairs denoting the code positions protected by an exception handler.
     * A pair {@code {p,h}} at index {@code i} in this array specifies that code position
     * {@code h} is the handler for an exception of type {@code t} occurring at position
     * {@code p} where {@code t} is the element at index {@code i / 2} in {@link #exceptionClassActors}.
     */
    private int[] exceptionPositionsToCatchPositions;

    /**
     * @see #exceptionPositionsToCatchPositions
     */
    private ClassActor[] exceptionClassActors;

    /**
     * The frame and register reference maps for this target method.
     *
     * The format of this byte array is described by the following pseudo C declaration:
     * <p>
     *
     * <pre>
     * referenceMaps {
     *     {
     *         u1 frameMap[frameReferenceMapSize];
     *         u1 registerMap[registerReferenceMapSize];
     *     } directCallMaps[numberOfDirectCalls]
     *     {
     *         u1 frameMap[frameReferenceMapSize];
     *         u1 registerMap[registerReferenceMapSize];
     *     } indirectCallMaps[numberOfIndirectCalls]
     *     {
     *         u1 frameMap[frameReferenceMapSize];
     *         u1 registerMap[registerReferenceMapSize];
     *     } safepointMaps[numberOfSafepoints]
     * }
     * </pre>
     */
    private byte[] referenceMaps;

    /**
     * The number of registers that may be used to store a reference value.
     */
    private int referenceRegisterCount = -1;

    @HOSTED_ONLY
    private CiTargetMethod bootstrappingCiTargetMethod;

    public C1XTargetMethod(RuntimeCompilerScheme compilerScheme, ClassMethodActor classMethodActor, CiTargetMethod ciTargetMethod) {
        super(classMethodActor, compilerScheme,  VMConfiguration.target().targetABIsScheme().optimizedJavaABI());
        init(ciTargetMethod);

        if (printTargetMethods.getValue() != null) {
            if (classMethodActor.format("%H.%n").contains(printTargetMethods.getValue())) {
                Log.println(traceToString());
            }
        }
    }

    public C1XTargetMethod(RuntimeCompilerScheme compilerScheme, String stubName, CiTargetMethod ciTargetMethod) {
        super(stubName, compilerScheme,  VMConfiguration.target().targetABIsScheme().optimizedJavaABI());
        init(ciTargetMethod);

        if (printTargetMethods.getValue() != null) {
            if (stubName.contains(printTargetMethods.getValue())) {
                Log.println(traceToString());
            }
        }
    }

    private void init(CiTargetMethod ciTargetMethod) {

        if (MaxineVM.isHosted()) {
            // Save the target method for later gathering of calls
            this.bootstrappingCiTargetMethod = ciTargetMethod;
        }

        initCodeBuffer(ciTargetMethod);
        initFrameLayout(ciTargetMethod);
        initStopPositions(ciTargetMethod);
        initExceptionTable(ciTargetMethod);

        if (!MaxineVM.isHosted()) {
            linkDirectCalls();
        }
    }

    /**
     * Gets the size (in bytes) of a reference map covering all the reference registers used by this target method.
     */
    private int registerReferenceMapSize() {
        assert referenceRegisterCount != -1 : "register size not yet initialized";
        return ByteArrayBitMap.computeBitMapSize(referenceRegisterCount);
    }

    /**
     * Gets size of an activation frame for this target method in words.
     */
    @UNSAFE
    private int frameWords() {
        return frameSize() / Word.size();
    }

    /**
     * Gets the size (in bytes) of a reference map covering an activation frame for this target method.
     */
    private int frameReferenceMapSize() {
        return ByteArrayBitMap.computeBitMapSize(frameWords());
    }

    /**
     * Gets the number of bytes in {@link #referenceMaps} corresponding to one stop position.
     */
    private int totalReferenceMapSize() {
        return registerReferenceMapSize() + frameReferenceMapSize();
    }

    private void setRegisterReferenceMapBit(int stopIndex, int registerIndex) {
        assert registerIndex >= 0 && registerIndex < referenceRegisterCount;
        int byteIndex = stopIndex * totalReferenceMapSize() + frameReferenceMapSize();
        ByteArrayBitMap.set(referenceMaps, byteIndex, registerReferenceMapSize(), registerIndex);
    }

    private void setFrameReferenceMapBit(int stopIndex, int slotIndex) {
        assert slotIndex >= 0 && slotIndex < frameSize();
        int byteIndex = stopIndex * totalReferenceMapSize();
        ByteArrayBitMap.set(referenceMaps, byteIndex, frameReferenceMapSize(), slotIndex);
    }

    private boolean isRegisterReferenceMapBitSet(int stopIndex, int registerIndex) {
        assert registerIndex >= 0 && registerIndex < referenceRegisterCount;
        int byteIndex = stopIndex * totalReferenceMapSize() + frameReferenceMapSize();
        return ByteArrayBitMap.isSet(referenceMaps, byteIndex, registerReferenceMapSize(), registerIndex);
    }

    private boolean isFrameReferenceMapBitSet(int stopIndex, int slotIndex) {
        assert slotIndex >= 0 && slotIndex < frameSize();
        int byteIndex = stopIndex * totalReferenceMapSize();
        return ByteArrayBitMap.isSet(referenceMaps, byteIndex, frameReferenceMapSize(), slotIndex);
    }

    private void initCodeBuffer(CiTargetMethod ciTargetMethod) {

        // Create the arrays for the scalar and the object reference literals
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        List<Object> objectReferences = new ArrayList<Object>();
        int[] relativeDataPos = serializeLiterals(ciTargetMethod, output, objectReferences);
        byte[] scalarLiterals = output.toByteArray();
        Object[] referenceLiterals = objectReferences.toArray();

        // Allocate and set the code and data buffer
        final TargetBundleLayout targetBundleLayout = new TargetBundleLayout(scalarLiterals.length, referenceLiterals.length, ciTargetMethod.targetCodeSize());
        Code.allocate(targetBundleLayout, this);
        this.setData(scalarLiterals, referenceLiterals, ciTargetMethod.targetCode());

        // Patch relative instructions in the code buffer
        patchInstructions(targetBundleLayout, ciTargetMethod, relativeDataPos);
    }

    private int[] serializeLiterals(CiTargetMethod ciTargetMethod, ByteArrayOutputStream output, List<Object> objectReferences) {
        Endianness endianness = Platform.hostOrTarget().endianess();
        int[] relativeDataPos = new int[ciTargetMethod.dataReferences.size()];
        int z = 0;
        int currentPos = 0;
        for (DataPatch site : ciTargetMethod.dataReferences) {

            final CiConstant data = site.data;
            relativeDataPos[z] = currentPos;

            try {
                switch (data.kind) {

                    case Double:
                        endianness.writeLong(output, Double.doubleToLongBits(data.asDouble()));
                        currentPos += Long.SIZE / Byte.SIZE;
                        break;

                    case Float:
                        endianness.writeInt(output, Float.floatToIntBits(data.asFloat()));
                        currentPos += Integer.SIZE / Byte.SIZE;
                        break;

                    case Int:
                        endianness.writeInt(output, data.asInt());
                        currentPos += Integer.SIZE / Byte.SIZE;
                        break;

                    case Long:
                        endianness.writeLong(output, data.asLong());
                        currentPos += Long.SIZE / Byte.SIZE;
                        break;

                    case Object:
                        objectReferences.add(data.asObject());
                        break;

                    default:
                        throw new IllegalArgumentException("Unknown constant type!");
                }

            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            // Align on double word boundary
            while (currentPos % (Platform.hostOrTarget().wordWidth().numberOfBytes * 2) != 0) {
                output.write(0);
                currentPos++;
            }

            z++;
        }

        return relativeDataPos;
    }

    @UNSAFE
    private void patchInstructions(TargetBundleLayout targetBundleLayout, CiTargetMethod ciTargetMethod, int[] relativeDataPositions) {
        Offset codeStart = targetBundleLayout.cellOffset(TargetBundleLayout.ArrayField.code);

        Offset dataDiff = Offset.zero();
        if (this.scalarLiterals != null) {
            Offset dataStart = targetBundleLayout.cellOffset(TargetBundleLayout.ArrayField.scalarLiterals);
            dataDiff = dataStart.minus(codeStart).asOffset();
        }

        Offset referenceDiff = Offset.zero();
        if (this.referenceLiterals() != null) {
            Offset referenceStart = targetBundleLayout.cellOffset(TargetBundleLayout.ArrayField.referenceLiterals);
            referenceDiff = referenceStart.minus(codeStart).asOffset();
        }

        int objectReferenceIndex = 0;
        int refSize = Platform.hostOrTarget().wordWidth().numberOfBytes;

        int z = 0;
        for (DataPatch site : ciTargetMethod.dataReferences) {

            switch (site.data.kind) {

                case Double: // fall through
                case Float: // fall through
                case Int: // fall through
                case Long:
                    patchRelativeInstruction(site.codePos, dataDiff.plus(relativeDataPositions[z] - site.codePos).toInt());
                    break;

                case Object:
                    patchRelativeInstruction(site.codePos, referenceDiff.plus(objectReferenceIndex * refSize - site.codePos).toInt());
                    objectReferenceIndex++;
                    break;

                default:
                    throw new IllegalArgumentException("Unknown constant type!");
            }

            z++;
        }
    }

    private void patchRelativeInstruction(int codePos, int displacement) {
        X86InstructionDecoder.patchRelativeInstruction(code(), codePos, displacement);
    }

    private void initFrameLayout(CiTargetMethod ciTargetMethod) {
        this.referenceRegisterCount = ciTargetMethod.referenceRegisterCount();
        this.setFrameSize(ciTargetMethod.frameSize());
        this.setRegisterRestoreEpilogueOffset(ciTargetMethod.registerRestoreEpilogueOffset());
    }

    private void initStopPositions(CiTargetMethod ciTargetMethod) {
        int numberOfIndirectCalls = ciTargetMethod.indirectCalls.size();
        int numberOfSafepoints = ciTargetMethod.safepoints.size();
        int totalStopPositions = ciTargetMethod.directCalls.size() + numberOfIndirectCalls + numberOfSafepoints;

        referenceMaps = new byte[totalReferenceMapSize() * totalStopPositions];

        int index = 0;
        int[] stopPositions = new int[totalStopPositions];
        Object[] directCallees = new Object[ciTargetMethod.directCalls.size()];

        for (Call site : ciTargetMethod.directCalls) {
            initStopPosition(index, stopPositions, site.codePos, site.registerMap, site.stackMap);

            if (site.method != null) {
                final MaxRiMethod maxMethod = (MaxRiMethod) site.method;
                final ClassMethodActor cma = maxMethod.asClassMethodActor("directCall()");
                assert cma != null : "unresolved direct call!";
                directCallees[index] = cma;
            } else if (site.runtimeCall != null) {
                final ClassMethodActor cma = C1XRuntimeCalls.getClassMethodActor(site.runtimeCall);
                assert cma != null : "unresolved runtime call!";
                directCallees[index] = cma;
            } else {
                assert site.globalStubID != null;
                TargetMethod globalStubMethod = (TargetMethod) site.globalStubID;
                directCallees[index] = globalStubMethod;
            }
            index++;
        }

        for (Call site : ciTargetMethod.indirectCalls) {
            initStopPosition(index, stopPositions, site.codePos, site.registerMap, site.stackMap);
            index++;
        }

        for (CiTargetMethod.Safepoint safepoint : ciTargetMethod.safepoints) {
            initStopPosition(index, stopPositions, safepoint.codePos, safepoint.registerMap, safepoint.stackMap);
            index++;
        }

        this.setStopPositions(stopPositions, directCallees, numberOfIndirectCalls, numberOfSafepoints);
    }

    private void initStopPosition(int index, int[] stopPositions, int codePos, boolean[] registerMap, boolean[] stackMap) {
        stopPositions[index] = codePos;

        if (registerMap != null) {
            initRegisterMap(index, registerMap);
        }

        if (stackMap != null) {
            initStackMap(index, stackMap);
        }
    }

    private void initRegisterMap(int index, boolean[] registerMap) {
        assert registerMap.length == referenceRegisterCount;
        for (int i = 0; i < registerMap.length; i++) {
            if (registerMap[i]) {
                setRegisterReferenceMapBit(index, i);
            }
        }
    }

    private void initStackMap(int index, boolean[] stackMap) {
        assert stackMap.length == frameWords();
        for (int i = 0; i < stackMap.length; i++) {
            if (stackMap[i]) {
                setFrameReferenceMapBit(index, i);
            }
        }
    }

    private void initExceptionTable(CiTargetMethod ciTargetMethod) {
        if (ciTargetMethod.exceptionHandlers.size() > 0) {
            exceptionPositionsToCatchPositions = new int[ciTargetMethod.exceptionHandlers.size() * 2];
            exceptionClassActors = new ClassActor[ciTargetMethod.exceptionHandlers.size()];

            int z = 0;
            for (ExceptionHandler handler : ciTargetMethod.exceptionHandlers) {
                exceptionPositionsToCatchPositions[z * 2] = handler.codePos;
                exceptionPositionsToCatchPositions[z * 2 + 1] = handler.handlerPos;
                exceptionClassActors[z] = (handler.exceptionType == null) ? null : ((MaxRiType) handler.exceptionType).classActor;
                z++;
            }
        }
    }

    @UNSAFE
    @Override
    public final void patchCallSite(int callOffset, Word callEntryPoint) {
        final int displacement = callEntryPoint.asAddress().minus(codeStart().plus(callOffset)).toInt();
        X86InstructionDecoder.patchRelativeInstruction(code(), callOffset, displacement);
    }

    @Override
    public void forwardTo(TargetMethod newTargetMethod) {
        forwardTo(this, newTargetMethod);
    }

    @UNSAFE
    public static void forwardTo(TargetMethod oldTargetMethod, TargetMethod newTargetMethod) {
        assert oldTargetMethod != newTargetMethod;
        assert oldTargetMethod.abi().callEntryPoint() != CallEntryPoint.C_ENTRY_POINT;

        final long newOptEntry = CallEntryPoint.OPTIMIZED_ENTRY_POINT.in(newTargetMethod).asAddress().toLong();
        final long newJitEntry = CallEntryPoint.JIT_ENTRY_POINT.in(newTargetMethod).asAddress().toLong();

        patchCode(oldTargetMethod, CallEntryPoint.OPTIMIZED_ENTRY_POINT.offsetFromCodeStart(), newOptEntry, RJMP);
        patchCode(oldTargetMethod, CallEntryPoint.JIT_ENTRY_POINT.offsetFromCodeStart(), newJitEntry, RJMP);
    }

    @UNSAFE
    private static void patchCode(TargetMethod targetMethod, int offset, long target, int controlTransferOpcode) {
        final Pointer callSite = targetMethod.codeStart().plus(offset);
        final long displacement = (target - (callSite.toLong() + 5L)) & 0xFFFFFFFFL;
        if (MaxineVM.isHosted()) {
            final byte[] code = targetMethod.code();
            code[offset] = (byte) controlTransferOpcode;
            code[offset + 1] = (byte) displacement;
            code[offset + 2] = (byte) (displacement >> 8);
            code[offset + 3] = (byte) (displacement >> 16);
            code[offset + 4] = (byte) (displacement >> 24);
        } else {
            // TODO: Patching code is probably not thread safe!
            //       Patch location must not straddle a cache-line (32-byte) boundary.
            FatalError.check(true | callSite.isWordAligned(), "Method " + targetMethod.classMethodActor().format("%H.%n(%p)") + " entry point is not word aligned.");
            // The read, modify, write below should be changed to simply a write once we have the method entry point alignment fixed.
            final Word patch = callSite.readWord(0).asAddress().and(0xFFFFFF0000000000L).or((displacement << 8) | controlTransferOpcode);
            callSite.writeWord(0, patch);
        }
    }

    @UNSAFE
    @Override
    public Address throwAddressToCatchAddress(boolean isTopFrame, Address throwAddress, Class<? extends Throwable> throwableClass) {
        final int exceptionPos = throwAddress.minus(codeStart).toInt();
        for (int i = 0; i < getExceptionHandlerCount(); i++) {
            int codePos = getExceptionPosAt(i);
            int catchPos = getCatchPosAt(i);
            ClassActor catchType = getCatchTypeAt(i);

            if (codePos == exceptionPos && checkType(throwableClass, catchType)) {
                return codeStart.plus(catchPos);
            }
        }
        return Address.zero();
    }

    private boolean isCatchAll(ClassActor type) {
        return type == null;
    }

    private boolean checkType(Class<? extends Throwable> throwableClass, ClassActor catchType) {
        return isCatchAll(catchType) || catchType.isAssignableFrom(ClassActor.fromJava(throwableClass));
    }

    /**
     * Gets the exception code position of an entry in the exception handler table.
     *
     * @param i the index of the requested exception handler table entry
     * @return the exception code position of element {@code i} in the exception handler table
     */
    private int getExceptionPosAt(int i) {
        return exceptionPositionsToCatchPositions[i * 2];
    }

    /**
     * Gets the exception handler code position of an entry in the exception handler table.
     *
     * @param i the index of the requested exception handler table entry
     * @return the exception handler position of element {@code i} in the exception handler table
     */
    private int getCatchPosAt(int i) {
        return exceptionPositionsToCatchPositions[i * 2 + 1];
    }

    /**
     * Gets the exception type of an entry in the exception handler table.
     *
     * @param i the index of the requested exception handler table entry
     * @return the exception type of element {@code i} in the exception handler table
     */
    private ClassActor getCatchTypeAt(int i) {
        return exceptionClassActors[i];
    }

    /**
     * Gets the number of entries in the exception handler table.
     */
    private int getExceptionHandlerCount() {
        return exceptionClassActors == null ? 0 : exceptionClassActors.length;
    }

    @Override
    public void prepareFrameReferenceMap(int stopIndex, Pointer refmapFramePointer, StackReferenceMapPreparer preparer, TargetMethod callee) {
        preparer.tracePrepareReferenceMap(this, stopIndex, refmapFramePointer, "frame");
        int frameSlotIndex = preparer.referenceMapBitIndex(refmapFramePointer);
        int byteIndex = stopIndex * totalReferenceMapSize();
        for (int i = 0; i < frameReferenceMapSize(); i++) {
            final byte frameReferenceMapByte = referenceMaps[byteIndex];
            preparer.traceReferenceMapByteBefore(byteIndex, frameReferenceMapByte, "Frame");
            preparer.setBits(frameSlotIndex, frameReferenceMapByte);
            preparer.traceReferenceMapByteAfter(refmapFramePointer, frameSlotIndex, frameReferenceMapByte);
            frameSlotIndex += Bytes.WIDTH;
            byteIndex++;
        }

        if (callee instanceof C1XTargetMethod) {
            final C1XTargetMethod c1xCallee = (C1XTargetMethod) callee;
            if (c1xCallee.isCalleeSaved()) {
                for (int i = 0; i < referenceRegisterCount; i++) {
                    boolean curBit = isRegisterReferenceMapBitSet(stopIndex, i);
                    if (curBit) {
//
//                        // TODO (tw): Check if this is correct?
//                        int numberOfWords = i + 2;
//                        Pointer referencePointer = stackPointer.minusWords(numberOfWords);
//                        result.setReferenceMapBit(referencePointer);
                    }
                }
            }
        }
    }

    @Override
    public void prepareRegisterReferenceMap(Pointer registerState, Pointer instructionPointer, StackReferenceMapPreparer preparer) {

        // TODO (tw): Tentative implementation! Make correct...

        int stopIndex = lookupStopPosition(instructionPointer);
        for (int i = 0; i < referenceRegisterCount; i++) {
            boolean curBit = isRegisterReferenceMapBitSet(stopIndex, i);
            if (curBit) {
                int numberOfWords = i;
                Pointer referencePointer = registerState.plusWords(numberOfWords);
                preparer.setReferenceMapBit(referencePointer);
            }
        }
    }

    private int lookupStopPosition(Pointer instructionPointer) {
        int offset = instructionPointer.minus(codeStart()).toInt();
        for (int i = 0; i < stopPositions.length; i++) {
            if (stopPositions[i] == offset) {
                return i;
            }
        }

        Log.print("Could not find stop position for instruction at position ");
        Log.print(instructionPointer.minus(codeStart()).toInt());
        Log.print(" in ");
        Log.printMethod(classMethodActor(), true);
        throw FatalError.unexpected("Could not find stop position in target method");
    }

    @Override
    @HOSTED_ONLY
    public void gatherCalls(AppendableSequence<MethodActor> directCalls, AppendableSequence<MethodActor> virtualCalls, AppendableSequence<MethodActor> interfaceCalls) {

        // iterate over direct calls
        for (CiTargetMethod.Call site : bootstrappingCiTargetMethod.directCalls) {
            if (site.runtimeCall != null) {
                directCalls.append(getClassMethodActor(site.runtimeCall, site.method));
            } else if (site.method != null) {
                MethodActor methodActor = ((MaxRiMethod) site.method).asMethodActor("gatherCalls()");
                directCalls.append(methodActor);
            }
        }

        // iterate over all the calls and append them to the appropriate lists
        for (CiTargetMethod.Call site : bootstrappingCiTargetMethod.indirectCalls) {
            assert site.method != null;
            if (site.method.isLoaded()) {
                MethodActor methodActor = ((MaxRiMethod) site.method).asMethodActor("gatherCalls()");
                if (site.method.holder().isInterface()) {
                    interfaceCalls.append(methodActor);
                } else {
                    virtualCalls.append(methodActor);
                }
            }
        }
    }

    private ClassMethodActor getClassMethodActor(CiRuntimeCall runtimeCall, RiMethod method) {
        if (method != null) {
            final MaxRiMethod maxMethod = (MaxRiMethod) method;
            return maxMethod.asClassMethodActor("directCall()");
        }

        assert runtimeCall != null : "A call can either be a call to a method or a runtime call";
        return C1XRuntimeCalls.getClassMethodActor(runtimeCall);
    }

    @Override
    public void traceDebugInfo(IndentWriter writer) {
    }

    @Override
    public void traceExceptionHandlers(IndentWriter writer) {
        if (getExceptionHandlerCount() != 0) {
            writer.println("Exception handlers:");
            writer.indent();
            for (int i = 0; i < getExceptionHandlerCount(); i++) {
                ClassActor catchType = getCatchTypeAt(i);
                writer.println((catchType == null ? "<any>" : catchType) + " @ " + getExceptionPosAt(i) + " -> " + getCatchPosAt(i));
            }
            writer.outdent();
        }
    }

    /**
     * Gets a string representation of the reference map for each {@linkplain #stopPositions() stop} in this target method.
     */
    @Override
    public String referenceMapsToString() {
        if (numberOfStopPositions() == 0) {
            return "";
        }
        final StringBuilder buf = new StringBuilder();
        final JavaStackFrameLayout layout = new C1XStackFrameLayout(frameSize());
        final Slots slots = layout.slots();
        final int firstSafepointStopIndex = numberOfDirectCalls() + numberOfIndirectCalls();
        for (int stopIndex = 0; stopIndex < numberOfStopPositions(); ++stopIndex) {
            final int stopPosition = stopPosition(stopIndex);
            buf.append(String.format("stop: index=%d, position=%d, type=%s%n", stopIndex, stopPosition,
                            stopIndex < numberOfDirectCalls() ? "direct call" :
                           (stopIndex < firstSafepointStopIndex ? "indirect call" : "safepoint")));
            int byteIndex = stopIndex * totalReferenceMapSize();
            final ByteArrayBitMap referenceMap = new ByteArrayBitMap(referenceMaps, byteIndex, frameReferenceMapSize());
            buf.append(slots.toString(referenceMap));
            if (registerReferenceMapSize() != 0) {
                byteIndex = stopIndex * totalReferenceMapSize() + frameReferenceMapSize();
                String referenceRegisters = "";
                buf.append("  register map:");
                for (int i = 0; i < registerReferenceMapSize(); i++) {
                    final byte refMapByte = referenceMaps[byteIndex];
                    buf.append(String.format(" 0x%x", refMapByte & 0xff));
                    if (refMapByte != 0) {
                        for (int bitIndex = 0; bitIndex < Bytes.WIDTH; bitIndex++) {
                            if (((refMapByte >>> bitIndex) & 1) != 0) {
                                referenceRegisters += "reg" + (bitIndex + (i * Bytes.WIDTH)) + " ";
                            }
                        }
                    }
                    byteIndex++;
                }
                if (!referenceRegisters.isEmpty()) {
                    buf.append(" { ").append(referenceRegisters).append("}");
                }
                buf.append(String.format("%n"));
            }
        }

        return buf.toString();
    }
}
