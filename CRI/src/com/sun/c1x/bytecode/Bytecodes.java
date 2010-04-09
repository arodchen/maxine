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
package com.sun.c1x.bytecode;

import static com.sun.c1x.bytecode.Bytecodes.Flags.*;
import static com.sun.c1x.bytecode.Bytecodes.MemoryBarriers.*;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.regex.*;

/**
 * The definitions of the bytecodes that are valid input to the compiler and
 * related utility methods. This comprises two groups: the standard Java
 * bytecodes defined by <a href=
 * "http://java.sun.com/docs/books/jvms/second_edition/html/VMSpecTOC.doc.html">
 * Java Virtual Machine Specification</a>, and a set of <i>extended</i>
 * bytecodes that support low-level programming, for example, memory barriers.
 *
 * The extended bytecodes are one or two bytes in size. The one-byte bytecodes
 * follow the values in the standard set, with no gap. The two-byte extended
 * bytecodes share a common first byte and carry additional instruction-specific
 * information in the second byte.
 *
 *
 * @author Ben L. Titzer
 * @author Doug Simon
 */
public class Bytecodes {
    public static final int NOP                  =   0; // 0x00
    public static final int ACONST_NULL          =   1; // 0x01
    public static final int ICONST_M1            =   2; // 0x02
    public static final int ICONST_0             =   3; // 0x03
    public static final int ICONST_1             =   4; // 0x04
    public static final int ICONST_2             =   5; // 0x05
    public static final int ICONST_3             =   6; // 0x06
    public static final int ICONST_4             =   7; // 0x07
    public static final int ICONST_5             =   8; // 0x08
    public static final int LCONST_0             =   9; // 0x09
    public static final int LCONST_1             =  10; // 0x0A
    public static final int FCONST_0             =  11; // 0x0B
    public static final int FCONST_1             =  12; // 0x0C
    public static final int FCONST_2             =  13; // 0x0D
    public static final int DCONST_0             =  14; // 0x0E
    public static final int DCONST_1             =  15; // 0x0F
    public static final int BIPUSH               =  16; // 0x10
    public static final int SIPUSH               =  17; // 0x11
    public static final int LDC                  =  18; // 0x12
    public static final int LDC_W                =  19; // 0x13
    public static final int LDC2_W               =  20; // 0x14
    public static final int ILOAD                =  21; // 0x15
    public static final int LLOAD                =  22; // 0x16
    public static final int FLOAD                =  23; // 0x17
    public static final int DLOAD                =  24; // 0x18
    public static final int ALOAD                =  25; // 0x19
    public static final int ILOAD_0              =  26; // 0x1A
    public static final int ILOAD_1              =  27; // 0x1B
    public static final int ILOAD_2              =  28; // 0x1C
    public static final int ILOAD_3              =  29; // 0x1D
    public static final int LLOAD_0              =  30; // 0x1E
    public static final int LLOAD_1              =  31; // 0x1F
    public static final int LLOAD_2              =  32; // 0x20
    public static final int LLOAD_3              =  33; // 0x21
    public static final int FLOAD_0              =  34; // 0x22
    public static final int FLOAD_1              =  35; // 0x23
    public static final int FLOAD_2              =  36; // 0x24
    public static final int FLOAD_3              =  37; // 0x25
    public static final int DLOAD_0              =  38; // 0x26
    public static final int DLOAD_1              =  39; // 0x27
    public static final int DLOAD_2              =  40; // 0x28
    public static final int DLOAD_3              =  41; // 0x29
    public static final int ALOAD_0              =  42; // 0x2A
    public static final int ALOAD_1              =  43; // 0x2B
    public static final int ALOAD_2              =  44; // 0x2C
    public static final int ALOAD_3              =  45; // 0x2D
    public static final int IALOAD               =  46; // 0x2E
    public static final int LALOAD               =  47; // 0x2F
    public static final int FALOAD               =  48; // 0x30
    public static final int DALOAD               =  49; // 0x31
    public static final int AALOAD               =  50; // 0x32
    public static final int BALOAD               =  51; // 0x33
    public static final int CALOAD               =  52; // 0x34
    public static final int SALOAD               =  53; // 0x35
    public static final int ISTORE               =  54; // 0x36
    public static final int LSTORE               =  55; // 0x37
    public static final int FSTORE               =  56; // 0x38
    public static final int DSTORE               =  57; // 0x39
    public static final int ASTORE               =  58; // 0x3A
    public static final int ISTORE_0             =  59; // 0x3B
    public static final int ISTORE_1             =  60; // 0x3C
    public static final int ISTORE_2             =  61; // 0x3D
    public static final int ISTORE_3             =  62; // 0x3E
    public static final int LSTORE_0             =  63; // 0x3F
    public static final int LSTORE_1             =  64; // 0x40
    public static final int LSTORE_2             =  65; // 0x41
    public static final int LSTORE_3             =  66; // 0x42
    public static final int FSTORE_0             =  67; // 0x43
    public static final int FSTORE_1             =  68; // 0x44
    public static final int FSTORE_2             =  69; // 0x45
    public static final int FSTORE_3             =  70; // 0x46
    public static final int DSTORE_0             =  71; // 0x47
    public static final int DSTORE_1             =  72; // 0x48
    public static final int DSTORE_2             =  73; // 0x49
    public static final int DSTORE_3             =  74; // 0x4A
    public static final int ASTORE_0             =  75; // 0x4B
    public static final int ASTORE_1             =  76; // 0x4C
    public static final int ASTORE_2             =  77; // 0x4D
    public static final int ASTORE_3             =  78; // 0x4E
    public static final int IASTORE              =  79; // 0x4F
    public static final int LASTORE              =  80; // 0x50
    public static final int FASTORE              =  81; // 0x51
    public static final int DASTORE              =  82; // 0x52
    public static final int AASTORE              =  83; // 0x53
    public static final int BASTORE              =  84; // 0x54
    public static final int CASTORE              =  85; // 0x55
    public static final int SASTORE              =  86; // 0x56
    public static final int POP                  =  87; // 0x57
    public static final int POP2                 =  88; // 0x58
    public static final int DUP                  =  89; // 0x59
    public static final int DUP_X1               =  90; // 0x5A
    public static final int DUP_X2               =  91; // 0x5B
    public static final int DUP2                 =  92; // 0x5C
    public static final int DUP2_X1              =  93; // 0x5D
    public static final int DUP2_X2              =  94; // 0x5E
    public static final int SWAP                 =  95; // 0x5F
    public static final int IADD                 =  96; // 0x60
    public static final int LADD                 =  97; // 0x61
    public static final int FADD                 =  98; // 0x62
    public static final int DADD                 =  99; // 0x63
    public static final int ISUB                 = 100; // 0x64
    public static final int LSUB                 = 101; // 0x65
    public static final int FSUB                 = 102; // 0x66
    public static final int DSUB                 = 103; // 0x67
    public static final int IMUL                 = 104; // 0x68
    public static final int LMUL                 = 105; // 0x69
    public static final int FMUL                 = 106; // 0x6A
    public static final int DMUL                 = 107; // 0x6B
    public static final int IDIV                 = 108; // 0x6C
    public static final int LDIV                 = 109; // 0x6D
    public static final int FDIV                 = 110; // 0x6E
    public static final int DDIV                 = 111; // 0x6F
    public static final int IREM                 = 112; // 0x70
    public static final int LREM                 = 113; // 0x71
    public static final int FREM                 = 114; // 0x72
    public static final int DREM                 = 115; // 0x73
    public static final int INEG                 = 116; // 0x74
    public static final int LNEG                 = 117; // 0x75
    public static final int FNEG                 = 118; // 0x76
    public static final int DNEG                 = 119; // 0x77
    public static final int ISHL                 = 120; // 0x78
    public static final int LSHL                 = 121; // 0x79
    public static final int ISHR                 = 122; // 0x7A
    public static final int LSHR                 = 123; // 0x7B
    public static final int IUSHR                = 124; // 0x7C
    public static final int LUSHR                = 125; // 0x7D
    public static final int IAND                 = 126; // 0x7E
    public static final int LAND                 = 127; // 0x7F
    public static final int IOR                  = 128; // 0x80
    public static final int LOR                  = 129; // 0x81
    public static final int IXOR                 = 130; // 0x82
    public static final int LXOR                 = 131; // 0x83
    public static final int IINC                 = 132; // 0x84
    public static final int I2L                  = 133; // 0x85
    public static final int I2F                  = 134; // 0x86
    public static final int I2D                  = 135; // 0x87
    public static final int L2I                  = 136; // 0x88
    public static final int L2F                  = 137; // 0x89
    public static final int L2D                  = 138; // 0x8A
    public static final int F2I                  = 139; // 0x8B
    public static final int F2L                  = 140; // 0x8C
    public static final int F2D                  = 141; // 0x8D
    public static final int D2I                  = 142; // 0x8E
    public static final int D2L                  = 143; // 0x8F
    public static final int D2F                  = 144; // 0x90
    public static final int I2B                  = 145; // 0x91
    public static final int I2C                  = 146; // 0x92
    public static final int I2S                  = 147; // 0x93
    public static final int LCMP                 = 148; // 0x94
    public static final int FCMPL                = 149; // 0x95
    public static final int FCMPG                = 150; // 0x96
    public static final int DCMPL                = 151; // 0x97
    public static final int DCMPG                = 152; // 0x98
    public static final int IFEQ                 = 153; // 0x99
    public static final int IFNE                 = 154; // 0x9A
    public static final int IFLT                 = 155; // 0x9B
    public static final int IFGE                 = 156; // 0x9C
    public static final int IFGT                 = 157; // 0x9D
    public static final int IFLE                 = 158; // 0x9E
    public static final int IF_ICMPEQ            = 159; // 0x9F
    public static final int IF_ICMPNE            = 160; // 0xA0
    public static final int IF_ICMPLT            = 161; // 0xA1
    public static final int IF_ICMPGE            = 162; // 0xA2
    public static final int IF_ICMPGT            = 163; // 0xA3
    public static final int IF_ICMPLE            = 164; // 0xA4
    public static final int IF_ACMPEQ            = 165; // 0xA5
    public static final int IF_ACMPNE            = 166; // 0xA6
    public static final int GOTO                 = 167; // 0xA7
    public static final int JSR                  = 168; // 0xA8
    public static final int RET                  = 169; // 0xA9
    public static final int TABLESWITCH          = 170; // 0xAA
    public static final int LOOKUPSWITCH         = 171; // 0xAB
    public static final int IRETURN              = 172; // 0xAC
    public static final int LRETURN              = 173; // 0xAD
    public static final int FRETURN              = 174; // 0xAE
    public static final int DRETURN              = 175; // 0xAF
    public static final int ARETURN              = 176; // 0xB0
    public static final int RETURN               = 177; // 0xB1
    public static final int GETSTATIC            = 178; // 0xB2
    public static final int PUTSTATIC            = 179; // 0xB3
    public static final int GETFIELD             = 180; // 0xB4
    public static final int PUTFIELD             = 181; // 0xB5
    public static final int INVOKEVIRTUAL        = 182; // 0xB6
    public static final int INVOKESPECIAL        = 183; // 0xB7
    public static final int INVOKESTATIC         = 184; // 0xB8
    public static final int INVOKEINTERFACE      = 185; // 0xB9
    public static final int XXXUNUSEDXXX         = 186; // 0xBA
    public static final int NEW                  = 187; // 0xBB
    public static final int NEWARRAY             = 188; // 0xBC
    public static final int ANEWARRAY            = 189; // 0xBD
    public static final int ARRAYLENGTH          = 190; // 0xBE
    public static final int ATHROW               = 191; // 0xBF
    public static final int CHECKCAST            = 192; // 0xC0
    public static final int INSTANCEOF           = 193; // 0xC1
    public static final int MONITORENTER         = 194; // 0xC2
    public static final int MONITOREXIT          = 195; // 0xC3
    public static final int WIDE                 = 196; // 0xC4
    public static final int MULTIANEWARRAY       = 197; // 0xC5
    public static final int IFNULL               = 198; // 0xC6
    public static final int IFNONNULL            = 199; // 0xC7
    public static final int GOTO_W               = 200; // 0xC8
    public static final int JSR_W                = 201; // 0xC9
    public static final int BREAKPOINT           = 202; // 0xCA

