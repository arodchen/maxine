/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.max.vm.ext.graal;

import java.util.*;
import java.util.concurrent.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.*;
import com.oracle.graal.compiler.target.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.java.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.phases.OptimisticOptimizations.Optimization;
import com.oracle.graal.phases.PhasePlan.PhasePosition;
import com.oracle.graal.printer.*;
import com.oracle.max.vm.ext.graal.amd64.*;
import com.oracle.max.vm.ext.maxri.*;
import com.sun.cri.ci.*;
import com.sun.max.annotate.*;
import com.sun.max.program.*;
import com.sun.max.vm.*;
import com.sun.max.vm.MaxineVM.Phase;
import com.sun.max.vm.actor.holder.*;
import com.sun.max.vm.actor.member.*;
import com.sun.max.vm.classfile.constant.*;
import com.sun.max.vm.compiler.*;
import com.sun.max.vm.compiler.target.*;

/**
 * Integration of the Graal compiler into Maxine's compilation framework. The compiler must be included in the boot image,
 * as the snippet generation needs access to things that are not available to a VM extension (i.e., {@link HOSTED_ONLY} fields of VM classes).
 * Currently the compiler is added to the set of compilers known the {@link CompilationBroker} and does not displace {@link C1X}.
 *
 * It is necessary to call {@link FieldIntrospection#rescanAllFieldOffsets()} on VM startup as the
 * {@link NodeClass} field offsets calculated during boot image construction are those of the host VM. In principle this
 * could be fixed up during boot image creation, as per the JDK classes, but as Graal provides a method to do it, and
 * the necessary fixup is quite complicated, we do it on VM startup.
 *
 * The Graal debug environment similarly needs to be re-established when the VM runs. It is setup for
 * debugging snippet installation in the boot image generation, but the config is stored in a thread local
 * so it does to survive in the target VM.
 */
public class MaxGraal implements RuntimeCompiler {

    private static class MaxGraphCache implements GraphCache {

        private final ConcurrentMap<ResolvedJavaMethod, StructuredGraph> cache = new ConcurrentHashMap<ResolvedJavaMethod, StructuredGraph>();

        @Override
        public void put(StructuredGraph graph) {
            cache.put(graph.method(), graph);
        }

        @Override
        public StructuredGraph get(ResolvedJavaMethod method) {
            return cache.get(method);
        }
    }

    private static final String NAME = "Graal";
    // The singleton instance of this compiler
    private static MaxGraal singleton;

    private MaxRuntime runtime;
    private Backend backend;
    private OptimisticOptimizations optimisticOpts;
    private MaxGraphCache cache;

    public MaxGraal() {
        if (singleton == null) {
            singleton = this;
        }
    }

    public static MaxRuntime runtime() {
        assert singleton != null && singleton.runtime != null;
        return singleton.runtime;
    }

    /**
     * See {@link CompilationBroker#initialize(Phase).
     * When an added compiler only called in {@link MaxineVM.Phase#HOSTED_COMPILING} and {@link MaxineVM.Phase#STARTING} phases,
     * <i>after</i> {@link CompilationBroker#addCompiler} has been called.
     */
    @Override
    public void initialize(Phase phase) {
        if (phase == MaxineVM.Phase.HOSTED_COMPILING) {
            MaxGraalOptions.initialize();
            createGraalCompiler(phase);
            VMOptions.addFieldOption("-XX:", "MaxGraalTests", "list of test methods to compile");
            VMOptions.addFieldOption("-XX:", "MaxGraalCompare", "compare compiled code against C1X/T1X");
            testsHack();
        } else if (phase == MaxineVM.Phase.STARTING) {
            // This is the obvious place to call rescanAllFieldOffsets() but it is too early in the startup without
            // more classes related to annotations being in the boot image, because bootclasspath is not setup.
            // So it is done lazily when the first compile is initiated.
        } else if (phase == MaxineVM.Phase.RUNNING) {
            // Would be called if the compiler was registered as the default optimizing compiler.
        }
    }

    private static boolean lazyRuntimeInitDone;

    private static void lazyRunTimeInit() {
        if (lazyRuntimeInitDone) {
            return;
        }
        try {
            FieldIntrospection.rescanAllFieldOffsets(new FieldIntrospection.DefaultCalcOffset());
            // This sets up the debug environment for the running VM
            DebugEnvironment.initialize(System.out);
            lazyRuntimeInitDone = true;
        } catch (Throwable t) {
            Log.println("FieldIntrospection.rescanAllFieldOffsets failed: " + t);
            MaxineVM.exit(-1);
        }
    }

    private void createGraalCompiler(Phase phase) {
        // This sets up the debug environment for the boot image build
        DebugEnvironment.initialize(System.out);
        MaxTargetDescription td = new MaxTargetDescription();
        runtime = new MaxRuntime(td);
        backend = new MaxAMD64Backend(runtime, td);
        EnumSet<Optimization> optimizations = EnumSet.allOf(Optimization.class);
        // We want exception handlers generated
        optimizations.remove(Optimization.UseExceptionProbability);
        optimisticOpts = new OptimisticOptimizations(optimizations);
        cache = new MaxGraphCache();
        changeInlineLimits();
        runtime.init();
    }

