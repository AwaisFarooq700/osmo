package osmo.tester.optimizer;

import osmo.common.TestUtils;
import osmo.common.log.Logger;
import osmo.tester.OSMOConfiguration;
import osmo.tester.OSMOTester;
import osmo.tester.coverage.ScoreCalculator;
import osmo.tester.coverage.ScoreConfiguration;
import osmo.tester.coverage.TestCoverage;
import osmo.tester.generator.MainGenerator;
import osmo.tester.generator.SingleInstanceModelFactory;
import osmo.tester.generator.endcondition.EndCondition;
import osmo.tester.generator.multi.MultiOSMO;
import osmo.tester.generator.testsuite.TestCase;
import osmo.tester.model.FSM;
import osmo.tester.model.Requirements;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * Generates test cases and greedily optimizes the resulting test suite with regards to coverage criteria as
 * given in an {@link osmo.tester.coverage.ScoreConfiguration}.
 * Resulting suite is optimised so the first test in the given set has the highest overall coverage score values,
 * the second one adds most score to that one as evaluated in terms of single test cases, the third adds the most
 * to these two, and so on.
 * New test cases are generated as long as the optimization of the suite in an iteration yields a higher value
 * than the previous iterations.
 * Best tests (ones that added anything to the suite score) are kept for the next round.
 * Each round (iteration) consists of generating a set of tests. The number of tests generated is equal to the
 * population size defined in the configuration.
 * The threshold to keep generating more tests is if any added score is observed.
 * This version generates test cases sequentially, using a single core on a single machine.
 * For a multi-core version, check the {@link MultiGreedy} version. However, note that the parallel versions are
 * not deterministic in the exact test cases returned, only in the scores they give (or should be).
 *
 * @author Teemu Kanstren
 */
public class GreedyOptimizer {
  private static Logger log = new Logger(GreedyOptimizer.class);
  /** Defines weights for different coverage requirements to optimize for. */
  private final ScoreConfiguration config;
  /** The test model. */
  private FSM fsm = null;
  /** Identifier for next greedy optimizer if several are created. */
  private static int nextId = 1;
  /** The identifier for this optimizer. */
  private int id = nextId++;
  /** Used to calculate coverage scores for different tests and suites. */
  private final ScoreCalculator scoreCalculator;
  /** How much does an iteration need to gain in score to go for another iteration? Defaults to 1. */
  private int threshold = 1;
  /** Seconds until the search times out. Timeout is checked between iterations and refers to how long overall generation progresses. */
  private long timeout = -1;
  /** For tracking all the path options encountered. */
  private Collection<String> possiblePairs = new HashSet<>();
  /** Generator configuration. */
  private final OSMOConfiguration osmoConfig;
  private long start = 0;
  private List<TestCase> suite = new ArrayList<>();
  private int iteration = 0;

  /**
   * @param configuration  For scoring the search.
   */
  public GreedyOptimizer(OSMOConfiguration osmoConfig, ScoreConfiguration configuration) {
    this.osmoConfig = osmoConfig;
    osmoConfig.setDataTraceRequested(false);
    this.config = configuration;
    this.scoreCalculator = new ScoreCalculator(configuration);
  }
  
  public void enableDataTrace() {
    osmoConfig.setDataTraceRequested(true);
  }
  
  /**
   * You can set any threshold you like. Zero or less means going on forever. Timeout is recommended in such case.
   *
   * @param threshold Required added coverage score for iteration to continue with another iteration.
   */
  public void setThreshold(int threshold) {
    this.threshold = threshold;
  }

  /** @param timeout Generation timeout in seconds. */
  public void setTimeout(int timeout) {
    this.timeout = timeout;
  }

  /**
   * Use default of 1000 for population size.
   * 
   * @param seed Generation seed.
   * @return The optimizer results.
   */
  public GenerationResults search(long seed) {
    return search(1000, seed);
    
  }
  /**
   * Performs a search following the defined search configuration.
   * Provides a sorted list of test cases, where the one with highest fitness is first, one that
   * adds most to this is second, one that adds most to those two is third and so on.
   * The set of tests is chosen by first generating a number of tests equals to populationSize.
   * From this overall set, the subset that gives some added coverage is returned.
   * If a test adds no coverage to the overall set, it is not returned.
   *
   * @return The sorted set of test cases, with requested number of tests.
   */
  public GenerationResults search(int populationSize, long seed) {
    check();

    CSVCoverageReport report = new CSVCoverageReport(scoreCalculator);
    MainGenerator generator = configure(seed);
    generate(report, generator, populationSize);

    this.possiblePairs = generator.getPossibleStepPairs();
    TestCoverage suiteCoverage = generator.getSuite().getCoverage();
    writeReport(report, suiteCoverage, suite.size(), iteration * populationSize, seed);

    updateRequirementsCoverage(suiteCoverage);
    return new GenerationResults(suite);
  }
  
