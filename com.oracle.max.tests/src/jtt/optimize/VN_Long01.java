/*
 * Copyright (c) 2009, 2012, Oracle and/or its affiliates. All rights reserved.
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
package jtt.optimize;

/*
 * Tests value numbering of long operations.
 * @Harness: java
 * @Runs: 0=6L; 1=0L; 2=36L; 3=1L; 4=0L; 5=5L; 6=7L; 7=0L
 */
public class VN_Long01 {
    public static long test(int arg) {
        if (arg == 0) {
            return add(arg);
        }
        if (arg == 1) {
            return sub(arg);
        }
        if (arg == 2) {
            return mul(arg);
        }
        if (arg == 3) {
            return div(arg);
        }
        if (arg == 4) {
            return mod(arg);
        }
        if (arg == 5) {
            return and(arg);
        }
        if (arg == 6) {
            return or(arg);
        }
        if (arg == 7) {
            return xor(arg);
        }
        return 0;
    }
    public static long add(long x) {
        long t = x + 3;
        long u = x + 3;
        return t + u;
    }
    public static long sub(long x) {
        long t = x - 3;
        long u = x - 3;
        return t - u;
    }
    public static long mul(long x) {
        long t = x * 3;
        long u = x * 3;
        return t * u;
    }
    public static long div(long x) {
        long t = 9 / x;
        long u = 9 / x;
        return t / u;
    }
    public static long mod(long x) {
        long t = 7 % x;
        long u = 7 % x;
        return t % u;
    }
    public static long and(long x) {
        long t = 7 & x;
        long u = 7 & x;
        return t & u;
    }
    public static long or(long x) {
        long t = 7 | x;
        long u = 7 | x;
        return t | u;
    }
    public static long xor(long x) {
        long t = 7 ^ x;
        long u = 7 ^ x;
        return t ^ u;
    }
}