    // Start extended bytecodes

    public static final int JNICALL              = 203;
    public static final int CALL                 = 204;

    public static final int WLOAD                = 205;
    public static final int WLOAD_0              = 206;
    public static final int WLOAD_1              = 207;
    public static final int WLOAD_2              = 208;
    public static final int WLOAD_3              = 209;

    public static final int WSTORE               = 210;
    public static final int WSTORE_0             = 211;
    public static final int WSTORE_1             = 212;
    public static final int WSTORE_2             = 213;
    public static final int WSTORE_3             = 214;

    public static final int WCONST_0             = 215;
    public static final int WDIV                 = 216;
    public static final int WDIVI                = 217; // Divisor is an int
    public static final int WREM                 = 218;
    public static final int WREMI                = 219; // Divisor is an int

    public static final int ICMP                 = 220; // Signed int compare, sets condition flags (for template JIT)
    public static final int WCMP                 = 221; // Word compare, sets condition flags (for template JIT)

    public static final int PREAD                = 222;
    public static final int PWRITE               = 223;

    public static final int PGET                 = 224;
    public static final int PSET                 = 225;

    public static final int PCMPSWP              = 226; // Pointer compare-and-swap

    public static final int MOV_I2F              = 227;
    public static final int MOV_F2I              = 228;
    public static final int MOV_L2D              = 229;
    public static final int MOV_D2L              = 230;

    /**
     * Unsigned integer comparison.
     *
     * <pre>
     * Format: { u1 opcode;   // UCMP
     *           u2 op;       // ABOVE_EQUAL, ABOVE_THAN, BELOW_EQUAL or BELOW_THAN
     *         }
     *
     * Operand Stack:
     *     ..., value, value => ..., value
     * </pre>
     *
     * @see UnsignedComparisons
     */
    public static final int UCMP                 = 231;

    /**
     * Unsigned word comparison.
     *
     * <pre>
     * Format: { u1 opcode;   // UCMP
     *           u2 op;       // ABOVE_EQUAL, ABOVE_THAN, BELOW_EQUAL or BELOW_THAN
     *         }
     *
     * Operand Stack:
     *     ..., value, value => ..., value
     * </pre>
     *
     * @see UnsignedComparisons
     */
    public static final int UWCMP                = 232;

    /**
     * Reads the value of a register playing a runtime-defined role.
     *
     * <pre>
     * Format: { u1 opcode;   // READREG
     *           u2 role;     // runtime-defined register role id
     *         }
     *
     * Operand Stack:
     *     ... => ..., value
     * </pre>
     */
    public static final int READREG              = 233;

    /**
     * Writes the value of a register playing a runtime-defined role.
     *
     * <pre>
     * Format: { u1 opcode;   // WRITEREG
     *           u2 role;     // runtime-defined register role id
     *         }
     *
     * Operand Stack:
     *     ..., value => ...
     * </pre>
     */
    public static final int WRITEREG             = 234;

    /**
     * Unsafe cast of top value on stack. The valid type characters and their corresponding kinds are:
     * <pre>
     *  'z' = boolean
     *  'c' = char
     *  'f' = float
     *  'd' = double
     *  'b' = byte
     *  's' = short
     *  'i' = int
     *  'l' = long
     *  'a' = Object
     *  'w' = Word
     * </pre>
     *
     * <pre>
     * Format: { u1 opcode;   // UNSAFE_CAST
     *           u1 from;     // type char denoting input type
     *           u1 to;       // type char denoting output type
     *         }
     *
     * Operand Stack:
     *     ..., value => ..., value
     * </pre>
     */
    public static final int UNSAFE_CAST          = 235;
    public static final int WRETURN              = 236;
    public static final int SAFEPOINT            = 237;

