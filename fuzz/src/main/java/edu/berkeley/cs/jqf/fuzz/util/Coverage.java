/*
 * Copyright (c) 2017-2018 The Regents of the University of California
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * 1. Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package edu.berkeley.cs.jqf.fuzz.util;

import edu.berkeley.cs.jqf.instrument.tracing.events.BranchEvent;
import edu.berkeley.cs.jqf.instrument.tracing.events.CallEvent;
import edu.berkeley.cs.jqf.instrument.tracing.events.TraceEvent;
import edu.berkeley.cs.jqf.instrument.tracing.events.TraceEventVisitor;
import janala.instrument.CoverageListener;
import org.eclipse.collections.api.iterator.IntIterator;
import org.eclipse.collections.api.list.primitive.IntList;
import org.eclipse.collections.api.tuple.primitive.IntIntPair;
import org.eclipse.collections.impl.list.mutable.primitive.IntArrayList;

import java.util.Iterator;

/**
 * Utility class to collect branch and function coverage
 *
 * @author Rohan Padhye
 */
public class Coverage implements TraceEventVisitor, CoverageListener {

    /** The starting size of the coverage map. */
    private final int COVERAGE_MAP_SIZE = (1 << 8);

    /** The coverage counts for each edge. */
    private final Counter counter = new Counter(COVERAGE_MAP_SIZE);

    /** Creates a new coverage map. */
    public Coverage() {

    }

    /**
     * Creates a copy of an existing coverage map.
     *
     * @param that the coverage map to copy
     */
    public Coverage(Coverage that) {
        this.counter.copyFrom(that.counter);
    }

    /**
     * Returns the size of the coverage map.
     *
     * @return the size of the coverage map
     */
    public int size() {
        return COVERAGE_MAP_SIZE;
    }

    /**
     * Updates coverage information based on emitted event.
     *
     * <p>This method updates its internal counters for branch and
     * call events.</p>
     *
     * @param e the event to be processed
     */
    public void handleEvent(TraceEvent e) {
        e.applyVisitor(this);
    }

    @Override
    public void visitBranchEvent(BranchEvent b) {
        counter.increment((b.getIid() << 2) + b.getArm());
    }

    @Override
    public void visitCallEvent(CallEvent e) {
        counter.increment((e.getIid() << 2) + 3);
    }

    /**
     * Returns the number of edges covered.
     *
     * @return the number of edges with non-zero counts
     */
    public int getNonZeroCount() {
        return counter.getNonZeroSize();
    }

    /**
     * Returns a collection of branches that are covered.
     *
     * @return a collection of keys that are covered
     */
    public IntList getCovered() {
        return counter.getNonZeroKeys();
    }


    public IntList computeNewCoverage(Coverage baseline) {
        IntArrayList newCoverage = new IntArrayList();

        IntList baseNonZero = this.counter.getNonZeroKeys();
        IntIterator iter = baseNonZero.intIterator();
        while (iter.hasNext()) {
            int idx = iter.next();
            if (baseline.counter.get(idx) == 0) {
                newCoverage.add(idx);
            }
        }
        return newCoverage;
    }



    /**
     * Clears the coverage map.
     */
    public void clear() {
        this.counter.clear();
    }

    private static int[] HOB_CACHE = new int[1024];

    /** Computes the highest order bit */
    private static int computeHob(int num)
    {
        if (num == 0)
            return 0;

        int ret = 1;

        while ((num >>= 1) != 0)
            ret <<= 1;

        return ret;
    }

    /** Populates the HOB cache. */
    static {
        for (int i = 0; i < HOB_CACHE.length; i++) {
            HOB_CACHE[i] = computeHob(i);
        }
    }

    /** Returns the highest order bit (perhaps using the cache) */
    private static int hob(int num) {
        if (num < HOB_CACHE.length) {
            return HOB_CACHE[num];
        } else {
            return computeHob(num);
        }
    }


    /**
     * Updates this coverage with bits from the parameter.
     *
     * @param that the run coverage whose bits to OR
     *
     * @return <tt>true</tt> iff <tt>that</tt> is not a subset
     *         of <tt>this</tt>, causing <tt>this</tt> to change.
     */
    public boolean updateBits(Coverage that) {
        boolean changed = false;
        synchronized (this.counter) {
            synchronized (that.counter) {
                Iterator<IntIntPair> thatIter = that.counter.counts.keyValuesView().iterator();

                while (thatIter.hasNext()) {
                    IntIntPair coverageEntry = thatIter.next();
                    int before = this.counter.counts.get(coverageEntry.getOne());
                    int after = before | hob(coverageEntry.getTwo());
                    if(before == 0){
                        this.counter.nonZeroKeys.add(coverageEntry.getOne());
                    }
                    if (after != before) {
                        this.counter.counts.put(coverageEntry.getOne(), after);
                        changed = true;
                    }
                }
            }
        }
        return changed;
    }

    @Override
    public void logCoverage(int iid, int arm) {
        //WARNING: this "collision free" coverage might collide on case/switch statements!
        //It would be nicer to just put an IID probe for each branch target, but this was the slightly less invasive fix
        //(can't just put the probe at the target, need to log on the edge, so it's a bit trickier). - JSB
        counter.increment(iid + arm);
    }

}
