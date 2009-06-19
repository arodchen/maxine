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
package com.sun.max.test;

import java.io.*;

/**
 * The {@code ProgressPrinter} class is useful for printing status information
 * to the console while running tests. This class supports three output modes;
 * <i>silent</i>, where only test failures are printed at the end, <i>quiet</i>,
 * where each test produces either an '.' or an 'X' for success or failure, respectively,
 * or <i>verbose</i>, where each test produces a line of output.
 *
 * @author Ben L. Titzer
 */
public class ProgressPrinter {

    private static final String CTRL_RED = "\u001b[0;31m";
    private static final String CTRL_GREEN = "\u001b[0;32m";
    private static final String CTRL_NORM = "\u001b[0;00m";

    public final int total;
    private String current;
    private boolean color;
    private int passed;
    private int finished;
    private int verbose;

    private final PrintStream output;
    private final String[] failedTests;
    private final String[] failedMessages;

    public ProgressPrinter(PrintStream out, int total, int verbose, boolean color) {
        this.output = out;
        this.total = total;
        this.verbose = verbose;
        this.color = color;
        this.failedTests = new String[total];
        this.failedMessages = new String[total];
    }

    /**
     * Begin running the next item.
     * @param test the name of the item to begin running, which is remembered in case the test fails
     */
    public void begin(String test) {
        current = test;
        if (verbose == 2) {
            printTest(test, finished);
            output.print("...");
        }
    }

    /**
     * Finish the current test, indicating success.
     */
    public void pass() {
        passed++;
        if (verbose > 0) {
            output(CTRL_GREEN, '.', "ok");
        }
    }

    /**
     * Finish the current test, indicating failure with the specified error message.
     * @param message the message to associate with the specified test failure
     */
    public void fail(String message) {
        failedTests[finished] = current;
        failedMessages[finished] = message;
        if (verbose > 0) {
            output(CTRL_RED, 'X', "failed");
        }
        if (verbose == 2) {
            this.output.print("\t-> ");
            this.output.println(message);
        }
    }

    /**
     * Sets the verbosity level of this progress printer.
     * @param verbose the new verbosity level of this printer
     */
    public void setVerbose(int verbose) {
        this.verbose = verbose;
    }

    /**
     * Sets the color output behavior of this progress printer.
     * @param color the color output of this printer
     */
    public void setColor(boolean color) {
        this.color = color;
    }

    private void printTest(String test, int i) {
        output.print(i);
        output.print(':');
        if (i < 100) {
            output.print(' ');
        }
        if (i < 100) {
            output.print(' ');
        }
        output.print(' ');
        output.print(test);
    }

    private void output(String ctrl, char ch, String str) {
        finished++;
        if (verbose == 1) {
            control(ctrl);
            output.print(ch);
            control(CTRL_NORM);
            if (finished == total) {
                // just go to next line
                output.println();
            } else if (finished % 50 == 0) {
                output.print(" ");
                output.print(finished);
                output.print(" of ");
                output.print(total);
                output.println();
            } else if (finished % 10 == 0) {
                output.print(' ');
            }
        } else if (verbose == 2) {
            control(ctrl);
            output.print(str);
            control(CTRL_NORM);
            output.println();
        }
    }

    private void control(String ctrl) {
        if (color) {
            output.print(ctrl);
        }
    }

    /**
     * Print a report of the number of tests that passed, and print the messages from test failures
     * in the <i>quiet</i> mode.
     */
    public void report() {
        output.print(passed);
        output.print(" of ");
        output.print(total);
        output.println(" passed");
        if (verbose < 2) {
            for (int i = 0; i < total; i++) {
                if (failedTests[i] != null) {
                    control(CTRL_RED);
                    printTest(failedTests[i], i);
                    control(CTRL_NORM);
                    output.print(": ");
                    output.println(failedMessages[i]);
                }
            }
        }
    }
}
