package osmo.tester.explorer;

import osmo.common.Logger;
import osmo.tester.coverage.TestCoverage;
import osmo.tester.explorer.trace.TraceNode;
import osmo.tester.generator.MainGenerator;
import osmo.tester.generator.algorithm.FSMTraversalAlgorithm;
import osmo.tester.generator.testsuite.TestCase;
import osmo.tester.generator.testsuite.TestCaseStep;
import osmo.tester.generator.testsuite.TestSuite;
import osmo.tester.model.FSM;
import osmo.tester.model.FSMTransition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;

/**
 * This is what runs a single iteration of exploration, controlling the high-level flow.
 * Invoked from the {@link ExplorerAlgorithm} each time a new step needs to be taken.
 *
 * @author Teemu Kanstren
 */
public class MainExplorer implements Runnable {
  private static final Logger log = new Logger(MainExplorer.class);
  /** The representation of the exploration trace. */
  private final TraceNode trace;
  /** The script so far, that is the current test case steps. We look for the next step on top of this. */
  private List<String> script;
  /** The overall state of exploration. */
  private ExplorationState state;
  /** The thread pool used for exploration. */
  private static ForkJoinPool explorationPool;
  /** The chosen step (name). */
  private String result = null;
  /** The test suite being generated. */
  private TestSuite suite = null;
  /** The test model. */
  private FSM fsm = null;
  /** If set to true, should abort exploration (path finished). */
  private boolean shouldStop = false;
  /** For collecting possible coverage metrics. */
  private final Collection<String> possibleStepPairs = new LinkedHashSet<>();
  /** For collecting possible coverage metrics. */
  private final Map<String, Collection<String>> possibleVVs = new LinkedHashMap<>();
  /** For collecting possible coverage metrics. */
  private final Map<String, Collection<String>> possibleStates = new LinkedHashMap<>();
  /** For collecting possible coverage metrics. */
  private final Map<String, Collection<String>> possibleStatePairs = new LinkedHashMap<>();
  /** To measure exploration time. */
  private long starttime = 0;
  /** To measure exploration time. */
  private long endtime = 0;
  /** Longest test length in current exploration. Used as max limiter for pruning.
   * Note that some may be shorter but in that case should achieve same score faster as we only look at highest scorers. */
  private int longest = 0;

  /**
   * @param trace  For storing the test generation trace.
   */
  public MainExplorer(TraceNode trace) {
    this.trace = trace;
  }

  public long getDuration() {
    return endtime - starttime;
  }

  public void init(FSM fsm, TestSuite suite, ExplorationState state, List<String> script, int parallelism) {
    if (explorationPool == null) explorationPool = new ForkJoinPool(parallelism);
    this.fsm = fsm;
    this.suite = suite;
    this.state = state;
    this.script = script;
  }

  /**
   * Explores the configured model, suite, and test to the given depth.
   * This is where the main execution from the {@link ExplorerAlgorithm} enters through.
   * The online version runs this for each test step, the offline for each test case.
   */
  public void explore() {
    starttime = System.currentTimeMillis();
    Thread t = new Thread(this);
    t.setDaemon(true);
    t.start();
  }

  public void run() {
    try {
      runrun();
    } catch (Exception e) {
      //if the exploration of this path is already stopped, there will likely be exceptions flying with the
      //change of seed, the path is no longer valid.
      if (shouldStop) return;
      e.printStackTrace();
      throw e;
    } finally {
      synchronized (this) {
        shouldStop = true;
        notifyAll();
      }
    }
  }

  /**
   * Delegation target to provide a clear try-catch in run().
   */
  public void runrun() {
    MainGenerator generator = ExplorationHelper.initPath(state, script);
    List<FSMTransition> enabled = generator.getEnabled();
    ExplorationConfiguration config = state.getConfig();
    PathExplorer explorer = new PathExplorer(state, config.getDepth()-1, trace, enabled, script, explorationPool);
    List<TestCase> testCases = explorationPool.invoke(explorer);
    if (shouldStop || testCases == null) {
      log.d("Exploration has stopped on the fly.");
      return;
    }
    if (testCases.size() == 0) {
      //this can happen if test end condition hits with 0 transitions available in the future
      log.d("No test cases to choose from. Assuming empty set, where no transition enabled.");
      shouldStop = true;
      return;
    }
    String best = findBest(testCases);
    setResult(best);
  }

  /**
   * When exploration threads have finished, this is used to set the result and notify waiting components.
   *
   * @param result The name of the chosen test step to be executed next.
   */
  private synchronized void setResult(String result) {
    endtime = System.currentTimeMillis();
    this.result = result;
    notifyAll();
  }

  /**
   * Provides access to the exploration result.
   * If one is not yet available, waits for the exploration threads to finish.
   *
   * @return The name of the chosen test step to execute next.
   */
  public synchronized String getResult() {
    while (result == null && !shouldStop) {
      try {
        wait();
      } catch (InterruptedException e) {
        log.e("Wait interrupted", e);
      }
    }
    return result;
  }

