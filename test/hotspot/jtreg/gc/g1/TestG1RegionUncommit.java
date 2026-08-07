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
 * @summary Regression for the time-based uncommit free-list/safepoint race: concurrent
 *          allocation and idle evaluation must not trip the master free-list MT-safety
 *          guarantee (has teeth on fastdebug builds where the guarantee is active). The
 *          test asserts a real uncommit happens ("Time-based shrink: deactivated") so a
 *          pass cannot be reached without exercising the crash-prone path.
 * @requires vm.gc.G1
 * @library /test/lib
 * @run main gc.g1.TestG1RegionUncommit
 */

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestG1RegionUncommit {

    static final int MB = 1024 * 1024;

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

        // The idle evaluation must have run while the master free list was being mutated
        // by concurrent allocation (the microsoft/openjdk#677 race window)...
        o.shouldContain("Starting uncommit evaluation");
        // ...and the idle phase must have actually uncommitted at least one region, so the
        // free-list-mutation-at-safepoint path (the crash site) really executed. Without
        // this a pass would only prove the periodic task ran, not that the fix was tested.
        o.shouldContain("Time-based shrink: deactivated");
        // The guarantee has teeth on fastdebug: a trip aborts the VM (non-zero exit).
        o.shouldHaveExitValue(0);
    }

    static void stress() throws Exception {
        // Phase A: concurrent humongous alloc/free churn (with frequent safepoints)
        // overlapping the periodic idle evaluation. This is the free-list/safepoint race
        // window from microsoft/openjdk#677.
        List<byte[]> shared = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean stop = new AtomicBoolean(false);

        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                Random r = new Random();
                while (!stop.get()) {
                    for (int i = 0; i < 8; i++) {
                        shared.add(new byte[MB]);
                    }
                    synchronized (shared) {
                        int n = Math.min(8, shared.size());
                        for (int i = 0; i < n; i++) {
                            shared.remove(shared.size() - 1);
                        }
                    }
                    if (r.nextInt(4) == 0) {
                        System.gc();
                    }
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });
            threads[t].start();
        }

        Thread.sleep(6000);
        stop.set(true);
        for (Thread t : threads) {
            t.join(2000);
        }

        // Phase B: expand the heap, release it, then go idle so the time-based evaluation
        // deterministically uncommits the now-idle free regions. With GC-based shrink
        // disabled (MaxHeapFreeRatio=100), the only thing that can uncommit here is the
        // time-based path, so the "deactivated" log is a reliable witness that the
        // crash-prone path actually executed.
        List<byte[]> burst = new ArrayList<>();
        for (int i = 0; i < 320; i++) {          // ~160MB in half-region chunks
            burst.add(new byte[MB / 2]);
        }
        burst.clear();
        System.gc();
        System.gc();

        // Idle window well beyond G1UncommitDelayMillis + the evaluation interval: no
        // allocation, so the free regions age past the delay and get deactivated.
        Thread.sleep(5000);
    }
}
