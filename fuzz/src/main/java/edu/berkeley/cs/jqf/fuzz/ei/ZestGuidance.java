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
package edu.berkeley.cs.jqf.fuzz.ei;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Console;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import edu.berkeley.cs.jqf.fuzz.ei.ExecutionIndex.Prefix;
import edu.berkeley.cs.jqf.fuzz.ei.ExecutionIndex.Suffix;
import edu.berkeley.cs.jqf.fuzz.guidance.Guidance;
import edu.berkeley.cs.jqf.fuzz.guidance.GuidanceException;
import edu.berkeley.cs.jqf.fuzz.guidance.Result;
import edu.berkeley.cs.jqf.fuzz.guidance.TimeoutException;
import edu.berkeley.cs.jqf.fuzz.util.Coverage;
import edu.berkeley.cs.jqf.fuzz.util.ProducerHashMap;
import edu.berkeley.cs.jqf.instrument.tracing.SingleSnoop;
import edu.berkeley.cs.jqf.instrument.tracing.events.CallEvent;
import edu.berkeley.cs.jqf.instrument.tracing.events.ReturnEvent;
import edu.berkeley.cs.jqf.instrument.tracing.events.TraceEvent;
import edu.berkeley.cs.jqf.instrument.tracing.events.TraceEventVisitor;
import org.eclipse.collections.api.iterator.IntIterator;
import org.eclipse.collections.api.iterator.ShortIterator;
import org.eclipse.collections.api.list.primitive.IntList;
import org.eclipse.collections.impl.list.mutable.primitive.ShortArrayList;
import org.eclipse.collections.impl.set.mutable.primitive.IntHashSet;

import static java.lang.Math.ceil;
import static java.lang.Math.log;

/**
 * A guidance that performs coverage-guided fuzzing using two coverage maps,
 * one for all inputs and one for valid inputs only.
 *
 * @author Rohan Padhye
 */
public class ZestGuidance implements Guidance, TraceEventVisitor {

    // Currently, we only support single-threaded applications
    // This field is used to ensure that
    private Thread appThread;

    /** The last event handled by this guidance */
    private TraceEvent lastEvent;

    /** The execution indexing logic. */
    private ExecutionIndexingState eiState;

    /** A pseudo-random number generator for generating fresh values. */
    private Random random = new Random();

    /** The name of the test for display purposes. */
    private final String testName;

    // ------------ ALGORITHM BOOKKEEPING ------------

    /** The max amount of time to run for, in milli-seconds */
    private final long maxDurationMillis;

    /** The number of trials completed. */
    private long numTrials = 0;

    /** The number of valid inputs. */
    private long numValid = 0;

    /** The directory where fuzzing results are written. */
    private final File outputDirectory;

    /** The directory where saved inputs are written. */
    private File savedInputsDirectory;

    /** The directory where saved inputs are written. */
    private File savedFailuresDirectory;

    /** Set of saved inputs to fuzz. */
    private ArrayList<Input> savedInputs = new ArrayList<>();

    /** Queue of seeds to fuzz. */
    private Deque<SeedInput> seedInputs = new ArrayDeque<>();

    /** Current input that's running -- valid after getInput() and before handleResult(). */
    private Input<?> currentInput;

    /** Index of currentInput in the savedInputs -- valid after seeds are processed (OK if this is inaccurate). */
    private int currentParentInputIdx = 0;

    /** Number of mutated inputs generated from currentInput. */
    private int numChildrenGeneratedForCurrentParentInput = 0;

    /** Number of cycles completed (i.e. how many times we've reset currentParentInputIdx to 0. */
    private int cyclesCompleted = 0;

    /** Number of favored inputs in the last cycle. */
    private int numFavoredLastCycle = 0;

    /** Blind fuzzing -- if true then the queue is always empty. */
    private boolean blind;

    /** Number of saved inputs.
     *
     * This is usually the same as savedInputs.size(),
     * but we do not really save inputs in TOTALLY_RANDOM mode.
     */
    private int numSavedInputs = 0;

    /** Coverage statistics for a single run. */
    private Coverage runCoverage = new Coverage();

    /** Cumulative coverage statistics. */
    private Coverage totalCoverage = new Coverage();

    /** Cumulative coverage for valid inputs. */
    private Coverage validCoverage = new Coverage();

    /** The maximum number of keys covered by any single input found so far. */
    private int maxCoverage = 0;

    /** A mapping of coverage keys to inputs that are responsible for them. */
    private Map<Object, Input> responsibleInputs = new HashMap<>(totalCoverage.size());

    /** The set of unique failures found so far. */
    Set<List<StackTraceElement>> uniqueFailures = new HashSet<>();

    /**
     * A map of execution contexts (call stacks) to locations in saved inputs with those contexts.
     *
     * This is a nifty data structure for quickly finding candidates for input splicing.
     */
    private Map<ExecutionContext, ArrayList<InputLocation>> ecToInputLoc
            = new ProducerHashMap<>(() -> new ArrayList<>());

    // ---------- LOGGING / STATS OUTPUT ------------

    /** Whether to print log statements to stderr (debug option; manually edit). */
    private final boolean verbose = true;

    /** A system console, which is non-null only if STDOUT is a console. */
    private final Console console = System.console();

    /** Time since this guidance instance was created. */
    private final Date startTime = new Date();

    /** Time at last stats refresh. */
    private Date lastRefreshTime = startTime;

    /** Total execs at last stats refresh. */
    private long lastNumTrials = 0;

    /** Minimum amount of time (in millis) between two stats refreshes. */
    private static final long STATS_REFRESH_TIME_PERIOD = 300;

    /** The file where log data is written. */
    private File logFile;

    /** The file where saved plot data is written. */
    private File statsFile;

    /** The currently executing input (for debugging purposes). */
    private File currentInputFile;

    /** Whether to print the fuzz config to the stats screen. */
    private static boolean SHOW_CONFIG = false;

    // ------------- TIMEOUT HANDLING ------------

    /** Timeout for an individual run. */
    private long singleRunTimeoutMillis;

    /** Date when last run was started. */
    private Date runStart;

    /** Number of conditional jumps since last run was started. */
    private long branchCount;

    // ------------- FUZZING HEURISTICS ------------

    /** Whether to use real execution indexes as opposed to flat numbering. */
    static final boolean DISABLE_EXECUTION_INDEXING = !Boolean.getBoolean("jqf.ei.ENABLE_EXECUTION_INDEXING");

    /** Whether to save only valid inputs **/
    static final boolean SAVE_ONLY_VALID = Boolean.getBoolean("jqf.ei.SAVE_ONLY_VALID");

    /** Max input size to generate. */
    static final int MAX_INPUT_SIZE = Integer.getInteger("jqf.ei.MAX_INPUT_SIZE", 10240);

