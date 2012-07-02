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
package com.oracle.max.vm.ext.vma.handlers.gctest.h;

import java.util.*;

import com.oracle.max.vm.ext.vma.*;
import com.sun.max.unsafe.*;
import com.sun.max.vm.*;
import com.sun.max.vm.actor.member.*;
import com.sun.max.vm.thread.*;


public class GCTestVMAdviceHandler extends VMAdviceHandler {

    private Random random = new Random(46737);
    private boolean gcAlways;
    private static AdviceMethod[] methodValues = AdviceMethod.values();
    private static AdviceMode[] modeValues = AdviceMode.values();


    private void randomlyGC(int methodOrd, int adviceOrd) {
        randomlyGC(methodValues[methodOrd].name(), modeValues[adviceOrd].name());
    }

    private void randomlyGC(String ident1, String ident2) {
        int next = random.nextInt(100);
        if (next == 57 || gcAlways) {
            final boolean lockDisabledSafepoints = Log.lock();
            Log.print("GCTestVMAdviceHandlerLog.GC: ");
            Log.print(ident1);
            Log.print(":");
            Log.println(ident2);
            Log.unlock(lockDisabledSafepoints);
            System.gc();
        }
    }

    @Override
    public void gcSurvivor(Pointer cell) {
        // Don't GC when in GC!
    }

    @Override
    public void initialise(MaxineVM.Phase phase) {
        if (phase == MaxineVM.Phase.RUNNING) {
            gcAlways = System.getProperty("max.vma.handler.gctest.always") != null;
        }
    }

// START GENERATED CODE
// EDIT AND RUN GCTestAdviceHandlerLogGenerator.main() TO MODIFY

    enum AdviceMethod {
        GC,
        ThreadStarting,
        ThreadTerminating,
        ConstLoad,
        Load,
        ArrayLoad,
        Store,
        ArrayStore,
        StackAdjust,
        Operation,
        Conversion,
        If,
        Bytecode,
        Return,
        GetStatic,
        PutStatic,
        GetField,
        PutField,
        InvokeVirtual,
        InvokeSpecial,
        InvokeStatic,
        InvokeInterface,
        ArrayLength,
        Throw,
        CheckCast,
        InstanceOf,
        MonitorEnter,
        MonitorExit,
        New,
        NewArray,
        MultiNewArray,
        MethodEntry;
    }
    @Override
    public void adviseBeforeGC() {
        randomlyGC(0, 0);
    }

    @Override
    public void adviseAfterGC() {
        randomlyGC(0, 1);
    }

    @Override
    public void adviseBeforeThreadStarting(VmThread arg1) {
        randomlyGC(1, 0);
    }

    @Override
    public void adviseBeforeThreadTerminating(VmThread arg1) {
        randomlyGC(2, 0);
    }

    @Override
    public void adviseBeforeConstLoad(long arg1) {
        randomlyGC(3, 0);
    }

    @Override
    public void adviseBeforeConstLoad(Object arg1) {
        randomlyGC(3, 0);
    }

    @Override
    public void adviseBeforeConstLoad(float arg1) {
        randomlyGC(3, 0);
    }

    @Override
    public void adviseBeforeConstLoad(double arg1) {
        randomlyGC(3, 0);
    }

    @Override
    public void adviseBeforeLoad(int arg1) {
        randomlyGC(4, 0);
    }

    @Override
    public void adviseBeforeArrayLoad(Object arg1, int arg2) {
        randomlyGC(5, 0);
    }

    @Override
    public void adviseBeforeStore(int arg1, long arg2) {
        randomlyGC(6, 0);
    }

    @Override
    public void adviseBeforeStore(int arg1, float arg2) {
        randomlyGC(6, 0);
    }

    @Override
    public void adviseBeforeStore(int arg1, double arg2) {
        randomlyGC(6, 0);
    }

    @Override
    public void adviseBeforeStore(int arg1, Object arg2) {
        randomlyGC(6, 0);
    }

    @Override
    public void adviseBeforeArrayStore(Object arg1, int arg2, float arg3) {
        randomlyGC(7, 0);
    }

    @Override
    public void adviseBeforeArrayStore(Object arg1, int arg2, long arg3) {
        randomlyGC(7, 0);
    }

    @Override
    public void adviseBeforeArrayStore(Object arg1, int arg2, double arg3) {
        randomlyGC(7, 0);
    }

    @Override
    public void adviseBeforeArrayStore(Object arg1, int arg2, Object arg3) {
        randomlyGC(7, 0);
    }

    @Override
    public void adviseBeforeStackAdjust(int arg1) {
        randomlyGC(8, 0);
    }

    @Override
    public void adviseBeforeOperation(int arg1, long arg2, long arg3) {
        randomlyGC(9, 0);
    }

    @Override
    public void adviseBeforeOperation(int arg1, float arg2, float arg3) {
        randomlyGC(9, 0);
    }

    @Override
    public void adviseBeforeOperation(int arg1, double arg2, double arg3) {
        randomlyGC(9, 0);
    }

