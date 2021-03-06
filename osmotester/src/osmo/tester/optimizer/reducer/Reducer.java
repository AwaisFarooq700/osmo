package osmo.tester.optimizer.reducer;

import osmo.common.Randomizer;
import osmo.common.TestUtils;
import osmo.common.Logger;
import osmo.tester.OSMOConfiguration;
import osmo.tester.OSMOTester;
import osmo.tester.generator.SingleInstanceModelFactory;
import osmo.tester.generator.testsuite.TestCase;
import osmo.tester.model.FSM;
import osmo.tester.model.FSMTransition;
import osmo.tester.optimizer.multiosmo.MultiOSMO;
import osmo.tester.parser.MainParser;
import osmo.tester.parser.ParserResult;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs a search for specific target in the model and once found, tries to reduce the path to it to minimal.
 * In d mode, the target is to find a part of the model that throws an exception.
 * In requirements mode, the target is to find shortest paths to all requirements, one for each.
 *
 * @author Teemu Kanstren
 */
public class Reducer {
  private static final Logger log = new Logger(Reducer.class);
  /** Shared configuration for all generators, only the seed will be different. */
  private OSMOConfiguration osmoConfig = new OSMOConfiguration();
  /** How many generators to run in parallel? */
  private final int parallelism;
  /** The thread pool for running the generator tasks. */
  private final ExecutorService pool;
  /** If true,  the osmo-output directory will be deleted at the beginning. */
  private boolean deleteOldOutput;
  /** Configuration for reduction tasks. */
  private final ReducerConfig config;
  /** Used to create random seeds for different tasks. Takes seed itself from reducer config. */
  private Randomizer rand;
  private TestCase startTest = null;

  public Reducer(ReducerConfig config) {
    parallelism = config.getParallelism();
    pool = Executors.newFixedThreadPool(parallelism);
    this.config = config;
  }

  public OSMOConfiguration getOsmoConfig() {
    return osmoConfig;
  }

  public void setStartTest(TestCase startTest) {
    this.startTest = startTest;
  }

  /**
   * Starts search using the given generation configuration and given number of parallel threads.
   * @return Final reducer state, contains information on how the reduction finished.
   */
  public ReducerState search() {
    check();
    //when reducing the path options, we can hit a spot where there is no path forward.
    //we do not want the whole process to end there
    osmoConfig.setFailWhenNoWayForward(false);
    //we do not wish to have excess spam logs as we produce our own traces in the end
    osmoConfig.setSequenceTraceRequested(false);
    //this also avoids the generator from spamming excess prints
    osmoConfig.setExploring(true);
    osmoConfig.setPrintExplorationErrors(config.isPrintExplorationErrors());
    //and if we get an e we want to continue and find more errors.. since errors are our targets
    osmoConfig.setStopGenerationOnError(false);
    //using the base seed we create a randomizer to create seeds for all tasks
    rand = new Randomizer(config.getSeed());

    //we need a list of all possible step names in the model to build reports in the end
    MainParser parser = new MainParser(osmoConfig);
    ParserResult parserResult = parser.parse(0, osmoConfig.getFactory(), null);
    FSM fsm = parserResult.getFsm();
    Collection<FSMTransition> transitions = fsm.getTransitions();
    List<String> allSteps = new ArrayList<>();
    for (FSMTransition transition : transitions) {
      allSteps.add(transition.getStringName());
    }
    ReducerState state = new ReducerState(allSteps, config);
    if (startTest != null) state.addTest(startTest);

    if (config.getRequirementsTarget() > 0) {
      requirementsSearch(state);
    } else {
      debugSearch(state);
    }

    pool.shutdown();
    writeReports(allSteps, state);
    return state;
  }

  /**
   * Runs the search for the debugging configuration.
   *
   * @param state To use in search.
   */
  private void debugSearch(ReducerState state) {
    //in the initial fuzzy search, we stop on first found instance. from this we shorten and then fuzz again
    state.startInitialSearch();
    //TODO: move these timeout sets into state
    long initialTime= config.getInitialUnit().toMillis(config.getInitialTime());
    log.i("Running initial search");
    if (startTest == null) fuzz1(state, initialTime);
    //need to clear scripts after initial fuzz in order to allow variation in search
    osmoConfig.setScripts(null);
    state.startShortening();
    if (state.getTests().size() == 0) {
      System.out.println("Could not find any test.");
      return;
    }
    log.i("Running shortening");
    long shorteningTime = config.getShorteningUnit().toMillis(config.getShorteningTime());
    shorten(state, shorteningTime);
    state.startFinalFuzz();
    long fuzzTime = config.getFuzzUnit().toMillis(config.getFuzzTime());
    log.i("Running final fuzz");
    fuzz2(state, fuzzTime);

    int minimum = state.getMinimum();

    state.prune();

    if (minimum < config.getLength()) {
      System.out.println("Got down to:" + minimum);
    } else {
      System.out.println("Failed to find errors!");
    }
  }

