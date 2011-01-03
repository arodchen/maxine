/*
 * Copyright (c) 2009, 2010, Oracle and/or its affiliates. All rights reserved.
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
package test.com.sun.max.vm.jtrun;

import static com.sun.max.vm.VMOptions.*;
import test.com.sun.max.vm.jtrun.all.*;

import com.sun.max.annotate.*;
import com.sun.max.vm.*;
import com.sun.max.vm.actor.holder.*;
import com.sun.max.vm.actor.member.*;
import com.sun.max.vm.classfile.constant.*;
import com.sun.max.vm.hosted.*;
import com.sun.max.vm.run.java.*;

/**
 * This abstract run scheme is shared by all the concrete run schemes generated by the {@link JTGenerator}.
 * It behaves as the standard {@link JavaRunScheme} if a main class is specified on the command.
 * If no main class is specified, then the tests will be run and the VM will exit.
 *
 * @author Doug Simon
 */
public abstract class JTAbstractRunScheme extends JavaRunScheme {

    @HOSTED_ONLY
    public JTAbstractRunScheme() {
    }

    protected static Utf8Constant testMethod = SymbolTable.makeSymbol("test");
    protected static boolean nativeTests;
    protected static boolean noTests;
    protected static int testStart;
    protected static int testEnd;
    protected static int testCount;

    private static VMIntOption startOption = register(new VMIntOption("-XX:TesterStart=", -1,
                    "The number of the first test to run."), MaxineVM.Phase.STARTING);
    private static VMIntOption endOption  = register(new VMIntOption("-XX:TesterEnd=", -1,
                    "The number of the last test to run. Specify 0 to run exactly one test."), MaxineVM.Phase.STARTING);
    private static final boolean COMPILE_ALL_TEST_METHODS = true;

    @HOSTED_ONLY
    public void addClassToImage(Class<?> javaClass) {
        final ClassActor actor = ClassActor.fromJava(javaClass);
        if (actor == null) {
            return;
        }
        if (BootImageGenerator.calleeJit) {
            CompiledPrototype.registerJitClass(javaClass);
        }
        if (BootImageGenerator.calleeC1X) {
            CompiledPrototype.registerC1XClass(javaClass);
        }
        if (COMPILE_ALL_TEST_METHODS) {
            // add all virtual and static methods to the image
            addMethods(actor.localStaticMethodActors());
            addMethods(actor.localVirtualMethodActors());
        } else {
            // add only the test method to the image
            final StaticMethodActor method = actor.findLocalStaticMethodActor(testMethod);
            if (method != null) {
                addMethodToImage(method);
            }
        }
        for (Class<?> declaredClass : javaClass.getDeclaredClasses()) {
            // load all inner and anonymous classes into the image as well
            addClassToImage(declaredClass);
        }
    }

    @HOSTED_ONLY
    private void addMethods(ClassMethodActor[] methodActors) {
        if (methodActors != null) {
            for (ClassMethodActor method : methodActors) {
                addMethodToImage(method);
            }
        }
    }

    @HOSTED_ONLY
    private void addMethodToImage(ClassMethodActor method) {
        CompiledPrototype.registerVMEntryPoint(method);
    }

    private boolean classesRegistered;

    @HOSTED_ONLY
    private void registerClasses() {
        if (!classesRegistered) {
            classesRegistered = true;
            if (BootImageGenerator.callerJit) {
                CompiledPrototype.registerJitClass(JTRuns.class);
            }
            Class[] list = getClassList();
            for (Class<?> testClass : list) {
                addClassToImage(testClass);
            }
            testCount = list.length;
        }
    }

    @Override
    protected boolean parseMain() {
        return noTests;
    }

    protected abstract void runTests();

    @Override
    public void initialize(MaxineVM.Phase phase) {
        noTests = VMOptions.parseMain(false);
        if (phase == MaxineVM.Phase.STARTING) {
            if (nativeTests || noTests) {
                super.initialize(phase);
            }
            if (!noTests) {
                testStart = startOption.getValue();
                if (testStart < 0) {
                    testStart = 0;
                }
                testEnd = endOption.getValue();
                if (testEnd < testStart || testEnd > testCount) {
                    testEnd = testCount;
                } else if (testEnd == testStart) {
                    testEnd = testStart + 1;
                }
                if (nativeTests) {
                    System.loadLibrary("javatest");
                }
                runTests();
            }
        } else {
            super.initialize(phase);
        }
        JTUtil.verbose = 3;
        if (MaxineVM.isHosted()) {
            registerClasses();
            nativeTests = BootImageGenerator.nativeTests;
            super.initialize(phase);
        }
    }

    @HOSTED_ONLY
    public abstract Class[] getClassList();
}
