/*
 * Copyright (c) 2011, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.max.vma.tools.gen.vma;

import static com.oracle.max.vm.ext.vma.AdviceMode.*;
import static com.oracle.max.vm.ext.vma.VMABytecodes.*;
import static com.oracle.max.vma.tools.gen.vma.AdviceGeneratorHelper.*;
import static com.sun.cri.ci.CiKind.*;
import static com.sun.max.vm.t1x.T1XTemplateGenerator.*;

import java.io.*;
import java.util.*;
import java.util.regex.*;

import com.oracle.max.vm.ext.vma.*;
import com.sun.cri.bytecode.*;
import com.sun.cri.ci.*;
import com.sun.max.annotate.*;
import com.sun.max.vm.t1x.*;
import com.sun.max.vm.t1x.T1XTemplateGenerator.AdviceHook;
import com.sun.max.vm.t1x.T1XTemplateGenerator.AdviceType;

/**
 * Generates the bytecode advising interface by processing {@link VMABytecodes}. This interface supports advising by having
 * the VM invoke the advice method when a bytecode is (logically) executed (logically because the bytecodes may have
 * been compiled into native code). The pertinent state associated with the bytecode is typically provided as arguments
 * to the method, to avoid having a separate API to the execution state.
 *
 * The generated API is not simply 1-1 with the list of bytecodes because several bytecodes, e.g. {@code ICONST_*} form
 * a group for which there is no value in providing independent advice and some, e.g. {@code GETFIELD} for which it is
 * necessary to create type-specific variants (in order to pass arguments unboxed). To reduce the fan out of methods
 * boolean, byte, char, short, int and Word values are all passed as long. On the other hand the {@link AdviceMode}
 * is encoded explicitly rather than being passed as argument. In part this is because only a few bytecodes can support
 * {@link AdviceMode#AFTER} owing to limitations in the template generator. This decision could be changed
 * relatively easily owing to the aggressive use of automatic source code generation.
 *
 * Note that, owing to implementation limitations and artifacts of the templates used
 * by {@link T1X}, not every bytecode can be advised independently (even if that were desirable).
 * For example, the {@link Bytecodes#ILOAD ILOAD_N} variants do not have separate
 * templates, so must be distinguished at advice time by a runtime argument.
 *
 * The end result is that the generated API collapses families of similar bytecodes into single
 * methods, with a possible fanout for denoting type-specific variants. To see the family that
 * a given bytecode falls into, see the {@link VMABytecodes#methodName} value.
 *
 * We use some of the auto-generation facilities from {@link T1XTemplateGenerator}.
 */

@HOSTED_ONLY
public class BytecodeAdviceGenerator {

    private static final String METHOD_PREFIX = "    public abstract void advise%s%s(";

    private static final Set<CiKind> getPutTypes = new HashSet<CiKind>();

    /**
     * For each bytecode, this array records whether {@link AdviceMode#BEFORE} or {@link AdviceMode#AFTER}
     * is available from the generated templates.
     */
    private static boolean[][] checks = new boolean[VMABytecodeValues.length][AdviceType.values().length];

    private static AdviceMode adviceMode;
    private static String adviceModeString;
    private static boolean logNotGenerated;
    private static ByteArrayOutputStream logByteOutput;
    private static PrintStream logOutput;
    private static T1XTemplateGenerator templateGen;

    public static void main(String[] args) {
        for (String arg : args) {
            if (arg.equals("-log")) {
                logNotGenerated = true;
                logByteOutput = new ByteArrayOutputStream();
                logOutput = new PrintStream(logByteOutput);
            }
        }
        templateGen = new T1XTemplateGenerator(BytecodeAdviceGenerator.class, System.out);
        for (CiKind k : kinds) {
            if (!(k == Boolean || k == Byte || k == Char || k == Short || k == Int || k == Word)) {
                getPutTypes.add(k);
            }
        }
        checkTemplates();
        for (AdviceMode am : AdviceMode.values()) {
            adviceMode = am;
            adviceModeString = toFirstUpper(am.name().toLowerCase());
            for (VMABytecodes f : VMABytecodeValues) {
                if (checks[f.ordinal()][am.ordinal()]) {
                    generateSpecific(f);
                }
            }
        }
        if (logOutput != null) {
            System.err.println(logByteOutput.toString());
        }
    }

    private static class CheckAdviceHook implements AdviceHook {
        public void startMethodGeneration() {
        }

