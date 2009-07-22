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
package com.sun.max.vm.compiler.adaptive;

import static com.sun.max.vm.VMOptions.register;

import java.util.*;
import java.util.concurrent.*;

import com.sun.max.annotate.*;
import com.sun.max.vm.*;
import com.sun.max.vm.MaxineVM.*;
import com.sun.max.vm.actor.member.*;
import com.sun.max.vm.compiler.*;
import com.sun.max.vm.compiler.c1x.*;
import com.sun.max.vm.compiler.instrument.*;
import com.sun.max.vm.compiler.target.*;
import com.sun.max.vm.prototype.*;

/**
 * This class implements an adaptive compilation system with multiple compilers with different compilation time / code
 * quality tradeoffs. It encapsulates the necessary infrastructure for recording profiling data, selecting what and when
 * to recompile, etc.
 *
 * @author Ben L. Titzer
 * @author Michael Van De Vanter
 */
public class AdaptiveCompilationScheme extends AbstractVMScheme implements CompilationScheme {

    /**
     * A constant used to indicated that recompilation is disabled.
     */
    public static final int RECOMPILATION_DISABLED = -1;

    private static final int DEFAULT_RECOMPILATION_THRESHOLD = 5000;

    /**
     * Predicted maximum number of compilations for most methods.
     */
    static final int DEFAULT_HISTORY_LENGTH = 2;

    /**
     * Stores the default threshold at which a recompilation is triggered from the baseline compiler to the next level
     * of optimization. This is typically the number of invocations of the method.
     */
    public static int defaultRecompilationThreshold0 = DEFAULT_RECOMPILATION_THRESHOLD;

    /**
     * Stores the default threshold at which a recompilation is triggered from optimized code to more highly optimized
     * code.
     */
    public static int defaultRecompilationThreshold1 = DEFAULT_RECOMPILATION_THRESHOLD;

    /**
     * A queue of pending compilations.
     */
    protected final LinkedList<Compilation> pending = new LinkedList<Compilation>();

    /**
     * The number of submitted compilations.
     */
    protected int submitted;

    /**
     * A lock to synchronize access to {@link #completed}.
     */
    protected final Object completionLock = new Object();

    /**
     * The number of completed compilations. If {@code _completed < _submitted}, then there is currently a compilation
     * pending or being performed.
     */
    protected int completed;

    /**
     * The compiler that is used as the default at prototyping time.
     */
    @PROTOTYPE_ONLY
    protected final CompilerScheme prototypeCompiler;

    /**
     * The baseline (JIT) compiler.
     */
    protected final DynamicCompilerScheme jitCompiler;

    /**
     * The optimizing compiler, if any.
     */
    protected final CompilerScheme optimizingCompiler;

    /**
     * List of attached Compilation observers.
     */
    @RESET
    protected LinkedList<CompilationObserver> observers;

    private static final VMOption jitOption = register(new VMOption("-Xjit",
                    "Selects JIT only mode, with no recompilation."), MaxineVM.Phase.STARTING);
    private static final VMOption optOption = register(new VMOption("-Xopt",
                    "Selects optimized only mode."), MaxineVM.Phase.STARTING);
    private static final VMIntOption thresholdOption = register(new VMIntOption("-XX:RCT=", DEFAULT_RECOMPILATION_THRESHOLD,
                    "In mixed mode, sets the recompilation threshold for methods."), MaxineVM.Phase.STARTING);
    static final VMBooleanXXOption gcOnCompileOption = register(new VMBooleanXXOption("-XX:-GCOnCompilation",
                    "When specified, the compiler will request GC before every compilation operation, " +
                    "which is useful for testing corner cases in the compiler/GC interactions."), MaxineVM.Phase.STARTING);
    private static final VMBooleanXXOption gcOnRecompileOption = register(new VMBooleanXXOption("-XX:-GCOnRecompilation",
                    "When specified, the compiler will request GC before every re-compilation operation, " +
                    "which is useful for testing corner cases in the compiler/GC interactions."), MaxineVM.Phase.STARTING);

    /**
     * The (dynamically selected) compilation mode.
     */
    private Mode mode = Mode.JIT;

    public Mode mode() {
        return mode;
    }

    /**
     * Set the compilation mode of this compilation scheme, which determines which compilers to select at runtime.
     */
    public void setMode(Mode mode) {
        this.mode = mode;
    }