    /**
     * Allocates a requested block of memory within the current activation frame.
     * The allocated memory is reclaimed when the method returns.
     *
     * The allocation is for the lifetime of the method execution. That is, the compiler
     * reserves the space in the compiled size of the frame. As such, a failure
     * to allocate the requested space will result in a {@link StackOverflowError}
     * when the method's prologue is executed.
     *
     * <pre>
     * Format: { u1 opcode;   // ALLOCA
     *           u2 unused;
     *         }
     *
     * Operand Stack:
     *     ..., value => ..., value
     * </pre>
     *
     * The value on the top of the stack is the size in bytes to allocate.
     * The result is the address of the allocated block. <b>N.B.</b> The contents of the block are uninitialized.
     */
    public static final int ALLOCA               = 238;

    /**
     * Inserts a memory barrier.
     *
     * <pre>
     * Format: { u1 opcode;   // MEMBAR
     *           u2 barrier;  // 1=LOAD_LOAD, 2=LOAD_STORE, 3=STORE_LOAD, 4=STORE_STORE, 5=MEMOP_STORE, 6=ALL
     *         }
     *
     * Operand Stack:
     *     ... => ...
     * </pre>
     */
    public static final int MEMBAR               = 239;
    public static final int STACKADDR            = 240;
    public static final int PAUSE                = 241;
    public static final int ADD_SP               = 242;
    public static final int READ_PC              = 243;
    public static final int FLUSHW               = 244;
    public static final int LSB                  = 245;
    public static final int MSB                  = 246;

    // End extended bytecodes

    // Extended bytecodes with operand:

    // Pointer compare-and-swap with word-sized offset
    public static final int PCMPSWP_INT         = PCMPSWP  | 1 << 8;
    public static final int PCMPSWP_WORD        = PCMPSWP  | 2 << 8;
    public static final int PCMPSWP_REFERENCE   = PCMPSWP  | 3 << 8;

    // Pointer compare-and-swap with int-sized offset
    public static final int PCMPSWP_INT_I       = PCMPSWP  | 4 << 8;
    public static final int PCMPSWP_WORD_I      = PCMPSWP  | 5 << 8;
    public static final int PCMPSWP_REFERENCE_I = PCMPSWP  | 6 << 8;

    // Pointer read with word-sized offset
    public static final int PREAD_BYTE         = PREAD  | 1 << 8;
    public static final int PREAD_CHAR         = PREAD  | 2 << 8;
    public static final int PREAD_SHORT        = PREAD  | 3 << 8;
    public static final int PREAD_INT          = PREAD  | 4 << 8;
    public static final int PREAD_FLOAT        = PREAD  | 5 << 8;
    public static final int PREAD_LONG         = PREAD  | 6 << 8;
    public static final int PREAD_DOUBLE       = PREAD  | 7 << 8;
    public static final int PREAD_WORD         = PREAD  | 8 << 8;
    public static final int PREAD_REFERENCE    = PREAD  | 9 << 8;

    // Pointer read with int-sized offset
    public static final int PREAD_BYTE_I       = PREAD  | 10  << 8;
    public static final int PREAD_CHAR_I       = PREAD  | 11 << 8;
    public static final int PREAD_SHORT_I      = PREAD  | 12 << 8;
    public static final int PREAD_INT_I        = PREAD  | 13 << 8;
    public static final int PREAD_FLOAT_I      = PREAD  | 14 << 8;
    public static final int PREAD_LONG_I       = PREAD  | 15 << 8;
    public static final int PREAD_DOUBLE_I     = PREAD  | 16 << 8;
    public static final int PREAD_WORD_I       = PREAD  | 17 << 8;
    public static final int PREAD_REFERENCE_I  = PREAD  | 18 << 8;

    // Pointer write with word-sized offset
    public static final int PWRITE_BYTE        = PWRITE | 1 << 8;
    public static final int PWRITE_SHORT       = PWRITE | 2 << 8;
    public static final int PWRITE_INT         = PWRITE | 3 << 8;
    public static final int PWRITE_FLOAT       = PWRITE | 4 << 8;
    public static final int PWRITE_LONG        = PWRITE | 5 << 8;
    public static final int PWRITE_DOUBLE      = PWRITE | 6 << 8;
    public static final int PWRITE_WORD        = PWRITE | 7 << 8;
    public static final int PWRITE_REFERENCE   = PWRITE | 8 << 8;
    // Pointer write with int-sized offset
    public static final int PWRITE_BYTE_I      = PWRITE | 9  << 8;
    public static final int PWRITE_SHORT_I     = PWRITE | 10  << 8;
    public static final int PWRITE_INT_I       = PWRITE | 11 << 8;
    public static final int PWRITE_FLOAT_I     = PWRITE | 12 << 8;
    public static final int PWRITE_LONG_I      = PWRITE | 13 << 8;
    public static final int PWRITE_DOUBLE_I    = PWRITE | 14 << 8;
    public static final int PWRITE_WORD_I      = PWRITE | 15 << 8;
    public static final int PWRITE_REFERENCE_I = PWRITE | 16 << 8;

    public static final int PGET_BYTE          = PGET   | 1 << 8;
    public static final int PGET_CHAR          = PGET   | 2 << 8;
    public static final int PGET_SHORT         = PGET   | 3 << 8;
    public static final int PGET_INT           = PGET   | 4 << 8;
    public static final int PGET_FLOAT         = PGET   | 5 << 8;
    public static final int PGET_LONG          = PGET   | 6 << 8;
    public static final int PGET_DOUBLE        = PGET   | 7 << 8;
    public static final int PGET_WORD          = PGET   | 8 << 8;
    public static final int PGET_REFERENCE     = PGET   | 9 << 8;

    public static final int PSET_BYTE          = PSET   | 1 << 8;
    public static final int PSET_SHORT         = PSET   | 2 << 8;
    public static final int PSET_INT           = PSET   | 3 << 8;
    public static final int PSET_FLOAT         = PSET   | 4 << 8;
    public static final int PSET_LONG          = PSET   | 5 << 8;
    public static final int PSET_DOUBLE        = PSET   | 6 << 8;
    public static final int PSET_WORD          = PSET   | 7 << 8;
    public static final int PSET_REFERENCE     = PSET   | 8 << 8;

    public static final int MEMBAR_LOAD_LOAD   = MEMBAR   | LOAD_LOAD << 8;
    public static final int MEMBAR_LOAD_STORE  = MEMBAR   | LOAD_STORE << 8;
    public static final int MEMBAR_STORE_LOAD  = MEMBAR   | STORE_LOAD << 8;
    public static final int MEMBAR_STORE_STORE = MEMBAR   | STORE_STORE << 8;
    public static final int MEMBAR_MEMOP_STORE = MEMBAR   | MEMOP_STORE << 8;
    public static final int MEMBAR_FENCE       = MEMBAR   | FENCE << 8;

    /**
     * Constants and {@link INTRINSIC} definitions for unsigned comparisons.
     */
    public static class UnsignedComparisons {
        public static final int ABOVE_THAN    = 1;
        public static final int ABOVE_EQUAL   = 2;
        public static final int BELOW_THAN    = 3;
        public static final int BELOW_EQUAL   = 4;

        @INTRINSIC(UCMP | (ABOVE_EQUAL << 8))
        public static native boolean aboveOrEqual(int x, int y);

        @INTRINSIC(UCMP | (BELOW_EQUAL << 8))
        public static native boolean belowOrEqual(int x, int y);

        @INTRINSIC(UCMP | (ABOVE_THAN << 8))
        public static native boolean aboveThan(int x, int y);

        @INTRINSIC(UCMP | (BELOW_THAN << 8))
        public static native boolean belowThan(int x, int y);
    }

    /**
     * Constants for memory barriers.
     *
     * The documentation for each constant is taken from the
     * <a href="http://gee.cs.oswego.edu/dl/jmm/cookbook.html">The JSR-133 Cookbook for Compiler Writers</a>
     * written by Doug Lea.
     */
    public static class MemoryBarriers {