        public void generate(T1XTemplateTag tag, AdviceType adviceType, Object... args) {
            if (tag.opcode >= 0 || tag == T1XTemplateTag.TRACE_METHOD_ENTRY) {
                checks[codeMap.get(tag.opcode).ordinal()][adviceType.ordinal()] = true;
            }
        }
    }

    /**
     * Learn exactly what template-based advice is available.
     */
    private static void checkTemplates() {
        final PrintStream saveOut = out;
        out = new PrintStream(new ByteArrayOutputStream());
        templateGen.generateAll(new CheckAdviceHook());
        out = saveOut;
    }

    private static void generateSpecific(VMABytecodes bytecode) {
        final String name = bytecode.name();
        if (bytecode == GETSTATIC) {
            generateGetStatic(bytecode);
        } else if (bytecode == GETFIELD) {
            generateGetField(bytecode);
        } else if (bytecode == PUTFIELD) {
            generatePutField(bytecode);
        } else if (bytecode == PUTSTATIC) {
            generatePutStatic(bytecode);
        } else if (bytecode == NEW) {
            generateNew(bytecode);
        } else if (bytecode == NEWARRAY || bytecode == ANEWARRAY) {
            generateNewArray(bytecode);
        } else if (bytecode == MULTIANEWARRAY) {
            generateMultiNewArray(bytecode);
        } else if (name.equals("CHECKCAST") || name.equals("INSTANCEOF")) {
            generateTypeCheck(bytecode);
        } else if (name.equals("ARRAYLENGTH")) {
            generateArrayLength();
        } else if (name.equals("ATHROW")) {
            generateThrow();
        } else if (name.equals("MONITORENTER") || name.equals("MONITOREXIT")) {
            generateMonitor(bytecode);
        } else if (Pattern.matches(".{1}RETURN|RETURN", name)) {
            generateReturn(bytecode);
        } else if (Pattern.matches("INVOKE.*", name) || name.equals("MENTRY")) {
            generateInvoke(bytecode);
        } else if (Pattern.matches(".{1}ALOAD", name)) {
            generateArrayLoadStore(bytecode);
        } else if (Pattern.matches(".{1}ASTORE", name)) {
            generateArrayLoadStore(bytecode);
        } else if (Pattern.matches(".{1}CONST.*", name)) {
            generateConst(bytecode);
        } else if (Pattern.matches(".{1}LOAD.*", name)) {
            generateScalarLoadStore(bytecode);
        } else if (Pattern.matches(".{1}STORE.*", name)) {
            generateScalarLoadStore(bytecode);
        } else if (Pattern.matches(".{1}IPUSH", name)) {
            generateIPush(bytecode);
        } else if (Pattern.matches(".{1}(ADD|SUB|MUL|DIV|REM|AND|OR|XOR|SHL|SHR|USHR|NEG|DIVI|REMI)", name)) {
            generateOperation(bytecode);
        } else if (Pattern.matches(".{1}2.{1}", name)) {
            generateConvert(bytecode);
        } else if (Pattern.matches("IF.*", name)) {
            generateIf(bytecode);
        } else if (Pattern.matches("POP|POP2|DUP.*|SWAP", name)) {
            generateStack(bytecode);
        } else if (Pattern.matches("LDC.*", name)) {
            // handled in CONST
        } else if (Pattern.matches("[FLD]CMP.*", name)) {
            // handled as an Operation
        } else if (Pattern.matches("MOV_.*", name)) {
            // handled as an Conversion
        } else if (name.equals("IINC")) {
            generateIInc(bytecode);
        } else {
            if (logNotGenerated && checks[bytecode.ordinal()][adviceMode.ordinal()]) {
                logOutput.printf("%s(%s) not generated%n", name, adviceModeString);
            }
            generateBytecode(bytecode);
        }
    }

    private static void generateGetField(VMABytecodes bytecode) {
        assert adviceMode == BEFORE;
        out.printf(METHOD_PREFIX + "Object object, int offset);%n%n", adviceModeString, bytecode.methodName);
    }

    private static void generateGetStatic(VMABytecodes bytecode) {
        assert adviceMode == BEFORE;
        templateGen.generateAutoComment();
        // StaticTuple but ClassActor.staticTuple returns Object
        out.printf(METHOD_PREFIX + "Object staticTuple, int offset);%n%n", adviceModeString, bytecode.methodName);
    }

    private static void generatePutField(VMABytecodes bytecode) {
        assert adviceMode == BEFORE;
        for (CiKind k : getPutTypes) {
            if (hasGetPutTemplates(k)) {
                templateGen.generateAutoComment();
                out.printf(METHOD_PREFIX + "Object object, int offset, %s value);%n%n", adviceModeString, bytecode.methodName, k);
            }
        }
    }