    /**
     * This method decides whether an update of the target code for a specified method is allowed. An update might be
     * disallowed for correctness reasons (e.g. due to deoptimization), or due to multiple compilations of the same
     * method at the same or different optimization levels happening because of concurrency.
     *
     * @param methodState the method state of the ClassMethodActor
     * @param newTargetMethod the new target code for the method
     * @return true if the update should be allowed; false otherwise
     */
    protected boolean allowUpdate(AdaptiveMethodState methodState, TargetMethod newTargetMethod) {
        return true;
    }

    /**
     * The constructor for this class initializes a new adaptive compilation system with the specified VM configuration,
     * configuring itself according to the compiler(s) selected in the VM configuration.
     *
     * @param vmConfiguration the configuration of the virtual machine
     */
    public AdaptiveCompilationScheme(VMConfiguration vmConfiguration) {
        super(vmConfiguration);
        prototypeCompiler = vmConfiguration.compilerScheme();
        optimizingCompiler = prototypeCompiler;
        jitCompiler = vmConfiguration.jitScheme();
    }

    /**
     * This method initializes the adaptive compilation system, either at prototyping time or
     * at VM startup time. This implementation creates daemon threads to handle asynchronous
     * compilations.
     *
     * @param phase the phase of VM starting up.
     */
    @Override
    public void initialize(MaxineVM.Phase phase) {
        if (MaxineVM.isPrototyping()) {
            // TODO: use more than one thread during prototyping
            final CompilationThread compilationThread = new CompilationThread();
            compilationThread.setDaemon(true);
            compilationThread.start();
        } else if (phase == MaxineVM.Phase.STARTING) {
            if (jitOption.isPresent()) {
                defaultRecompilationThreshold0 = RECOMPILATION_DISABLED;
                defaultRecompilationThreshold1 = RECOMPILATION_DISABLED;
                setMode(Mode.JIT);
            } else if (optOption.isPresent()) {
                defaultRecompilationThreshold0 = RECOMPILATION_DISABLED;
                defaultRecompilationThreshold1 = RECOMPILATION_DISABLED;
                setMode(Mode.OPTIMIZED);
            } else {
                defaultRecompilationThreshold0 = thresholdOption.getValue();
                setMode(Mode.MIXED);
            }

            // only use one compilation thread after starting
            final CompilationThread compilationThread = new CompilationThread();
            compilationThread.setDaemon(true);
            compilationThread.start();
        }
    }

    /**
     * This method builds a new method instrumentation object for the specified method, if one
     * does not already exist.
     *
     * @param classMethodActor the method for which to make the instrumentation
     * @return the canonical method instrumentation object associated with the specified method
     */
    public MethodInstrumentation makeMethodInstrumentation(ClassMethodActor classMethodActor) {
        return makeMethodState(classMethodActor).makeMethodInstrumentation();
    }

    /**
     * This method gets a method instrumentation object associated with the specified method, if it exists.
     *
     * @param classMethodActor the method for which to get the method instrumentation
     * @return the method instrumentation associated with the specified method if it exists; null otherwise
     */
    public MethodInstrumentation getMethodInstrumentation(ClassMethodActor classMethodActor) {
        final AdaptiveMethodState methodState = getMethodState(classMethodActor);
        if (methodState != null) {
            return methodState.getMethodInstrumentation();
        }
        return null;
    }

    /**
     * Get the method state and cast it to a {@code AdaptiveMethodState} instance.
     *
     * @param classMethodActor the method for which to get the method state
     * @return the method state as an instance of the {@code AdaptiveMethodState} class
     */
    private AdaptiveMethodState getMethodState(ClassMethodActor classMethodActor) {
        synchronized (classMethodActor) {
            return (AdaptiveMethodState) classMethodActor.methodState();
        }
    }

    /**
     * Gets the method state object for the specified class method actor, building it if necessary.
     *
     * @param classMethodActor the class method for which to make the method state
     * @return the method state associated with the specified method
     */
    public AdaptiveMethodState makeMethodState(ClassMethodActor classMethodActor) {
        synchronized (classMethodActor) {
            AdaptiveMethodState methodState = (AdaptiveMethodState) classMethodActor.methodState();
            if (methodState == null) {
                methodState = new AdaptiveMethodState(this, classMethodActor);
                classMethodActor.setMethodState(methodState);
            }
            return methodState;
        }
    }

