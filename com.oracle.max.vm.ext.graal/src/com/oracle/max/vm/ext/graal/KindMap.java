/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
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

/**
 * Map from {@link CiKind} to Graal {@link Kind}.
 */
public class KindMap {

    /**
     * Maps from a Ci kind to a Graal kind.
     * Indexed by {@link com.sun.cri.ci.CiKind#ordinal()}.
     */
    private static com.oracle.graal.api.meta.Kind[] ciToGraal;

    /**
     * Maps from a Graal kind to a Ci kind.
     * Indexed by {@link com.oracle.graal.api.meta.Kind#ordinal()}.
     *
     */
    private static com.sun.cri.ci.CiKind[] graalToCi;

    static {
        graalToCi = new com.sun.cri.ci.CiKind[com.oracle.graal.api.meta.Kind.values().length];
        ciToGraal = new com.oracle.graal.api.meta.Kind[com.sun.cri.ci.CiKind.VALUES.length];
        for (com.oracle.graal.api.meta.Kind graalKind : com.oracle.graal.api.meta.Kind.values()) {
            graalToCi[graalKind.ordinal()] = null;
        }
        for (com.sun.cri.ci.CiKind maxKind : com.sun.cri.ci.CiKind.VALUES) {
            ciToGraal[maxKind.ordinal()] = null;
        }
    }

    public static com.oracle.graal.api.meta.Kind toGraalKind(com.sun.cri.ci.CiKind maxKind) {
        com.oracle.graal.api.meta.Kind result = ciToGraal[maxKind.ordinal()];
        if (result == null) {
            // CheckStyle : Stop
            switch (maxKind) {
                case Boolean: result = com.oracle.graal.api.meta.Kind.Boolean; break;
                case Byte: result = com.oracle.graal.api.meta.Kind.Byte; break;
                case Char: result = com.oracle.graal.api.meta.Kind.Char; break;
                case Short: result = com.oracle.graal.api.meta.Kind.Short; break;
                case Int: result = com.oracle.graal.api.meta.Kind.Int; break;
                case Long: result = com.oracle.graal.api.meta.Kind.Long; break;
                case Float: result = com.oracle.graal.api.meta.Kind.Float; break;
                case Double: result = com.oracle.graal.api.meta.Kind.Double; break;
                case Void: result = com.oracle.graal.api.meta.Kind.Void; break;
                case Object: result = com.oracle.graal.api.meta.Kind.Object; break;
                case Jsr: result = com.oracle.graal.api.meta.Kind.Jsr; break;
                case Illegal: result = com.oracle.graal.api.meta.Kind.Illegal; break;
            }
            // CheckStyle : Resume
            ciToGraal[maxKind.ordinal()] = result;
        }
        return result;
    }

    public static com.sun.cri.ci.CiKind toCiKind(com.oracle.graal.api.meta.Kind graalKind) {
        com.sun.cri.ci.CiKind result = graalToCi[graalKind.ordinal()];
        if (result == null) {
            // Checkstyle : stop
            switch (graalKind) {
                case Boolean: result = com.sun.cri.ci.CiKind.Boolean; break;
                case Byte: result = com.sun.cri.ci.CiKind.Byte; break;
                case Char: result = com.sun.cri.ci.CiKind.Char; break;
                case Short: result = com.sun.cri.ci.CiKind.Short; break;
                case Int: result = com.sun.cri.ci.CiKind.Int; break;
                case Long: result = com.sun.cri.ci.CiKind.Long; break;
                case Float: result = com.sun.cri.ci.CiKind.Float; break;
                case Double: result = com.sun.cri.ci.CiKind.Double; break;
                case Void: result = com.sun.cri.ci.CiKind.Void; break;
                case Object: result = com.sun.cri.ci.CiKind.Object; break;
                case Jsr: result = com.sun.cri.ci.CiKind.Jsr; break;
                case Illegal: result = com.sun.cri.ci.CiKind.Illegal; break;
            }
            // Checkstyle : resume
            graalToCi[graalKind.ordinal()] = result;
        }
        return result;
    }

}
