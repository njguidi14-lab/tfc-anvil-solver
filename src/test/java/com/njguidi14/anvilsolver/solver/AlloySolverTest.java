package com.njguidi14.anvilsolver.solver;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AlloySolver}.
 *
 * <p>Every fixture below is a real TFC alloy recipe, because the numbers this class produces are
 * read off a screen by a player standing at a crucible and acted on immediately. A made-up range
 * would prove the search works on made-up ranges.
 *
 * <p>The ingot volume is TFC's 100 mB unless a test is specifically about the volume.
 */
class AlloySolverTest {

    /** TFC's ingot volume in mB, and the one every real answer here is counted in. */
    private static final int INGOT = 100;

    /** The crucible overlay's own cap on how many ingots it will suggest. */
    private static final int MAX_INGOTS = 64;

    /**
     * A restatement of TFC's {@code FluidAlloy.EPSILON}, used only where a test has to judge a
     * fraction the same way the game would.
     *
     * <p>Same caveat as {@code ForgeSimTest.rulesOkOn}: this is a copy of the constant the solver
     * also holds a copy of, so it sits on both sides of those comparisons and cannot catch the two
     * disagreeing with TFC. It is not what these tests are for - they check the search, not the
     * tolerance - and every hand-verified case below is far enough inside its range that the value
     * makes no difference to it at all. Confirm the number against TFC's source, not a green run.
     */
    private static final double TFC_EPSILON = 1.0d / (2.0d + (Integer.MAX_VALUE - 2));

    // Real TFC alloy ranges, as {min...}, {max...} pairs in the recipes' own component order.
    private static final double[] BRONZE_MIN = { 0.88, 0.08 };   // copper, tin
    private static final double[] BRONZE_MAX = { 0.92, 0.12 };
    private static final double[] BRASS_MIN = { 0.88, 0.08 };    // copper, zinc
    private static final double[] BRASS_MAX = { 0.92, 0.12 };
    private static final double[] STERLING_MIN = { 0.20, 0.60 }; // copper, silver
    private static final double[] STERLING_MAX = { 0.40, 0.80 };
    private static final double[] ROSE_GOLD_MIN = { 0.15, 0.70 }; // copper, gold
    private static final double[] ROSE_GOLD_MAX = { 0.30, 0.85 };
    private static final double[] BLACK_BRONZE_MIN = { 0.50, 0.10, 0.10 }; // copper, silver, gold
    private static final double[] BLACK_BRONZE_MAX = { 0.70, 0.25, 0.25 };
    private static final double[] BISMUTH_BRONZE_MIN = { 0.50, 0.20, 0.10 }; // copper, zinc, bismuth
    private static final double[] BISMUTH_BRONZE_MAX = { 0.65, 0.30, 0.20 };

    // ---------------------------------------------------------------------------------------------
    // Known-good answers, verified by hand
    // ---------------------------------------------------------------------------------------------

    @Test
    void bronzeFromAnEmptyPotIsEightCopperAndOneTin() {
        // Hand-check: 9 ingots is the first count where both brackets are non-empty. At a final
        // total of 900 mB, copper must land in [792, 828] mB (i.e. exactly 8 ingots) and tin in
        // [72, 108] mB (exactly 1). 800/900 = 88.9% and 100/900 = 11.1%, both comfortably inside.
        // Every k below 9 fails because 0.88k and 0.92k enclose no integer - the interval is only
        // 0.04k wide, so it cannot contain one until k reaches 9.
        final int[] plan = AlloySolver.solve(
            BRONZE_MIN, BRONZE_MAX, new double[2], 0.0, INGOT, MAX_INGOTS);

        assertArrayEquals(new int[] { 8, 1 }, plan, "bronze from empty");
        assertPlanIsStrictlyInRange(BRONZE_MIN, BRONZE_MAX, new double[2], 0.0, INGOT, plan,
            "bronze from empty");
    }

