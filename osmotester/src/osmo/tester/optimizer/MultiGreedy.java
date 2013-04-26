package osmo.tester.optimizer;

import osmo.common.Randomizer;
import osmo.common.log.Logger;
import osmo.tester.OSMOConfiguration;
import osmo.tester.coverage.ScoreConfiguration;
import osmo.tester.coverage.TestCoverage;
import osmo.tester.generator.endcondition.EndCondition;
import osmo.tester.generator.testsuite.TestCase;
import osmo.tester.model.FSM;
import osmo.tester.model.ModelFactory;
import osmo.tester.model.Requirements;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs multiple GreedyOptimizers in parallel to produce test variations, and in the end runs a single overall
 * optimization on the resulting set to produce the final test suite.
 * User needs to provide a factory to create configurations for the optimizer, as each one needs a different copy
 * with different model object instances, which are stored in the configuration, and a different seed for each
 * optimizer.
 * User also needs to provide a model factory for creating the model objects to be stored in the new configuration.
 * Note that with parallel executions, different executions might generate different test instances in different order.
 * The final optimized set can then contain different instances of test cases that add the same coverage criteria
 * to the test suite.
 * Thus the score achieved should be deterministic but the exact set of tests may have variation.
 *
 * @author Teemu Kanstren
 */
public class MultiGreedy {
  private static Logger log = new Logger(MultiGreedy.class);
  /** Configuration for the optimizer(s). */
  private final ScoreConfiguration optimizerConfig;
  /** The thread pool for running the optimizer tasks. */
  private final ExecutorService greedyPool;
  /** For randomization.. (doh) */
  private final Randomizer rand;
  /** The test model. */
  private FSM fsm = null;
  /** Number of tests a greedy optimizer will generate in an iteration. */
  private final int populationSize;
  /** Alternative to defining a factory for model objects is to give the classes that define the model objects. */
  private final Collection<Class> modelClasses = new ArrayList<>();
  private ModelFactory factory = null;
  private final EndCondition endCondition;

  public MultiGreedy(ScoreConfiguration optimizerConfig, int populationSize, EndCondition endCondition) {
    this(optimizerConfig, Runtime.getRuntime().availableProcessors(), populationSize, endCondition);
  }

  public MultiGreedy(ScoreConfiguration optimizerConfig, int parallelism, int populationSize, EndCondition endCondition) {
    this.optimizerConfig = optimizerConfig;
    greedyPool = Executors.newFixedThreadPool(parallelism);
    rand = new Randomizer(OSMOConfiguration.getSeed());
    this.populationSize = populationSize;
    this.endCondition = endCondition;
  }

  public void addModelClass(Class modelClass) {
    this.modelClasses.add(modelClass);
  }

  public void setFactory(ModelFactory factory) {
    this.factory = factory;
  }

  /**
   * Initializes a set of {@link GreedyOptimizer} instances to search for an optimal set of tests (test suite).
   *
   * @param optimizerCount The number of optimizers to instantiate.
   * @return The optimized set of tests.
   */
  public List<TestCase> search(int optimizerCount) {
    log.info("Starting search with " + optimizerCount + " optimizers");
    Collection<Future<List<TestCase>>> futures = new ArrayList<>();
    List<GreedyOptimizer> optimizers = new ArrayList<>();
    for (int i = 0 ; i < optimizerCount ; i++) {
      OSMOConfiguration.setSeed(rand.nextLong());
      GreedyOptimizer optimizer = new GreedyOptimizer(optimizerConfig, populationSize, endCondition);
      for (Class modelClass : modelClasses) {
        optimizer.addModelClass(modelClass);
      }
      optimizer.setFactory(factory);
      GreedyTask task = new GreedyTask(optimizer);
      Future<List<TestCase>> future = greedyPool.submit(task);
      log.debug("task submitted to pool");
      futures.add(future);
      optimizers.add(optimizer);
    }
    List<TestCase> allTests = new ArrayList<>();
    for (Future<List<TestCase>> future : futures) {
      try {
        allTests.addAll(future.get());
      } catch (Exception e) {
        throw new RuntimeException("Failed to run a (Multi) GreedyOptimizer", e);
      }
    }
    log.info("optimizers done");
    greedyPool.shutdown();

    log.info("sorting set from all optimizers");
    GreedyOptimizer optimizer = optimizers.get(0);
//    GreedyOptimizer optimizer = new GreedyOptimizer(optimizerConfig, populationSize, endCondition);
//    Collections.sort(allTests, new TestSorter());
    List<TestCase> cases = optimizer.sortAndPrune(allTests);
    TestCoverage coverage = new TestCoverage(cases);

    writeFinalReport(optimizers, cases);

    //trick or treat. we use this to get valid set of requirements from the created model
    //and in the end we update this with the correct covered requirements in order for coverage reporters to
    //report the correct requirements coverage
    fsm = optimizer.getFsm();

    //finally, we need to update the coverage in the FSM to reflect the final pruned suite
    //the coverage in fsm is used by coverage reporters which is why we need this
    Requirements reqs = fsm.getRequirements();
    reqs.clearCoverage();
    Collection<String> coveredReqs = coverage.getRequirements();
    for (String req : coveredReqs) {
      reqs.covered(req);
    }
    log.info("search done");
    return cases;
  }

  private void writeFinalReport(List<GreedyOptimizer> optimizers, List<TestCase> cases) {
    String csv1 = "cumulative coverage per test\n";
    String csv2 = "gained coverage per test\n";
    String csv3 = "number of tests in suite\n";
    String csv4 = "total score\n";
    String summary = "summary\n";

    Collection<String> possiblePairs = new HashSet<>();
    for (GreedyOptimizer optimizer : optimizers) {
      possiblePairs.addAll(optimizer.getPossiblePairs());
    }
    
    GreedyOptimizer optimizer = optimizers.get(0);
    csv1 += optimizer.csvForCoverage(cases);
    csv2 += optimizer.csvForGain(cases);
    csv3 += optimizer.csvNumberOfTests(cases);
    csv4 += optimizer.csvTotalScores(cases);

    TestCoverage tc = new TestCoverage(cases);
    summary += tc.coverageString(possiblePairs.size(), 0, 0);

    String totalCsv = "";
    totalCsv += csv1 + "\n";
    totalCsv += csv2 + "\n";
    totalCsv += csv3 + "\n";
    totalCsv += csv4 + "\n";
    totalCsv += summary + "\n";
    optimizer.writeFile("final-scores.csv", totalCsv);
  }

  public FSM getFsm() {
    return fsm;
  }
}