        /**
         * The sequence {@code Load1; LoadLoad; Load2} ensures that {@code Load1}'s data are loaded before data accessed
         * by {@code Load2} and all subsequent load instructions are loaded. In general, explicit {@code LoadLoad}
         * barriers are needed on processors that perform speculative loads and/or out-of-order processing in which
         * waiting load instructions can bypass waiting stores. On processors that guarantee to always preserve load
         * ordering, these barriers amount to no-ops.
         */
        public static final int LOAD_LOAD   = 0x0001;

        /**
         * The sequence {@code Load1; LoadStore; Store2} ensures that {@code Load1}'s data are loaded before all data
         * associated with {@code Store2} and subsequent store instructions are flushed. {@code LoadStore} barriers are
         * needed only on those out-of-order processors in which waiting store instructions can bypass loads.
         */
        public static final int LOAD_STORE  = 0x0002;

        /**
         * The sequence {@code Store1; StoreLoad; Load2} ensures that {@code Store1}'s data are made visible to other
         * processors (i.e., flushed to main memory) before data accessed by {@code Load2} and all subsequent load
         * instructions are loaded. {@code StoreLoad} barriers protect against a subsequent load incorrectly using
         * {@code Store1}'s data value rather than that from a more recent store to the same location performed by a
         * different processor. Because of this, on the processors discussed below, a {@code StoreLoad} is strictly
         * necessary only for separating stores from subsequent loads of the same location(s) as were stored before the
         * barrier. {@code StoreLoad} barriers are needed on nearly all recent multiprocessors, and are usually the most
         * expensive kind. Part of the reason they are expensive is that they must disable mechanisms that ordinarily
         * bypass cache to satisfy loads from write-buffers. This might be implemented by letting the buffer fully
         * flush, among other possible stalls.
         */
        public static final int STORE_LOAD  = 0x0004;

        /**
         * The sequence {@code Store1; StoreStore; Store2} ensures that {@code Store1}'s data are visible to other
         * processors (i.e., flushed to memory) before the data associated with {@code Store2} and all subsequent store
         * instructions. In general, {@code StoreStore} barriers are needed on processors that do not otherwise
         * guarantee strict ordering of flushes from write buffers and/or caches to other processors or main memory.
         */
        public static final int STORE_STORE = 0x0008;

        public static final int MEMOP_STORE = STORE_STORE | LOAD_STORE;
        public static final int FENCE = LOAD_LOAD | LOAD_STORE | STORE_STORE | STORE_LOAD;

        /**
         * Ensures all preceding loads complete before any subsequent loads.
         */
        @INTRINSIC(MEMBAR_LOAD_LOAD)
        public static void loadLoad() {
        }

        /**
         * Ensures all preceding loads complete before any subsequent stores.
         */
        @INTRINSIC(MEMBAR_LOAD_STORE)
        public static void loadStore() {
        }

        /**
         * Ensures all preceding stores complete before any subsequent loads.
         */
        @INTRINSIC(MEMBAR_STORE_LOAD)
        public static void storeLoad() {
        }

        /**
         * Ensures all preceding stores complete before any subsequent stores.
         */
        @INTRINSIC(MEMBAR_STORE_STORE)
        public static void storeStore() {
        }

        /**
         * Ensures all preceding stores and loads complete before any subsequent stores.
         */
        @INTRINSIC(MEMBAR_MEMOP_STORE)
        public static void memopStore() {
        }

        /**
         * Ensures all preceding stores and loads complete before any subsequent stores and loads.
         */
        @INTRINSIC(MEMBAR_FENCE)
        public static void fence() {
        }

    }

    public static final int ILLEGAL = 255;
    public static final int END = 256;

    /**
     * The last opcode defined by the JVM specification. To iterate over all JVM bytecodes:
     * <pre>
     *     for (int opcode = 0; opcode <= Bytecodes.LAST_JVM_OPCODE; ++opcode) {
     *         //
     *     }
     * </pre>
     */
    public static final int LAST_JVM_OPCODE = JSR_W;

    /**
     * A collection of flags describing various bytecode attributes.
     */
    static class Flags {

        /**
         * Denotes an instruction that ends a basic block and does not let control flow fall through to its lexical successor.
         */
        static final int STOP = 0x00000001;

        /**
         * Denotes an instruction that ends a basic block and may let control flow fall through to its lexical successor.
         * In practice this means it is a conditional branch.
         */
        static final int FALL_THROUGH = 0x00000002;

        /**
         * Denotes an instruction that has a 2 or 4 byte operand that is an offset to another instruction in the same method.
         * This does not include the {@link Bytecodes#TABLESWITCH} or {@link Bytecodes#LOOKUPSWITCH} instructions.
         */
        static final int BRANCH = 0x00000004;

        /**
         * Denotes an instruction that reads the value of a static or instance field.
         */
        static final int FIELD_READ = 0x00000008;

        /**
         * Denotes an instruction that writes the value of a static or instance field.
         */
        static final int FIELD_WRITE = 0x00000010;

        /**
         * Denotes an instruction that is not defined in the JVM specification.
         */
        static final int EXTENSION = 0x00000020;

        /**
         * Denotes an instruction that can cause an implicit exception.
         */
        static final int TRAP        = 0x00000080;
        /**
         * Denotes an instruction that is commutative.
         */
        static final int COMMUTATIVE = 0x00000100;
        /**
         * Denotes an instruction that is associative.
         */
        static final int ASSOCIATIVE = 0x00000200;
        /**
         * Denotes an instruction that loads an operand.
         */
        static final int LOAD        = 0x00000400;
        /**
         * Denotes an instruction that stores an operand.
         */
        static final int STORE       = 0x00000800;

    }

    // Performs a sanity check that none of the flags overlap.
    static {
        int allFlags = 0;
        try {
            for (Field field : Flags.class.getDeclaredFields()) {
                int flagsFilter = Modifier.FINAL | Modifier.STATIC;
                if ((field.getModifiers() & flagsFilter) == flagsFilter) {
                    assert field.getType() == int.class : "Only " + field;
                    final int flag = field.getInt(null);
                    assert flag != 0;
                    assert (flag & allFlags) == 0 : field.getName() + " has a value conflicting with another flag";
                    allFlags |= flag;
                }
            }
        } catch (Exception e) {
            throw new InternalError(e.toString());
        }
    }

    /**
     * A array that maps from a bytecode value to a {@link String} for the corresponding instruction mnemonic.
     * This will include the root instruction for the two-byte extended instructions.
     */
    private static final String[] names = new String[256];
    /**
     * Maps from a two-byte extended bytecode value to a {@link String} for the corresponding instruction mnemonic.
     */
    private static HashMap<Integer, String> twoByteExtNames = new HashMap<Integer, String>();
    /**
     * A array that maps from a bytecode value to the set of {@link Flags} for the corresponding instruction.
     */
    private static final int[] flags = new int[256];
    /**
     * A array that maps from a bytecode value to the length in bytes for the corresponding instruction.
     */
    private static final int[] length = new int[256];

