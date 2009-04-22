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
package com.sun.max.vm.layout.ohm;

import java.lang.reflect.*;

import com.sun.max.annotate.*;
import com.sun.max.unsafe.*;
import com.sun.max.vm.actor.holder.*;
import com.sun.max.vm.grip.*;
import com.sun.max.vm.layout.*;
import com.sun.max.vm.object.host.*;
import com.sun.max.vm.type.*;
import com.sun.max.vm.value.*;

/**
 * @author Bernd Mathiske
 */
public abstract class OhmArrayLayout<Value_Type extends Value<Value_Type>> extends OhmArrayHeaderLayout implements ArrayLayout<Value_Type> {

    protected final Kind<Value_Type> _elementKind;

    public OhmArrayLayout(GripScheme gripScheme, Kind<Value_Type> elementKind) {
        super(gripScheme);
        _elementKind = elementKind;
    }

    @INLINE
    public final Kind<Value_Type> elementKind() {
        return _elementKind;
    }

    public Layout.Category category() {
        return Layout.Category.ARRAY;
    }

    @Override
    public final boolean isReferenceArrayLayout() {
        @JavacSyntax("Incomparable types bug")
        final Kind rawKind = _elementKind;
        return rawKind == Kind.REFERENCE;
    }

    @INLINE
    public final int elementSize() {
        return elementKind().size();
    }

    @INLINE
    protected final int originDisplacement() {
        return headerSize();
    }

    @INLINE
    public final Offset getElementOffsetFromOrigin(int index) {
        return getElementOffsetInCell(index);
    }

    @INLINE
    public final Offset getElementOffsetInCell(int index) {
        // Converting to 'Offset' before multiplication to avoid overflow:
        return Offset.fromInt(index).times(elementSize()).plus(headerSize());
    }

    @INLINE
    public final Size getArraySize(int length) {
        return getElementOffsetInCell(length).aligned().asSize();
    }

    @INLINE
    public final Size specificSize(Accessor accessor) {
        return getArraySize(readLength(accessor));
    }

    @PROTOTYPE_ONLY
    @Override
    public void visitHeader(ObjectCellVisitor visitor, Object array) {
        super.visitHeader(visitor, array);
        visitor.visitHeaderField(_lengthOffset, "length", JavaTypeDescriptor.INT, IntValue.from(HostObjectAccess.getArrayLength(array)));
    }

    @PROTOTYPE_ONLY
    private void visitElements(ObjectCellVisitor visitor, Object array) {
        final int length = Array.getLength(array);
        final Hub hub = HostObjectAccess.readHub(array);
        final Kind elementKind = hub.classActor().componentClassActor().kind();
        if (elementKind == Kind.REFERENCE) {
            for (int i = 0; i < length; i++) {
                final Object object = Array.get(array, i);
                visitor.visitElement(getElementOffsetInCell(i).toInt(), i, ReferenceValue.from(object));
            }
        } else {
            for (int i = 0; i < length; i++) {
                final Object boxedJavaValue = Array.get(array, i);
                final Value value = elementKind.asValue(boxedJavaValue);
                visitor.visitElement(getElementOffsetInCell(i).toInt(), i, value);
            }
        }
    }

    @PROTOTYPE_ONLY
    public void visitObjectCell(Object array, ObjectCellVisitor visitor) {
        visitHeader(visitor, array);
        visitElements(visitor, array);
    }

    public Value readValue(Kind kind, ObjectMirror mirror, int offset) {
        if (offset == _lengthOffset) {
            return mirror.readArrayLength();
        }
        final Value value = readHeaderValue(mirror, offset);
        if (value != null) {
            return value;
        }
        assert kind.isPrimitiveOfSameSizeAs(_elementKind);
        final int index = (offset - headerSize()) / kind.size();
        return mirror.readElement(kind, index);
    }

    public void writeValue(Kind kind, ObjectMirror mirror, int offset, Value value) {
        assert kind.isPrimitiveOfSameSizeAs(value.kind());
        if (offset == _lengthOffset) {
            mirror.writeArrayLength(value);
            return;
        }
        if (writeHeaderValue(mirror, offset, value)) {
            return;
        }
        assert kind.isPrimitiveOfSameSizeAs(_elementKind);
        final int index = (offset - headerSize()) / elementSize();
        mirror.writeElement(kind, index, value);
    }

}