    @Override
    public void adviseBeforeConversion(int arg1, float arg2) {
        randomlyGC(10, 0);
    }

    @Override
    public void adviseBeforeConversion(int arg1, long arg2) {
        randomlyGC(10, 0);
    }

    @Override
    public void adviseBeforeConversion(int arg1, double arg2) {
        randomlyGC(10, 0);
    }

    @Override
    public void adviseBeforeIf(int arg1, int arg2, int arg3) {
        randomlyGC(11, 0);
    }

    @Override
    public void adviseBeforeIf(int arg1, Object arg2, Object arg3) {
        randomlyGC(11, 0);
    }

    @Override
    public void adviseBeforeBytecode(int arg1) {
        randomlyGC(12, 0);
    }

    @Override
    public void adviseBeforeReturn() {
        randomlyGC(13, 0);
    }

    @Override
    public void adviseBeforeReturn(long arg1) {
        randomlyGC(13, 0);
    }

    @Override
    public void adviseBeforeReturn(float arg1) {
        randomlyGC(13, 0);
    }

    @Override
    public void adviseBeforeReturn(double arg1) {
        randomlyGC(13, 0);
    }

    @Override
    public void adviseBeforeReturn(Object arg1) {
        randomlyGC(13, 0);
    }

    @Override
    public void adviseBeforeGetStatic(Object arg1, int arg2) {
        randomlyGC(14, 0);
    }

    @Override
    public void adviseBeforePutStatic(Object arg1, int arg2, Object arg3) {
        randomlyGC(15, 0);
    }

    @Override
    public void adviseBeforePutStatic(Object arg1, int arg2, float arg3) {
        randomlyGC(15, 0);
    }

    @Override
    public void adviseBeforePutStatic(Object arg1, int arg2, double arg3) {
        randomlyGC(15, 0);
    }

    @Override
    public void adviseBeforePutStatic(Object arg1, int arg2, long arg3) {
        randomlyGC(15, 0);
    }

    @Override
    public void adviseBeforeGetField(Object arg1, int arg2) {
        randomlyGC(16, 0);
    }

    @Override
    public void adviseBeforePutField(Object arg1, int arg2, Object arg3) {
        randomlyGC(17, 0);
    }

    @Override
    public void adviseBeforePutField(Object arg1, int arg2, float arg3) {
        randomlyGC(17, 0);
    }

    @Override
    public void adviseBeforePutField(Object arg1, int arg2, double arg3) {
        randomlyGC(17, 0);
    }

    @Override
    public void adviseBeforePutField(Object arg1, int arg2, long arg3) {
        randomlyGC(17, 0);
    }

    @Override
    public void adviseBeforeInvokeVirtual(Object arg1, MethodActor arg2) {
        randomlyGC(18, 0);
    }

    @Override
    public void adviseBeforeInvokeSpecial(Object arg1, MethodActor arg2) {
        randomlyGC(19, 0);
    }

    @Override
    public void adviseBeforeInvokeStatic(Object arg1, MethodActor arg2) {
        randomlyGC(20, 0);
    }

    @Override
    public void adviseBeforeInvokeInterface(Object arg1, MethodActor arg2) {
        randomlyGC(21, 0);
    }

    @Override
    public void adviseBeforeArrayLength(Object arg1, int arg2) {
        randomlyGC(22, 0);
    }

    @Override
    public void adviseBeforeThrow(Object arg1) {
        randomlyGC(23, 0);
    }

    @Override
    public void adviseBeforeCheckCast(Object arg1, Object arg2) {
        randomlyGC(24, 0);
    }

    @Override
    public void adviseBeforeInstanceOf(Object arg1, Object arg2) {
        randomlyGC(25, 0);
    }

    @Override
    public void adviseBeforeMonitorEnter(Object arg1) {
        randomlyGC(26, 0);
    }

    @Override
    public void adviseBeforeMonitorExit(Object arg1) {
        randomlyGC(27, 0);
    }

    @Override
    public void adviseAfterInvokeVirtual(Object arg1, MethodActor arg2) {
        randomlyGC(18, 1);
    }

    @Override
    public void adviseAfterInvokeSpecial(Object arg1, MethodActor arg2) {
        randomlyGC(19, 1);
    }

    @Override
    public void adviseAfterInvokeStatic(Object arg1, MethodActor arg2) {
        randomlyGC(20, 1);
    }

    @Override
    public void adviseAfterInvokeInterface(Object arg1, MethodActor arg2) {
        randomlyGC(21, 1);
    }

    @Override
    public void adviseAfterNew(Object arg1) {
        randomlyGC(28, 1);
    }

    @Override
    public void adviseAfterNewArray(Object arg1, int arg2) {
        randomlyGC(29, 1);
    }

    @Override
    public void adviseAfterMultiNewArray(Object arg1, int[] arg2) {
        randomlyGC(30, 1);
    }

    @Override
    public void adviseAfterMethodEntry(Object arg1, MethodActor arg2) {
        randomlyGC(31, 1);
    }

// END GENERATED CODE

}