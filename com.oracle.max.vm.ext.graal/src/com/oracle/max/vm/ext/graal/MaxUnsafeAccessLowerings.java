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

import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.nodes.HeapAccess.WriteBarrierType;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;
import com.oracle.max.vm.ext.graal.nodes.*;
import com.oracle.max.vm.ext.graal.snippets.*;

class MaxUnsafeAccessLowerings {

    static void registerLowerings(Map<Class< ? extends Node>, LoweringProvider> lowerings) {
        UnsafeLoadLowering unsafeLoadLowering = new UnsafeLoadLowering();
        lowerings.put(UnsafeLoadNode.class, unsafeLoadLowering);
        UnsafeStoreLowering unsafeStoreLowering = new UnsafeStoreLowering();
        lowerings.put(UnsafeStoreNode.class, unsafeStoreLowering);

        lowerings.put(MaxCompareAndSwapNode.class, new MaxCompareAndSwapLowering());
    }

    private static class UnsafeLoadLowering implements LoweringProvider<UnsafeLoadNode> {

        @Override
        public void lower(UnsafeLoadNode node, LoweringTool tool) {
            StructuredGraph graph = node.graph();
            assert node.kind() != Kind.Illegal;
            lower(graph, node, node.stamp(), node.object(), node.offset(), node.displacement(), node.accessKind());
        }

        void lower(StructuredGraph graph, FixedWithNextNode node, Stamp stamp, ValueNode object, ValueNode offset, int displacement, Kind accessKind) {
            IndexedLocationNode location = IndexedLocationNode.create(LocationIdentity.ANY_LOCATION, accessKind, displacement, offset, graph, 1);
            ReadNode memoryRead = graph.add(new ReadNode(object, location, stamp, AbstractBeginNode.prevBegin(node), WriteBarrierType.NONE, false));
            graph.replaceFixedWithFixed(node, memoryRead);

        }
    }

    private static class UnsafeStoreLowering implements LoweringProvider<UnsafeStoreNode> {

        @Override
        public void lower(UnsafeStoreNode node, LoweringTool tool) {
            StructuredGraph graph = node.graph();
            lower(graph, node, node.stamp(), node.object(), node.offset(), node.value(), node.displacement(), node.accessKind(), node.stateAfter());
        }

        void lower(StructuredGraph graph, FixedWithNextNode node, Stamp stamp, ValueNode object, ValueNode offset, ValueNode value,
                        int displacement, Kind accessKind, FrameState stateAfter) {
            IndexedLocationNode location = IndexedLocationNode.create(LocationIdentity.ANY_LOCATION, accessKind, displacement, offset, graph, 1);
            WriteNode write = graph.add(new WriteNode(object, value, location, WriteBarrierType.NONE, false));
            write.setStateAfter(stateAfter);
            graph.replaceFixedWithFixed(node, write);
        }

    }

    protected static class MaxCompareAndSwapLowering implements LoweringProvider<MaxCompareAndSwapNode> {

        @Override
        public void lower(MaxCompareAndSwapNode node, LoweringTool tool) {
            // The MaxCompareAndSwapNode is lowered to LIR instructions.
        }
    }

}