    private static void generatePutStatic(VMABytecodes bytecode) {
        assert adviceMode == BEFORE;
        for (CiKind k : getPutTypes) {
            if (hasGetPutTemplates(k)) {
                templateGen.generateAutoComment();
                // StaticTuple but ClassActor.staticTuple returns Object
                out.printf(METHOD_PREFIX + "Object staticTuple, int offset, %s value);%n%n", adviceModeString, bytecode.methodName, k);
            }
        }
    }

    private static void generateNew(VMABytecodes bytecode) {
        assert adviceMode == AFTER;
        templateGen.generateAutoComment();
        out.printf(METHOD_PREFIX + "Object object);%n%n", adviceModeString, bytecode.methodName);
    }

    private static boolean newArrayDone;

    private static void generateNewArray(VMABytecodes bytecode) {
        if (!newArrayDone) {
            assert adviceMode == AFTER;
            templateGen.generateAutoComment();
            out.printf(METHOD_PREFIX + "Object object, int length);%n%n", adviceModeString, bytecode.methodName);
            newArrayDone = true;
        }
    }

    private static void generateMultiNewArray(VMABytecodes bytecode) {
        assert adviceMode == AFTER;
        templateGen.generateAutoComment();
        out.printf(METHOD_PREFIX + "Object object, int[] lengths);%n%n", adviceModeString, bytecode.methodName);
    }

    private static void generateInvoke(VMABytecodes bytecode) {
        templateGen.generateAutoComment();
        out.printf(METHOD_PREFIX + "Object object, MethodActor methodActor);%n%n", adviceModeString, bytecode.methodName);
    }

    private static Set<String> scalarArraySet = new HashSet<String>();

    private static boolean arrayLoadDone;

    private static void generateArrayLoadStore(VMABytecodes bytecode) {
        assert adviceMode == BEFORE;
        boolean isLoad = bytecode.methodName.contains("Load");
        String value = isLoad ? "" : ", %s value";
        if (isLoad && arrayLoadDone) {
            return;
        }
        String type = typeFor(bytecode.name().charAt(0));
        if (!scalarArraySet.contains(bytecode.methodName + type)) {
            templateGen.generateAutoComment();
            out.printf(METHOD_PREFIX + "Object array, int index" + value + ");%n%n", adviceModeString, bytecode.methodName, type);
            scalarArraySet.add(bytecode.methodName + type);
            if (isLoad) {
                arrayLoadDone = true;
            }
        }
    }

    private static Set<String> scalarConstSet = new HashSet<String>();

    private static void generateConst(VMABytecodes bytecode) {
        assert adviceMode == BEFORE;
        String type = typeFor(bytecode.name().charAt(0));
        if (!scalarConstSet.contains(bytecode.methodName + type)) {
            templateGen.generateAutoComment();
            out.printf(METHOD_PREFIX + "%s value);%n%n", adviceModeString, bytecode.methodName, type);
            scalarConstSet.add(bytecode.methodName + type);
        }
    }

    private static Set<String> scalarLoadSet = new HashSet<String>();

    private static boolean loadDone;

    private static void generateScalarLoadStore(VMABytecodes bytecode) {
        assert adviceMode == BEFORE;
        boolean isLoad = bytecode.methodName.contains("Load");
        String value = isLoad ? "" : ", %s value";
        if (isLoad && loadDone) {
            return;
        }
        String type = typeFor(bytecode.name().charAt(0));
        if (!scalarLoadSet.contains(bytecode.methodName + type)) {
            templateGen.generateAutoComment();
            out.printf(METHOD_PREFIX + "int dispToLocalSlot" + value + ");%n%n", adviceModeString, bytecode.methodName, type);
            scalarLoadSet.add(bytecode.methodName + type);
            if (isLoad) {
                loadDone = true;
            }
        }
    }

    private static boolean iPushDone;
    private static void generateIPush(VMABytecodes bytecode) {
        assert adviceMode == BEFORE;
        if (iPushDone) {
            return;
        }
        templateGen.generateAutoComment();
        out.printf(METHOD_PREFIX + "int value);%n%n", adviceModeString, bytecode.methodName);
        iPushDone = true;
    }

    private static Set<String> scalarOpSet = new HashSet<String>();

