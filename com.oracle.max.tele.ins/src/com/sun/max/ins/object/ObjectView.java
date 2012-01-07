/*
 * Copyright (c) 2007, 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.sun.max.ins.object;

import static com.sun.max.tele.MaxProcessState.*;

import java.awt.*;

import javax.swing.*;

import com.sun.max.ins.*;
import com.sun.max.ins.gui.*;
import com.sun.max.ins.gui.TableColumnVisibilityPreferences.TableColumnViewPreferenceListener;
import com.sun.max.ins.view.InspectionViews.ViewKind;
import com.sun.max.program.*;
import com.sun.max.tele.*;
import com.sun.max.tele.object.*;
import com.sun.max.tele.reference.*;
import com.sun.max.unsafe.*;

/**
 * A view that displays the content of a low level heap object in the VM.
 */
public abstract class ObjectView<View_Type extends ObjectView> extends AbstractView<View_Type> {

    private static final int TRACE_VALUE = 1;
    private static final ViewKind VIEW_KIND = ViewKind.OBJECT;

    private static ObjectViewManager viewManager;

    public static ObjectViewManager makeViewManager(Inspection inspection) {
        if (viewManager == null) {
            viewManager = new ObjectViewManager(inspection);
        }
        return viewManager;
    }

    private TeleObject teleObject;

    private boolean followingTeleObject = true;

    /**
     * @return local surrogate for the object being inspected in the VM
     */
    TeleObject teleObject() {
        return teleObject;
    }

    /** The origin is an actual location in memory of the VM;
     * keep a copy for comparison, since it might change via GC.
     */
    private Pointer currentObjectOrigin;

    /**
     * @return The actual location in VM memory where
     * the object resides at present; this may change via GC.
     */
    Pointer currentOrigin() {
        return currentObjectOrigin;
    }

    /**
     * Cache of the most recent update to the frame title; needed
     * in situations where the frame becomes unavailable.
     * This cache does not include the object state modifier.
     */
    private String title = null;

    private InspectorTable objectHeaderTable;

    protected final ObjectViewPreferences instanceViewPreferences;

    private Rectangle originalFrameGeometry = null;

    protected ObjectView(final Inspection inspection, final TeleObject teleObject) {
        super(inspection, VIEW_KIND, null);
        this.teleObject = teleObject;
        this.currentObjectOrigin = teleObject().origin();
        this.title = "";
        instanceViewPreferences = new ObjectViewPreferences(ObjectViewPreferences.globalPreferences(inspection)) {
            @Override
            protected void setShowHeader(boolean showHeader) {
                super.setShowHeader(showHeader);
                reconstructView();
            }
            @Override
            protected void setHideNullArrayElements(boolean hideNullArrayElements) {
                super.setHideNullArrayElements(hideNullArrayElements);
                reconstructView();
            }
        };
        instanceViewPreferences.addListener(new TableColumnViewPreferenceListener() {
            public void tableColumnViewPreferencesChanged() {
                reconstructView();
            }
        });
        Trace.line(TRACE_VALUE, tracePrefix() + " creating for " + getTextForTitle());
    }

    @Override
    public InspectorFrame createFrame(boolean addMenuBar) {
        final InspectorFrame frame = super.createFrame(addMenuBar);
        gui().setLocationRelativeToMouse(this, preference().geometry().newFrameDiagonalOffset());
        originalFrameGeometry = getGeometry();
        final InspectorMenu defaultMenu = frame.makeMenu(MenuKind.DEFAULT_MENU);
        defaultMenu.add(defaultMenuItems(MenuKind.DEFAULT_MENU));
        defaultMenu.addSeparator();
        defaultMenu.add(views().deactivateOtherViewsAction(ViewKind.OBJECT, this));
        defaultMenu.add(views().deactivateAllViewsAction(ViewKind.OBJECT));

        final InspectorMenu memoryMenu = frame.makeMenu(MenuKind.MEMORY_MENU);
        memoryMenu.add(views().memory().makeViewAction(teleObject, "View this object's memory"));
        if (vm().heap().providesHeapRegionInfo()) {
            // TODO: Need to revisit this to better integrate with the Views framework, e.g., have something like:
            // views().heapRegionInfo().makeViewAction(...). This requires adding a factory and other boiler plate.
            InspectorAction action = HeapRegionInfoView.viewManager(inspection()).makeViewAction(teleObject, "View this object's heap region info");
            memoryMenu.add(action);
        }
        if (vm().watchpointManager() != null) {
            memoryMenu.add(actions().setObjectWatchpoint(teleObject, "Watch this object's memory"));
        }
        memoryMenu.add(actions().copyObjectOrigin(teleObject, "Copy this object's origin to clipboard"));
        memoryMenu.add(actions().copyObjectDescription(teleObject, "Copy this object's origin + description to clipboard"));
        memoryMenu.add(defaultMenuItems(MenuKind.MEMORY_MENU));
        memoryMenu.add(views().activateSingletonViewAction(ViewKind.ALLOCATIONS));

        frame.makeMenu(MenuKind.OBJECT_MENU);

        if (teleObject.getTeleClassMethodActorForObject() != null) {
            frame.makeMenu(MenuKind.CODE_MENU);
        }

        if (teleObject.getTeleClassMethodActorForObject() != null || TeleTargetMethod.class.isAssignableFrom(teleObject.getClass())) {
            frame.makeMenu(MenuKind.DEBUG_MENU);
        }

        frame.makeMenu(MenuKind.VIEW_MENU).add(defaultMenuItems(MenuKind.VIEW_MENU));
        return frame;
    }

