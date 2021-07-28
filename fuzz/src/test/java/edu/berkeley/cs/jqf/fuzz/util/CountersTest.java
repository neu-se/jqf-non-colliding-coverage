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

import java.util.Collection;

import com.pholser.junit.quickcheck.Property;
import com.pholser.junit.quickcheck.generator.InRange;
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck;
import org.eclipse.collections.api.iterator.IntIterator;
import org.eclipse.collections.api.list.primitive.IntList;
import org.eclipse.collections.api.set.primitive.IntSet;
import org.junit.runner.RunWith;


import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

@RunWith(JUnitQuickcheck.class)
public class CountersTest {

    private static final int COUNTER_SIZE = 6151;

    @Property
    public void incrementWorks(int[] keys) {
        Counter counter = new Counter(COUNTER_SIZE);
        // Check local state
        for (int key : keys) {
            int before = counter.get(key);
            int after = counter.increment(key);
            assertEquals(before+1, after);
        }
        // Check global state
        int sum = (int) counter.counts.sum();
        assertEquals(keys.length, sum);
    }

    @Property
    public void incrementDeltaWorks(int[] keys, int delta) {
        Counter counter = new Counter(COUNTER_SIZE);
        // Check local state
        for (int key : keys) {
            int before = counter.get(key);
            int after = counter.increment(key, delta);
            assertEquals(before+delta, after);
        }
        // Check global state
        int sum = (int) counter.counts.sum();
        assertEquals(keys.length*delta, sum);
    }

    @Property
    public void incrementDeltaWorks(int key, int delta, @InRange(minInt=2, maxInt=42) int times) {
        Counter counter = new Counter(COUNTER_SIZE);
        // Check local state
        for (int j = 0; j < times; j++) {
            int before = counter.get(key);
            int after = counter.increment(key, delta);
            assertEquals(before+delta, after);
        }
        // Check global state
        int sum = (int) counter.counts.sum();
        assertEquals(delta*times, sum);
    }

    @Property
    public void nonZeroValuesIsAccurate(int[] keys, int delta) {
        Counter counter = new Counter(COUNTER_SIZE);
        for (int key : keys) {
            counter.increment(key, delta);
        }

        IntList nonZeroValues = counter.getNonZeroValues();
        assertThat(nonZeroValues.size(), lessThanOrEqualTo(keys.length));


        int sum = 0;
        IntIterator iter = nonZeroValues.intIterator();
        while(iter.hasNext()){
            sum += iter.next();
        }

        assertEquals(keys.length * delta, sum);

    }

    @Property
    public void nonZeroIndicesIsAccurate(int[] keys, int delta) {
        Counter counter = new Counter(COUNTER_SIZE);
        for (int key : keys) {
            counter.increment(key, delta);
        }

        IntList nonZeroIndices = counter.getNonZeroKeys();
        IntList nonZeroValues = counter.getNonZeroValues();
        assertEquals(nonZeroValues.size(), nonZeroIndices.size());

    }


    @Property
    public void nonZeroSizeIsAccurate(int[] keys, int delta) {
        Counter counter = new Counter(COUNTER_SIZE);
        for (int key : keys) {
            counter.increment(key, delta);
        }

        int nonZeroSize = counter.getNonZeroSize();
        IntList nonZeroValues = counter.getNonZeroValues();
        assertEquals(nonZeroValues.size(), nonZeroSize);

    }

    @Property
    public void clearsToZero(int[] keys) {
        // Fill counter with some data
        Counter counter = new Counter(COUNTER_SIZE);
        for (int key : keys) {
            int before = counter.get(key);
            int after = counter.increment(key);
            assertEquals(before + 1, after);
        }

        // Clear it
        counter.clear();

        // Check that all are zero
        for (int i = 0; i < COUNTER_SIZE; i++) {
            assertEquals(0, counter.get(i));
        }

        for (int index : keys) {
            assertEquals(0, counter.get(index));
        }

        assertThat(counter.getNonZeroSize(), is(0));
        assertThat(counter.getNonZeroKeys().size(), is(0));
        assertThat(counter.getNonZeroValues().size(), is(0));
    }
}
