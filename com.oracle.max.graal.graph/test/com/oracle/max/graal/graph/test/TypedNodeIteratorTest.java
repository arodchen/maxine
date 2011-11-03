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
package com.oracle.max.graal.graph.test;
import static org.junit.Assert.*;

import java.util.*;

import org.junit.*;

import com.oracle.max.graal.graph.*;



public class TypedNodeIteratorTest {

    @Test
    public void singleNodeTest() {
        Graph<TestNode> graph = new Graph<TestNode>(new TestNode("a"));
        assertTrue(graph.hasNode(TestNode.class));
        assertEquals("a", toString(graph.getNodes(TestNode.class)));
    }

    @Test
    public void deletingNodeTest() {
        TestNode testNode = new TestNode("a");
        Graph<TestNode> graph = new Graph<TestNode>(testNode);
        testNode.delete();
        assertEquals("", toString(graph.getNodes(TestNode.class)));
    }

    @Test
    public void iteratorBehaviorTest() {
        Graph<TestNode> graph = new Graph<TestNode>(new TestNode("a"));
        Iterator<TestNode> iterator = graph.getNodes(TestNode.class).iterator();
        assertTrue(iterator.hasNext());
        assertEquals("a", iterator.next().getName());
        assertFalse(iterator.hasNext());
        graph.add(new TestNode("b"));
        assertTrue(iterator.hasNext());
        assertEquals("b", iterator.next().getName());
        assertFalse(iterator.hasNext());
        TestNode c = new TestNode("c");
        graph.add(c);
        assertTrue(iterator.hasNext());
        c.delete();
        assertFalse(iterator.hasNext());
    }

    @Test
    public void complicatedIterationTest() {
        Graph<TestNode> graph = new Graph<TestNode>(new TestNode("a"));
        for (TestNode tn : graph.getNodes(TestNode.class)) {
            String name = tn.getName();
            for (int i = 0; i < name.length(); ++i) {
                char c = name.charAt(i);
                if (c == 'a') {
                    tn.delete();
                    graph.add(new TestNode("b"));
                    graph.add(new TestNode("c"));
                } else if (c == 'b') {
                    tn.delete();
                } else if (c == 'c') {
                    graph.add(new TestNode("d"));
                    graph.add(new TestNode("e"));
                    graph.add(new TestNode("d"));
                    graph.add(new TestNode("e"));
                    graph.add(new TestNode("e"));
                    graph.add(new TestNode("d"));
                    graph.add(new TestNode("e"));
                    graph.add(new TestNode("d"));
                } else if (c == 'd') {
                    for (TestNode tn2 : graph.getNodes(TestNode.class)) {
                        if (tn2.getName().equals("e")) {
                            tn2.delete();
                        } else if (tn2.getName().equals("c")) {
                            tn2.delete();
                        }
                    }
                } else if (c == 'e') {
                    fail("All e nodes must have been deleted by visiting the d node");
                }
            }
        }
        assertEquals("dddd", toString(graph.getNodes(TestNode.class)));
    }

    @Test
    public void addingNodeDuringIterationTest() {
        Graph<TestNode> graph = new Graph<TestNode>(new TestNode("a"));
        StringBuilder sb = new StringBuilder();
        int z = 0;
        for (TestNode tn : graph.getNodes(TestNode.class)) {
            if (z == 0) {
                graph.add(new TestNode("b"));
            }
            sb.append(tn.getName());
            z++;
        }
        assertEquals(2, z);
        assertEquals("ab", sb.toString());
        z = 0;
        for (TestNode tn : graph.getNodes(TestNode.class)) {
            if (z == 0) {
                graph.add(new TestNode("c"));
            }
            assertNotNull(tn);
            z++;
        }
        assertEquals(3, z);
    }

    private String toString(Iterable<TestNode> nodes) {
        StringBuilder sb = new StringBuilder();
        for (TestNode tn : nodes) {
            sb.append(tn.getName());
        }
        return sb.toString();
    }
}
