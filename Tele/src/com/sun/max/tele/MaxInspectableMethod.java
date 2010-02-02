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
package com.sun.max.tele;

import com.sun.max.tele.object.*;

/**
 * Access to a remote method in the VM that is predefined for convenient access
 * by clients, for example by setting breakpoints at generally useful locations.
 * <br>
 * These are intended to be methods loaded and compiled into the boot image,
 * and which will never be dynamically recompiled.
 *
 * @author Michael Van De Vanter
 */
public interface MaxInspectableMethod {

    /**
     * @return the canonical surrogate in the VM for the method
     */
    TeleClassMethodActor teleClassMethodActor();

    /**
     * A textual description of the role played by the method.
     *
     * @return a human readable description of the method, intended to describe
     * the function of the method on a menu.
     */
    String description();

}