    // Checkstyle: stop
    static {
        def("nop"             , "b"    );
        def("aconst_null"     , "b"    );
        def("iconst_m1"       , "b"    );
        def("iconst_0"        , "b"    );
        def("iconst_1"        , "b"    );
        def("iconst_2"        , "b"    );
        def("iconst_3"        , "b"    );
        def("iconst_4"        , "b"    );
        def("iconst_5"        , "b"    );
        def("lconst_0"        , "b"    );
        def("lconst_1"        , "b"    );
        def("fconst_0"        , "b"    );
        def("fconst_1"        , "b"    );
        def("fconst_2"        , "b"    );
        def("dconst_0"        , "b"    );
        def("dconst_1"        , "b"    );
        def("bipush"          , "bc"   );
        def("sipush"          , "bcc"  );
        def("ldc"             , "bi"   , TRAP);
        def("ldc_w"           , "bii"  , TRAP);
        def("ldc2_w"          , "bii"  , TRAP);
        def("iload"           , "bi"   , LOAD);
        def("lload"           , "bi"   , LOAD);
        def("fload"           , "bi"   , LOAD);
        def("dload"           , "bi"   , LOAD);
        def("aload"           , "bi"   , LOAD);
        def("iload_0"         , "b"    , LOAD);
        def("iload_1"         , "b"    , LOAD);
        def("iload_2"         , "b"    , LOAD);
        def("iload_3"         , "b"    , LOAD);
        def("lload_0"         , "b"    , LOAD);
        def("lload_1"         , "b"    , LOAD);
        def("lload_2"         , "b"    , LOAD);
        def("lload_3"         , "b"    , LOAD);
        def("fload_0"         , "b"    , LOAD);
        def("fload_1"         , "b"    , LOAD);
        def("fload_2"         , "b"    , LOAD);
        def("fload_3"         , "b"    , LOAD);
        def("dload_0"         , "b"    , LOAD);
        def("dload_1"         , "b"    , LOAD);
        def("dload_2"         , "b"    , LOAD);
        def("dload_3"         , "b"    , LOAD);
        def("aload_0"         , "b"    , LOAD);
        def("aload_1"         , "b"    , LOAD);
        def("aload_2"         , "b"    , LOAD);
        def("aload_3"         , "b"    , LOAD);
        def("iaload"          , "b"    , TRAP);
        def("laload"          , "b"    , TRAP);
        def("faload"          , "b"    , TRAP);
        def("daload"          , "b"    , TRAP);
        def("aaload"          , "b"    , TRAP);
        def("baload"          , "b"    , TRAP);
        def("caload"          , "b"    , TRAP);
        def("saload"          , "b"    , TRAP);
        def("istore"          , "bi"   , STORE);
        def("lstore"          , "bi"   , STORE);
        def("fstore"          , "bi"   , STORE);
        def("dstore"          , "bi"   , STORE);
        def("astore"          , "bi"   , STORE);
        def("istore_0"        , "b"    , STORE);
        def("istore_1"        , "b"    , STORE);
        def("istore_2"        , "b"    , STORE);
        def("istore_3"        , "b"    , STORE);
        def("lstore_0"        , "b"    , STORE);
        def("lstore_1"        , "b"    , STORE);
        def("lstore_2"        , "b"    , STORE);
        def("lstore_3"        , "b"    , STORE);
        def("fstore_0"        , "b"    , STORE);
        def("fstore_1"        , "b"    , STORE);
        def("fstore_2"        , "b"    , STORE);
        def("fstore_3"        , "b"    , STORE);
        def("dstore_0"        , "b"    , STORE);
        def("dstore_1"        , "b"    , STORE);
        def("dstore_2"        , "b"    , STORE);
        def("dstore_3"        , "b"    , STORE);
        def("astore_0"        , "b"    , STORE);
        def("astore_1"        , "b"    , STORE);
        def("astore_2"        , "b"    , STORE);
        def("astore_3"        , "b"    , STORE);
        def("iastore"         , "b"    , TRAP);
        def("lastore"         , "b"    , TRAP);
        def("fastore"         , "b"    , TRAP);
        def("dastore"         , "b"    , TRAP);
        def("aastore"         , "b"    , TRAP);
        def("bastore"         , "b"    , TRAP);
        def("castore"         , "b"    , TRAP);
        def("sastore"         , "b"    , TRAP);
        def("pop"             , "b"    );
        def("pop2"            , "b"    );
        def("dup"             , "b"    );
        def("dup_x1"          , "b"    );
        def("dup_x2"          , "b"    );
        def("dup2"            , "b"    );
        def("dup2_x1"         , "b"    );
        def("dup2_x2"         , "b"    );
        def("swap"            , "b"    );
        def("iadd"            , "b"    , COMMUTATIVE | ASSOCIATIVE);
        def("ladd"            , "b"    , COMMUTATIVE | ASSOCIATIVE);
        def("fadd"            , "b"    , COMMUTATIVE | ASSOCIATIVE);
        def("dadd"            , "b"    , COMMUTATIVE | ASSOCIATIVE);
        def("isub"            , "b"    );
        def("lsub"            , "b"    );
        def("fsub"            , "b"    );
        def("dsub"            , "b"    );
        def("imul"            , "b"    , COMMUTATIVE | ASSOCIATIVE);
        def("lmul"            , "b"    , COMMUTATIVE | ASSOCIATIVE);
        def("fmul"            , "b"    , COMMUTATIVE | ASSOCIATIVE);
        def("dmul"            , "b"    , COMMUTATIVE | ASSOCIATIVE);
        def("idiv"            , "b"    , TRAP);
        def("ldiv"            , "b"    , TRAP);
        def("fdiv"            , "b"    );
        def("ddiv"            , "b"    );
        def("irem"            , "b"    , TRAP);
        def("lrem"            , "b"    , TRAP);
        def("frem"            , "b"    );
        def("drem"            , "b"    );
        def("ineg"            , "b"    );
        def("lneg"            , "b"    );
        def("fneg"            , "b"    );
        def("dneg"            , "b"    );
        def("ishl"            , "b"    );
        def("lshl"            , "b"    );
        def("ishr"            , "b"    );
        def("lshr"            , "b"    );
        def("iushr"           , "b"    );
        def("lushr"           , "b"    );
        def("iand"            , "b"    , COMMUTATIVE | ASSOCIATIVE);
        def("land"            , "b"    , COMMUTATIVE | ASSOCIATIVE);
        def("ior"             , "b"    , COMMUTATIVE | ASSOCIATIVE);
        def("lor"             , "b"    , COMMUTATIVE | ASSOCIATIVE);
        def("ixor"            , "b"    , COMMUTATIVE | ASSOCIATIVE);
        def("lxor"            , "b"    , COMMUTATIVE | ASSOCIATIVE);
        def("iinc"            , "bic"  , LOAD | STORE);
        def("i2l"             , "b"    );
        def("i2f"             , "b"    );
        def("i2d"             , "b"    );
        def("l2i"             , "b"    );
        def("l2f"             , "b"    );
        def("l2d"             , "b"    );
        def("f2i"             , "b"    );
        def("f2l"             , "b"    );
        def("f2d"             , "b"    );
        def("d2i"             , "b"    );
        def("d2l"             , "b"    );
        def("d2f"             , "b"    );
        def("i2b"             , "b"    );
        def("i2c"             , "b"    );
        def("i2s"             , "b"    );
        def("lcmp"            , "b"    );
        def("fcmpl"           , "b"    );
        def("fcmpg"           , "b"    );
        def("dcmpl"           , "b"    );
        def("dcmpg"           , "b"    );
        def("ifeq"            , "boo"  , FALL_THROUGH | BRANCH);
        def("ifne"            , "boo"  , FALL_THROUGH | BRANCH);
        def("iflt"            , "boo"  , FALL_THROUGH | BRANCH);
        def("ifge"            , "boo"  , FALL_THROUGH | BRANCH);
        def("ifgt"            , "boo"  , FALL_THROUGH | BRANCH);
        def("ifle"            , "boo"  , FALL_THROUGH | BRANCH);
        def("if_icmpeq"       , "boo"  , COMMUTATIVE | FALL_THROUGH | BRANCH);
        def("if_icmpne"       , "boo"  , COMMUTATIVE | FALL_THROUGH | BRANCH);
        def("if_icmplt"       , "boo"  , FALL_THROUGH | BRANCH);
        def("if_icmpge"       , "boo"  , FALL_THROUGH | BRANCH);
        def("if_icmpgt"       , "boo"  , FALL_THROUGH | BRANCH);
        def("if_icmple"       , "boo"  , FALL_THROUGH | BRANCH);
        def("if_acmpeq"       , "boo"  , COMMUTATIVE | FALL_THROUGH | BRANCH);
        def("if_acmpne"       , "boo"  , COMMUTATIVE | FALL_THROUGH | BRANCH);
        def("goto"            , "boo"  , STOP | BRANCH);
        def("jsr"             , "boo"  , STOP | BRANCH);
        def("ret"             , "bi"   , STOP);
        def("tableswitch"     , ""     , STOP);
        def("lookupswitch"    , ""     , STOP);
        def("ireturn"         , "b"    , TRAP | STOP);
        def("lreturn"         , "b"    , TRAP | STOP);
        def("freturn"         , "b"    , TRAP | STOP);
        def("dreturn"         , "b"    , TRAP | STOP);
        def("areturn"         , "b"    , TRAP | STOP);
        def("return"          , "b"    , TRAP | STOP);
        def("getstatic"       , "bjj"  , TRAP | FIELD_READ);
        def("putstatic"       , "bjj"  , TRAP | FIELD_WRITE);
        def("getfield"        , "bjj"  , TRAP | FIELD_READ);
        def("putfield"        , "bjj"  , TRAP | FIELD_WRITE);
        def("invokevirtual"   , "bjj"  , TRAP);
        def("invokespecial"   , "bjj"  , TRAP);
        def("invokestatic"    , "bjj"  , TRAP);
        def("invokeinterface" , "bjja_", TRAP);
        def("xxxunusedxxx"    , ""     );
        def("new"             , "bii"  , TRAP);
        def("newarray"        , "bc"   , TRAP);
        def("anewarray"       , "bii"  , TRAP);
        def("arraylength"     , "b"    , TRAP);
        def("athrow"          , "b"    , TRAP | STOP);
        def("checkcast"       , "bii"  , TRAP);
        def("instanceof"      , "bii"  , TRAP);
        def("monitorenter"    , "b"    , TRAP);
        def("monitorexit"     , "b"    , TRAP);
        def("wide"            , ""     );
        def("multianewarray"  , "biic" , TRAP);
        def("ifnull"          , "boo"  , FALL_THROUGH | BRANCH);
        def("ifnonnull"       , "boo"  , FALL_THROUGH | BRANCH);
        def("goto_w"          , "boooo", STOP | BRANCH);
        def("jsr_w"           , "boooo", STOP | BRANCH);
        def("breakpoint"      , "b"    , TRAP);

        def("wload"           , "bi"   , EXTENSION);
        def("wload_0"         , "b"    , EXTENSION);
        def("wload_1"         , "b"    , EXTENSION);
        def("wload_2"         , "b"    , EXTENSION);
        def("wload_3"         , "b"    , EXTENSION);
        def("wstore"          , "bi"   , EXTENSION);
        def("wstore_0"        , "b"    , EXTENSION);
        def("wstore_1"        , "b"    , EXTENSION);
        def("wstore_2"        , "b"    , EXTENSION);
        def("wstore_3"        , "b"    , EXTENSION);
        def("wconst_0"        , "bii"  , EXTENSION);
        def("wdiv"            , "bii"  , EXTENSION | TRAP);
        def("wdivi"           , "bii"  , EXTENSION | TRAP);
        def("wrem"            , "bii"  , EXTENSION | TRAP);
        def("wremi"           , "bii"  , EXTENSION | TRAP);
        def("icmp"            , "bii"  , EXTENSION);
        def("wcmp"            , "bii"  , EXTENSION);
        def("pread"           , "bii"  , EXTENSION | TRAP);
        def("pwrite"          , "bii"  , EXTENSION | TRAP);
        def("pget"            , "bii"  , EXTENSION | TRAP);
        def("pset"            , "bii"  , EXTENSION | TRAP);
        def("pcmpswp"         , "bii"  , EXTENSION | TRAP);
        def("mov_i2f"         , "bii"  , EXTENSION | TRAP);
        def("mov_f2i"         , "bii"  , EXTENSION | TRAP);
        def("mov_l2d"         , "bii"  , EXTENSION | TRAP);
        def("mov_d2l"         , "bii"  , EXTENSION | TRAP);
        def("ucmp"            , "bii"  , EXTENSION);
        def("uwcmp"            , "bii"  , EXTENSION);
        def("jnicall"         , "bii"  , EXTENSION | TRAP);
        def("call"            , "bii"  , EXTENSION | TRAP);
        def("readreg"         , "bii"  , EXTENSION);
        def("writereg"        , "bii"  , EXTENSION);
        def("unsafe_cast"     , "bii"  , EXTENSION);
        def("wreturn"         , "b"    , EXTENSION | TRAP | STOP);
        def("safepoint"       , "bii"  , EXTENSION | TRAP);
        def("alloca"          , "bii"  , EXTENSION);
        def("membar"          , "bii"  , EXTENSION);
        def("stackaddr"       , "bii"  , EXTENSION);
        def("pause"           , "bii"  , EXTENSION);
        def("add_sp"          , "bii"  , EXTENSION);
        def("read_pc"         , "bii"  , EXTENSION);
        def("flushw"          , "bii"  , EXTENSION);
        def("lsb"             , "bii"  , EXTENSION);
        def("msb"             , "bii"  , EXTENSION);
    }
    // Checkstyle: resume