    @Test
    void bronzeFromNineHundredCopperIsASingleTin() {
        // The single most common real case: a pot of pure copper, nine ingots' worth, and the
        // player wants bronze. One tin takes the total to 1000 mB, leaving copper at exactly 90%
        // and tin at exactly 10% - dead centre of both ranges. No copper is needed, which is the
        // part worth pinning: a solve that always added something would still "work" here and
        // would still be wrong.
        final double[] have = { 900.0, 0.0 };
        final int[] plan = AlloySolver.solve(
            BRONZE_MIN, BRONZE_MAX, have, 900.0, INGOT, MAX_INGOTS);

        assertArrayEquals(new int[] { 0, 1 }, plan, "bronze from 900 mB copper");
        assertPlanIsStrictlyInRange(BRONZE_MIN, BRONZE_MAX, have, 900.0, INGOT, plan,
            "bronze from 900 mB copper");
    }

    @Test
    void sterlingSilverFromAnEmptyPotIsThreeUnits() {
        // Pinned because this exact number is what broke the overlay's auto-select. Sterling
        // silver's ranges are 20 percentage points wide against bronze's 4, so from empty it
        // finishes in 3 ingots while bronze needs 9 - and a ranking that sorted candidates by
        // "fewest ingots to finish" therefore recommended sterling silver from every empty
        // crucible, whatever the player was actually making. The 3 is correct; the conclusion drawn
        // from it was not. If this number ever moves, the ranking's reasoning has to be re-read.
        final int[] plan = AlloySolver.solve(
            STERLING_MIN, STERLING_MAX, new double[2], 0.0, INGOT, MAX_INGOTS);

        assertNotNull(plan, "sterling silver is reachable from empty");
        assertEquals(3, sum(plan), "sterling silver from empty");
        // 1 copper + 2 silver: 33.3% / 66.7%.
        assertArrayEquals(new int[] { 1, 2 }, plan, "sterling silver from empty");
        assertPlanIsStrictlyInRange(STERLING_MIN, STERLING_MAX, new double[2], 0.0, INGOT, plan,
            "sterling silver from empty");
    }

    // ---------------------------------------------------------------------------------------------
    // Every plan is actually valid
    // ---------------------------------------------------------------------------------------------