    /**
     * Graal's inlining limits are very aggressive, too high for the Maxine environment.
     * So we dial them down here.
     */
    private static void changeInlineLimits() {
        GraalOptions.NormalBytecodeSize = 30;
        GraalOptions.NormalComplexity = 30;
        GraalOptions.NormalCompiledCodeSize = 150;
    }

    @NEVER_INLINE
    public static void unimplemented(String methodName) {
        ProgramError.unexpected("unimplemented: " + methodName);
    }

    @Override
    public TargetMethod compile(ClassMethodActor methodActor, boolean isDeopt, boolean install, CiStatistics stats) {
        lazyRunTimeInit();
        TargetMethod c1xTM = null;
        TargetMethod t1xTM = null;
        if (MaxGraalCompare) {
            // compile with C1X/T1X for comparison
            c1xTM = CompilationBroker.singleton.optimizingCompiler.compile(methodActor, isDeopt, install, stats);
            t1xTM = CompilationBroker.singleton.baselineCompiler.compile(methodActor, isDeopt, install, stats);
            defaultCompilations(c1xTM, t1xTM);
        }
        try {
            PhasePlan phasePlan = new PhasePlan();
            ResolvedJavaMethod method = MaxResolvedJavaMethod.get(methodActor);

            StructuredGraph graph = (StructuredGraph) method.getCompilerStorage().get(Graph.class);
            if (graph == null) {
                GraphBuilderPhase graphBuilderPhase = new MaxGraphBuilderPhase(runtime, GraphBuilderConfiguration.getDefault(),
                                optimisticOpts);
                phasePlan.addPhase(PhasePosition.AFTER_PARSING, graphBuilderPhase);
                if (MaxineVM.isHosted()) {
                    MaxIntrinsicsPhase maxIntrinsicsPhase = new MaxIntrinsicsPhase();
                    phasePlan.addPhase(PhasePosition.AFTER_PARSING, maxIntrinsicsPhase);
                }
                graph = new StructuredGraph(method);
            }
            CompilationResult result = GraalCompiler.compileMethod(runtime, backend, runtime.maxTargetDescription,
                            method, graph, cache, phasePlan, optimisticOpts, new SpeculationLog());
            MaxTargetMethod graalTM = GraalMaxTargetMethod.create(methodActor, MaxCiTargetMethod.create(result), true);
            if (MaxGraalCompare) {
                compare(graalTM, c1xTM, t1xTM);
            }
            return graalTM;
        } catch (Throwable t) {
            ProgramError.unexpected(t);
            return null;
        }
    }

    @NEVER_INLINE
    private static void compare(TargetMethod graalTM, TargetMethod c1xTM, TargetMethod t1xTM) {

    }

    @NEVER_INLINE
    private static void defaultCompilations(TargetMethod c1xTM, TargetMethod t1xTM) {

    }

    @Override
    public Nature nature() {
        return Nature.OPT;
    }

    @Override
    public boolean matches(String compilerName) {
        return compilerName.equals(NAME);
    }

    /**
     * Hack option to compile given tests after boot image generation (ease of debugging).
     */
    private static String MaxGraalTests;

    /**
     * Prevents compilation (and comparison with C1X/T1X) when {@code false}.
     * Important in boot image mode, as multiple compilations of the same method
     * cause an assertion error.
     */
    private static boolean MaxGraalCompare = true;

    /**
     * Hack to compile some tests during boot image generation - easier debugging.
     *
     */
    private void testsHack() {
        if (MaxGraalTests != null) {
            String[] tests = MaxGraalTests.split(",");
            for (String test : tests) {
                String[] classAndMethod = test.split(":");
                try {
                    Class< ? > testClass = Class.forName(classAndMethod[0]);
                    ClassActor testClassActor = ClassActor.fromJava(testClass);
                    MethodActor[] methodActors;
                    if (classAndMethod[1].equals("*")) {
                        methodActors = testClassActor.getLocalMethodActorsArray();
                    } else {
                        MethodActor methodActor = testClassActor.findLocalClassMethodActor(SymbolTable.makeSymbol(classAndMethod[1]), null);
                        if (methodActor == null) {
                            throw new NoSuchMethodError(classAndMethod[1]);
                        }
                        methodActors = new MethodActor[1];
                        methodActors[0] = methodActor;
                    }
                    for (MethodActor methodActor : methodActors) {
                        compile((ClassMethodActor) methodActor, false, true, null);
                    }
                } catch (ClassNotFoundException ex) {
                    System.err.println("failed to find test class: " + test);
                } catch (NoSuchMethodError ex) {
                    System.err.println("failed to find test method: " + test);
                }
            }
        }
    }




}
