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
package com.sun.max.vm;

import static com.sun.max.vm.MaxineVM.*;
import static com.sun.max.vm.compiler.CallEntryPoint.*;

import java.io.*;
import java.util.*;

import com.sun.max.annotate.*;
import com.sun.max.config.*;
import com.sun.max.platform.*;
import com.sun.max.program.*;
import com.sun.max.vm.compiler.*;
import com.sun.max.vm.compiler.target.*;
import com.sun.max.vm.heap.*;
import com.sun.max.vm.layout.*;
import com.sun.max.vm.monitor.*;
import com.sun.max.vm.reference.*;
import com.sun.max.vm.run.*;

/**
 * The configuration of a VM, which includes the schemes, safepoint mechanism, etc.
 *
 * @author Bernd Mathiske
 * @author Ben L. Titzer
 * @author Doug Simon
 */
public final class VMConfiguration {

    public final BuildLevel buildLevel;

    @HOSTED_ONLY public final BootImagePackage referencePackage;
    @HOSTED_ONLY public final BootImagePackage layoutPackage;
    @HOSTED_ONLY public final BootImagePackage heapPackage;
    @HOSTED_ONLY public final BootImagePackage monitorPackage;
    @HOSTED_ONLY public final BootImagePackage optCompilerPackage;
    @HOSTED_ONLY public final BootImagePackage jitCompilerPackage;
    @HOSTED_ONLY public final BootImagePackage compilationPackage;
    @HOSTED_ONLY public final BootImagePackage runPackage;

    private ArrayList<VMScheme> vmSchemes = new ArrayList<VMScheme>();
    private boolean areSchemesLoadedAndInstantiated = false;

    @CONSTANT_WHEN_NOT_ZERO private ReferenceScheme referenceScheme;
    @CONSTANT_WHEN_NOT_ZERO private LayoutScheme layoutScheme;
    @CONSTANT_WHEN_NOT_ZERO private HeapScheme heapScheme;
    @CONSTANT_WHEN_NOT_ZERO private MonitorScheme monitorScheme;
    @CONSTANT_WHEN_NOT_ZERO private RuntimeCompilerScheme jitCompilerScheme;
    @CONSTANT_WHEN_NOT_ZERO private RuntimeCompilerScheme optCompilerScheme;
    @CONSTANT_WHEN_NOT_ZERO private CompilationScheme compilationScheme;
    @CONSTANT_WHEN_NOT_ZERO private RunScheme runScheme;

    public VMConfiguration(BuildLevel buildLevel,
                           Platform platform,
                           BootImagePackage referencePackage,
                           BootImagePackage layoutPackage,
                           BootImagePackage heapPackage,
                           BootImagePackage monitorPackage,
                           BootImagePackage optCompilerPackage,
                           BootImagePackage jitCompilerPackage,
                           BootImagePackage compilationPackage,
                           BootImagePackage runPackage) {
        assert optCompilerPackage != null;
        this.buildLevel = buildLevel;
        this.referencePackage = referencePackage;
        this.layoutPackage = layoutPackage;
        this.heapPackage = heapPackage;
        this.monitorPackage = monitorPackage;
        this.optCompilerPackage = optCompilerPackage;
        this.jitCompilerPackage = jitCompilerPackage == null ? optCompilerPackage : jitCompilerPackage;
        this.compilationPackage = compilationPackage;
        this.runPackage = runPackage;
    }

    @INLINE public ReferenceScheme       referenceScheme()   { return referenceScheme;   }
    @INLINE public LayoutScheme          layoutScheme()      { return layoutScheme;      }
    @INLINE public HeapScheme            heapScheme()        { return heapScheme;        }
    @INLINE public MonitorScheme         monitorScheme()     { return monitorScheme;     }
    @INLINE public RuntimeCompilerScheme jitCompilerScheme() { return jitCompilerScheme; }
    @INLINE public RuntimeCompilerScheme optCompilerScheme() { return optCompilerScheme; }
    @INLINE public CompilationScheme     compilationScheme() { return compilationScheme; }
    @INLINE public RunScheme             runScheme()         { return runScheme;         }

    @HOSTED_ONLY
    public List<BootImagePackage> packages() {
        return Arrays.asList(new BootImagePackage[] {
            referencePackage,
            layoutPackage,
            heapPackage,
            monitorPackage,
            compilationPackage,
            optCompilerPackage,
            jitCompilerPackage,
            runPackage});
    }

    public List<VMScheme> vmSchemes() {
        return vmSchemes;
    }

