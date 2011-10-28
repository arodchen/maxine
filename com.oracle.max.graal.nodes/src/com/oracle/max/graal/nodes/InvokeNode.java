/*
 * Copyright (c) 2009, 2011, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.max.graal.nodes;

import com.oracle.max.graal.graph.*;
import com.oracle.max.graal.nodes.extended.*;
import com.oracle.max.graal.nodes.java.*;
import com.oracle.max.graal.nodes.spi.*;
import com.sun.cri.ri.*;

/**
 * The {@code InvokeNode} represents all kinds of method calls.
 */
public final class InvokeNode extends AbstractMemoryCheckpointNode implements Node.IterableNodeType, Invoke {

    @Input private MethodCallTargetNode callTarget;
    @Input private FrameState stateBefore;
    private boolean canInline = true;

    /**
     * Constructs a new Invoke instruction.
     *
     * @param bci the bytecode index of the original invoke (used for debug infos)
     * @param opcode the opcode of the invoke
     * @param target the target method being called
     * @param args the list of instructions producing arguments to the invocation, including the receiver object
     */
    public InvokeNode(MethodCallTargetNode callTarget, FrameState stateBefore) {
        super(callTarget.returnKind().stackKind());
        assert stateBefore != null && callTarget != null;
        this.callTarget = callTarget;
    }

    public boolean canInline() {
        return canInline;
    }

    public void setCanInline(boolean b) {
        canInline = b;
    }

    public MethodCallTargetNode callTarget() {
        return callTarget;
    }

    @Override
    public RiResolvedType declaredType() {
        RiType returnType = callTarget().returnType();
        return (returnType instanceof RiResolvedType) ? ((RiResolvedType) returnType) : null;
    }

    @Override
    public void accept(ValueVisitor v) {
        v.visitInvoke(this);
    }

    @Override
    public String toString(Verbosity verbosity) {
        if (verbosity == Verbosity.Long) {
            return super.toString(Verbosity.Short) + "(bci=" + bci() + ")";
        } else {
            return super.toString(verbosity);
        }
    }

    public int bci() {
        return stateBefore().bci;
    }

    @Override
    public FixedNode node() {
        return this;
    }

    public FrameState stateBefore() {
        return stateBefore;
    }

    @Override
    public FrameState stateDuring() {
        FrameState stateAfter = stateAfter();
        return stateAfter.duplicateModified(bci(), stateAfter.rethrowException(), this.kind);
    }
}
