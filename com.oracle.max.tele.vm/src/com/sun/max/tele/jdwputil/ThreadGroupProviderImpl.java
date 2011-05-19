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
package com.sun.max.tele.jdwputil;

import java.util.*;

import com.sun.max.jdwp.vm.proxy.*;
import com.sun.max.tele.*;
import com.sun.max.tele.debug.*;

/**
 * Represents a thread group used for logical grouping in the JDWP
 * protocol. Currently we only distinguish between Java and native threads.
 *
 * @author Thomas Wuerthinger
 * @author Michael Van De Vanter
 */
public class ThreadGroupProviderImpl implements ThreadGroupProvider {

    private final TeleVM teleVM;
    private final boolean containsJavaThreads;

    public ThreadGroupProviderImpl(TeleVM teleVM, boolean b) {
        this.teleVM = teleVM;
        this.containsJavaThreads = b;
    }

    public String getName() {
        return containsJavaThreads ? "Java Threads" : "Native Threads";
    }

    public ThreadGroupProvider getParent() {
        return null;
    }

    public ThreadProvider[] getThreadChildren() {
        final List<ThreadProvider> result = new LinkedList<ThreadProvider>();
        for (TeleNativeThread t : teleVM.teleProcess().threads()) {
            if (t.isJava() == containsJavaThreads) {
                result.add(t);
            }
        }
        return result.toArray(new ThreadProvider[result.size()]);
    }

    public ThreadGroupProvider[] getThreadGroupChildren() {
        return new ThreadGroupProvider[0];
    }

    public ReferenceTypeProvider getReferenceType() {
        assert false : "No reference type for thread groups available!";
        return null;
    }

}
