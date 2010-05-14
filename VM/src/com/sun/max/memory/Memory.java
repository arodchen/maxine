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
package com.sun.max.memory;

import com.sun.max.annotate.*;
import com.sun.max.program.*;
import com.sun.max.unsafe.*;
import com.sun.max.vm.runtime.*;

/**
 * This class provides methods to access raw memory through pointers.
 * It also provides allocation methods that are expected to be for small quantities
 * of memory (or quantities that are not multiple of a page) that will be satisfied
 * by the native allocation library, i.e. malloc/free.
 * Large amounts of memory should be allocated using the {@link VirtualMemory} class.
 *
 * @author Bernd Mathiske
 */
public final class Memory {

    private Memory() {
    }

    public static final OutOfMemoryError OUT_OF_MEMORY_ERROR = new OutOfMemoryError();

    @C_FUNCTION
    private static native Pointer memory_allocate(Size size);

    /**
     * Allocates an alligned chunk of memory using a malloc(3)-like facility.
     *
     * @param size the size of the chunk of memory to be allocated
     * @return a pointer to the allocated chunk of memory or {@code Pointer.zero()} if allocation failed
     */
    public static Pointer allocate(Size size) {
        if (size.toLong() < 0) {
            throw new IllegalArgumentException();
        }
        return Word.isBoxed() ? BoxedMemory.allocate(size) : memory_allocate(size);
    }

    /**
     * @param size the size of the chunk of memory to be allocated
     * @return a pointer to the allocated chunk of memory
     * @throws OutOfMemoryError if allocation failed
     */
    public static Pointer mustAllocate(Size size) throws OutOfMemoryError, IllegalArgumentException {
        final Pointer result = Word.isBoxed() ? BoxedMemory.allocate(size) : memory_allocate(size);
        if (result.isZero()) {
            throw new OutOfMemoryError();
        }
        return result;
    }

    /**
     * @param size the size of the chunk of memory to be allocated
     * @return a pointer to the allocated chunk of memory
     * @throws IllegalArgumentException if size is negative
     * @throws OutOfMemoryError if allocation failed
     */
    public static Pointer mustAllocate(int size) throws OutOfMemoryError, IllegalArgumentException {
        return mustAllocate(Size.fromInt(size));
    }

    @C_FUNCTION
    private static native Pointer memory_reallocate(Pointer block, Size size);

    public static Pointer reallocate(Pointer block, Size size) throws OutOfMemoryError, IllegalArgumentException {
        return Word.isBoxed() ? BoxedMemory.reallocate(block, size) : memory_reallocate(block, size);
    }

    public static Pointer reallocate(Pointer block, int size) throws OutOfMemoryError, IllegalArgumentException {
        return reallocate(block, Size.fromInt(size));
    }

    @C_FUNCTION
    private static native int memory_deallocate(Address pointer);

    public static void deallocate(Address block) throws IllegalArgumentException {
        if (block.isZero()) {
            throw new IllegalArgumentException();
        }
        final int errorCode = Word.isBoxed() ? BoxedMemory.deallocate(block) : memory_deallocate(block);
        if (errorCode != 0) {
            ProgramError.unexpected("Memory.deallocate() failed with OS error code: " + errorCode);
        }
    }

    @NO_SAFEPOINTS("speed")
    public static void setBytes(Pointer pointer, Size numberOfBytes, byte value) {
        for (Offset i = Offset.zero(); i.lessThan(numberOfBytes.asOffset()); i = i.plus(1)) {
            pointer.writeByte(i, value);
        }
    }

    @NO_SAFEPOINTS("speed")
    public static void setWords(Pointer pointer, int numberOfWords, Word value) {
        for (int i = 0; i < (numberOfWords * Word.size()); i += Word.size()) {
            pointer.writeWord(i, value);
        }
    }

    @NO_SAFEPOINTS("speed")
    public static void clearWords(Pointer start, int length) {
        FatalError.check(start.isWordAligned(), "Can only zero word-aligned region");
        for (int i = 0; i < length; i++) {
            start.asPointer().setWord(i, Address.zero());
        }
    }