  /**
   * Calculates the scores for the given potential test cases and picks the test step leading to the best of those.
   * If there are more than one potential path with equal best score, the one where the next step produces the
   * highest score is chosen. If there are still several with equal best score and equal score for the next step,
   * then one of these is chosen in random.
   *
   * @param from The set of test paths to choose from.
   * @return The name of the transition chosen as the best next step.
   */
  public String findBest(List<TestCase> from) {
    log.d("finding best from:" + from);

    collectMetrics(from);
//    calculateAddedCoverages(from, 0);
    List<TestCase> choices = pruneBest(from, -1);

    log.d("pruned:" + choices);
    return findBestFrom(choices, script.size());
  }

  /**
   * Collect all coverage values that could have been covered by the different explored paths.
   *
   * @param from Where to look for the coverage metrics.
   */
  private void collectMetrics(List<TestCase> from) {
    TestCoverage tc = new TestCoverage(from);

    collectPossible(tc.getVariableValues(), possibleVVs);
    collectPossible(tc.getStates(), possibleStates);
    collectPossible(tc.getStatePairs(), possibleStatePairs);

    possibleStepPairs.addAll(tc.getStepPairs());
  }

  private void collectPossible(Map<String, Collection<String>> observed, Map<String, Collection<String>> to) {
    for (String key : observed.keySet()) {
      Collection<String> possibles = to.get(key);
      if (possibles == null) {
        possibles = new LinkedHashSet<>();
        to.put(key, possibles);
      }
      possibles.addAll(observed.get(key));
    }
  }

  /**
   * Prunes the set of overall equal explored choices to pick the set of tests that achieve the biggest
   * gains the fastest.
   *
   * @param choices What to prune.
   * @return The choice of tests that gain the most fastest. 1-N.
   */
  private List<TestCase> pruneBest(Collection<TestCase> choices, int count) {
    longest = 0;
    //this defines the highest score for the following step of tests
    int highest = Integer.MIN_VALUE;
    //best tests
    List<TestCase> optimum = new ArrayList<>();
    for (TestCase test : choices) {
      int length = test.getAllStepNames().size();
      //this can happen if some test is shorter but gets same score, e.g. end condition ends sooner and all score 0
      if (count >= length) continue;
      TestCaseStep step = null;
      if (count < 0) {
        step = test.getCurrentStep();
      } else {
        step = test.getSteps().get(count);
      }
      int score = step.getAddedCoverage();
      if (score > highest) {
        optimum.clear();
        highest = score;
      }
      if (score == highest) {
        //we are compiling list of longest for next round, if there is a long one that is not in next round we cannot use it
        //this is why the value can only be set at this point
        if (length > longest) longest = length;
        optimum.add(test);
      }
    }
    if (optimum.size() == 0) {
      //for some reason the algorithm still seems to do this, so we must provide the ultimate fallback
      optimum.addAll(choices);
    }
    return optimum;
  }

  /**
   * Finds the best next step from the given set of test paths, which already should have been determined to
   * all have equal overall best score. Includes checking which has the first step to give the best score, and if
   * all of these are equal, choosing one of them on random.
   * Also includes trimming longer tests, so only the tests that have the shortest overall path to gain highest score
   * are left before any other choices are made.
   *
   * @param from The set of potential test paths, assumed to have equal best score overall.
   * @param count   The number of test steps to consider for optimality.
   * @return The chosen best transition for next step.
   */
  public String findBestFrom(List<TestCase> from, int count) {
//    calculateAddedCoverages(from, count);

    List<TestCase> optimum = pruneBest(from, count);

    if (longest > (count+1) && optimum.size() > 1) {
      return findBestFrom(optimum, count + 1);
    }

    List<FSMTransition> choices = new ArrayList<>();
    int index = script.size();
    for (TestCase tc : optimum) {
      String tn = tc.getSteps().get(index).getName();
      FSMTransition t = fsm.getTransition(tn);
      choices.add(t);
    }

    ExplorationConfiguration config = state.getConfig();
    int salt = state.getSuiteCoverage().getTotalSteps();
    long seed = config.getSeed() + salt;
    FSMTraversalAlgorithm fallback = config.getFallback(seed, fsm);
    FSMTransition choice = fallback.choose(suite, choices);

    return choice.getStringName();
  }

  public Collection<String> getPossibleStepPairs() {
    return possibleStepPairs;
  }

  public Map<String, Collection<String>> getPossibleVariableValues() {
    return possibleVVs;
  }

  public Map<String, Collection<String>> getPossibleStates() {
    return possibleStates;
  }

  public Map<String, Collection<String>> getPossibleStatePairs() {
    return possibleStatePairs;
  }

  /**
   * Signals exploration to stop when possible.
   */
  public void stop() {
    shouldStop = true;
    synchronized (this) {
      notifyAll();
    }
  }
}
