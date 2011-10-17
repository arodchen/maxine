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
package com.oracle.max.graal.graph;

import java.util.Arrays;
import java.util.Iterator;

public final class NodeReverseList implements Iterable<Node> {

    protected static final Node[] EMPTY_NODE_ARRAY = new Node[0];

    protected Node[] nodes = EMPTY_NODE_ARRAY;
    private int size = 0;
    private int modCount = 0;

    NodeReverseList() {
        this.size = 0;
        this.nodes = EMPTY_NODE_ARRAY;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    protected void incModCount() {
        modCount++;
    }

    public boolean add(Node node) {
        incModCount();
        if (size == nodes.length) {
            nodes = Arrays.copyOf(nodes, nodes.length * 2 + 1);
        }
        nodes[size++] = node;
        return true;
    }

    void copyAndClear(NodeReverseList other) {
        incModCount();
        other.incModCount();
        nodes = other.nodes;
        size = other.size;
        nodes = EMPTY_NODE_ARRAY;
        size = 0;
    }

    public void clear() {
        incModCount();
        nodes = EMPTY_NODE_ARRAY;
        size = 0;
    }

    boolean remove(Node node) {
        int i = 0;
        incModCount();
        while (i < size && nodes[i] != node) {
            i++;
        }
        if (i < size) {
            i++;
            while (i < size) {
                nodes[i - 1] = nodes[i];
                i++;
            }
            nodes[--size] = null;
            return true;
        } else {
            return false;
        }
    }

    public Node remove(int index) {
        assert index >= 0 && index < size;
        Node oldValue = nodes[index];
        int i = index + 1;
        incModCount();
        while (i < size) {
            nodes[i - 1] = nodes[i];
            i++;
        }
        nodes[--size] = null;
        return oldValue;
    }

    boolean replaceFirst(Node node, Node other) {
        for (int i = 0; i < size; i++) {
            if (nodes[i] == node) {
                nodes[i] = other;
                return true;
            }
        }
        return false;
    }

    @Override
    public Iterator<Node> iterator() {
        return new Iterator<Node>() {
            private final int expectedModCount = NodeReverseList.this.modCount;
            private int index = 0;

            @Override
            public boolean hasNext() {
                assert expectedModCount == NodeReverseList.this.modCount;
                return index < NodeReverseList.this.size;
            }

            @Override
            public Node next() {
                assert expectedModCount == NodeReverseList.this.modCount;
                return NodeReverseList.this.nodes[index++];
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }

    public boolean contains(Node other) {
        for (int i = 0; i < size; i++) {
            if (nodes[i] == other) {
                return true;
            }
        }
        return false;
    }

    public Iterable<Node> snapshot() {
        return new Iterable<Node>() {

            @Override
            public Iterator<Node> iterator() {
                return new Iterator<Node>() {
                    private Node[] nodesCopy = Arrays.copyOf(NodeReverseList.this.nodes, NodeReverseList.this.size);
                    private int index = 0;

                    @Override
                    public boolean hasNext() {
                        return index < nodesCopy.length;
                    }

                    @Override
                    public Node next() {
                        return nodesCopy[index++];
                    }

                    @Override
                    public void remove() {
                        throw new UnsupportedOperationException();
                    }
                };
            }
        };
    }

    @Override
    public String toString() {
        StringBuilder str = new StringBuilder();
        str.append('[');
        for (int i = 0; i < size; i++) {
            if (i > 0) {
                str.append(", ");
            }
            str.append(nodes[i]);
        }
        str.append(']');
        return str.toString();
    }

}
