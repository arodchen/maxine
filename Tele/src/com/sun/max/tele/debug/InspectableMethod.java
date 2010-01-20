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
package com.sun.max.tele.debug;

import com.sun.max.tele.*;
import com.sun.max.tele.method.*;
import com.sun.max.tele.object.*;

/**
 * Wrapper for a remote method in the VM that is intended to be accessed
 * by clients, for example by setting breakpoints at predefined locations.
 *
 * @author Michael Van De Vanter
 */
public final class InspectableMethod implements MaxInspectableMethod {

    private final TeleMethodAccess teleMethodAccess;
    private final String description;
    private TeleClassMethodActor teleClassMethodActor;

    public InspectableMethod(TeleMethodAccess teleMethodAccess, String description) {
        this.teleMethodAccess = teleMethodAccess;
        this.description = description;
    }

    public TeleClassMethodActor teleClassMethodActor() {
        // Initialize this lazily; requires that some other Inspector machinery be in operation.
        if (teleClassMethodActor == null) {
            teleClassMethodActor = teleMethodAccess.teleClassMethodActor();
        }
        return teleClassMethodActor;
    }

    public String description() {
        return description;
    }

}