    @HOSTED_ONLY
    private <VMScheme_Type extends VMScheme> VMScheme_Type loadAndInstantiateScheme(List<VMScheme> loadedSchemes, BootImagePackage p, Class<VMScheme_Type> vmSchemeType) {
        if (p == null) {
            throw ProgramError.unexpected("Package not found for scheme: " + vmSchemeType.getSimpleName());
        }

        if (loadedSchemes != null) {
            Class< ? extends VMScheme_Type> impl = p.loadSchemeImplementation(vmSchemeType);
            for (VMScheme vmScheme : loadedSchemes) {
                if (vmScheme.getClass() == impl) {
                    vmSchemes.add(vmScheme);
                    return vmSchemeType.cast(vmScheme);
                }
            }
        }

        // If one implementation class in package p implements multiple schemes, then only a single
        // instance of that class is created and shared by the VM configuration for all the schemes
        // it implements.
        for (VMScheme vmScheme : vmSchemes) {
            if (vmSchemeType.isInstance(vmScheme) && p.loadSchemeImplementation(vmSchemeType).equals(vmScheme.getClass())) {
                return vmSchemeType.cast(vmScheme);
            }
        }

        final VMScheme_Type vmScheme = p.loadAndInstantiateScheme(vmSchemeType);
        vmSchemes.add(vmScheme);
        return vmScheme;
    }

    /**
     * Loads and instantiates all the schemes of this configuration.
     *
     * @param loadedSchemes the set of schemes already loaded and instantiated in this process. If non-{@code null},
     *            this list is used to prevent any given scheme implementation from being instantiated more than once.
     */
    @HOSTED_ONLY
    public void loadAndInstantiateSchemes(List<VMScheme> loadedSchemes) {
        if (areSchemesLoadedAndInstantiated) {
            return;
        }

        referenceScheme = loadAndInstantiateScheme(loadedSchemes, referencePackage, ReferenceScheme.class);
        layoutScheme = loadAndInstantiateScheme(loadedSchemes, layoutPackage, LayoutScheme.class);
        monitorScheme = loadAndInstantiateScheme(loadedSchemes, monitorPackage, MonitorScheme.class);
        heapScheme = loadAndInstantiateScheme(loadedSchemes, heapPackage, HeapScheme.class);
        optCompilerScheme = loadAndInstantiateScheme(loadedSchemes, optCompilerPackage, RuntimeCompilerScheme.class);
        if (jitCompilerPackage != optCompilerPackage) {
            jitCompilerScheme = loadAndInstantiateScheme(loadedSchemes, jitCompilerPackage, RuntimeCompilerScheme.class);
        } else {
            jitCompilerScheme = optCompilerScheme;
        }

        if (loadedSchemes == null) {
            // FIXME: This is a hack to avoid adding an "AdapterFrameScheme".
            if (needsAdapters()) {
                OPTIMIZED_ENTRY_POINT.init(8, 8);
                JIT_ENTRY_POINT.init(0, 0);
                VTABLE_ENTRY_POINT.init(OPTIMIZED_ENTRY_POINT);
                // Calls made from a C_ENTRY_POINT method link to the OPTIMIZED_ENTRY_POINT of the callee
                C_ENTRY_POINT.init(0, OPTIMIZED_ENTRY_POINT.offset());
            } else {
                CallEntryPoint.initAllToZero();
            }
        }

        compilationScheme = loadAndInstantiateScheme(loadedSchemes, compilationPackage, CompilationScheme.class);
        runScheme = loadAndInstantiateScheme(loadedSchemes, runPackage, RunScheme.class);
        areSchemesLoadedAndInstantiated = true;
    }

    /**
     * Determines if any pair of compilers in this configuration use different
     * calling conventions and thus mandate the use of {@linkplain Adapter adapters}
     * to adapt the arguments when a call crosses a calling convention boundary.
     */
    public boolean needsAdapters() {
        return optCompilerScheme.calleeEntryPoint() != jitCompilerScheme.calleeEntryPoint();
    }

    public void initializeSchemes(MaxineVM.Phase phase) {
        for (int i = 0; i < vmSchemes.size(); i++) {
            vmSchemes.get(i).initialize(phase);
        }
    }

    /**
     * Convenience method for accessing the configuration associated with the
     * current {@linkplain MaxineVM#vm() VM} context.
     * @return
     */
    @FOLD
    public static VMConfiguration vmConfig() {
        return vm().config;
    }

    @Override
    public String toString() {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        print(new PrintStream(baos), "");
        return baos.toString();
    }

    public void print(PrintStream out, String indent) {
        out.println(indent + "Build level: " + buildLevel);
        for (VMScheme vmScheme : vmSchemes()) {
            final String specification = vmScheme.specification().getSimpleName();
            out.println(indent + specification.replace("Scheme", " scheme") + ": " + vmScheme.getClass().getName());
        }
    }

    /**
     * Use {@link MaxineVM#isDebug()} instead of calling this directly.
     */
    @FOLD
    public boolean debugging() {
        return buildLevel == BuildLevel.DEBUG;
    }

    /**
     * Determines if a given package is considered part of the VM under this VM configuration.
     * @return {@code true} if the specified package is part of this VM in this configuration
     */
    @HOSTED_ONLY
    public boolean isMaxineBootImagePackage(BootImagePackage pkg) {
        if (pkg == null) {
            return false;
        }
        if (pkg instanceof BootImagePackage) {
            final BootImagePackage bootImagePackage = pkg;
            return bootImagePackage.isPartOfMaxineVM(this);
        }
        return false;
    }
}
