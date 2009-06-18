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
package com.sun.max.profile;

import java.io.*;
import java.util.*;
import java.util.Arrays;

import com.sun.max.lang.*;
import com.sun.max.profile.Metrics.*;
import com.sun.max.util.timer.*;

public class GlobalMetrics {

    static class EntryComparator implements Comparator<Map.Entry<String, Metric>> {
        public int compare(Map.Entry<String, Metric> a, Map.Entry<String, Metric> b) {
            return String.CASE_INSENSITIVE_ORDER.compare(a.getKey(), b.getKey());
        }
    }

    static class MetricSet<Metric_Type extends Metric> {
        private final Class<Metric_Type> _clazz;
        private final Map<String, Metric_Type> _metrics = new HashMap<String, Metric_Type>();

        MetricSet(Class<Metric_Type> mClass) {
            _clazz = mClass;
        }
    }

    protected static final Map<Class<? extends Metric>, MetricSet> _metricSets = new HashMap<Class<? extends Metric>, MetricSet>();

    /**
     * This method allocates a new counter with the specified name and adds it to the global
     * metric list. If a previous metric with the same name exists, it will return a reference
     * to the first one created.
     * @param name the name of the metric for which to create a counter
     * @return a reference to a code {@code Counter} object which can be incremented and accumulated
     */
    public static Metrics.Counter newCounter(String name) {
        if (name == null) {
            return new Metrics.Counter();
        }
        return getCounter(name);
    }

    public static TimerMetric newTimer(String name, Clock clock) {
        if (name == null) {
            return new TimerMetric(new MultiThreadTimer(clock));
        }
        return getTimer(name, clock);
    }

    public static Metrics.Rate newRate(String name, Metrics.Counter count, Clock clock) {
        if (name == null) {
            return new Rate(count, clock);
        }
        return getRate(name, count, clock);
    }

    static synchronized Metrics.Counter getCounter(String name) {
        Metrics.Counter counter = getMetric(name, Metrics.Counter.class);
        if (counter == null) {
            counter = setMetric(name, Metrics.Counter.class, new Metrics.Counter());
        }
        return counter;
    }

    static synchronized TimerMetric getTimer(String name, Clock clock) {
        TimerMetric timer = getMetric(name, TimerMetric.class);
        if (timer == null) {
            timer = setMetric(name, TimerMetric.class, new TimerMetric(new MultiThreadTimer(clock)));
        }
        return timer;
    }

    static synchronized Metrics.Rate getRate(String name, Metrics.Counter count, Clock clock) {
        Metrics.Rate rate = getMetric(name, Metrics.Rate.class);
        if (rate == null) {
            rate = setMetric(name, Metrics.Rate.class, new Metrics.Rate(count, clock));
        }
        return rate;
    }

    public static <Metric_Type extends Metric> Metric_Type getMetric(String name, Class<Metric_Type> mClass) {
        final MetricSet<Metric_Type> metricSet = StaticLoophole.cast(_metricSets.get(mClass));
        if (metricSet != null) {
            final Metric_Type metric = metricSet._metrics.get(name);
            if (metric != null) {
                return metric;
            }
        }
        return null;
    }

    public static <Metric_Type extends Metric> Metric_Type setMetric(String name, Class<Metric_Type> mClass, Metric_Type metric) {
        MetricSet<Metric_Type> metricSet = StaticLoophole.cast(_metricSets.get(mClass));
        if (metricSet == null) {
            metricSet = new MetricSet<Metric_Type>(mClass);
            _metricSets.put(mClass, metricSet);
        }
        metricSet._metrics.put(name, metric);
        return metric;
    }

    /**
     * Resets of all the currently registered metrics.
     */
    public static synchronized void reset() {
        for (MetricSet<? extends Metric> metricSet : _metricSets.values()) {
            for (Metric metric : metricSet._metrics.values()) {
                metric.reset();
            }
        }
    }

    /**
     * This method prints a report of all the metrics that have been created during this
     * execution run.
     * @param stream the print stream to which to print the report
     */
    public static synchronized void report(PrintStream stream) {
        final Map<String, Metric> allMetrics = new HashMap<String, Metric>();
        for (MetricSet<? extends Metric> metricSet : _metricSets.values()) {
            allMetrics.putAll(metricSet._metrics);
        }

        Map.Entry<String, Metric>[] array = StaticLoophole.cast(new Map.Entry[allMetrics.size()]);
        array = allMetrics.entrySet().toArray(array);
        Arrays.sort(array, new GlobalMetrics.EntryComparator());
        for (Map.Entry<String, Metric> entry : array) {
            if (entry.getKey().length() > Metrics._longestMetricName) {
                Metrics._longestMetricName = entry.getKey().length();
            }
        }
        for (Map.Entry<String, Metric> entry : array) {
            entry.getValue().report(entry.getKey(), stream);
        }
        stream.flush();
    }

}