    @NO_SAFEPOINTS("speed")
    public static void setBytes(Pointer pointer, int numberOfBytes, byte value) {
        for (int i = 0; i < numberOfBytes; i++) {
            pointer.writeByte(i, value);
        }
    }

    @NO_SAFEPOINTS("speed")
    public static void clearBytes(Pointer pointer, int numberOfBytes) {
        setBytes(pointer, numberOfBytes, (byte) 0);
    }

    @NO_SAFEPOINTS("speed")
    public static boolean equals(Pointer pointer1, Pointer pointer2, Size numberOfBytes) {
        for (Offset i = Offset.zero(); i.lessThan(numberOfBytes.asOffset()); i = i.plus(1)) {
            if (pointer1.readByte(i) != pointer2.readByte(i)) {
                return false;
            }
        }
        return true;
    }

    @NO_SAFEPOINTS("speed")
    public static boolean equals(Pointer pointer1, byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            if (pointer1.readByte(i) != bytes[i]) {
                return false;
            }
        }
        return true;
    }

    @NO_SAFEPOINTS("speed")
    public static void copyBytes(Pointer fromPointer, Pointer toPointer, Size numberOfBytes) {
        for (Offset i = Offset.zero(); i.lessThan(numberOfBytes.asOffset()); i = i.plus(1)) {
            toPointer.writeByte(i, fromPointer.readByte(i));
        }
    }

    @NO_SAFEPOINTS("speed")
    public static void readBytes(Pointer fromPointer, int numberOfBytes, byte[] toArray, int startIndex) {
        for (int i = 0; i < numberOfBytes; i++) {
            toArray[startIndex + i] = fromPointer.readByte(i);
        }
    }

    @NO_SAFEPOINTS("speed")
    public static void readBytes(Pointer fromPointer, int numberOfBytes, byte[] toArray) {
        readBytes(fromPointer, numberOfBytes, toArray, 0);
    }

    @NO_SAFEPOINTS("speed")
    public static void readBytes(Pointer fromPointer, byte[] toArray) {
        readBytes(fromPointer, toArray.length, toArray, 0);
    }

    @NO_SAFEPOINTS("speed")
    public static void readWords(Pointer fromPointer, int numberOfWords, Word[] toArray, int startIndex) {
        for (int i = 0; i < numberOfWords; i++) {
            WordArray.set(toArray, startIndex + i, fromPointer.getWord(i));
        }
    }

    @NO_SAFEPOINTS("speed")
    public static void readWords(Pointer fromPointer, int numberOfWords, Word[] toArray) {
        readWords(fromPointer, numberOfWords, toArray, 0);
    }

    @NO_SAFEPOINTS("speed")
    public static void readWords(Pointer fromPointer, Word[] toArray) {
        readWords(fromPointer, toArray.length, toArray, 0);
    }

    @NO_SAFEPOINTS("speed")
    public static void writeBytes(byte[] fromArray, int startIndex, int numberOfBytes, Pointer toPointer) {
        if (Word.isBoxed()) {
            BoxedMemory.writeBytes(fromArray, startIndex, numberOfBytes, toPointer);
        } else {
            for (int i = 0; i < numberOfBytes; i++) {
                toPointer.writeByte(i, fromArray[startIndex + i]);
            }
        }
    }

    @NO_SAFEPOINTS("speed")
    public static void writeBytes(byte[] fromArray, int numberOfBytes, Pointer toPointer) {
        writeBytes(fromArray, 0, numberOfBytes, toPointer);
    }

    @NO_SAFEPOINTS("speed")
    public static void writeBytes(byte[] fromArray, Pointer toPointer) {
        writeBytes(fromArray, fromArray.length, toPointer);
    }

    @NO_SAFEPOINTS("speed")
    public static void zapRegion(RuntimeMemoryRegion region) {
        FatalError.check(region.start().isWordAligned(), "Can only zap word-aligned region");
        FatalError.check(region.size().remainder(Word.size()) == 0, "Can only zap region of words");
        setWords(region.start().asPointer(), region.size().dividedBy(Word.size()).toInt(), Address.fromLong(0xDEADBEEFCAFEBABEL));
    }

}