    @Override
    protected void createViewContent() {
        final JPanel panel = new InspectorPanel(inspection(), new BorderLayout());
        if (instanceViewPreferences.showHeader()) {
            objectHeaderTable = new ObjectHeaderTable(inspection(), teleObject, instanceViewPreferences);
            objectHeaderTable.setBorder(preference().style().defaultPaneBottomBorder());
            // Will add without column headers
            panel.add(objectHeaderTable, BorderLayout.NORTH);
        }
        setContentPane(panel);
    }

    @Override
    protected Rectangle defaultGeometry() {
        return originalFrameGeometry;
    }

    @Override
    public final String getTextForTitle() {
        final MaxMemoryRegion memoryRegion = vm().state().findMemoryRegion(currentObjectOrigin);
        final String regionSuffix = " in "
            + (memoryRegion == null ? "unknown region" : memoryRegion.regionName());
        final ObjectMemoryStatus memoryStatus = teleObject.memoryStatus();
        if (memoryStatus.isLive()) {
            Pointer pointer = teleObject.origin();
            title = "Object: " + pointer.toHexString() + inspection().nameDisplay().referenceLabelText(teleObject);
            return title + regionSuffix;
        }
        return memoryStatus.label() + " " + title + regionSuffix;
    }

    @Override
    public InspectorAction getViewOptionsAction() {
        return new InspectorAction(inspection(), "View Options") {
            @Override
            public void procedure() {
                final ObjectViewPreferences globalPreferences = ObjectViewPreferences.globalPreferences(inspection());
                new TableColumnVisibilityPreferences.ColumnPreferencesDialog<ObjectColumnKind>(inspection(), "View Options", instanceViewPreferences, globalPreferences);
            }
        };
    }

    @Override
    public void threadFocusSet(MaxThread oldThread, MaxThread thread) {
        // Object view displays are sensitive to the current thread selection.
        forceRefresh();
    }

    @Override
    public void addressFocusChanged(Address oldAddress, Address newAddress) {
        forceRefresh();
    }

    @Override
    public void viewGetsWindowFocus() {
        if (teleObject != focus().heapObject()) {
            focus().setHeapObject(teleObject);
        }
        super.viewGetsWindowFocus();
    }

    @Override
    public void viewLosesWindowFocus() {
        if (teleObject == focus().heapObject()) {
            focus().setHeapObject(null);
        }
        super.viewLosesWindowFocus();
    }

    @Override
    public void viewClosing() {
        if (teleObject == focus().heapObject()) {
            focus().setHeapObject(null);
        }
        super.viewClosing();
    }

    @Override
    public void watchpointSetChanged() {
        // TODO (mlvdv)  patch for concurrency issue; not completely safe
        if (vm().state().processState() == STOPPED) {
            forceRefresh();
        }
    }

    @Override
    public void vmProcessTerminated() {
        dispose();
    }

    @Override
    protected void refreshState(boolean force) {
        final ObjectMemoryStatus memoryStatus = teleObject.memoryStatus();
        if (memoryStatus.isObsolete() && followingTeleObject) {
            Trace.line(TRACE_VALUE, tracePrefix() + "Following relocated object to 0x" + teleObject.reference().getForwardedTeleRef().toOrigin().toHexString());
            TeleObject forwardedTeleObject = teleObject.getForwardedTeleObject();
            if (viewManager.isObjectViewObservingObject(forwardedTeleObject.reference().makeOID())) {
                followingTeleObject = false;
                setWarning();
                setTitle();
                return;
            }
            viewManager.resetObjectToViewMapEntry(teleObject, forwardedTeleObject, this);
            teleObject = forwardedTeleObject;
            currentObjectOrigin = teleObject.origin();
            reconstructView();
            if (objectHeaderTable != null) {
                objectHeaderTable.refresh(force);
            }
        }

        final Pointer newOrigin = teleObject.origin();
        if (!newOrigin.equals(currentObjectOrigin)) {
            // The object has been relocated in memory
            currentObjectOrigin = newOrigin;
            reconstructView();
        } else {
            if (objectHeaderTable != null) {
                objectHeaderTable.refresh(force);
            }
        }
        setTitle();
        switch(memoryStatus) {
            case DEAD:
                setStateColor(preference().style().deadObjectBackgroundColor());
                break;
            case OBSOLETE:
                setStateColor(preference().style().vmStoppedInGCBackgroundColor(false));
                break;
            default:
                setStateColor(null);
        }
    }

}