    /**
     * Determines if an opcode is commutative.
     * @param opcode the opcode to check
     * @return {@code true} iff commutative
     */
    public static boolean isCommutative(int opcode) {
        return (flags[opcode & 0xff] & COMMUTATIVE) != 0;
    }

    /**
     * Gets the length of an instruction denoted by a given opcode.
     *
     * @param opcode an instruction opcode
     * @return the length of the instruction denoted by {@code opcode}. If {@code opcode} is an illegal instruction or denotes a
     *         variable length instruction (e.g. {@link #TABLESWITCH}), then 0 is returned.
     */
    public static int lengthOf(int opcode) {
        return length[opcode & 0xff];
    }

    /**
     * Gets the length of an instruction at a given position in a given bytecode array.
     * This methods handles variable length and {@linkplain #WIDE widened} instructions.
     *
     * @param code an array of bytecode
     * @param bci the position in {@code code} of an instruction's opcode
     * @return the length of the instruction at position {@code bci} in {@code code}
     */
    public static int lengthOf(byte[] code, int bci) {
        int opcode = Bytes.beU1(code, bci);
        int length = Bytecodes.length[opcode & 0xff];
        if (length == 0) {
            switch (opcode) {
                case TABLESWITCH: {
                    return new BytecodeTableSwitch(code, bci).size();
                }
                case LOOKUPSWITCH: {
                    return new BytecodeLookupSwitch(code, bci).size();
                }
                case WIDE: {
                    int opc = Bytes.beU1(code, bci + 1);
                    if (opc == RET) {
                        return 4;
                    } else if (opc == IINC) {
                        return 6;
                    } else {
                        return 4; // a load or store bytecode
                    }
                }
                default:
                    throw new Error("unknown variable-length bytecode: " + opcode);
            }
        }
        return length;
    }

    /**
     * Gets the lower-case mnemonic for a given opcode.
     *
     * @param opcode an opcode
     * @return the mnemonic for {@code opcode} or {@code "<illegal opcode: " + opcode + ">"} if {@code opcode} is not a legal opcode
     */
    public static String nameOf(int opcode) throws IllegalArgumentException {
        String extName = twoByteExtNames.get(Integer.valueOf(opcode));
        if (extName != null) {
            return extName;
        }
        String name = names[opcode & 0xff];
        if (name == null) {
            return "<illegal opcode: " + opcode + ">";
        }
        return name;
    }