    private static void generateOperation(VMABytecodes bytecode) {
        assert adviceMode == BEFORE;
        String type = typeFor(bytecode.name().charAt(0));
        if (!scalarOpSet.contains(bytecode.methodName + type)) {
            templateGen.generateAutoComment();
            out.printf(METHOD_PREFIX + "int opcode, %s op1, %s op2);%n%n", adviceModeString, bytecode.methodName, type, type);
            scalarOpSet.add(bytecode.methodName + type);
        }
    }

    private static void generateConvert(VMABytecodes bytecode) {
        assert adviceMode == BEFORE;
        String type = typeFor(bytecode.name().charAt(0));
        if (!scalarOpSet.contains(bytecode.methodName + type)) {
            templateGen.generateAutoComment();
            out.printf(METHOD_PREFIX + "int opcode, %s op);%n%n", adviceModeString, bytecode.methodName, type);
            scalarOpSet.add(bytecode.methodName + type);
        }
    }

    private static Set<String> scalarIfSet = new HashSet<String>();

    private static void generateIf(VMABytecodes bytecode) {
        assert adviceMode == BEFORE;
        String type = bytecode.name().charAt(3) == 'A' ? "Object" : "int";
        if (!scalarIfSet.contains(bytecode.methodName + type)) {
            templateGen.generateAutoComment();
            out.printf(METHOD_PREFIX + "int opcode, %s op1, %s op2);%n%n", adviceModeString, bytecode.methodName, type, type);
            scalarIfSet.add(bytecode.methodName + type);
        }
    }

    private static void generateTypeCheck(VMABytecodes bytecode) {
        assert adviceMode == BEFORE;
        templateGen.generateAutoComment();
        out.printf(METHOD_PREFIX + "Object object, Object classActor);%n%n", adviceModeString, bytecode.methodName);
    }

    private static void generateArrayLength() {
        assert adviceMode == BEFORE;
        templateGen.generateAutoComment();
        out.printf(METHOD_PREFIX + "Object array, int length);%n%n", adviceModeString, ARRAYLENGTH.methodName);

    }

    private static void generateThrow() {
        assert adviceMode == BEFORE;
        templateGen.generateAutoComment();
        out.printf(METHOD_PREFIX + "Object object);%n%n", adviceModeString, ATHROW.methodName);

    }

    private static void generateMonitor(VMABytecodes bytecode) {
        assert adviceMode == BEFORE;
        templateGen.generateAutoComment();
        out.printf(METHOD_PREFIX + "Object object);%n%n", adviceModeString, bytecode.methodName);
    }

    private static Set<String> scalarReturnSet = new HashSet<String>();

    private static void generateReturn(VMABytecodes bytecode) {
        assert adviceMode == BEFORE;
        if (bytecode == RETURN) {
            out.printf(METHOD_PREFIX + ");%n%n", adviceModeString, bytecode.methodName);
            return;
        }
        String type = typeFor(bytecode.name().charAt(0));
        if (!scalarReturnSet.contains(bytecode.methodName + type)) {
            templateGen.generateAutoComment();
            out.printf(METHOD_PREFIX + "%s value);%n%n", adviceModeString, bytecode.methodName, type);
            scalarReturnSet.add(bytecode.methodName + type);
        }
    }

    private static boolean stackOpDone;

    private static void generateStack(VMABytecodes bytecode) {
        assert adviceMode == BEFORE;
        if (!stackOpDone) {
            templateGen.generateAutoComment();
            out.printf(METHOD_PREFIX + "int opcode);%n%n", adviceModeString, bytecode.methodName);
            stackOpDone = true;
        }
    }

    private static void generateIInc(VMABytecodes bytecode) {
        assert adviceMode == BEFORE;
        templateGen.generateAutoComment();
        out.printf(METHOD_PREFIX + "int dispToLocalSlot, int value, int increment);%n%n", adviceModeString, bytecode.methodName);
    }

    private static boolean bytecodeDone;

    private static void generateBytecode(VMABytecodes bytecode) {
        assert adviceMode == BEFORE;
        if (!bytecodeDone) {
            templateGen.generateAutoComment();
            out.printf(METHOD_PREFIX + "int opcode);%n%n", adviceModeString, bytecode.methodName);
            bytecodeDone = true;
        }
    }

    private static String typeFor(char c) {
        switch (c) {
            case 'A':
                return "Object";
            case 'I':
            case 'L':
            case 'B':
            case 'C':
            case 'S':
            case 'W':
                return "long";
            case 'F':
                return "float";
            case 'D':
                return "double";
        }
        return "???";
    }

}