  private void generate(CSVCoverageReport report, MainGenerator generator, int populationSize) {
    suite = new ArrayList<>();
    start = System.currentTimeMillis();
    int gain = Integer.MAX_VALUE;
    int previousScore = 0;
    long endTime = -1;
    if (timeout > 0) {
      //timeout is given in seconds so we multiple by 1000 to get milliseconds
      endTime = System.currentTimeMillis() + timeout * 1000;
    }
    log.info("greedy "+id+" starting up");
    //to get a shorter test suite, use negative length weight.. in most cases should be no problem
    while (gain >= threshold) {
      long iStart = System.currentTimeMillis();
      log.info(id + ":starting iteration " + iteration);
      iteration++;

      for (int i = 0 ; i < populationSize ; i++) {
        log.debug("creating test case " + i);
        TestCase testCase = generator.nextTest();
        suite.add(testCase);
      }
      log.info(id + ":sorting");
      suite = sortAndPrune(suite, scoreCalculator);
      //we process each iteration to produce a list of how it was overall progressing
      report.process(suite);
      TestCoverage suiteCoverage = new TestCoverage(suite);

      int score = scoreCalculator.calculateScore(suiteCoverage);
      gain = score - previousScore;
      previousScore = score;
      
      long diff = System.currentTimeMillis() - iStart;
      log.info(id + ":iteration time:(" + iteration + ")" + diff + " gain:" + gain);
      if (endTime > 0 && endTime < System.currentTimeMillis()) {
        log.info("Generation timed out");
        break;
      }
    }
    if (gain < threshold) log.info("gain under threshold (" + gain + " vs " + threshold + ")");
    generator.endSuite();
  }
  
  private void updateRequirementsCoverage(TestCoverage suiteCoverage) {
    //finally, we need to update the coverage in the FSM to reflect the final pruned suite
    //the coverage in fsm is used by coverage reporters which is why we need this
    Requirements reqs = fsm.getRequirements();
    reqs.clearCoverage();
    Collection<String> coveredReqs = suiteCoverage.getRequirements();
    for (String req : coveredReqs) {
      reqs.covered(req);
    }
  }

  private void check() {
    if (osmoConfig.getFactory() instanceof SingleInstanceModelFactory) {
      System.out.println(MultiOSMO.ERROR_MSG);
    }
    if (config.getLengthWeight() > 0) {
      log.warn("Length weight was defined as > 0, reset to 0.");
      //we do not use positive length weight as it would potentially go on for ever..
      config.setLengthWeight(0);
    }
    if (threshold < 1) log.warn("Threshold is " + threshold + ", which is impossible to reach. Are you sure?");
  }
  
  private MainGenerator configure(long seed) {
    OSMOTester tester = new OSMOTester();
    tester.setConfig(osmoConfig);
    MainGenerator generator = tester.initGenerator(seed);
    osmoConfig.initialize(seed, tester.getFsm());
    generator.initSuite();
    this.fsm = generator.getFsm();
    EndCondition endCondition = osmoConfig.getTestCaseEndCondition();
    endCondition.init(seed, fsm);
    tester.setTestEndCondition(endCondition);
    config.validate(fsm);
    log.debug("greedy configuration validated");
    return generator;
  }

  private void writeReport(CSVCoverageReport report, TestCoverage tc, int resultSize, int generationCount, long seed) {

    String summary = "summary\n";
    //we do not have the set of possible states or state pairs as those would require executing the "states" which greedy does not do..
    summary += tc.coverageString(fsm, possiblePairs, null, null, null, false);

    String totalCsv = report.report();
    totalCsv += summary + "\n";
    String filename = id + "-scores.csv";
    TestUtils.write(totalCsv, "osmo-output/greedy-" + seed + "/" + filename);
    long end = System.currentTimeMillis();
    long diff = end - start;
    log.info("GreedyOptimizer " + id + " generated " + generationCount + " tests.");
    log.info("Resulting suite has " + resultSize + " tests. Generation time " + diff + " millis");
  }

  /**
   * Same as createSortedSet(int howMany) but does not generate the tests, uses the given set as source instead.
   *
   * @param from The source set to pick from.
   * @return Greedily sorted suite of requested size.
   */
  public static List<TestCase> sortAndPrune(List<TestCase> from, ScoreCalculator calculator) {
    //this sort is here to ensure deterministic results (as far as sequence of steps and scores go..)
    Collections.sort(from, new TestSorter());
    List<TestCase> suite = new ArrayList<>();

    TestCoverage previous = new TestCoverage();
//    long total1 = 0;
//    long total2 = 0;
//    long total3 = 0;
    int times = 0;
    while (from.size() > 0) {
      int bestScore = 0;
      TestCase best = null;
      for (TestCase test : from) {
//        long start1 = System.currentTimeMillis();
        TestCoverage tc = test.getCoverage().cloneMe();
//        long end1 = System.currentTimeMillis();
//        total1 += end1-start1;

//        long start2 = System.currentTimeMillis();
        tc.removeAll(previous);
//        long end2 = System.currentTimeMillis();
//        total2 += end2-start2;

//        long start3 = System.currentTimeMillis();
        int score = calculator.calculateScore(tc);
//        long end3 = System.currentTimeMillis();
//        total3 += end3-start3;
        if (score > bestScore) {
          bestScore = score;
          best = test;
        }
        times++;
      }
      if (best == null) {
        //no more gains found in coverage
        break;
      }
      from.remove(best);
      suite.add(best);
      previous.addCoverage(best.getCoverage());
    }
//    log.info("time1:"+total1);
//    log.info("time2:"+total2);
//    log.info("time3:"+total3);
    log.info("loops in sort:"+times);
    return suite;
  }

  public FSM getFsm() {
    return fsm;
  }

  public Collection<String> getPossiblePairs() {
    return possiblePairs;
  }
//
//  public static void main(String[] args) {
//    int a = 0;
//    for (int i = 0 ; i <= 1000 ; i++) {
//      a += i;
//    }
//    System.out.println("a:"+a);
//  }
}
