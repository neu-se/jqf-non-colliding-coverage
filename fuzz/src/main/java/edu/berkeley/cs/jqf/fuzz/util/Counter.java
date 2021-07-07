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

import org.eclipse.collections.api.iterator.IntIterator;
import org.eclipse.collections.api.iterator.MutableIntIterator;
import org.eclipse.collections.api.list.primitive.IntList;
import org.eclipse.collections.api.tuple.primitive.IntIntPair;
import org.eclipse.collections.impl.list.mutable.primitive.IntArrayList;
import org.eclipse.collections.impl.map.mutable.primitive.IntIntHashMap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

/**
 * Maps integer keys to integer counts using a hash table.
 *
 * <p>Throughout the internal documentation, the term "key" is used
 * to refer to the keys that are hashed. We do not expose the index of
 * those keys in the map. </p>
 *
 * @author Rohan Padhye (Initial version, had hash collisions)
 * @author Jonathan Bell (Refactored to  Eclipse collections to resolve hash collisions)
 *
 */
public class Counter {

    /** The counter map as a map of integers. */
    protected IntIntHashMap counts;

    /**
     * Creates a new counter with given size.
     *
     * @param size the starting size of the hashtable.
     */
    public Counter(int size) {
        this.counts = new IntIntHashMap(size);
    }

    /**
     * Returns the size of this counter.
     *
     * @return the size of this counter
     */
    public int size() {
        return this.counts.size();
    }

    /**
     * Clears the counter by setting all values to zero.
     */
    public void clear() {
        this.counts.clear();
    }

    /**
     * Increments the count at the given key.
     *
     *
     * @param key the key whose count to increment
     * @return the new value after incrementing the count
     */
    public int increment(int key) {
        int cur = this.counts.get(key);
        int incr = cur + 1;
        this.counts.put(key, incr);
        return incr;
    }

    /**
     *
     * Increments the count at the given key by a given delta.
     *
     * @param key the key whose count to increment
     * @param delta the amount to increment by
     * @return the new value after incrementing the count
     */
    public int increment(int key, int delta) {
        int cur = this.counts.get(key);
        int incr = cur + delta;
        this.counts.put(key, incr);
        return incr;
    }

    /**
     * Returns the number of indices with non-zero counts.
     *
     * @return the number of indices with non-zero counts
     */
    public int getNonZeroSize() {
        int size = 0;
        MutableIntIterator iter = this.counts.values().intIterator();
        while(iter.hasNext()){
            if(iter.next() != 0){
                size++;
            }
        }
        return size;
    }


    /**
     * Returns a set of keys at which the count is non-zero.
     *
     * @return a set of keys at which the count is non-zero
     */
    public IntList getNonZeroKeys() {
        IntArrayList keys = new IntArrayList(this.counts.size()/2);
        Iterator<IntIntPair> iter = this.counts.keyValuesView().iterator();
        while(iter.hasNext()){
            IntIntPair each  = iter.next();
            if(each.getTwo() != 0){
                keys.add(each.getOne());
            }
        }
        return keys;
    }

    /**
     * Returns a set of non-zero count values in this counter.
     *
     * @return a set of non-zero count values in this counter.
     */
    public IntList getNonZeroValues() {
        IntArrayList values = new IntArrayList(this.counts.size() / 2);
        IntIterator iter = this.counts.values().intIterator();
        while (iter.hasNext()) {
            int val = iter.next();
            if (val != 0) {
                values.add(val);
            }
        }
        return values;
    }

    /**
     * Retreives a value for a given key.
     *
     * <p>The key is first hashed to retreive a value from
     * the counter, and hence the result is modulo collisions.</p>
     *
     * @param key the key to query
     * @return the count for the index corresponding to this key
     */
    public int get(int key) {
        return this.counts.get(key);
    }

    public void copyFrom(Counter counter) {
        this.counts = new IntIntHashMap(counter.counts);
    }

}