    /** Whether to generate EOFs when we run out of bytes in the input, instead of randomly generating new bytes. **/
    static final boolean GENERATE_EOF_WHEN_OUT = Boolean.getBoolean("jqf.ei.GENERATE_EOF_WHEN_OUT");

    /** Baseline number of mutated children to produce from a given parent input. */
    static final int NUM_CHILDREN_BASELINE = 50;

    /** Multiplication factor for number of children to produce for favored inputs. */
    static final int NUM_CHILDREN_MULTIPLIER_FAVORED = 20;

    /** Mean number of mutations to perform in each round. */
    static final double MEAN_MUTATION_COUNT = 8.0;

    /** Mean number of contiguous bytes to mutate in each mutation. */
    static final double MEAN_MUTATION_SIZE = 4.0; // Bytes

    /** Max number of contiguous bytes to splice in from another input during the splicing stage. */
    static final int MAX_SPLICE_SIZE = 64; // Bytes

    /** Whether to splice only in the same sub-tree */
    static final boolean SPLICE_SUBTREE = Boolean.getBoolean("jqf.ei.SPLICE_SUBTREE");

    /** Whether to save inputs that only add new coverage bits (but no new responsibilities). */
    static final boolean SAVE_NEW_COUNTS = true;

    /** Whether to steal responsibility from old inputs (this increases computation cost). */
    static final boolean STEAL_RESPONSIBILITY = Boolean.getBoolean("jqf.ei.STEAL_RESPONSIBILITY");

    /** Probability of splicing in getOrGenerateFresh() */
    static final double DEMAND_DRIVEN_SPLICING_PROBABILITY = 0;

    /**
     * @param testName the name of test to display on the status screen
     * Creates a new execution-index-parametric guidance.
     *
     * @param duration the amount of time to run fuzzing for, where
     *                 {@code null} indicates unlimited time.
     * @param outputDirectory the directory where fuzzing results will be written
     * @throws IOException if the output directory could not be prepared
     */
    public ZestGuidance(String testName, Duration duration, File outputDirectory) throws IOException {
        this.testName = testName;
        this.maxDurationMillis = duration != null ? duration.toMillis() : Long.MAX_VALUE;
        this.outputDirectory = outputDirectory;
        SingleSnoop.setCoverageListener(runCoverage);
        this.blind = Boolean.getBoolean("jqf.ei.TOTALLY_RANDOM");
        prepareOutputDirectory();

        // Try to parse the single-run timeout
        String timeout = System.getProperty("jqf.ei.TIMEOUT");
        if (timeout != null && !timeout.isEmpty()) {
            try {
                // Interpret the timeout as milliseconds (just like `afl-fuzz -t`)
                this.singleRunTimeoutMillis = Long.parseLong(timeout);
            } catch (NumberFormatException e1) {
                throw new IllegalArgumentException("Invalid timeout duration: " + timeout);
            }
        }
    }

    /**
     * @param testName the name of test to display on the status screen
     * @param duration the amount of time to run fuzzing for, where
     *                 {@code null} indicates unlimited time.
     * @param outputDirectory the directory where fuzzing results will be written
     * @param seedInputFiles one or more input files to be used as initial inputs
     * @throws IOException if the output directory could not be prepared
     */
    public ZestGuidance(String testName, Duration duration, File outputDirectory, File... seedInputFiles) throws IOException {
        this(testName, duration, outputDirectory);
        for (File seedInputFile : seedInputFiles) {
            seedInputs.add(new SeedInput(seedInputFile));
        }
    }