    /**
     * Performs a compilation of the specified method, waiting for the compilation to finish.
     *
     * @param classMethodActor the method to compile
     * @param compilationDirective the compilation directive specifying the compiler to be used
     * @return the target method that results from compiling the specified method
     */
    public TargetMethod synchronousCompile(ClassMethodActor classMethodActor, CompilationDirective compilationDirective) {
        final AdaptiveMethodState methodState = makeMethodState(classMethodActor);
        Compilation compilation = null;
        synchronized (methodState) {
            if (methodState.currentCompilation(compilationDirective) != null) {
                try {
                    // wait for the existing compilation to finish
                    return methodState.currentCompilation(compilationDirective).get();
                } catch (InterruptedException e) {
                    return null;
                }
            }
            if (methodState.currentTargetMethod(compilationDirective) != null) {
                return methodState.currentTargetMethod(compilationDirective);
            }
            compilation = new Compilation(this, methodState, compilationDirective);
            methodState.setCurrentCompilation(compilation, compilationDirective);
        }
        // perform a synchronous compile
        perform(compilation, true);

        // return the result
        return methodState.currentTargetMethod(compilationDirective);
    }

    /**
     * Performs a compilation of the specified method in the background.
     *
     * @param classMethodActor the method to compile
     * @param compilationDirective the compilation directive specifying the compiler to be used
     * @return a {@code Future} object that can be used to retrieve the result of the compilation later
     */
    public Future<TargetMethod> asynchronousCompile(ClassMethodActor classMethodActor, CompilationDirective compilationDirective) {
        final AdaptiveMethodState methodState = makeMethodState(classMethodActor);
        Compilation compilation = null;
        synchronized (methodState) {
            if (methodState.currentCompilation(compilationDirective) != null) {
                return methodState.currentCompilation(compilationDirective);
            }
            if (methodState.currentTargetMethod(compilationDirective) != null) {
                compilation = new Compilation(this, methodState, compilationDirective);
                compilation.done = true;
                return compilation;
            }
            compilation = new Compilation(this, methodState, compilationDirective);
            methodState.setCurrentCompilation(compilation, compilationDirective);
        }
        // perform an asynchronous compile
        return perform(compilation, false);
    }

    /**
     * This method allows an observer to be notified before the compilation of a method begins.
     */
    public synchronized void addObserver(CompilationObserver observer) {
        if (observers == null) {
            observers = new LinkedList<CompilationObserver>();
        }
        observers.add(observer);
    }

    /**
     * This method allows an observer to be notified after the compilation of a method completes.
     */
    public synchronized void removeObserver(CompilationObserver observer) {
        if (observers != null) {
            observers.remove(observer);
            if (observers.size() == 0) {
                observers = null;
            }
        }
    }

    synchronized void observeBeforeCompilation(Compilation compilation, DynamicCompilerScheme compiler) {
        if (observers != null) {
            for (CompilationObserver observer : observers) {
                observer.observeBeforeCompilation(compilation.methodState.classMethodActor(), compilation.compilationDirective, compiler);
            }
        }
    }

    synchronized void observeAfterCompilation(Compilation compilation, DynamicCompilerScheme compiler, TargetMethod targetMethod) {
        if (observers != null) {
            for (CompilationObserver observer : observers) {
                observer.observeAfterCompilation(compilation.methodState.classMethodActor(), compilation.compilationDirective, compiler, targetMethod);
            }
        }
    }

    /**
     * Perform a compilation in either synchronous or asynchronous mode.
     *
     * @param compilation the compilation to perform
     * @param synchronous {@code true} if the compilation should be performed immediately; {@code false} if it should be
     *            queued
     * @return the compilation
     */
    @INLINE
    private Compilation perform(Compilation compilation, boolean synchronous) {
        synchronized (pending) {
            // a compilation is required.
            submitted++;
            if (!synchronous) {
                pending.offer(compilation);
                pending.notify();
            }
        }
        if (synchronous) {
            compilation.compile(compilation.compilationDirective);
        }
        return compilation;
    }

    /**
     * Checks whether there is currently a compilation being performed.
     *
     * @return {@code true} if there is currently a compilation pending or being performed; {@code false} otherwise
     */
    public boolean isCompiling() {
        synchronized (pending) {
            synchronized (completionLock) {
                return submitted > completed;
            }
        }
    }

    /**
     * Select the appropriate compiler based on the current state of the method.
     *
     * @param methodState the state of the method
     * @return the compiler that should be used to perform the next compilation of the method
     */
    DynamicCompilerScheme selectCompiler(AdaptiveMethodState methodState) {
        final ClassMethodActor classMethodActor = methodState.classMethodActor();
        if (classMethodActor.isUnsafe()) {
            // for unsafe methods there is no other choice no matter what, since the JIT cannot handle unsafe features
            return optimizingCompiler;
        }
        if (MaxineVM.isPrototyping()) {
            // if we are prototyping, then always use the prototype compiler
            // unless forced to use the JIT (e.g. for testing purposes)
            if (CompiledPrototype.jitCompile(classMethodActor)) {
                return jitCompiler;
            } else if (CompiledPrototype.c1xCompile(classMethodActor)) {
                final DynamicCompilerScheme result = new C1XCompiler(vmConfiguration());
                result.initialize(Phase.PROTOTYPING);
                return result;
            }

            return prototypeCompiler;
        }

        // templates should only be compiled at prototyping time
        assert !classMethodActor.isTemplate();

        if (methodState.currentTargetMethod() == null) {
            if (classMethodActor.isSynthetic() && !classMethodActor.isNative()) {
                // we must at first use the JIT for reflective invocation stubs, otherwise meta-evaluation may not
                // terminate
                return jitCompiler;
            }
            if (mode == Mode.OPTIMIZED) {
                return optimizingCompiler;
            }
        } else if (mode != Mode.JIT) {
            return optimizingCompiler;
        }
        return jitCompiler;
    }

