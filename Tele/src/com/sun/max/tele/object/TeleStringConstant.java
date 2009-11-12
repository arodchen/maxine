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
package com.sun.max.tele.object;

import com.sun.max.tele.*;
import com.sun.max.vm.classfile.constant.*;
import com.sun.max.vm.reference.*;

/**
 * Canonical surrogate for an object of type {@link StringConstant} in the {@link TeleVM}.
 *
 * @author Michael Van De Vanter
 */
public final class TeleStringConstant extends TelePoolConstant {

    protected TeleStringConstant(TeleVM teleVM, Reference stringConstantReference) {
        super(teleVM, stringConstantReference);
    }

    // The field is final; cache it.
    private String value;

    /**
     * @return a local copy of the string contained in this object in the tele VM
     */
    public String getString() {
        if (value == null) {
            final Reference stringReference = teleVM().teleFields().StringConstant_value.readReference(reference());
            final TeleString teleString = (TeleString) teleVM().makeTeleObject(stringReference);
            value = teleString.getString();
        }
        return value;
    }


    @Override
    public String maxineRole() {
        return "StringConstant";
    }

    @Override
    public String maxineTerseRole() {
        return "StringConst";
    }
}