  /**
   * Runs the search for the requirements configuration.
   *
   * @param state To use in search.
   */
  private void requirementsSearch(ReducerState state) {
    boolean shouldRun = true;
    long fuzzTime = config.getFuzzUnit().toMillis(config.getFuzzTime());
    long shorteningTime = config.getShorteningUnit().toMillis(config.getShorteningTime());
    while (shouldRun) {
      state.startInitialSearch();
      log.i("Running initial search with tests:" + state.getTests());
      fuzz1(state, fuzzTime);
      if (state.getTests().size() > 0) {
        //need to clear scripts after initial fuzz in order to allow variation in search
        osmoConfig.setScripts(null);
        log.d("Searching with tests: " + state.getTests());
        state.startShortening();
        log.i("Running shortening");
        //we do not care about finding many options as we just need one for a requirement, thus no fuzz in end
        shorten(state, shorteningTime);
      }
      log.i("Next requirement");
      //move to next requirement, or stop if all have been processed
      shouldRun = state.nextRequirement();
      log.d("Search continue status:" + shouldRun);
    }
    state.prune();
  }

  /**
   * Write the d reports for found steps and invariants.
   *
   * @param allSteps All steps in test model, used in test or not.
   * @param state End state for reduction to report.
   */
  private void writeReports(List<String> allSteps, ReducerState state) {
    //first we find all invariants and write the basic report on those
    Analyzer analyzer = new Analyzer(allSteps, state);
    analyzer.analyze();
    analyzer.writeReport("reducer-final");

    //then we write the traces for best tests, limiting to max of 100 traces
    List<TestCase> tests = state.getTests();
    List<TestCase> traced = new ArrayList<>();
    int i = 1;
    for (TestCase test : tests) {
      i++;
      //only print max of 100 traces
      if (i > 100) break;
      traced.add(test);
    }
    String filename = analyzer.getPath() + "final-tests";
    OSMOTester.writeTrace(filename, traced, config.getSeed(), osmoConfig);
    String filename2 = analyzer.getPath() + "final-fuzz-times.csv";
    TestUtils.write(state.getFinalFuzzTimes(), filename2);
  }

  /**
   * Fuzz tests, that is create random test cases for given configuration.
   *
   * @param state Reduction state to use for fuzzing.
   * @param waitTime Time to wait until signalling stop for fuzz tasks if not stopped yet.
   */
  private void fuzz1(ReducerState state, long waitTime) {
    Collection<Runnable> tasks = new ArrayList<>();
    //in d mode the requirements tests are not set, thus this becomes null as expected
    TestCase test = state.getRequirementTest();
    for (int i = 0; i < parallelism; i++) {
      FuzzerTask task = new FuzzerTask(osmoConfig, test, rand.nextLong(), state);
      tasks.add(task);
    }
    runTasks(tasks, state, waitTime);
  }

  /**
   * Fuzz tests, that is create random test cases for given configuration.
   *
   * @param state Reduction state to use for fuzzing.
   * @param waitTime Time to wait until signalling stop for fuzz tasks if not stopped yet.
   */
  private void fuzz2(ReducerState state, long waitTime) {
    Collection<Runnable> tasks = new ArrayList<>();
    List<TestCase> tests = state.getTests();
    while (tests.size() < parallelism) tests.addAll(tests);
    for (TestCase test : tests) {
      FuzzerTask task = new FuzzerTask(osmoConfig, test, rand.nextLong(), state);
      tasks.add(task);
    }
    runTasks(tasks, state, waitTime);
  }

  /**
   * Run the given set of search tasks, fuzz or shortening.
   *
   * @param state    Current search state.
   * @param waitTime Milliseconds to wait for completion before signalling tasks to end the search.
   */
  private void runTasks(Collection<Runnable> tasks, ReducerState state, long waitTime) {
    Collection<Future> futures = new ArrayList<>();
    for (Runnable task : tasks) {
      Future future = pool.submit(task);
      log.d("task submitted to pool");
      futures.add(future);
    }
    try {
      //wait for search to finish
      synchronized (state) {
        log.d("waiting time " + waitTime);
        //we might have finished before coming here if previous searches were 100% success
        if (!state.isDone()) state.wait(waitTime);
      }
      log.i("Notifying state to stop just in case..");
      //if overall timeout instead of reduction, we terminate searches (signal them to stop)
      state.endSearch();
    } catch (InterruptedException e) {
      log.d("Could not sleep", e);
    }
    //here we wait for the tasks to finish properly
    for (Future future : futures) {
      try {
        future.get();
      } catch (Exception e) {
        throw new RuntimeException("Failed to run a reducer task", e);
      }
    }
  }

  /**
   * Tries to shorten current found test.
   * Meaning, removes a step at a time, sees if still produces exception, if so repeat with shorter.
   *
   * @param state Current search state.
   */
  private void shorten(ReducerState state, long waitTime) {
    Collection<Runnable> tasks = new ArrayList<>();
    List<TestCase> tests = state.getTests();
    for (TestCase test : tests) {
      ShortenerTask task = new ShortenerTask(osmoConfig, test, rand.nextLong(), state);
      tasks.add(task);
    }
//    for (int i = 0; i < parallelism; i++) {
//      ShortenerTask task = new ShortenerTask(osmoConfig, rand.nextLong(), state);
//      tasks.add(task);
//    }
    runTasks(tasks, state, waitTime);
  }

  /**
   * Checks if current configuration has known issues, such as sharing model instances over tasks.
   * Also deletes old output if so configured.
   */
  private void check() {
    if (osmoConfig.getFactory() instanceof SingleInstanceModelFactory) {
      System.out.println(MultiOSMO.ERROR_MSG);
    }
    if (deleteOldOutput) {
      TestUtils.recursiveDelete("osmo-output");
    }
  }

  public void setDeleteOldOutput(boolean deleteOldOutput) {
    this.deleteOldOutput = deleteOldOutput;
  }

  public boolean isDeleteOldOutput() {
    return deleteOldOutput;
  }
}