    /**
     * Gets the opcode corresponding to a given mnemonic.
     *
     * @param name an opcode mnemonic
     * @return the opcode corresponding to {@code mnemonic}
     * @throws IllegalArgumentException if {@code name} does not denote a valid opcode
     */
    public static int valueOf(String name) {
        for (int opcode = 0; opcode < names.length; ++opcode) {
            if (name.equalsIgnoreCase(names[opcode])) {
                return opcode;
            }
        }
        for (Map.Entry<Integer, String> entry : twoByteExtNames.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(name)) {
                return entry.getKey();
            }
        }
        throw new IllegalArgumentException("No opcode for " + name);
    }

    /**
     * Determines if a given opcode denotes an instruction that can cause an implicit exception.
     *
     * @param opcode an opcode to test
     * @return {@code true} iff {@code opcode} can cause an implicit exception, {@code false} otherwise
     */
    public static boolean canTrap(int opcode) {
        return (flags[opcode & 0xff] & TRAP) != 0;
    }

    /**
     * Determines if a given opcode denotes an instruction that loads a local variable to the operand stack.
     *
     * @param opcode an opcode to test
     * @return {@code true} iff {@code opcode} loads a local variable to the operand stack, {@code false} otherwise
     */
    public static boolean isLoad(int opcode) {
        return (flags[opcode & 0xff] & LOAD) != 0;
    }

    /**
     * Determines if a given opcode denotes an instruction that ends a basic block and does not let control flow fall
     * through to its lexical successor.
     *
     * @param opcode an opcode to test
     * @return {@code true} iff {@code opcode} properly ends a basic block
     */
    public static boolean isStop(int opcode) {
        return (flags[opcode & 0xff] & STOP) != 0;
    }

    /**
     * Determines if a given opcode denotes an instruction that stores a value to a local variable
     * after popping it from the operand stack.
     *
     * @param opcode an opcode to test
     * @return {@code true} iff {@code opcode} stores a value to a local variable, {@code false} otherwise
     */
    public static boolean isStore(int opcode) {
        return (flags[opcode & 0xff] & STORE) != 0;
    }

    /**
     * Determines if a given opcode is an instruction that delimits a basic block.
     *
     * @param opcode an opcode to test
     * @return {@code true} iff {@code opcode} delimits a basic block
     */
    public static boolean isBlockEnd(int opcode) {
        return (flags[opcode & 0xff] & (STOP | FALL_THROUGH)) != 0;
    }

    /**
     * Determines if a given opcode is an instruction that has a 2 or 4 byte operand that is an offset to another
     * instruction in the same method. This does not include the {@linkplain #TABLESWITCH switch} instructions.
     *
     * @param opcode an opcode to test
     * @return {@code true} iff {@code opcode} is a branch instruction with a single operand
     */
    public static boolean isBranch(int opcode) {
        return (flags[opcode & 0xff] & BRANCH) != 0;
    }

    /**
     * Determines if a given opcode denotes a conditional branch.
     * @param opcode
     * @return {@code true} iff {@code opcode} is a conditional branch
     */
    public static boolean isConditionalBranch(int opcode) {
        return (flags[opcode & 0xff] & FALL_THROUGH) != 0;
    }

    /**
     * Determines if a given opcode denotes a standard bytecode. A standard bytecode is
     * defined in the JVM specification.
     *
     * @param opcode an opcode to test
     * @return {@code true} iff {@code opcode} is a standard bytecode
     */
    public static boolean isStandard(int opcode) {
        return (flags[opcode & 0xff] & EXTENSION) == 0;
    }

    /**
     * Determines if a given opcode denotes an extended bytecode.
     *
     * @param opcode an opcode to test
     * @return {@code true} if {@code opcode} is an extended bytecode
     */
    public static boolean isExtended(int opcode) {
    	return (flags[opcode & 0xff] & EXTENSION) != 0;
    }

    /**
     * Determines if a given opcode is a two-byte extended bytecode.
     *
     * @param opcode an opcode to test
     * @return {@code true} if {@code (opcode & ~0xff) != 0}
     */
    public static boolean isTwoByteExtended(int opcode) {
        return (opcode & ~0xff) != 0;
    }

    /**
     * Gets the arithmetic operator name for a given opcode. If {@code opcode} does not denote an
     * arithmetic instruction, then the {@linkplain #nameOf(int) name} of the opcode is returned
     * instead.
     *
     * @param op an opcode
     * @return the arithmetic operator name
     */
    public static String operator(int op) {
        switch (op) {
            // arithmetic ops
            case IADD : // fall through
            case LADD : // fall through
            case FADD : // fall through
            case DADD : return "+";
            case ISUB : // fall through
            case LSUB : // fall through
            case FSUB : // fall through
            case DSUB : return "-";
            case IMUL : // fall through
            case LMUL : // fall through
            case FMUL : // fall through
            case DMUL : return "*";
            case IDIV : // fall through
            case LDIV : // fall through
            case FDIV : // fall through
            case DDIV : return "/";
            case IREM : // fall through
            case LREM : // fall through
            case FREM : // fall through
            case DREM : return "%";
            // shift ops
            case ISHL : // fall through
            case LSHL : return "<<";
            case ISHR : // fall through
            case LSHR : return ">>";
            case IUSHR: // fall through
            case LUSHR: return ">>>";
            // logic ops
            case IAND : // fall through
            case LAND : return "&";
            case IOR  : // fall through
            case LOR  : return "|";
            case IXOR : // fall through
            case LXOR : return "^";
        }
        return nameOf(op);
    }

    /**
     * Attempts to fold a binary operation on two constant integer inputs.
     *
     * @param opcode the bytecode operation to perform
     * @param x the first input
     * @param y the second input
     * @return a {@code Integer} instance representing the result of folding the operation,
     * if it is foldable, {@code null} otherwise
     */
    public static Integer foldIntOp2(int opcode, int x, int y) {
        // attempt to fold a binary operation with constant inputs
        switch (opcode) {
            case IADD: return x + y;
            case ISUB: return x - y;
            case IMUL: return x * y;
            case IDIV: {
                if (y == 0) {
                    return null;
                }
                return x / y;
            }
            case IREM: {
                if (y == 0) {
                    return null;
                }
                return x % y;
            }
            case IAND: return x & y;
            case IOR:  return x | y;
            case IXOR: return x ^ y;
            case ISHL: return x << y;
            case ISHR: return x >> y;
            case IUSHR: return x >>> y;
        }
        return null;
    }

    /**
     * Attempts to fold a binary operation on two constant long inputs.
     *
     * @param opcode the bytecode operation to perform
     * @param x the first input
     * @param y the second input
     * @return a {@code Long} instance representing the result of folding the operation,
     * if it is foldable, {@code null} otherwise
     */
    public static Long foldLongOp2(int opcode, long x, long y) {
        // attempt to fold a binary operation with constant inputs
        switch (opcode) {
            case LADD: return x + y;
            case LSUB: return x - y;
            case LMUL: return x * y;
            case LDIV: {
                if (y == 0) {
                    return null;
                }
                return x / y;
            }
            case LREM: {
                if (y == 0) {
                    return null;
                }
                return x % y;
            }
            case LAND: return x & y;
            case LOR:  return x | y;
            case LXOR: return x ^ y;
            case LSHL: return x << y;
            case LSHR: return x >> y;
            case LUSHR: return x >>> y;
        }
        return null;
    }

    @INTRINSIC(WDIV)
    public static native long unsignedDivide(long x, long y);

    @INTRINSIC(WDIVI)
    public static native long unsignedDivideByInt(long x, int y);

    @INTRINSIC(WREM)
    public static native long unsignedRemainder(long x, long y);

    @INTRINSIC(WREMI)
    public static native long unsignedRemainderByInt(long x, int y);

    /**
     * Attempts to fold a binary operation on two constant word inputs.
     *
     * @param opcode the bytecode operation to perform
     * @param x the first input
     * @param y the second input
     * @return a {@code Long} instance representing the result of folding the operation,
     * if it is foldable, {@code null} otherwise
     */
    public static Long foldWordOp2(int opcode, long x, long y) {
        if (y == 0) {
            return null;
        }
        // attempt to fold a binary operation with constant inputs
        switch (opcode) {
            case WDIV:  return unsignedDivide(x, y);
            case WDIVI: return unsignedDivideByInt(x, (int) y);
            case WREM:  return unsignedRemainder(x, y);
            case WREMI: return unsignedRemainderByInt(x, (int) y);
        }
        return null;
    }

    /**
     * Attempts to fold a binary operation on two constant {@code float} inputs.
     *
     * @param opcode the bytecode operation to perform
     * @param x the first input
     * @param y the second input
     * @return a {@code Float} instance representing the result of folding the operation,
     * if it is foldable, {@code null} otherwise
     */
    public static strictfp Float foldFloatOp2(int opcode, float x, float y) {
        switch (opcode) {
            case FADD: return x + y;
            case FSUB: return x - y;
            case FMUL: return x * y;
            case FDIV: return x / y;
            case FREM: return x % y;
        }
        return null;
    }

    /**
     * Attempts to fold a binary operation on two constant {@code double} inputs.
     *
     * @param opcode the bytecode operation to perform
     * @param x the first input
     * @param y the second input
     * @return a {@code Double} instance representing the result of folding the operation,
     * if it is foldable, {@code null} otherwise
     */
    public static strictfp Double foldDoubleOp2(int opcode, double x, double y) {
        switch (opcode) {
            case DADD: return x + y;
            case DSUB: return x - y;
            case DMUL: return x * y;
            case DDIV: return x / y;
            case DREM: return x % y;
        }
        return null;
    }

    /**
     * Attempts to fold a comparison operation on two constant {@code long} inputs.
     *
     * @param x the first input
     * @param y the second input
     * @return an {@code int}  representing the result of the compare
     */
    public static int foldLongCompare(long x, long y) {
        if (x < y) {
            return -1;
        }
        if (x == y) {
            return 0;
        }
        return 1;
    }

    /**
     * Attempts to fold a comparison operation on two constant {@code float} inputs.
     *
     * @param opcode the bytecode operation to perform
     * @param x the first input
     * @param y the second input
     * @return an {@code Integer}  instance representing the result of the compare,
     * if it is foldable, {@code null} otherwise
     */
    public static Integer foldFloatCompare(int opcode, float x, float y) {
        // unfortunately we cannot write Java source to generate FCMPL or FCMPG
        int result = 0;
        if (x < y) {
            result = -1;
        } else if (x > y) {
            result = 1;
        }
        if (opcode == FCMPL) {
            if (Float.isNaN(x) || Float.isNaN(y)) {
                return -1;
            }
            return result;
        } else if (opcode == FCMPG) {
            if (Float.isNaN(x) || Float.isNaN(y)) {
                return 1;
            }
            return result;
        }
        return null; // unknown compare opcode
    }

    /**
     * Attempts to fold a comparison operation on two constant {@code double} inputs.
     *
     * @param opcode the bytecode operation to perform
     * @param x the first input
     * @param y the second input
     * @return an {@code Integer}  instance representing the result of the compare,
     * if it is foldable, {@code null} otherwise
     */
    public static Integer foldDoubleCompare(int opcode, double x, double y) {
        // unfortunately we cannot write Java source to generate DCMPL or DCMPG
        int result = 0;
        if (x < y) {
            result = -1;
        } else if (x > y) {
            result = 1;
        }
        if (opcode == DCMPL) {
            if (Double.isNaN(x) || Double.isNaN(y)) {
                return -1;
            }
            return result;
        } else if (opcode == DCMPG) {
            if (Double.isNaN(x) || Double.isNaN(y)) {
                return 1;
            }
            return result;
        }
        return null; // unknown compare opcode
    }

    private static void def(String name, String format) {
        def(name, format, 0);
    }

    /**
     * Defines a bytecode by entering it into the arrays that record its state.
     * @param name instruction name (lower case)
     * @param format encodes the length of the instruction
     * @param byteCodeFlags the set of {@link Flags} associated with the instruction
     */
    private static void def(String name, String format, int byteCodeFlags) {
        try {
            Field field = Bytecodes.class.getDeclaredField(name.toUpperCase());
            int opcode = field.getInt(null);
            assert names[opcode] == null : "opcode " + opcode + " is already bound to name " + names[opcode];
            names[opcode] = name;
            int byteCodeLength = format.length();
            length[opcode] = byteCodeLength;
            flags[opcode] = byteCodeFlags;

            assert !isConditionalBranch(opcode) || isBranch(opcode) : "a conditional branch must also be a branch";

            /*
             * All the two-byte extended instructions are entered with one call of this method, using the common
             * first-byte value that is shared among members of the instruction variant, e.g., PREAD for PREAD_BYTE, PREAD_INT,...
             * N.B. There is currently no simple way to tell if an extended instruction has two-byte variants, so
             * the code below using prefix name matching.
             */
            if (isExtended(opcode)) {
                for (Field otherField : Bytecodes.class.getDeclaredFields()) {
                    if (otherField.getName().startsWith(field.getName()) && !otherField.equals(field)) {
                    	// we have a prefix match, necessary but not sufficient
                        int opcodeWithOperand = otherField.getInt(null);
                        if (isTwoByteExtended(opcodeWithOperand)) {
                            String extName = otherField.getName();
                            assert byteCodeLength == 3;
                            assert (opcodeWithOperand & 0xff) == opcode : "Extended opcode " + extName + " must share same low 8 bits as " + field.getName();
                            String oldValue = twoByteExtNames.put(opcodeWithOperand, extName.toLowerCase());
                            assert oldValue == null;
                        }
                    }
                }
            }

        } catch (Exception e) {
            throw (InternalError) new InternalError("Error defining " + name).initCause(e);
        }
    }

    /**
     * Utility for ensuring that the extended opcodes are contiguous and follow on directly
     * from the standard JVM opcodes. If these conditions do not hold for the input source
     * file, then it is modified 'in situ' to fix the problem.
     *
     * @param args {@code args[0]} is the path to this source file
     */
    public static void main(String[] args) throws Exception {
        Pattern opcodeDecl = Pattern.compile("(\\s*public static final int )(\\w+)(\\s*=\\s*)(\\d+)(;.*)");

        File file = new File(args[0]);
        BufferedReader br = new BufferedReader(new FileReader(file));
        CharArrayWriter buffer = new CharArrayWriter((int) file.length());
        PrintWriter out = new PrintWriter(buffer);
        String line;
        int lastExtendedOpcode = BREAKPOINT;
        boolean modified = false;
        int section = 0;
        while ((line = br.readLine()) != null) {
            if (section == 0) {
                if (line.equals("    // Start extended bytecodes")) {
                    section = 1;
                }
            } else if (section == 1) {
                if (line.equals("    // End extended bytecodes")) {
                    section = 2;
                } else {
                    Matcher matcher = opcodeDecl.matcher(line);
                    if (matcher.matches()) {
                        String name = matcher.group(2);
                        String value = matcher.group(4);
                        int opcode = Integer.parseInt(value);
                        if (names[opcode] == null || !names[opcode].equalsIgnoreCase(name)) {
                            throw new RuntimeException("Missing definition of name and flags for " + opcode + ":" + name + " -- " + names[opcode]);
                        }
                        if (opcode != lastExtendedOpcode + 1) {
                            System.err.println("Fixed declaration of opcode " + name + " to be " + (lastExtendedOpcode + 1) + " (was " + value + ")");
                            opcode = lastExtendedOpcode + 1;
                            line = line.substring(0, matcher.start(4)) + opcode + line.substring(matcher.end(4));
                            modified = true;
                        }

                        if (opcode >= 256) {
                            throw new RuntimeException("Exceeded maximum opcode value with " + name);
                        }

                        lastExtendedOpcode = opcode;
                    }
                }
            }

            out.println(line);
        }
        if (section == 0) {
            throw new RuntimeException("Did not find line starting extended bytecode declarations:\n\n    // Start extended bytecodes");
        } else if (section == 1) {
            throw new RuntimeException("Did not find line ending extended bytecode declarations:\n\n    // End extended bytecodes");
        }

        if (modified) {
            out.flush();
            FileWriter fileWriter = new FileWriter(file);
            fileWriter.write(buffer.toCharArray());
            fileWriter.close();

            System.out.println("Modified: " + file);
        }


        // Uncomment to print out visitor method declarations:
//        for (int opcode = 0; opcode < flags.length; ++opcode) {
//            if (isExtension(opcode)) {
//                String visitorParams = length(opcode) == 1 ? "" : "int index";
//                System.out.println("@Override");
//                System.out.println("protected void " + name(opcode) + "(" + visitorParams + ") {");
//                System.out.println("}");
//                System.out.println();
//            }
//        }

        // Uncomment to print out visitor method declarations:
//        for (int opcode = 0; opcode < flags.length; ++opcode) {
//            if (isExtension(opcode)) {
//                System.out.println("case " + name(opcode).toUpperCase() + ": {");
//                String arg = "";
//                int length = length(opcode);
//                if (length == 2) {
//                    arg = "readUnsigned1()";
//                } else if (length == 3) {
//                    arg = "readUnsigned2()";
//                }
//                System.out.println("    bytecodeVisitor." + name(opcode) + "(" + arg + ");");
//                System.out.println("    break;");
//                System.out.println("}");
//            }
//        }

    }
}