    @Test
    void everyPlanLandsEveryComponentInsideItsRange() {
        // Recomputed from the returned counts rather than trusted: AlloySolver.verify runs the same
        // check internally, so a test that only asserted "a plan came back" would be asserting that
        // the solver agrees with itself. These fractions are worked out here, from the plan and
        // nothing else, exactly as a player counting ingots into a pot would end up with.
        for (final Case c : REAL_CASES) {
            final int[] plan =
                AlloySolver.solve(c.min(), c.max(), c.have(), c.total(), c.volume(), c.maxUnits());
            if (plan == null) {
                // Unreachable-within-the-cap is a legitimate answer; minimality is checked
                // separately, and a case with no plan has nothing to validate here.
                continue;
            }
            assertEquals(c.min().length, plan.length, c.name() + ": one count per component");
            for (final int count : plan) {
                assertTrue(count >= 0, c.name() + ": negative ingot count " + count);
            }
            assertTrue(sum(plan) <= c.maxUnits(), c.name() + ": plan exceeds the cap");
            assertPlanIsStrictlyInRange(
                c.min(), c.max(), c.have(), c.total(), c.volume(), plan, c.name());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Minimality
    // ---------------------------------------------------------------------------------------------

    @Test
    void unitCountIsMinimalAgainstABruteForceOracle() {
        // The mod's entire selling point on this screen is FEWEST ingots, and nothing else in this
        // file checks it - a solve that returned the first k it stumbled on would pass every other
        // test here. The oracle below shares no code with the solver: it enumerates every possible
        // distribution of k ingots and asks whether any of them fits, which is the thing the
        // solver's bracket arithmetic is a clever shortcut for. Agreement between the two is
        // therefore a real cross-check rather than a restatement.
        for (final Case c : REAL_CASES) {
            final int[] plan =
                AlloySolver.solve(c.min(), c.max(), c.have(), c.total(), c.volume(), c.maxUnits());
            final int oracle =
                oracleMinUnits(c.min(), c.max(), c.have(), c.total(), c.volume(), c.maxUnits());

            if (oracle < 0) {
                assertNull(plan, c.name() + ": no split fits, so there must be no plan");
                continue;
            }
            assertNotNull(plan, c.name() + ": " + oracle + " ingots fits, so a plan must be found");
            assertEquals(oracle, sum(plan), c.name() + ": ingot count is not minimal");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Unit-volume invariance
    // ---------------------------------------------------------------------------------------------

    @Test
    void fromAnEmptyPotTheAnswerDoesNotDependOnTheUnitVolume() {
        // Counter-intuitive and therefore pinned: someone will eventually "fix" this. From EMPTY,
        // every bound in the solve is (fraction * k * V) / V, so V cancels outright and the answer
        // is a function of the ratios alone. Bronze is 8 + 1 whether an ingot is 100 mB or 15.
        //
        // It stops being true the moment the pot is not empty - see the second half - because the
        // metal already in it does not scale with V. That asymmetry is the reason this is worth a
        // test rather than a comment: "volume does not matter" is true in exactly one case and
        // false in general, and the two are one line apart.
        final int[] expected = { 8, 1 };
        for (final int volume : new int[] { 1, 5, 15, 100, 144, 1000 }) {
            final int[] plan = AlloySolver.solve(
                BRONZE_MIN, BRONZE_MAX, new double[2], 0.0, volume, MAX_INGOTS);
            assertArrayEquals(expected, plan, "bronze from empty at V = " + volume);
        }

        // The same ratios with metal already in the pot: 900 mB of copper is nine 100 mB ingots but
        // sixty 15 mB ones, so the two are genuinely different starting states and give genuinely
        // different answers. At V = 100 a single tin finishes it; at V = 15 it takes six, because
        // the smallest step available is smaller. The counts are left to the oracle rather than
        // written down here - what this half is pinning is that they are allowed to differ.
        final double[] have = { 900.0, 0.0 };
        final int[] atHundred = AlloySolver.solve(
            BRONZE_MIN, BRONZE_MAX, have, 900.0, 100, MAX_INGOTS);
        final int[] atFifteen = AlloySolver.solve(
            BRONZE_MIN, BRONZE_MAX, have, 900.0, 15, MAX_INGOTS);
        assertNotNull(atHundred, "bronze from 900 mB copper at V = 100");
        assertNotNull(atFifteen, "bronze from 900 mB copper at V = 15");
        assertPlanIsStrictlyInRange(BRONZE_MIN, BRONZE_MAX, have, 900.0, 100, atHundred, "V = 100");
        assertPlanIsStrictlyInRange(BRONZE_MIN, BRONZE_MAX, have, 900.0, 15, atFifteen, "V = 15");
        assertEquals(
            oracleMinUnits(BRONZE_MIN, BRONZE_MAX, have, 900.0, 15, MAX_INGOTS),
            sum(atFifteen),
            "bronze from 900 mB copper at V = 15 is not minimal");
    }

    // ---------------------------------------------------------------------------------------------
    // Degenerate and hostile inputs - none of these may throw
    // ---------------------------------------------------------------------------------------------

    @Test
    void anEmptyComponentListHasNoPlan() {
        // A recipe with no components is not "already satisfied", it is unreadable data. Returning
        // a zero-length plan would be printed by the overlay as a target with no instructions under
        // it, which reads as "add nothing" - a wrong answer wearing the shape of a right one.
        assertNull(AlloySolver.solve(
            new double[0], new double[0], new double[0], 0.0, INGOT, MAX_INGOTS));
        assertNull(AlloySolver.solve(
            new double[0], new double[0], new double[0], 500.0, INGOT, MAX_INGOTS));
    }

    @Test
    void aCapOfZeroFindsNothingRatherThanFailing() {
        // k is then only ever 0. From an empty pot the final total is 0 and every fraction would be
        // 0/0, which is the case the solve skips rather than divides; from a full pot there is
        // simply nothing it is allowed to add.
        final double[] copperOnly = { 900.0, 0.0 };

        assertNull(AlloySolver.solve(BRONZE_MIN, BRONZE_MAX, new double[2], 0.0, INGOT, 0),
            "empty pot, no budget");
        assertNull(AlloySolver.solve(BRONZE_MIN, BRONZE_MAX, copperOnly, 900.0, INGOT, 0),
            "pot of copper, no budget");
        assertNull(AlloySolver.solve(BRONZE_MIN, BRONZE_MAX, new double[2], 0.0, INGOT, -5),
            "a negative cap is a cap of zero");
    }

    @Test
    void aPotAlreadyInRangeNeedsNothingAdded() {
        // 900 copper + 100 tin is already bronze. The honest answer is a plan of all zeroes, NOT
        // null: "you are done" and "this is impossible" are opposite answers and the overlay prints
        // opposite things for them. This is also the reason null is the failure signal rather than
        // an empty or all-zero array.
        final double[] have = { 900.0, 100.0 };
        final int[] plan = AlloySolver.solve(BRONZE_MIN, BRONZE_MAX, have, 1000.0, INGOT, MAX_INGOTS);
        assertArrayEquals(new int[] { 0, 0 }, plan, "already bronze");
    }

    @Test
    void aComponentOverItsMaximumIsFixedOnlyByDilutionAndOnlyIfTheCapAllowsIt() {
        // 1000 mB of pure tin, aiming at bronze. Tin is at 100% against a 12% ceiling, and adding
        // tin is the one thing that cannot help - the only route is enough copper to dilute it, and
        // 1000/(1000 + 100k) <= 0.12 needs k >= 74.
        final double[] have = { 0.0, 1000.0 };

        // Inside the overlay's real 64-ingot cap there is no answer, and reporting none is correct.
        assertNull(AlloySolver.solve(BRONZE_MIN, BRONZE_MAX, have, 1000.0, INGOT, MAX_INGOTS),
            "74 ingots does not fit inside a cap of 64");

        // Given the room, it finds the dilution rather than giving up on the state.
        final int[] plan = AlloySolver.solve(BRONZE_MIN, BRONZE_MAX, have, 1000.0, INGOT, 100);
        assertArrayEquals(new int[] { 74, 0 }, plan, "dilute the tin with 74 copper");
        assertPlanIsStrictlyInRange(BRONZE_MIN, BRONZE_MAX, have, 1000.0, INGOT, plan, "diluted");
    }

    @Test
    void rangesThatCannotHoldTogetherHaveNoPlan() {
        // Two components that each demand at least 60% of the pot. No k can satisfy both, because
        // their minima alone always need more ingots than k is - the "sumLo > k" rejection.
        assertNull(AlloySolver.solve(
            new double[] { 0.6, 0.6 }, new double[] { 0.7, 0.7 },
            new double[2], 0.0, INGOT, MAX_INGOTS),
            "minima sum past 100%");

        // The mirror image: two components that between them can never account for the pot, so
        // there is always metal in it that belongs to neither - the "sumHi < k" rejection.
        assertNull(AlloySolver.solve(
            new double[] { 0.1, 0.1 }, new double[] { 0.2, 0.2 },
            new double[2], 0.0, INGOT, MAX_INGOTS),
            "maxima sum short of 100%");
    }

    @Test
    void aRangeWithNoWidthIsStillSolvableWhenItLandsOnAWholeUnit() {
        // min == max is a single exact ratio, which is the case the EDGE_TOLERANCE exists for: the
        // bound sits exactly ON the ceil/floor boundary, so a product that computes a hair high
        // rounds the candidate away and the correct answer is lost. Two components at exactly 50%
        // each is one ingot apiece.
        final int[] half = AlloySolver.solve(
            new double[] { 0.5, 0.5 }, new double[] { 0.5, 0.5 },
            new double[2], 0.0, INGOT, MAX_INGOTS);
        assertArrayEquals(new int[] { 1, 1 }, half, "an exact 50/50 split");

        // Sharper: exact thirds are not representable in binary at all, so min * total lands
        // fractionally either side of the integer it mathematically is, in a direction that depends
        // on the numbers. Without the tolerance this is precisely the shape of input that returns
        // "no mix found" for an alloy that is one ingot each.
        final double third = 1.0 / 3.0;
        final int[] thirds = AlloySolver.solve(
            new double[] { third, third, third }, new double[] { third, third, third },
            new double[3], 0.0, INGOT, MAX_INGOTS);
        assertArrayEquals(new int[] { 1, 1, 1 }, thirds, "an exact three-way split");
    }

    @Test
    void unusableDataIsAnsweredWithNoPlanRatherThanAnException() {
        // Ranges arrive from datapacks and amounts from a block entity, so none of this is
        // hypothetical. All of it runs on Minecraft's render path, where one escaping exception is
        // not a crash but a crash on every frame for as long as the screen is open - so every one
        // of these has to come back as "no plan".
        final double[] ok2 = new double[2];

        assertNull(AlloySolver.solve(BRONZE_MIN, BRONZE_MAX, ok2, 0.0, 0, MAX_INGOTS),
            "a unit volume of zero would be divided by");
        assertNull(AlloySolver.solve(BRONZE_MIN, BRONZE_MAX, ok2, 0.0, -100, MAX_INGOTS),
            "a negative unit volume");

        assertNull(AlloySolver.solve(
            new double[] { 0.9, 0.1 }, new double[] { 0.8, 0.2 }, ok2, 0.0, INGOT, MAX_INGOTS),
            "an inverted range, min above max");
        assertNull(AlloySolver.solve(
            new double[] { Double.NaN, 0.1 }, new double[] { 0.9, 0.2 }, ok2, 0.0, INGOT, MAX_INGOTS),
            "a NaN minimum");
        assertNull(AlloySolver.solve(
            new double[] { 0.1, 0.1 }, new double[] { Double.POSITIVE_INFINITY, 0.2 },
            ok2, 0.0, INGOT, MAX_INGOTS),
            "an infinite maximum");

        assertNull(AlloySolver.solve(
            BRONZE_MIN, BRONZE_MAX, new double[] { Double.NaN, 0.0 }, 100.0, INGOT, MAX_INGOTS),
            "a NaN amount already in the pot");
        assertNull(AlloySolver.solve(BRONZE_MIN, BRONZE_MAX, ok2, Double.NaN, INGOT, MAX_INGOTS),
            "a NaN total");
        assertNull(AlloySolver.solve(
            BRONZE_MIN, BRONZE_MAX, ok2, Double.POSITIVE_INFINITY, INGOT, MAX_INGOTS),
            "an infinite total");

        // The three arrays are parallel, so a length disagreement means index i is reading one
        // component's minimum against another's maximum. There is no sane answer to give.
        assertNull(AlloySolver.solve(
            new double[] { 0.88, 0.08 }, new double[] { 0.92 }, ok2, 0.0, INGOT, MAX_INGOTS),
            "max array shorter than min");
        assertNull(AlloySolver.solve(
            BRONZE_MIN, BRONZE_MAX, new double[3], 0.0, INGOT, MAX_INGOTS),
            "have array longer than the ranges");
    }

    // ---------------------------------------------------------------------------------------------
    // Contract of the returned and passed arrays
    // ---------------------------------------------------------------------------------------------

    @Test
    void theCallersArraysAreNeverWrittenTo() {
        // The overlay solves every candidate alloy against one shared snapshot of the crucible, so
        // a solve that scribbled on its inputs would corrupt the next candidate's answer rather
        // than its own - a bug that would show up in a different alloy from the one that caused it.
        final double[] min = BRONZE_MIN.clone();
        final double[] max = BRONZE_MAX.clone();
        final double[] have = { 300.0, 50.0 };
        final double[] haveBefore = have.clone();

        AlloySolver.solve(min, max, have, 350.0, INGOT, MAX_INGOTS);

        assertArrayEquals(BRONZE_MIN, min, "min was modified");
        assertArrayEquals(BRONZE_MAX, max, "max was modified");
        assertArrayEquals(haveBefore, have, "have was modified");
    }

    @Test
    void eachCallReturnsItsOwnArray() {
        // The solve builds its answer in one scratch array that it overwrites on every iteration of
        // its k loop, and hands back a clone. Handing back the scratch array itself would mean the
        // overlay's memoised plan for an alloy silently changing under it - and the overlay caches
        // these plans across frames, so it would be reading a rewritten one.
        final int[] first = AlloySolver.solve(
            BRONZE_MIN, BRONZE_MAX, new double[2], 0.0, INGOT, MAX_INGOTS);
        final int[] second = AlloySolver.solve(
            BRONZE_MIN, BRONZE_MAX, new double[2], 0.0, INGOT, MAX_INGOTS);

        assertNotNull(first);
        assertNotNull(second);
        assertNotSame(first, second, "two solves handed back the same array");
        assertArrayEquals(first, second, "the same inputs must give the same answer");

        first[0] = 999;
        final int[] third = AlloySolver.solve(
            BRONZE_MIN, BRONZE_MAX, new double[2], 0.0, INGOT, MAX_INGOTS);
        assertArrayEquals(new int[] { 8, 1 }, third, "a caller's edit leaked back into the solver");
    }

    // ---------------------------------------------------------------------------------------------
    // Fuzz
    // ---------------------------------------------------------------------------------------------

    @Test
    void fuzzRandomRangesAndPotsKeepTheInvariantsWhenAPlanIsFound() {
        // Mirrors ForgeSimTest's fuzz pass, and for the same reason: the curated cases above are
        // all recipes someone thought about, and the inputs that actually broke this arithmetic
        // nine times were the ones nobody thought about. Seeded so a failure is reproducible.
        //
        // This asserts the invariants only, never minimality - the brute-force oracle is far too
        // slow across four components and sixty-four ingots, and the curated table already pins
        // minimality on inputs whose answers are known.
        final Random random = new Random(20260811L);
        final int[] volumes = { 1, 15, 100, 144 };
        int solved = 0;

        for (int i = 0; i < 500; i++) {
            final int size = 1 + random.nextInt(4);
            final double[] min = new double[size];
            final double[] max = new double[size];

            if (random.nextBoolean()) {
                // Ranges built around a genuine partition of the pot, so a decent share of these
                // iterations are actually solvable and the assertions below get exercised.
                final double[] share = randomPartition(random, size);
                for (int j = 0; j < size; j++) {
                    final double slack = 0.02 + random.nextDouble() * 0.15;
                    min[j] = Math.max(0.0, share[j] - slack);
                    max[j] = Math.min(1.0, share[j] + slack);
                }
            } else {
                // Arbitrary ranges, mostly unsatisfiable. These are the ones that have to come back
                // as null instead of throwing or looping.
                for (int j = 0; j < size; j++) {
                    final double a = random.nextDouble();
                    final double b = random.nextDouble();
                    min[j] = Math.min(a, b);
                    max[j] = Math.max(a, b);
                }
            }

            final int volume = volumes[random.nextInt(volumes.length)];
            final double[] have = new double[size];
            double total = 0.0;
            if (random.nextBoolean()) {
                // Whole units of metal already in the pot, so the starting state is one a crucible
                // could really be in - the total is the sum of the parts, never independent of it.
                for (int j = 0; j < size; j++) {
                    have[j] = (double) volume * random.nextInt(6);
                    total += have[j];
                }
            }
            final int maxUnits = random.nextInt(MAX_INGOTS + 1);

            final int[] plan = AlloySolver.solve(min, max, have, total, volume, maxUnits);
            if (plan == null) {
                continue;
            }
            solved++;

            final String where = "iteration " + i
                + " min=" + Arrays.toString(min) + " max=" + Arrays.toString(max)
                + " have=" + Arrays.toString(have) + " total=" + total
                + " V=" + volume + " cap=" + maxUnits;

            assertEquals(size, plan.length, where + ": wrong number of counts");
            int units = 0;
            for (final int count : plan) {
                assertTrue(count >= 0, where + ": negative count");
                units += count;
            }
            assertTrue(units <= maxUnits, where + ": plan of " + units + " exceeds the cap");
            // Judged with TFC's tolerance rather than strictly, unlike the curated cases: random
            // ranges genuinely can be satisfiable only right on a bound, and the solve's contract
            // is the game's own tolerant test, not an exact one.
            assertPlanInRange(min, max, have, total, volume, plan, TFC_EPSILON, where);
        }

        // Guards the fuzz against quietly becoming a no-op. If a change to the generator (or to the
        // solve) made every iteration unsolvable, every assertion above would be skipped and this
        // test would still pass, which is the classic way a fuzz pass rots.
        assertTrue(solved > 50, "only " + solved + " of 500 fuzz cases produced a plan");
    }

    // ---------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------

    /** One solve's worth of inputs, named so a failure says which case broke. */
    private record Case(
        String name, double[] min, double[] max, double[] have, double total,
        int volume, int maxUnits
    ) {
    }

    private static Case realCase(String name, double[] min, double[] max, double... have) {
        double total = 0.0;
        for (final double amount : have) {
            total += amount;
        }
        return new Case(name, min, max, have, total, INGOT, MAX_INGOTS);
    }

    /**
     * The cases that are both validated and checked for minimality.
     *
     * <p>Deliberately small alloys and small pots: the oracle enumerates every distribution of
     * every ingot count up to the cap, which is fine at three components and a dozen ingots and is
     * not fine much past that.
     */
    private static final List<Case> REAL_CASES = List.of(
        realCase("bronze from empty", BRONZE_MIN, BRONZE_MAX, 0.0, 0.0),
        realCase("bronze from 900 copper", BRONZE_MIN, BRONZE_MAX, 900.0, 0.0),
        realCase("bronze from 100 copper", BRONZE_MIN, BRONZE_MAX, 100.0, 0.0),
        realCase("bronze from a 50/50 pot", BRONZE_MIN, BRONZE_MAX, 500.0, 500.0),
        realCase("bronze already made", BRONZE_MIN, BRONZE_MAX, 900.0, 100.0),
        realCase("brass from empty", BRASS_MIN, BRASS_MAX, 0.0, 0.0),
        realCase("brass from 400 zinc", BRASS_MIN, BRASS_MAX, 0.0, 400.0),
        realCase("sterling silver from empty", STERLING_MIN, STERLING_MAX, 0.0, 0.0),
        realCase("sterling silver from 300 copper", STERLING_MIN, STERLING_MAX, 300.0, 0.0),
        realCase("rose gold from empty", ROSE_GOLD_MIN, ROSE_GOLD_MAX, 0.0, 0.0),
        realCase("rose gold from 200 gold", ROSE_GOLD_MIN, ROSE_GOLD_MAX, 0.0, 200.0),
        realCase("black bronze from empty", BLACK_BRONZE_MIN, BLACK_BRONZE_MAX, 0.0, 0.0, 0.0),
        realCase("black bronze from 500 copper", BLACK_BRONZE_MIN, BLACK_BRONZE_MAX, 500.0, 0.0, 0.0),
        realCase("bismuth bronze from empty",
            BISMUTH_BRONZE_MIN, BISMUTH_BRONZE_MAX, 0.0, 0.0, 0.0),
        realCase("bismuth bronze from 300 copper 100 zinc",
            BISMUTH_BRONZE_MIN, BISMUTH_BRONZE_MAX, 300.0, 100.0, 0.0));

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private static int sum(int[] counts) {
        int total = 0;
        for (final int count : counts) {
            total += count;
        }
        return total;
    }

    /**
     * Asserts the plan lands every component inside {@code [min, max]} with no tolerance at all.
     *
     * <p>Used for the hand-verified alloys, whose answers are ratios of small integers and sit well
     * clear of their bounds. Holding them to the exact interval rather than TFC's tolerant one is
     * what makes these cases pin the arithmetic instead of pinning the epsilon.
     */
    private static void assertPlanIsStrictlyInRange(
        double[] min, double[] max, double[] have, double total, int volume, int[] counts,
        String where
    ) {
        assertPlanInRange(min, max, have, total, volume, counts, 0.0, where);
    }

    /** Recomputes each component's final share from the plan and checks it against its range. */
    private static void assertPlanInRange(
        double[] min, double[] max, double[] have, double total, int volume, int[] counts,
        double tolerance, String where
    ) {
        final double finalTotal = total + (double) sum(counts) * volume;
        assertTrue(finalTotal > 0.0, where + ": a plan was returned for an empty final pot");
        for (int i = 0; i < counts.length; i++) {
            final double fraction = (have[i] + (double) volume * counts[i]) / finalTotal;
            assertTrue(fraction >= min[i] - tolerance && fraction <= max[i] + tolerance,
                where + ": component " + i + " finishes at " + fraction
                    + ", outside [" + min[i] + ", " + max[i] + "]");
        }
    }

    /**
     * Independent oracle for the true minimum number of units.
     *
     * <p>This deliberately does not re-derive {@code AlloySolver}'s brackets - re-running the same
     * arithmetic would prove nothing. It counts upwards from zero and, for each count, enumerates
     * every way of splitting that many units between the components, checking each split by simply
     * computing the resulting fractions. That is the definition the bracket arithmetic is a
     * shortcut for, so agreement between the two is a real cross-check.
     *
     * <p><b>One deliberate asymmetry.</b> The check below is strict - a fraction must be inside
     * {@code [min, max]} with no tolerance - while the solver accepts anything within TFC's epsilon
     * of the range. That is the safe direction: a strictly-fitting split is always inside the
     * solver's brackets too, so if this finds an answer at some count, the solver is obliged to
     * find one at that count or lower. The converse does not hold, which means this cannot fault
     * the solver for missing a plan that only fits by epsilon - a real, tiny gap, documented here
     * rather than asserted away.
     *
     * @return the fewest units for which some split fits, or -1 if none does within {@code maxUnits}
     */
    private static int oracleMinUnits(
        double[] min, double[] max, double[] have, double total, int volume, int maxUnits
    ) {
        for (int k = 0; k <= maxUnits; k++) {
            final double finalTotal = total + (double) k * volume;
            if (finalTotal <= 0.0) {
                // An empty pot with nothing added is not a mixture; every fraction would be 0/0.
                continue;
            }
            if (anySplitFits(min, max, have, finalTotal, volume, new int[min.length], 0, k)) {
                return k;
            }
        }
        return -1;
    }

    /**
     * Whether any way of handing out {@code remaining} more units, from component {@code index}
     * onwards, ends with every component inside its range.
     *
     * <p>Plain exhaustive recursion over the compositions of {@code remaining}. It is exponential
     * in the component count, which is why {@link #REAL_CASES} keeps the pots small.
     */
    private static boolean anySplitFits(
        double[] min, double[] max, double[] have, double finalTotal, int volume,
        int[] counts, int index, int remaining
    ) {
        if (index == counts.length) {
            if (remaining != 0) {
                // Units left over with nobody to give them to - not a split of this size at all.
                return false;
            }
            for (int i = 0; i < counts.length; i++) {
                final double fraction = (have[i] + (double) volume * counts[i]) / finalTotal;
                if (fraction < min[i] || fraction > max[i]) {
                    return false;
                }
            }
            return true;
        }
        for (int n = 0; n <= remaining; n++) {
            counts[index] = n;
            if (anySplitFits(min, max, have, finalTotal, volume, counts, index + 1, remaining - n)) {
                return true;
            }
        }
        // Left as found, so the caller's slot is not carrying this branch's last trial value into
        // the next one.
        counts[index] = 0;
        return false;
    }

    /** Random non-negative shares summing to exactly 1.0, used to seed solvable fuzz ranges. */
    private static double[] randomPartition(Random random, int size) {
        final double[] share = new double[size];
        double sum = 0.0;
        for (int i = 0; i < size; i++) {
            // Offset off zero so no component is handed a share of nothing, which would make its
            // range meaningless rather than tight.
            share[i] = 0.05 + random.nextDouble();
            sum += share[i];
        }
        for (int i = 0; i < size; i++) {
            share[i] /= sum;
        }
        return share;
    }
}
