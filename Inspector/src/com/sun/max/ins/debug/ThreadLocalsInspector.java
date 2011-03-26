/*
 * Copyright (c) 2008, 2011, Oracle and/or its affiliates. All rights reserved.
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
package com.sun.max.ins.debug;

import static com.sun.max.tele.MaxProcessState.*;

import java.awt.*;
import java.awt.print.*;
import java.text.*;
import java.util.*;

import javax.swing.*;
import javax.swing.event.*;

import com.sun.max.ins.*;
import com.sun.max.ins.InspectionSettings.SaveSettingsListener;
import com.sun.max.ins.gui.*;
import com.sun.max.ins.gui.TableColumnVisibilityPreferences.TableColumnViewPreferenceListener;
import com.sun.max.program.*;
import com.sun.max.tele.*;
import com.sun.max.unsafe.*;
import com.sun.max.vm.runtime.*;

/**
 * A singleton inspector that displays thread local areas for the thread the VM that is the current user focus.
 *
 * @author Michael Van De Vanter
 */
public final class ThreadLocalsInspector extends Inspector implements TableColumnViewPreferenceListener {

    private static final int TRACE_VALUE = 1;

    private static final Safepoint.State DEFAULT_STATE_SELECTION = Safepoint.State.ENABLED;

    private static final Map<MaxThread, Safepoint.State> stateSelections = new HashMap<MaxThread, Safepoint.State>();

    // Set to null when inspector closed.
    private static ThreadLocalsInspector threadLocalsInspector;

    /**
     * Displays the (singleton) thread locals inspector, creating it if needed.
     */
    public static ThreadLocalsInspector make(Inspection inspection) {
        if (threadLocalsInspector == null) {
            threadLocalsInspector = new ThreadLocalsInspector(inspection);
        }
        return threadLocalsInspector;
    }

    private final SaveSettingsListener saveSettingsListener = createGeometrySettingsListener(this, "threadlocalsInspectorGeometry");

    // This is a singleton viewer, so only use a single level of view preferences.
    private final ThreadLocalsViewPreferences viewPreferences;

    private MaxThread thread;
    private InspectorTabbedPane tabbedPane;

    private ThreadLocalsInspector(Inspection inspection) {
        super(inspection);
        Trace.begin(TRACE_VALUE,  tracePrefix() + " initializing");
        viewPreferences = ThreadLocalsViewPreferences.globalPreferences(inspection());
        viewPreferences.addListener(this);

        final InspectorFrame frame = createFrame(true);

        frame.makeMenu(MenuKind.DEFAULT_MENU).add(defaultMenuItems(MenuKind.DEFAULT_MENU));

        final InspectorMenu memoryMenu = frame.makeMenu(MenuKind.MEMORY_MENU);
        memoryMenu.add(actions().inspectSelectedThreadLocalsBlockMemoryWords("Inspect memory for thread's locals block"));
        for (Safepoint.State state : Safepoint.State.CONSTANTS) {
            memoryMenu.add(actions().inspectSelectedThreadLocalsAreaMemoryWords(state, "Inspect memory for thread's " + state.name() + " area"));
        }
        memoryMenu.add(actions().inspectSelectedThreadStackMemoryWords("Inspect memory for thread's stack"));
        memoryMenu.add(defaultMenuItems(MenuKind.MEMORY_MENU));
        final JMenuItem viewMemoryRegionsMenuItem = new JMenuItem(actions().viewMemoryRegions());
        viewMemoryRegionsMenuItem.setText("View Memory Regions");
        memoryMenu.add(viewMemoryRegionsMenuItem);

        frame.makeMenu(MenuKind.VIEW_MENU).add(defaultMenuItems(MenuKind.VIEW_MENU));

        final InspectorMenu editMenu = frame.makeMenu(MenuKind.EDIT_MENU);
        Watchpoints.buildThreadLocalWatchpointMenu(inspection, editMenu);

        forceRefresh();
        Trace.end(TRACE_VALUE,  tracePrefix() + " initializing");
    }

    @Override
    protected Rectangle defaultGeometry() {
        return inspection().geometry().threadLocalsDefaultFrameGeometry();
    }