    private void prepareOutputDirectory() throws IOException {

        // Create the output directory if it does not exist
        if (!outputDirectory.exists()) {
            if (!outputDirectory.mkdirs()) {
                throw new IOException("Could not create output directory" +
                        outputDirectory.getAbsolutePath());
            }
        }

        // Make sure we can write to output directory
        if (!outputDirectory.isDirectory() || !outputDirectory.canWrite()) {
            throw new IOException("Output directory is not a writable directory: " +
                    outputDirectory.getAbsolutePath());
        }

        // Name files and directories after AFL
        this.savedInputsDirectory = new File(outputDirectory, "corpus");
        this.savedInputsDirectory.mkdirs();
        this.savedFailuresDirectory = new File(outputDirectory, "failures");
        this.savedFailuresDirectory.mkdirs();
        this.statsFile = new File(outputDirectory, "plot_data");
        this.logFile = new File(outputDirectory, "fuzz.log");
        this.currentInputFile = new File(outputDirectory, ".cur_input");


        // Delete everything that we may have created in a previous run.
        // Trying to stay away from recursive delete of parent output directory in case there was a
        // typo and that was not a directory we wanted to nuke.
        // We also do not check if the deletes are actually successful.
        statsFile.delete();
        logFile.delete();
        for (File file : savedInputsDirectory.listFiles()) {
            file.delete();
        }
        for (File file : savedFailuresDirectory.listFiles()) {
            file.delete();
        }


        statsWriter = new PrintWriter(new FileWriter(this.statsFile));
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                if(statsWriter != null){
                    statsWriter.flush();
                    statsWriter.close();
                }
            }
        }));

        appendToStatsFile("# unix_time, cycles_done, cur_path, paths_total, pending_total, " +
                "pending_favs, unique_crashes, unique_hangs, max_depth, execs_per_sec, valid_inputs, invalid_inputs, valid_cov");



    }

    static PrintWriter statsWriter;

    private static void appendToStatsFile(String line){
        statsWriter.println(line);
    }

    private void appendLineToFile(File file, String line) throws GuidanceException {
        try (PrintWriter out = new PrintWriter(new FileWriter(file, true))) {
            out.println(line);
        } catch (IOException e) {
            throw new GuidanceException(e);
        }

    }

    private void infoLog(String str, Object... args) {
        if (verbose) {
            String line = String.format(str, args);
            if (logFile != null) {
                appendLineToFile(logFile, line);

            } else {
                System.err.println(line);
            }
        }
    }

    private String millisToDuration(long millis) {
        long seconds = TimeUnit.MILLISECONDS.toSeconds(millis % TimeUnit.MINUTES.toMillis(1));
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis % TimeUnit.HOURS.toMillis(1));
        long hours = TimeUnit.MILLISECONDS.toHours(millis);
        String result = "";
        if (hours > 0) {
            result = hours + "h ";
        }
        if (hours > 0 || minutes > 0) {
            result += minutes + "m ";
        }
        result += seconds + "s";
        return result;
    }

    // Call only if console exists
    private void displayStats() {
        assert (console != null);

        Date now = new Date();
        long intervalMilliseconds = now.getTime() - lastRefreshTime.getTime();
        if (intervalMilliseconds < STATS_REFRESH_TIME_PERIOD) {
            return;
        }
        long interlvalTrials = numTrials - lastNumTrials;
        long intervalExecsPerSec = interlvalTrials * 1000L / intervalMilliseconds;
        double intervalExecsPerSecDouble = interlvalTrials * 1000.0 / intervalMilliseconds;
        lastRefreshTime = now;
        lastNumTrials = numTrials;
        long elapsedMilliseconds = now.getTime() - startTime.getTime();
        long execsPerSec = numTrials * 1000L / elapsedMilliseconds;

        String currentParentInputDesc;
        if (seedInputs.size() > 0 || savedInputs.isEmpty()) {
            currentParentInputDesc = "<seed>";
        } else {
            Input currentParentInput = savedInputs.get(currentParentInputIdx);
            currentParentInputDesc = currentParentInputIdx + " ";
            currentParentInputDesc += currentParentInput.isFavored() ? "(favored)" : "(not favored)";
            currentParentInputDesc += " {" + numChildrenGeneratedForCurrentParentInput +
                    "/" + getTargetChildrenForParent(currentParentInput) + " mutations}";
        }

        int nonZeroCount = totalCoverage.getNonZeroCount();
        double nonZeroFraction = nonZeroCount * 100.0 / totalCoverage.size();
        int nonZeroValidCount = validCoverage.getNonZeroCount();
        double nonZeroValidFraction = nonZeroValidCount * 100.0 / validCoverage.size();

        console.printf("\033[2J");
        console.printf("\033[H");
        console.printf("Zest: Validity Fuzzing with Parametric Generators\n");
        console.printf("-------------------------------------------------\n");
        if (this.testName != null) {
            console.printf("Test name:            %s\n", this.testName);
        }
        console.printf("Results directory:    %s\n", this.outputDirectory.getAbsolutePath());
        if (SHOW_CONFIG) {
            if (blind) {
                console.printf("Config:               TOTALLY_RANDOM\n");
            } else {
                console.printf("Config:               DISABLE_EXECUTION_INDEXING = %s,\n" +
                                "                      STEAL_RESPONSIBILITY       = %s,\n" +
                                "                      SPLICE_SUBTREE             = %s\n\n",
                        DISABLE_EXECUTION_INDEXING, STEAL_RESPONSIBILITY, SPLICE_SUBTREE);
            }
        }
        console.printf("Elapsed time:         %s (%s)\n", millisToDuration(elapsedMilliseconds),
                maxDurationMillis == Long.MAX_VALUE ? "no time limit" : ("max " + millisToDuration(maxDurationMillis)));
        console.printf("Number of executions: %,d\n", numTrials);
        console.printf("Valid inputs:         %,d (%.2f%%)\n", numValid, numValid*100.0/numTrials);
        console.printf("Cycles completed:     %d\n", cyclesCompleted);
        console.printf("Unique failures:      %,d\n", uniqueFailures.size());
        console.printf("Queue size:           %,d (%,d favored last cycle)\n", savedInputs.size(), numFavoredLastCycle);
        console.printf("Current parent input: %s\n", currentParentInputDesc);
        console.printf("Execution speed:      %,d/sec now | %,d/sec overall\n", intervalExecsPerSec, execsPerSec);
        console.printf("Total coverage:       %,d \n", nonZeroCount);
        console.printf("Valid coverage:       %,d \n", nonZeroValidCount);

        String plotData = String.format("%d, %d, %d, %d, %d, %d, %d, %d, %d, %.2f, %d, %d",
                TimeUnit.MILLISECONDS.toSeconds(now.getTime()), cyclesCompleted, currentParentInputIdx,
                savedInputs.size(), 0, 0, uniqueFailures.size(), 0, 0, intervalExecsPerSecDouble,
                numValid, numTrials-numValid);
        appendToStatsFile(plotData);

    }

    public void setBlind(boolean blind) {
        this.blind = blind;
    }

    private int getTargetChildrenForParent(Input parentInput) {
        // Baseline is a constant
        int target = NUM_CHILDREN_BASELINE;

        // We like inputs that cover many things, so scale with fraction of max
        if (maxCoverage > 0) {
            target = (NUM_CHILDREN_BASELINE * parentInput.nonZeroCoverage) / maxCoverage;
        }

        // We absolutey love favored inputs, so fuzz them more
        if (parentInput.isFavored()) {
            target = target * NUM_CHILDREN_MULTIPLIER_FAVORED;
        }

        return target;
    }

    private void completeCycle() {
        // Increment cycle count
        cyclesCompleted++;
        infoLog("\n# Cycle " + cyclesCompleted + " completed.");

        // Go over all inputs and do a sanity check (plus log)
        infoLog("Here is a list of favored inputs:");
        int sumResponsibilities = 0;
        numFavoredLastCycle = 0;
        for (Input input : savedInputs) {
            if (input.isFavored()) {
                int responsibleFor = input.responsibilities.size();
                infoLog("Input %d is responsible for %d branches", input.id, responsibleFor);
                sumResponsibilities += responsibleFor;
                numFavoredLastCycle++;
            }
        }
        int totalCoverageCount = totalCoverage.getNonZeroCount();
        infoLog("Total %d branches covered", totalCoverageCount);
        if (sumResponsibilities != totalCoverageCount) {
            throw new AssertionError("Responsibilty mistmatch");
        }

        // Refresh ecToInputLoc so that subsequent splices are only from favored inputs
        ecToInputLoc.clear();
        for (Input input : savedInputs) {
            if (input.isFavored()) {
                mapEcToInputLoc(input);
            }
        }

        // Break log after cycle
        infoLog("\n\n\n");
    }

    @Override
    public InputStream getInput() throws GuidanceException {
        // Clear coverage stats for this run
        runCoverage.clear();

        // Reset execution index state
        eiState = new ExecutionIndexingState();

        // Choose an input to execute based on state of queues
        if (!seedInputs.isEmpty()) {
            // First, if we have some specific seeds, use those
            currentInput = seedInputs.removeFirst();

            // Hopefully, the seeds will lead to new coverage and be added to saved inputs

        } else if (savedInputs.isEmpty()) {
            // If no seeds given try to start with something random
            if (!blind && numTrials > 100_000) {
                throw new GuidanceException("Too many trials without coverage; " +
                        "likely all assumption violations");
            }

            // Make fresh input using either list or maps
            infoLog("Spawning new input from thin air");
            currentInput = DISABLE_EXECUTION_INDEXING ? new LinearInput() : new MappedInput();
        } else {
            // The number of children to produce is determined by how much of the coverage
            // pool this parent input hits
            Input currentParentInput = savedInputs.get(currentParentInputIdx);
            int targetNumChildren = getTargetChildrenForParent(currentParentInput);
            if (numChildrenGeneratedForCurrentParentInput >= targetNumChildren) {
                // Select the next saved input to fuzz
                currentParentInputIdx = (currentParentInputIdx + 1) % savedInputs.size();

                // Count cycles
                if (currentParentInputIdx == 0) {
                    completeCycle();
                }

                numChildrenGeneratedForCurrentParentInput = 0;
            }
            Input parent = savedInputs.get(currentParentInputIdx);

            // Fuzz it to get a new input
            infoLog("Mutating input: %s", parent.desc);
            currentInput = parent.fuzz(random);
            numChildrenGeneratedForCurrentParentInput++;

            // Write it to disk for debugging
            try {
                writeCurrentInputToFile(currentInputFile);
            } catch (IOException ignore) { }

            // Start time-counting for timeout handling
            this.runStart = new Date();
            this.branchCount = 0;
        }


        // Return an input stream that uses the EI map
        return new InputStream() {
            int bytesRead = 0;

            @Override
            public int read() throws IOException {

                // lastEvent must not be null
                if (DISABLE_EXECUTION_INDEXING == false && lastEvent == null) {
                    throw new IOException("Could not compute execution index; no instrumentation?");
                }

                // For linear inputs, get with key = bytesRead (which is then incremented)
                if (currentInput instanceof LinearInput) {
                    LinearInput linearInput = (LinearInput) currentInput;
                    // Attempt to get a value from the list, or else generate a random value
                    int ret = linearInput.getOrGenerateFresh(bytesRead++, random);
                    // infoLog("read(%d) = %d", bytesRead, ret);
                    return ret;
                }

                // For mapped inputs, make a suitable execution index
                else {
                    MappedInput mappedInput = (MappedInput) currentInput;

                    // Get the execution index of the last event
                    ExecutionIndex executionIndex = eiState.getExecutionIndex(lastEvent);

                    // Attempt to get a value from the map, or else generate a random value
                    int value = mappedInput.getOrGenerateFresh(executionIndex, random);

                    // Keep track of how many bytes were read in this input
                    bytesRead++;

                    return value;

                }
            }
        };
    }

    @Override
    public boolean hasInput() {
        Date now = new Date();
        long elapsedMilliseconds = now.getTime() - startTime.getTime();
        return elapsedMilliseconds < maxDurationMillis;
    }

    @Override
    public void handleResult(Result result, Throwable error) throws GuidanceException {
        // Stop timeout handling
        this.runStart = null;

        // Increment run count
        this.numTrials++;

        // Trim input (remove unused keys)
        currentInput.gc();

        // It must still be non-empty
        assert(currentInput.size() > 0) : String.format("Empty input: %s", currentInput.desc);

        boolean valid = result == Result.SUCCESS;

        if (valid) {
            // Increment valid counter
            numValid++;
        }

        if (result == Result.SUCCESS || result == Result.INVALID) {

            // Coverage before
            int nonZeroBefore = totalCoverage.getNonZeroCount();
            int validNonZeroBefore = validCoverage.getNonZeroCount();

            // Compute a list of keys for which this input can assume responsiblity.
            // Newly covered branches are always included.
            // Existing branches *may* be included, depending on the heuristics used.
            // A valid input will steal responsibility from invalid inputs
            IntHashSet responsibilities = computeResponsibilities(valid);

            // Update total coverage
            boolean coverageBitsUpdated = totalCoverage.updateBits(runCoverage);
            if (valid) {
                validCoverage.updateBits(runCoverage);
            }

            // Coverage after
            int nonZeroAfter = totalCoverage.getNonZeroCount();
            if (nonZeroAfter > maxCoverage) {
                maxCoverage = nonZeroAfter;
            }
            int validNonZeroAfter = validCoverage.getNonZeroCount();

            // Possibly save input
            boolean toSave = false;
            String why = "";


            if (SAVE_NEW_COUNTS && coverageBitsUpdated) {
                toSave = true;
                why = why + "+count";
            }

            // Save if new total coverage found
            if (nonZeroAfter > nonZeroBefore) {
                // Must be responsible for some branch
                assert(responsibilities.size() > 0);
                toSave = true;
                why = why + "+cov";
            }

            if (validNonZeroAfter > validNonZeroBefore) {
                // Must be responsible for some branch
                assert(responsibilities.size() > 0);
                currentInput.valid = true;
                toSave = true;
                why = why + "+valid";
            }

            if (toSave) {

                infoLog("Saving new input (at run %d): " +
                                "input #%d " +
                                "of size %d; " +
                                "total coverage = %d",
                        numTrials,
                        savedInputs.size(),
                        currentInput.size(),
                        nonZeroAfter);

                // Save input to queue and to disk
                try {
                    saveCurrentInput(responsibilities, why);
                } catch (IOException e) {
                    throw new GuidanceException(e);
                }

            }
        } else if (result == Result.FAILURE || result == Result.TIMEOUT) {
            String msg = error.getMessage();

            // Get the root cause of the failure
            Throwable rootCause = error;
            while (rootCause.getCause() != null) {
                rootCause = rootCause.getCause();
            }

            // Attempt to add this to the set of unique failures
            if (uniqueFailures.add(Arrays.asList(rootCause.getStackTrace()))) {

                // Save crash to disk
                try {
                    int crashIdx = uniqueFailures.size()-1;
                    String saveFileName = String.format("id_%06d", crashIdx);
                    File saveFile = new File(savedFailuresDirectory, saveFileName);
                    writeCurrentInputToFile(saveFile);
                    infoLog("%s","Found crash: " + error.getClass() + " - " + (msg != null ? msg : ""));
                    String how = currentInput.desc;
                    String why = result == Result.FAILURE ? "+crash" : "+hang";
                    infoLog("Saved - %s %s %s", saveFile.getPath(), how, why);
                } catch (IOException e) {
                    throw new GuidanceException(e);
                }

            }
        }

        if (console != null) {
            displayStats();
        }

    }


    // Compute a set of branches for which the current input may assume responsibility
    private IntHashSet computeResponsibilities(boolean valid) {
        IntHashSet result = new IntHashSet();

        // This input is responsible for all new coverage
        IntList newCoverage = runCoverage.computeNewCoverage(totalCoverage);
        if (newCoverage.size() > 0) {
            result.addAll(newCoverage);
        }

        // If valid, this input is responsible for all new valid coverage
        if (valid) {
            IntList newValidCoverage = runCoverage.computeNewCoverage(validCoverage);
            if (newValidCoverage.size() > 0) {
                result.addAll(newValidCoverage);
            }
        }

        // Perhaps it can also steal responsibility from other inputs
        if (STEAL_RESPONSIBILITY) {
            int currentNonZeroCoverage = runCoverage.getNonZeroCount();
            int currentInputSize = currentInput.size();
            IntHashSet covered = new IntHashSet();
            covered.addAll(runCoverage.getCovered());

            // Search for a candidate to steal responsibility from
            candidate_search:
            for (Input candidate : savedInputs) {
                IntHashSet responsibilities = candidate.responsibilities;

                // Candidates with no responsibility are not interesting
                if (responsibilities.isEmpty()) {
                    continue candidate_search;
                }

                // To avoid thrashing, only consider candidates with either
                // (1) strictly smaller total coverage or
                // (2) same total coverage but strictly larger size
                if (candidate.nonZeroCoverage < currentNonZeroCoverage ||
                        (candidate.nonZeroCoverage == currentNonZeroCoverage &&
                                currentInputSize < candidate.size())) {

                    // Check if we can steal all responsibilities from candidate
                    IntIterator iter = responsibilities.intIterator();
                    while(iter.hasNext()){
                        int b = iter.next();
                        if (covered.contains(b) == false) {
                            // Cannot steal if this input does not cover something
                            // that the candidate is responsible for
                            continue candidate_search;
                        }
                    }
                    // If all of candidate's responsibilities are covered by the
                    // current input, then it can completely subsume the candidate
                    result.addAll(responsibilities);
                }

            }
        }

        return result;
    }

    private void writeCurrentInputToFile(File saveFile) throws IOException {
        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(saveFile))) {
            if (currentInput instanceof Iterable) {
                for (Integer b : ((Iterable<Integer>) currentInput)) {
                    assert (b >= 0 && b < 256);
                    out.write(b);
                }
            } else if (currentInput instanceof LinearInput) {
                ShortIterator iter = ((LinearInput) currentInput).shortIterator();
                while (iter.hasNext()) {
                    short b = iter.next();
                    assert (b >= 0 && b < 256);
                    out.write((int) b);
                }
            }
        }

    }

    private void saveCurrentInput(IntHashSet responsibilities, String why) throws IOException {

        // First, save to disk (note: we issue IDs to everyone, but only write to disk  if valid)
        int newInputIdx = numSavedInputs++;
        String saveFileName = String.format("id_%06d", newInputIdx);
        String how = currentInput.desc;
        File saveFile = new File(savedInputsDirectory, saveFileName);
        if (SAVE_ONLY_VALID == false || currentInput.valid) {
            writeCurrentInputToFile(saveFile);
            infoLog("Saved - %s %s %s", saveFile.getPath(), how, why);
        }

        // If not using guidance, do nothing else
        if (blind) {
            return;
        }

        // Second, save to queue
        savedInputs.add(currentInput);

        // Third, store basic book-keeping data
        currentInput.id = newInputIdx;
        currentInput.saveFile = saveFile;
        currentInput.coverage = new Coverage(runCoverage);
        currentInput.nonZeroCoverage = runCoverage.getNonZeroCount();
        currentInput.offspring = 0;
        savedInputs.get(currentParentInputIdx).offspring += 1;

        // Fourth, assume responsibility for branches
        currentInput.responsibilities = responsibilities;
        IntIterator iter = responsibilities.intIterator();
        while(iter.hasNext()){
            int b = iter.next();
            // If there is an old input that is responsible,
            // subsume it
            Input oldResponsible = responsibleInputs.get(b);
            if (oldResponsible != null) {
                oldResponsible.responsibilities.remove(b);
                // infoLog("-- Stealing responsibility for %s from input %d", b, oldResponsible.id);
            } else {
                // infoLog("-- Assuming new responsibility for %s", b);
            }
            // We are now responsible
            responsibleInputs.put(b, currentInput);
        }


        // Fifth, map executions to input locations for splicing
        mapEcToInputLoc(currentInput);

    }

    private void mapEcToInputLoc(Input input) {
        if (input instanceof MappedInput) {
            MappedInput mappedInput = (MappedInput) input;
            for (int offset = 0; offset < mappedInput.size(); offset++) {
                ExecutionIndex ei = mappedInput.orderedKeys.get(offset);
                ExecutionContext ec = new ExecutionContext(ei);
                ecToInputLoc.get(ec).add(new InputLocation(mappedInput, offset));
            }
        }

    }


    @Override
    public Consumer<TraceEvent> generateCallBack(Thread thread) {
        if (appThread != null) {
            throw new IllegalStateException(ZestGuidance.class +
                " only supports single-threaded apps at the moment");
        }
        appThread = thread;

        return this::handleEvent;
    }

    private void handleEvent(TraceEvent e) {
        // Set last event to this event
        lastEvent = e;

        // Update execution indexing logic
        if (!DISABLE_EXECUTION_INDEXING) {
            e.applyVisitor(this);
        }

        // Collect totalCoverage
        runCoverage.handleEvent(e);
        // Check for possible timeouts every so often
        if (this.singleRunTimeoutMillis > 0 &&
                this.runStart != null && (++this.branchCount) % 10_000 == 0) {
            long elapsed = new Date().getTime() - runStart.getTime();
            if (elapsed > this.singleRunTimeoutMillis) {
                throw new TimeoutException(elapsed, this.singleRunTimeoutMillis);
            }
        }
    }

    @Override
    public void visitCallEvent(CallEvent c) {
        eiState.pushCall(c);
    }

    @Override
    public void visitReturnEvent(ReturnEvent r) {
        eiState.popReturn(r);
    }

    /**
     * Returns a reference to the coverage statistics.
     * @return a reference to the coverage statistics
     */
    public Coverage getTotalCoverage() {
        return totalCoverage;
    }

    /**
     * A candidate or saved test input that maps objects of type K to bytes.
     */
    public static abstract class Input<K> {

        /**
         * The file where this input is saved.
         *
         * <p>This field is null for inputs that are not saved.</p>
         */
        File saveFile = null;

        /**
         * An ID for a saved input.
         *
         * <p>This field is -1 for inputs that are not saved.</p>
         */
        int id;

        /**
         * The description for this input.
         *
         * <p>This field is modified by the construction and mutation
         * operations.</p>
         */
        String desc;

        /**
         * The run coverage for this input, if the input is saved.
         *
         * <p>This field is null for inputs that are not saved.</p>
         */
        Coverage coverage = null;

        /**
         * The number of non-zero elements in `coverage`.
         *
         * <p>This field is -1 for inputs that are not saved.</p>
         *
         * <p></p>When this field is non-negative, the information is
         * redundant (can be computed using {@link Coverage#getNonZeroCount()}),
         * but we store it here for performance reasons.</p>
         */
        int nonZeroCoverage = -1;

        /**
         * The number of mutant children spawned from this input that
         * were saved.
         *
         * <p>This field is -1 for inputs that are not saved.</p>
         */
        int offspring = -1;

        /**
         * Whether this input resulted in a valid run.
         */
        boolean valid = false;

        /**
         * The set of coverage keys for which this input is
         * responsible.
         *
         * <p>This field is null for inputs that are not saved.</p>
         *
         * <p>Each coverage key appears in the responsibility set
         * of exactly one saved input, and all covered keys appear
         * in at least some responsibility set. Hence, this list
         * needs to be kept in-sync with {@link #responsibleInputs}.</p>
         */
        IntHashSet responsibilities = null;


        /**
         * Create an empty input.
         */
        public Input() {
            desc = "random";
        }

        /**
         * Create a copy of an existing input.
         *
         * @param toClone the input map to clone
         */
        public Input(Input toClone) {
            desc = String.format("src:%06d", toClone.id);
        }

        public abstract int getOrGenerateFresh(K key, Random random);
        public abstract int size();
        public abstract Input fuzz(Random random);
        public abstract void gc();



        /**
         * Returns whether this input should be favored for fuzzing.
         *
         * <p>An input is favored if it is responsible for covering
         * at least one branch.</p>
         *
         * @return
         */
        private boolean isFavored() {
            return responsibilities.size() > 0;
        }


        /**
         * Sample from a geometric distribution with given mean.
         *
         * Utility method used in implementing mutation operations.
         *
         * @param random a pseudo-random number generator
         * @param mean the mean of the distribution
         * @return a randomly sampled value
         */
        protected static int sampleGeometric(Random random, double mean) {
            double p = 1 / mean;
            double uniform = random.nextDouble();
            return (int) ceil(log(1 - uniform) / log(1 - p));
        }
    }

    public class LinearInput extends Input<Integer> {

        /** A list of byte values (0-255) ordered by their index. */
        protected ShortArrayList values;

        /** The number of bytes requested so far */
        protected int requested = 0;

        public LinearInput() {
            super();
            this.values = new ShortArrayList();
        }

        public LinearInput(LinearInput other) {
            super(other);
            this.values = new ShortArrayList(other.values.size());
            this.values.addAll(other.values);
        }


        @Override
        public int getOrGenerateFresh(Integer key, Random random) {
            // Otherwise, make sure we are requesting just beyond the end-of-list
            // assert (key == values.size());
            if (key != requested) {
                throw new IllegalStateException(String.format("Bytes from linear input out of order. " +
                        "Size = %d, Key = %d", values.size(), key));
            }

            // Don't generate over the limit
            if (requested >= MAX_INPUT_SIZE) {
                return -1;
            }

            // If it exists in the list, return it
            if (key < values.size()) {
                requested++;
                // infoLog("Returning old byte at key=%d, total requested=%d", key, requested);
                return values.get(key);
            }

            // Handle end of stream
            if (GENERATE_EOF_WHEN_OUT) {
                return -1;
            } else {
                // Just generate a random input
                int val = random.nextInt(256);
                values.add((short) val);
                requested++;
                // infoLog("Generating fresh byte at key=%d, total requested=%d", key, requested);
                return val;
            }
        }

        @Override
        public int size() {
            return values.size();
        }

        /**
         * Truncates the input list to remove values that were never actually requested.
         *
         * <p>Although this operation mutates the underlying object, the effect should
         * not be externally visible (at least as long as the test executions are
         * deterministic).</p>
         */
        @Override
        public void gc() {
            // Remove elements beyond "requested"
            ShortArrayList old = values;
            if(requested != old.size()) {
                values = new ShortArrayList(requested);
                for (int i = 0; i < old.size(); i++)
                    values.add(old.get(i));
                values.trimToSize();
            }
        }

        @Override
        public Input fuzz(Random random) {
            // Clone this input to create initial version of new child
            LinearInput newInput = new LinearInput(this);

            // Stack a bunch of mutations
            int numMutations = sampleGeometric(random, MEAN_MUTATION_COUNT);
            newInput.desc += ",havoc:"+numMutations;

            boolean setToZero = random.nextDouble() < 0.1; // one out of 10 times

            for (int mutation = 1; mutation <= numMutations; mutation++) {

                // Select a random offset and size
                int offset = random.nextInt(newInput.values.size());
                int mutationSize = sampleGeometric(random, MEAN_MUTATION_SIZE);

                // desc += String.format(":%d@%d", mutationSize, idx);

                // Mutate a contiguous set of bytes from offset
                for (int i = offset; i < offset + mutationSize; i++) {
                    // Don't go past end of list
                    if (i >= newInput.values.size()) {
                        break;
                    }

                    // Otherwise, apply a random mutation
                    int mutatedValue = setToZero ? 0 : random.nextInt(256);
                    newInput.values.set(i, (short) mutatedValue);
                }
            }

            return newInput;
        }

        public ShortIterator shortIterator() {
            return values.shortIterator();
        }
    }


    /**
     * A candidate test input represented as a map from execution indices
     * to integer values.
     *
     * <p>When a quickcheck-like generator requests a new ``random'' byte,
     * the current execution index is used to retrieve the input from
     * this input map (a fresh value is generated and stored in the map
     * if the key is not mapped).</p>
     *
     * <p>Inputs should not be publicly mutable. The only way to mutate
     * an input is via the {@link #fuzz} method which produces a new input
     * object with some values mutated.</p>
     */
    public class MappedInput extends Input<ExecutionIndex> {

        /**
         * Whether this input has been executed.
         *
         * When this field is {@code false}, the field {@link #orderedKeys}
         * is not yet populated and must not be used. When this field is {@code true},
         * the input should be considered immutable and neither {@link #orderedKeys} nor
         * {@link #valuesMap} must be modified.
         */
        protected boolean executed = false;

        /** A map from execution indexes to the byte (0-255) to be returned at that index. */
        protected LinkedHashMap<ExecutionIndex, Integer> valuesMap;

        /**
         * A list of execution indexes that are actually requested by the test program when
         * executed with this input.
         *
         * <p>This list is initially empty, and is populated at the end of the run, after which
         * it is frozen. The list of keys are in order of their occurrence in the execution
         * trace and can therefore be used to serialize the map into a sequence of bytes.</p>
         *
         */
        protected ArrayList<ExecutionIndex> orderedKeys = new ArrayList<>();


        private List<InputPrefixMapping> demandDrivenSpliceMap = new ArrayList<>();

        /**
         * Create an empty input map.
         */
        public MappedInput() {
            super();
            valuesMap = new LinkedHashMap<>();
        }

        /**
         * Create a copy of an existing input map.
         *
         * @param toClone the input map to clone
         */
        public MappedInput(MappedInput toClone) {
            super(toClone);
            valuesMap = new LinkedHashMap<>(toClone.valuesMap);
        }

        /**
         * Returns the size of this input, in terms of number of bytes
         * in its value map.
         *
         * @return the size of this input
         */
        public final int size() {
            return valuesMap.size();
        }

        /**
         * Returns the byte mapped by this input at a given offset.
         *
         * @param offset the byte offset in the input
         * @return the byte value at that offset
         *
         * @throws IndexOutOfBoundsException if the offset is negative or
         *      larger than {@link #size}()-1
         * @throws IllegalStateException if this method is called before the input
         *                               has been executed
         */
        private final int getValueAtOffset(int offset) throws IndexOutOfBoundsException, IllegalStateException {
            if (!executed) {
                throw new IllegalStateException("Cannot get with offset before execution");
            }

            // Return the mapping for the execution index queried at the offset
            ExecutionIndex ei = orderedKeys.get(offset);
            return valuesMap.get(ei);
        }


        /**
         * Returns the execution index mapped by this input at a given offset.
         *
         * @param offset the byte offset in the input
         * @return the execution index value at that offset
         *
         * @throws IndexOutOfBoundsException if the offset is negative or
         *      larger than {@link #size}()-1
         * @throws IllegalStateException if this method is called before the input
         *                               has been executed
         */
        private final ExecutionIndex getKeyAtOffset(int offset) throws IndexOutOfBoundsException, IllegalStateException {
            if (!executed) {
                throw new IllegalStateException("Cannot get with offset before execution");
            }

            // Return the execution index queried at the offset
            return orderedKeys.get(offset);
        }

        private InputPrefixMapping getInputPrefixMapping(ExecutionIndex ei) {
            for (InputPrefixMapping ipm : demandDrivenSpliceMap) {
                if (ei.hasPrefix(ipm.targetPrefix)) {
                    return ipm;
                }
            }
            return null;
        }


        /**
         * Retrieve a value for an execution index if mapped, else generate
         * a fresh value.
         *
         * @param key    the execution index of the trace event requesting a new byte
         * @param random the PRNG
         * @return the value to return to the quickcheck-like generator
         * @throws IllegalStateException if this method is called after the input
         *                               has been executed
         */
        @Override
        public int getOrGenerateFresh(ExecutionIndex key, Random random) throws IllegalStateException {
            if (executed) {
                throw new IllegalStateException("Cannot generate fresh values after execution");
            }

            // If we reached a limit, then just return EOF
            if (orderedKeys.size() >= MAX_INPUT_SIZE) {
                return -1;
            }

            // Try to get existing values
            Integer val = valuesMap.get(key);

            // If not, generate a new value
            if (val == null) {
                InputPrefixMapping ipm;

                // If we have an input prefix mapping for this execution index,
                // then splice from the source input
                if ((ipm = getInputPrefixMapping(key)) != null) {
                    Prefix sourcePrefix = ipm.sourcePrefix;
                    Suffix sourceSuffix = ipm.sourcePrefix.getEi().getSuffixOfPrefix(sourcePrefix);
                    ExecutionIndex sourceEi = new ExecutionIndex(sourcePrefix, sourceSuffix);
                    // The value can be taken from the source
                    val = ipm.sourceInput.getValueAtKey(sourceEi);
                }

                // If we could not splice or were unsuccessful, try to generate a new input
                if (val == null) {
                    if (GENERATE_EOF_WHEN_OUT) {
                        return -1;
                    }
                    if (random.nextDouble() < DEMAND_DRIVEN_SPLICING_PROBABILITY) {
                        // TODO: Find a random inputLocation with same EC,
                        // extract common suffix of sourceEi and targetEi,
                        // and map targetPrefix to sourcePrefix in the IPM


                    } else {
                        // Just generate a random input
                        val = random.nextInt(256);
                    }
                }

                // Put the new value into the map
                assert (val != null);

                valuesMap.put(key, val);
            }

            // Mark this key as visited
            orderedKeys.add(key);

            return val;
        }


        /**
         * Gets the byte mapped by this input at a given execution index.
         *
         * @param ei the execution index
         * @return the value mapped for this index, or {@code null} if no such mapping exists
         *
         * @throws IndexOutOfBoundsException if the offset is negative or
         *      larger than {@link #size}()-1
         */
        protected final Integer getValueAtKey(ExecutionIndex ei) throws IndexOutOfBoundsException {
            return valuesMap.get(ei);
        }

        /**
         * Sets the byte mapped by this input at a given execution index.
         *
         * @param ei  the execution index at which to insert
         * @param val the byte to insert
         *
         * @throws IndexOutOfBoundsException if the offset is negative or
         *      larger than {@link #size}()-1
         * @throws IllegalStateException if this method is called after the input
         *                               has been executed
         */
        protected final void setValueAtKey(ExecutionIndex ei, int val) throws IndexOutOfBoundsException, IllegalStateException {
            if (executed) {
                throw new IllegalStateException("Cannot set value before execution");
            }

            valuesMap.put(ei, val);
        }

        /**
         * Trims the input map of all keys that were never actually requested since
         * its construction.
         *
         * <p>Although this operation mutates the underlying object, the effect should
         * not be externally visible (at least as long as the test executions are
         * deterministic).</p>
         */
        @Override
        public void gc() {
            LinkedHashMap<ExecutionIndex, Integer> newMap = new LinkedHashMap<>();
            for (ExecutionIndex key : orderedKeys) {
                newMap.put(key, valuesMap.get(key));
            }
            valuesMap = newMap;

            // Set the `executed` flag
            executed = true;
        }

        /**
         * Return a new input derived from this one with some values
         * mutated.
         *
         * Pass-through to {@link #fuzz(Random, Map)}
         *
         */
        @Override
        public Input fuzz(Random random) {
            return fuzz(random, ZestGuidance.this.ecToInputLoc);
        }

        /**
         * Return a new input derived from this one with some values
         * mutated.
         *
         * <p>This method performs one or both of random mutations
         * and splicing.</p>
         *
         * <p>Random mutations are done by performing M
         * mutation operations each on a random contiguous sequence of N bytes,
         * where M and N are sampled from a geometric distribution with mean
         * {@link #MEAN_MUTATION_COUNT} and {@link #MEAN_MUTATION_SIZE}
         * respectively.</p>
         *
         * <p>Splicing is performed by first randomly choosing a location and
         * its corresponding execution context in this input's value map, and then
         * copying a contiguous sequence of up to Z bytes from another input,
         * starting with a location that also maps the same execution context.
         * Here, Z is sampled from a uniform distribution from 0 to
         * {@link #MAX_SPLICE_SIZE}.</p>
         *
         * @param random the PRNG
         * @return a newly fuzzed input
         */
        protected MappedInput fuzz(Random random, Map<ExecutionContext, ArrayList<InputLocation>> ecToInputLoc) {
            // Derive new input from this object as source
            MappedInput newInput = new MappedInput(this);

            // Maybe try splicing
            boolean splicingDone = false;

            // Only splice if we have been provided the ecToInputLoc
            if (ecToInputLoc != null) {

                // TODO: Do we really want splicing to be this frequent?
                if (random.nextBoolean()) {
                    final int MIN_TARGET_ATTEMPTS = 3;
                    final int MAX_TARGET_ATTEMPTS = 6;

                    int targetAttempts = MIN_TARGET_ATTEMPTS;

                    outer: for (int targetAttempt = 1; targetAttempt < targetAttempts; targetAttempt++) {

                        // Choose an execution context at which to splice at
                        // Note: We get EI and value from `this` rather than `newInput`
                        // because `this` has already been executed
                        int targetOffset = random.nextInt(newInput.valuesMap.size());
                        ExecutionIndex targetEi = this.getKeyAtOffset(targetOffset);

                        ExecutionContext targetEc = new ExecutionContext(targetEi);
                        int valueAtTarget = this.getValueAtOffset(targetOffset);

                        // Find a suitable input location to splice from
                        ArrayList<InputLocation> inputLocations = ecToInputLoc.get(targetEc);

                        // If this was a bad choice of target, try again without penalty if possible
                        if (inputLocations.size() == 0) {
                            // Try to increase the loop bound a little bit to get another chance
                            targetAttempts = Math.min(targetAttempts+1, MAX_TARGET_ATTEMPTS);
                            continue;
                        }

                        InputLocation inputLocation;

                        // Try a bunch of times
                        for (int attempt = 1; attempt <= 10; attempt++) {

                            // Get a candidate source location with the same execution context
                            inputLocation = inputLocations.get(random.nextInt(inputLocations.size()));
                            MappedInput sourceInput = inputLocation.input;
                            int sourceOffset = inputLocation.offset;


                            // Do not splice with ourselves
                            if (sourceInput == this) {
                                continue;
                            }

                            // Do not splice if the first value is the same in source and target
                            if (sourceInput.getValueAtOffset(sourceOffset) == valueAtTarget) {
                                continue;
                            }

                            int splicedBytes = 0;
                            if (!DISABLE_EXECUTION_INDEXING && SPLICE_SUBTREE) {
                                // Do not splice if there is no common suffix between EI of source and target
                                ExecutionIndex sourceEi = sourceInput.getKeyAtOffset(sourceOffset);
                                Suffix suffix = targetEi.getCommonSuffix(sourceEi);
                                if (suffix.size() == 0) {
                                    continue;
                                }

                                // Extract the source and target prefixes
                                Prefix sourcePrefix = sourceEi.getPrefixOfSuffix(suffix);
                                Prefix targetPrefix = targetEi.getPrefixOfSuffix(suffix);
                                assert (sourcePrefix.size() == targetPrefix.size());

                                // OK, this looks good. Let's splice!
                                int srcIdx = sourceOffset;
                                while (srcIdx < sourceInput.size()) {
                                    ExecutionIndex candidateEi = sourceInput.getKeyAtOffset(srcIdx);
                                    if (candidateEi.hasPrefix(sourcePrefix) == false) {
                                        // We are no more in the same sub-tree as sourceEi
                                        break;
                                    }
                                    Suffix spliceSuffix = candidateEi.getSuffixOfPrefix(sourcePrefix);
                                    ExecutionIndex spliceEi = new ExecutionIndex(targetPrefix, spliceSuffix);
                                    newInput.valuesMap.put(spliceEi, sourceInput.valuesMap.get(candidateEi));

                                    srcIdx++;
                                }
                                splicedBytes = srcIdx - sourceOffset;
                            } else {

                                int spliceSize = 1 + random.nextInt(MAX_SPLICE_SIZE);
                                int src = sourceOffset;
                                int tgt = targetOffset;
                                int srcSize = sourceInput.size();
                                int tgtSize = newInput.size();
                                while (splicedBytes < spliceSize && src < srcSize && tgt < tgtSize) {
                                    int val = sourceInput.getValueAtOffset(src);
                                    ExecutionIndex key = this.getKeyAtOffset(tgt);
                                    newInput.setValueAtKey(key, val);

                                    splicedBytes++;
                                    src++;
                                    tgt++;
                                }
                            }

                            // Complete splicing
                            splicingDone = true;
                            newInput.desc += String.format(",splice:%06d:%d@%d->%d", sourceInput.id, splicedBytes,
                                    sourceOffset, targetOffset);

                            break outer; // Stop more splicing attempts!

                        }
                    }
                }
            }

            // Maybe do random mutations
            if (splicingDone == false || random.nextBoolean()) {

                // Stack a bunch of mutations
                int numMutations = sampleGeometric(random, MEAN_MUTATION_COUNT);
                newInput.desc += ",havoc:"+numMutations;

                boolean setToZero = random.nextDouble() < 0.1; // one out of 10 times

                for (int mutation = 1; mutation <= numMutations; mutation++) {

                    // Select a random offset and size
                    int offset = random.nextInt(newInput.valuesMap.size());
                    int mutationSize = sampleGeometric(random, MEAN_MUTATION_SIZE);

                    // desc += String.format(":%d@%d", mutationSize, idx);

                    // Iterate over all entries in the value map
                    Iterator<Map.Entry<ExecutionIndex, Integer>> entryIterator
                            = newInput.valuesMap.entrySet().iterator();
                    for (int i = 0; entryIterator.hasNext(); i++) {
                        Map.Entry<ExecutionIndex, Integer> e = entryIterator.next();
                        // Only mutate `mutationSize` contiguous entries from
                        // the randomly selected `idx`.
                        if (i >= offset && i < (offset + mutationSize)) {
                            // Apply a random mutation
                            int mutatedValue = setToZero ? 0 : random.nextInt(256);
                            e.setValue(mutatedValue);
                        }
                    }
                }
            }

            return newInput;

        }

        public Iterator<Integer> iterator() {
            return new Iterator<Integer>() {

                Iterator<ExecutionIndex> keyIt = orderedKeys.iterator();

                @Override
                public boolean hasNext() {
                    return keyIt.hasNext();
                }

                @Override
                public Integer next() {
                    return valuesMap.get(keyIt.next());
                }
            };
        }
    }

    public class SeedInput extends LinearInput {
        final File seedFile;
        final InputStream in;

        public SeedInput(File seedFile) throws IOException {
            super();
            this.seedFile = seedFile;
            this.in = new BufferedInputStream(new FileInputStream(seedFile));
            this.desc = "seed";
        }

        @Override
        public int getOrGenerateFresh(Integer key, Random random) {
            int value;
            try {
                value = in.read();
            } catch (IOException e) {
                throw new GuidanceException("Error reading from seed file: " + seedFile.getName(), e);

            }

            // assert (key == values.size())
            if (key != values.size()) {
                throw new IllegalStateException(String.format("Bytes from seed out of order. " +
                        "Size = %d, Key = %d", values.size(), key));
            }

            if (value >= 0) {
                requested++;
                values.add((short) value);
            }

            // If value is -1, then it is returned (as EOF) but not added to the list
            return value;
        }

        @Override
        public void gc() {
            super.gc();
            try {
                in.close();
            } catch (IOException e) {
                throw new GuidanceException("Error closing seed file:" + seedFile.getName(), e);
            }
        }

    }


    static class InputLocation {
        private final MappedInput input;
        private final int offset;

        InputLocation(MappedInput input, int offset) {
            this.input = input;
            this.offset = offset;
        }
    }

    static class InputPrefixMapping {
        private final MappedInput sourceInput;
        private final Prefix sourcePrefix;
        private final Prefix targetPrefix;

        InputPrefixMapping(MappedInput sourceInput, Prefix sourcePrefix, Prefix targetPrefix) {
            this.sourceInput = sourceInput;
            this.sourcePrefix = sourcePrefix;
            this.targetPrefix = targetPrefix;
        }
    }

}
