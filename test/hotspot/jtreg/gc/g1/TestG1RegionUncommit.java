/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
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

package gc.g1;

/**
 * @test TestG1RegionUncommit
 * @bug 8357445
 * @summary Regression for the time-based uncommit free-list/safepoint bug
 *          (microsoft/openjdk#677): grow then release the heap and go idle so a real
 *          time-based region uncommit fires, and assert it happened ("Time-based shrink:
 *          deactivated") so the free-list-mutation-at-safepoint path is actually
 *          exercised. On fastdebug the master free-list MT-safety guarantee is active, so
 *          an off-safepoint regression aborts the VM.
 * @requires vm.gc.G1
 * @library /test/lib
 * @run main gc.g1.TestG1RegionUncommit
 */

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestG1RegionUncommit {

    static final int MB = 1024 * 1024;

    // Sink for the background mutator's allocations so they are not optimized away.
    static volatile byte[] blackhole;

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            stress();
            return;
        }

        OutputAnalyzer o = new OutputAnalyzer(ProcessTools.createLimitedTestJavaProcessBuilder(
            "-XX:+UseG1GC",
            "-XX:+UnlockDiagnosticVMOptions",
            "-Xms32m", "-Xmx256m",
            "-XX:G1HeapRegionSize=1M",
            // Disable GC-based shrink so any uncommit is attributable to the time-based path.
            "-XX:MaxHeapFreeRatio=100",
            "-XX:G1TimeBasedEvaluationIntervalMillis=1000",
            "-XX:G1UncommitDelayMillis=1000",
            "-XX:G1MinRegionsToUncommit=1",
            "-Xlog:gc+ergo+heap=debug",
            "gc.g1.TestG1RegionUncommit", "stress").start());

        // The periodic evaluation must have run...
        o.shouldContain("Starting uncommit evaluation");
        // ...and once the app went idle it must have actually uncommitted at least one
        // region, i.e. the free-list-mutation-at-safepoint path (the #677 crash site)
        // really executed. Without this a pass would only prove the periodic task ran.
        o.shouldContain("Time-based shrink: deactivated");
        // The master free-list MT-safety guarantee has teeth on fastdebug: a trip aborts
        // the VM (non-zero exit).
        o.shouldHaveExitValue(0);
    }

    static void stress() throws Exception {
        // Grow the heap with a large retained block of normal-sized (non-humongous)
        // objects, then release it and collect, so the regions become free but stay
        // committed (GC-based shrink is disabled via MaxHeapFreeRatio=100). This leaves
        // the time-based path a large pool of idle free regions to uncommit.
        final int chunk = 64 * 1024;            // 64K: well below the 512K humongous threshold
        final int count = 120 * MB / chunk;     // ~120MB retained, well under -Xmx
        List<byte[]> retained = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            retained.add(new byte[chunk]);
        }
        retained.clear();
        System.gc();                            // free the regions; MaxHeapFreeRatio=100 keeps them committed

        // Keep only a light background mutator alive: the uncommit fires at a safepoint,
        // so a running mutator gives the fastdebug master free-list guarantee something to
        // race against, while staying light enough that GC overhead decays below the
        // uncommit pre-check threshold and the bulk of the free regions remain idle. The
        // feature only uncommits when the app is idle, so heavy allocation here would
        // suppress it entirely.
        AtomicBoolean stop = new AtomicBoolean(false);
        Thread mutator = new Thread(() -> {
            while (!stop.get()) {
                blackhole = new byte[4 * 1024];
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        mutator.setDaemon(true);
        mutator.start();

        // Idle window well beyond G1UncommitDelayMillis + the evaluation interval: with the
        // heap quiet, GC overhead falls under the pre-check threshold and the free regions
        // age past the delay, so the time-based evaluation uncommits them at a safepoint
        // over several cycles.
        Thread.sleep(12000);

        stop.set(true);
        mutator.join(2000);
    }
}