    @Override
    protected void createView() {
        thread = focus().thread();
        Safepoint.State initialStateSelection = stateSelections.get(thread);
        if (initialStateSelection == null) {
            initialStateSelection = DEFAULT_STATE_SELECTION;
        }
        ThreadLocalsAreaPanel initialPanelSelection = null;
        tabbedPane = new InspectorTabbedPane(inspection());
        if (thread != null) {
            for (Safepoint.State state : Safepoint.State.CONSTANTS) {
                final MaxThreadLocalsArea tla = thread.localsBlock().tlaFor(state);
                if (tla != null) {
                    final ThreadLocalsAreaPanel panel = new ThreadLocalsAreaPanel(inspection(), thread, tla, viewPreferences);
                    if (state == initialStateSelection) {
                        initialPanelSelection = panel;
                    }
                    tabbedPane.add(state.toString(), panel);
                }
            }
            if (initialPanelSelection != null) {
                tabbedPane.setSelectedComponent(initialPanelSelection);
            }
            tabbedPane.addChangeListener(new ChangeListener() {
                // Refresh a newly exposed pane to be sure it is current
                public void stateChanged(ChangeEvent event) {
                    // TODO (mlvdv)  Data reading PATCH, there should be a more systematic way of handling this.
                    if (vm().state().processState() == MaxProcessState.TERMINATED) {
                        return;
                    }
                    final ThreadLocalsAreaPanel tlaPanel = (ThreadLocalsAreaPanel) tabbedPane.getSelectedComponent();
                    if (tlaPanel != null) {
                        tlaPanel.refresh(true);
                        stateSelections.put(thread, tlaPanel.getSafepointState());
                    }
                }
            });
        }
        setContentPane(tabbedPane);
        setTitle();
    }

    @Override
    protected SaveSettingsListener saveSettingsListener() {
        return saveSettingsListener;
    }

    @Override
    public String getTextForTitle() {
        String title = "Thread Local Variables: ";
        if (thread != null) {
            title += inspection().nameDisplay().longNameWithState(thread);
        }
        return title;
    }

    @Override
    public InspectorAction getViewOptionsAction() {
        return new InspectorAction(inspection(), "View Options") {
            @Override
            public void procedure() {
                new TableColumnVisibilityPreferences.ColumnPreferencesDialog<ThreadLocalVariablesColumnKind>(inspection(), "Thread Local Variables View Options", viewPreferences);
            }
        };
    }

    @Override
    public InspectorAction getPrintAction() {
        return new InspectorAction(inspection(), "Print") {
            @Override
            public void procedure() {
                final ThreadLocalsAreaPanel tlaPanel = (ThreadLocalsAreaPanel) tabbedPane.getSelectedComponent();
                final String name = getTextForTitle() + " " + tlaPanel.getSafepointState().toString();
                final MessageFormat footer = new MessageFormat(vm().entityName() + ": " + name + "  Printed: " + new Date() + " -- Page: {0, number, integer}");
                try {
                    final InspectorTable inspectorTable = tlaPanel.getTable();
                    assert inspectorTable != null;
                    inspectorTable.print(JTable.PrintMode.FIT_WIDTH, null, footer);
                } catch (PrinterException printerException) {
                    gui().errorMessage("Print failed: " + printerException.getMessage());
                }
            }
        };
    }

    private ThreadLocalsAreaPanel threadLocalsPanelFor(Safepoint.State state) {
        for (Component component : tabbedPane.getComponents()) {
            final ThreadLocalsAreaPanel tlaPanel = (ThreadLocalsAreaPanel) component;
            if (tlaPanel.getSafepointState() == state) {
                return tlaPanel;
            }
        }
        return null;
    }

    @Override
    protected void refreshState(boolean force) {
        boolean panelsAddedOrRemoved = false;
        for (Safepoint.State state : Safepoint.State.CONSTANTS) {
            final MaxThreadLocalsArea tla = thread.localsBlock().tlaFor(state);
            final ThreadLocalsAreaPanel panel = threadLocalsPanelFor(state);
            if (tla != null) {
                if (panel == null) {
                    tabbedPane.add(state.toString(), new ThreadLocalsAreaPanel(inspection(), thread, tla, viewPreferences));
                    panelsAddedOrRemoved = true;
                }
            } else {
                if (panel != null) {
                    tabbedPane.remove(panel);
                    panelsAddedOrRemoved = true;
                }
            }
        }
        if (panelsAddedOrRemoved) {
            reconstructView();
        }

        // Only need to refresh the panel that's visible, as long as we refresh them when they become visible
        final ThreadLocalsAreaPanel tlaPanel = (ThreadLocalsAreaPanel) tabbedPane.getSelectedComponent();
        if (tlaPanel != null) {
            tlaPanel.refresh(force);
        }
        // The title displays thread state, so must be updated.
        setTitle();
    }

    public void viewConfigurationChanged() {
        reconstructView();
    }

    @Override
    public void threadFocusSet(MaxThread oldThread, MaxThread thread) {
        reconstructView();
    }

    @Override
    public void addressFocusChanged(Address oldAddress, Address newAddress) {
        forceRefresh();
    }

    public void tableColumnViewPreferencesChanged() {
        reconstructView();
    }

    @Override
    public void watchpointSetChanged() {
        if (vm().state().processState() != TERMINATED) {
            forceRefresh();
        }
    }

    @Override
    public void inspectorClosing() {
        Trace.line(TRACE_VALUE, tracePrefix() + " closing");
        threadLocalsInspector = null;
        viewPreferences.removeListener(this);
        super.inspectorClosing();
    }

    @Override
    public void vmProcessTerminated() {
        reconstructView();
    }

}