    /**
     * This method provides a hint to the adaptive compilation system that it should increase the optimization level for
     * a particular method, potentially recompiling it if it has already been compiled. This method is typically called
     * in response to dynamic feedback mechanisms such as a method invocation counter.
     *
     * @param classMethodActor the method for which to increase the optimization level
     * @param synchronous a boolean indicating whether any recompilation should be performed synchronously (i.e.
     *            immediately) or asynchronously (i.e. queued and potentially performed later by another thread)
     */
    public static void increaseOptimizationLevel(ClassMethodActor classMethodActor, boolean synchronous, CompilationDirective compilationDirective) {
        final CompilationScheme compilationScheme = VMConfiguration.target().compilationScheme();
        if (compilationScheme instanceof AdaptiveCompilationScheme) {
            ((AdaptiveCompilationScheme) compilationScheme).reoptimize(classMethodActor, synchronous, compilationDirective);
        }
    }

    /**
     * This method reoptimizes the specified method with a higher level of optimization, choosing a more optimizing
     * compiler and/or more aggressive optimizations. This method can be used in either synchronous mode (i.e.
     * compilation is performed right away) or asynchronous mode (i.e. compilation is performed in the background).
     *
     * @param classMethodActor the method to be reoptimized
     * @param synchronous a boolean indicating whether the compilation should be performed immediately or queued for
     *            later
     */
    public void reoptimize(ClassMethodActor classMethodActor, boolean synchronous, CompilationDirective compilationDirective) {
        if (gcOnRecompileOption.getValue()) {
            System.gc();
        }
        final AdaptiveMethodState methodState = makeMethodState(classMethodActor);
        Compilation compilation = null;
        synchronized (methodState) {
            final TargetMethod targetMethod = methodState.currentTargetMethod();
            if (methodState.currentCompilation(CompilationDirective.DEFAULT) != null) {
                if (synchronous) {
                    try {
                        // wait for current compilation to finish
                        methodState.currentCompilation(CompilationDirective.DEFAULT).get();
                    } catch (InterruptedException e) {
                        // do nothing
                    }
                }
                return;
            }
            if (targetMethod != null && targetMethod.compilerScheme() == optimizingCompiler) {
                // method is already optimized.
                return;
            }
            // start a new compilation with the optimizing compiler
            compilation = new Compilation(this, methodState, compilationDirective);
            compilation.compiler = optimizingCompiler;
            methodState.setCurrentCompilation(compilation, compilationDirective);
        }
        perform(compilation, synchronous);
    }

    /**
     * This class implements a daemon thread that performs compilations in the background. Depending on the compiler
     * configuration, multiple compilation threads may be working in parallel.
     *
     * @author Ben L. Titzer
     */
    protected class CompilationThread extends Thread {

        protected CompilationThread() {
            super("compile");
            setDaemon(true);
        }

        /**
         * The current compilation being performed by this thread.
         */
        Compilation compilation;

        /**
         * Continuously polls the compilation queue for work, performing compilations as they are removed from the
         * queue.
         */
        @Override
        public void run() {
            while (true) {
                try {
                    compileOne();
                } catch (InterruptedException e) {
                    // do nothing.
                } catch (Throwable t) {
                    Log.print("Exception during compilation of " + compilation.methodState.classMethodActor() + " " + t.getClass());
                    t.printStackTrace();
                }
            }
        }

        /**
         * Polls the compilation queue and performs a single compilation.
         *
         * @throws InterruptedException if the thread was interrupted waiting on the queue
         */
        void compileOne() throws InterruptedException {
            compilation = null;
            synchronized (pending) {
                while (compilation == null) {
                    compilation = pending.poll();
                    if (compilation == null) {
                        pending.wait();
                    }
                }
            }
            compilation.compile(compilation.compilationDirective);
            compilation = null;
        }
    }
}
