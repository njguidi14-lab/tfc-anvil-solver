package com.njguidi14.anvilsolver.solver;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForgeSimTest {

    @Test
    void alreadyCompleteReturnsEmptyPlan() {
        final Solution s = ForgeSim.solve(50, 50, List.of(), List.of());
        assertTrue(s.feasible());
        assertEquals(0, s.pressCount());
        assertEquals(50, s.finalWork());
    }

    @Test
    void outOfRangeTargetIsInfeasible() {
        assertFalse(ForgeSim.solve(151, 0, List.of(), List.of()).feasible());
        assertFalse(ForgeSim.solve(-1, 0, List.of(), List.of()).feasible());
        assertFalse(ForgeSim.solve(0, 151, List.of(), List.of()).feasible());
    }

    @Test
    void noRulesLengthMatchesIndependentBfsForEveryTarget() {
        for (int target = 0; target <= Step.LIMIT; target++) {
            final Solution s = ForgeSim.solve(target, 0, List.of(), List.of());
            final int expected = bruteShortest(0, target);
            if (expected == -1) {
                assertFalse(s.feasible(), "target " + target + " should be unreachable");
            } else {
                assertTrue(s.feasible(), "target " + target + " should be reachable");
                assertEquals(expected, s.pressCount(), "press count for target " + target);
                assertEquals(target, s.finalWork());
            }
        }
    }

    @Test
    void swordBladeRulesLandExactlyAndSatisfyRules() {
        // Like tfc:metal/sword_blade/* - Hit Last, Bend Second From Last, Bend Third From Last
        final List<Rule> rules = List.of(
            new Rule(Slot.LAST, Family.HIT),
            new Rule(Slot.SECOND_LAST, Family.BEND),
            new Rule(Slot.THIRD_LAST, Family.BEND));
        final Solution s = ForgeSim.solve(62, 0, List.of(), rules);
        assertValid(s, 62, 0, List.of(), rules);
        assertTrue(s.pressCount() >= 3);
        final List<Step> p = s.presses();
        assertEquals(Family.BEND, p.get(p.size() - 2).family());
        assertEquals(Family.BEND, p.get(p.size() - 3).family());
        assertEquals(Family.HIT, p.get(p.size() - 1).family());
    }

    @Test
    void hitRuleAcceptsAnyHitStrength() {
        final List<Rule> rules = List.of(new Rule(Slot.LAST, Family.HIT));
        final Solution s = ForgeSim.solve(0, 0, List.of(), rules);
        assertValid(s, 0, 0, List.of(), rules);
        assertTrue(s.pressCount() >= 1);
        assertEquals(Family.HIT, s.presses().get(s.presses().size() - 1).family());
    }

    @Test
    void liveTrackingPreservesExistingPressesInTheWindow() {
        // One press already made (PUNCH), rule needs a punch in third-last position.
        // The planned presses must keep that press in the final last-three window.
        final List<Step> history = List.of(Step.PUNCH);
        final List<Rule> rules = List.of(new Rule(Slot.THIRD_LAST, Family.PUNCH));
        final Solution s = ForgeSim.solve(10, 2, history, rules);
        assertValid(s, 10, 2, history, rules);
        assertTrue(s.pressCount() >= 2, "needs enough presses to land and place history in third-last");
        // The rule must hold on the completed sequence: the PUNCH may come from the fixed
        // history itself (when only 2 presses are planned) or from the planned presses.
        final List<Step> full = new ArrayList<>(history);
        full.addAll(s.presses());
        assertEquals(Family.PUNCH, full.get(full.size() - 3).family());
    }

    @Test
    void midForgeWithTwoPresses() {
        // Two presses already made: BEND (+7) then PUNCH (+2) -> work is now 9.
        // The rule forces BEND third-from-last and PUNCH second-from-last in the
        // completed sequence, so the planned presses must carry those presses into
        // the final last-three window (e.g. [UPSET, UPSET, HIT_LIGHT, BEND, PUNCH, SHRINK]).
        final List<Step> history = List.of(Step.BEND, Step.PUNCH);
        final List<Rule> rules = List.of(
            new Rule(Slot.SECOND_LAST, Family.PUNCH),
            new Rule(Slot.THIRD_LAST, Family.BEND));
        final Solution s = ForgeSim.solve(57, 9, history, rules);
        assertValid(s, 57, 9, history, rules);
        final List<Step> p = s.presses();
        assertTrue(p.size() >= 3, "needs enough presses to place the fixed history in the window");
        assertEquals(Family.BEND, p.get(p.size() - 3).family());
        assertEquals(Family.PUNCH, p.get(p.size() - 2).family());
    }

    @Test
    void anyRuleOnlySatisfiedWithAtLeastThreePresses() {
        final List<Rule> rules = List.of(new Rule(Slot.ANY, Family.SHRINK));
        final Solution s = ForgeSim.solve(50, 0, List.of(), rules);
        assertValid(s, 50, 0, List.of(), rules);
        assertTrue(s.pressCount() >= 3);
        final List<Step> p = s.presses();
        final boolean shrinkPresent =
            p.get(p.size() - 1).family() == Family.SHRINK
                || p.get(p.size() - 2).family() == Family.SHRINK
                || p.get(p.size() - 3).family() == Family.SHRINK;
        assertTrue(shrinkPresent);
    }

    @Test
    void fuzzRandomStatesKeepsInvariantsWhenFeasible() {
        final Random random = new Random(12345L);
        final List<Step[]> ruleSets = List.of(
            new Step[] { Step.DRAW, Step.BEND, Step.PUNCH, Step.HIT_LIGHT },
            new Step[] { Step.PUNCH, Step.SHRINK, Step.BEND, Step.HIT_MEDIUM },
            new Step[] { Step.HIT_LIGHT, Step.DRAW, Step.UPSET, Step.HIT_HARD });
        final Slot[] slots = Slot.values();

        for (int i = 0; i < 300; i++) {
            final int target = random.nextInt(Step.LIMIT + 1);
            final int startWork = random.nextInt(Step.LIMIT + 1);
            final Step[] pool = ruleSets.get(random.nextInt(ruleSets.size()));
            final List<Rule> rules = new ArrayList<>();
            final int ruleCount = random.nextInt(4);
            for (int r = 0; r < ruleCount; r++) {
                rules.add(new Rule(slots[random.nextInt(slots.length)], pool[random.nextInt(pool.length)].family()));
            }
            final List<Step> history = new ArrayList<>();
            for (int h = 0; h < random.nextInt(4); h++) {
                history.add(Step.values()[random.nextInt(Step.values().length)]);
            }

            final Solution s = ForgeSim.solve(target, startWork, history, rules);
            if (s.feasible()) {
                assertValid(s, target, startWork, history, rules);
            }
        }
    }

    @Test
    void historyLongerThanThreeUsesOnlyTheMostRecentThree() {
        // Rules only ever look at the last three presses, so a history longer than three must be
        // reduced by taking its TAIL. Taking the head instead would still produce plausible-looking
        // plans, which is exactly why this needs pinning: solve(longHistory) must be identical in
        // every respect to solve(last three of longHistory).
        final List<Step> longHistory =
            List.of(Step.SHRINK, Step.UPSET, Step.DRAW, Step.PUNCH, Step.BEND, Step.HIT_LIGHT);
        final List<Rule> rules = List.of(
            new Rule(Slot.THIRD_LAST, Family.PUNCH),
            new Rule(Slot.SECOND_LAST, Family.BEND));
        final int work = 40;
        final int target = 40;

        for (int length = 4; length <= longHistory.size(); length++) {
            final List<Step> history = longHistory.subList(0, length);
            final List<Step> lastThree = history.subList(length - 3, length);

            final Solution full = ForgeSim.solve(target, work, history, rules);
            final Solution tailOnly = ForgeSim.solve(target, work, lastThree, rules);

            final String where = "history length " + length;
            assertEquals(tailOnly.feasible(), full.feasible(), where + ": feasibility");
            assertEquals(tailOnly.pressCount(), full.pressCount(), where + ": press count");
            // The search is deterministic for identical inputs, so an identical start window must
            // yield the identical press sequence - not merely one of the same length.
            assertEquals(tailOnly.presses(), full.presses(), where + ": press sequence");
            assertEquals(tailOnly.finalWork(), full.finalWork(), where + ": final work");
        }

        // Sharpens the check above into something a head-taking implementation cannot fake. The full
        // six-press history ends [PUNCH, BEND, HIT_LIGHT], which already satisfies both rules, and
        // work already equals target - so the correct answer is "do nothing". Its first three
        // presses are [SHRINK, UPSET, DRAW], which satisfy neither rule and would force extra
        // presses.
        final Solution done = ForgeSim.solve(target, work, longHistory, rules);
        assertTrue(done.feasible());
        assertEquals(0, done.pressCount(), "the most recent three presses already satisfy the rules");
    }

    @Test
    void ruleAwarePressCountIsMinimalAgainstIndependentOracle() {
        // The existing no-rules test proves minimality only when there are no rules to satisfy.
        // Every other test proves validity but never minimality, which leaves the mod's actual
        // selling point - FEWEST presses - unverified for the case that matters. This closes that.
        final List<List<Rule>> ruleSets = List.of(
            List.of(),
            List.of(new Rule(Slot.LAST, Family.HIT)),
            List.of(new Rule(Slot.ANY, Family.SHRINK)),
            List.of(new Rule(Slot.NOT_LAST, Family.PUNCH)),
            List.of(new Rule(Slot.THIRD_LAST, Family.DRAW), new Rule(Slot.LAST, Family.UPSET)),
            List.of(new Rule(Slot.SECOND_LAST, Family.SHRINK), new Rule(Slot.LAST, Family.HIT)),
            // The real sword-blade rule set.
            List.of(
                new Rule(Slot.LAST, Family.HIT),
                new Rule(Slot.SECOND_LAST, Family.BEND),
                new Rule(Slot.THIRD_LAST, Family.BEND)));
        final int[] targets = { 0, 1, 2, 3, 5, 7, 9, 13, 16, 25, 40, 62, 75, 99, 100, 137, 145, 148, 149, 150 };

        for (final List<Rule> rules : ruleSets) {
            for (final int target : targets) {
                assertMatchesOracle(target, 0, rules);
            }
        }
    }

    @Test
    void ruleAwarePressCountIsMinimalFromAPartlyForgedStart() {
        // Same oracle, but starting from a work value the player has already moved off zero. The
        // history stays empty here on purpose: this is the "reopened the anvil on a partly worked
        // item" case, where TFC has the work value but no remembered presses. The mid-sequence case
        // (work value AND a live press window) is covered separately by
        // ruleAwarePressCountIsMinimalWithNonEmptyHistory.
        final List<List<Rule>> ruleSets = List.of(
            List.of(new Rule(Slot.LAST, Family.HIT)),
            List.of(new Rule(Slot.ANY, Family.BEND)),
            List.of(new Rule(Slot.SECOND_LAST, Family.PUNCH), new Rule(Slot.LAST, Family.HIT)));
        final int[] startWorks = { 4, 25, 77, 150 };
        final int[] targets = { 0, 11, 48, 90, 143, 150 };

        for (final List<Rule> rules : ruleSets) {
            for (final int startWork : startWorks) {
                for (final int target : targets) {
                    assertMatchesOracle(target, startWork, rules);
                }
            }
        }
    }

    @Test
    void ruleAwarePressCountIsMinimalWithNonEmptyHistory() {
        // The case the mod actually runs in. The overlay re-solves after EVERY press, so almost
        // every real call arrives with a 1-3 entry history seeding the BFS start window - which is
        // where ForgeSim does its trickiest work (the right-alignment loop that decides which window
        // slots the fixed presses occupy). The other history tests assert validity only, so a bug
        // that produced a valid-but-longer-than-necessary plan from a seeded window would have gone
        // through the entire suite unnoticed. This pins minimality for that case.
        final List<List<Step>> histories = List.of(
            // Length 1: only the "last" slot is fixed.
            List.of(Step.PUNCH),
            List.of(Step.HIT_MEDIUM),
            // Length 2: "second last" and "last" are fixed, "third last" is still open.
            List.of(Step.BEND, Step.PUNCH),
            List.of(Step.SHRINK, Step.DRAW),
            // Length 3: the window is completely full before a single press is planned.
            List.of(Step.BEND, Step.BEND, Step.HIT_LIGHT),
            List.of(Step.DRAW, Step.HIT_HARD, Step.PUNCH));
        final List<List<Rule>> ruleSets = List.of(
            List.of(new Rule(Slot.LAST, Family.HIT)),
            List.of(new Rule(Slot.ANY, Family.PUNCH)),
            List.of(new Rule(Slot.NOT_LAST, Family.BEND), new Rule(Slot.LAST, Family.HIT)));
        // Two bases so each history is exercised from both a low and a high work value; the deltas
        // of every history above keep both inside the bar.
        final int[] bases = { 30, 90 };
        final int[] targets = { 0, 19, 66, 121, 150 };

        for (final List<Step> history : histories) {
            for (final int base : bases) {
                // startWork is the work value AFTER the history, which is what TFC hands the mod.
                int startWork = base;
                for (final Step step : history) {
                    startWork += step.delta();
                }
                assertTrue(startWork >= 0 && startWork <= Step.LIMIT,
                    "test data error: history " + history + " from base " + base
                        + " leaves work " + startWork + " off the bar");

                for (final List<Rule> rules : ruleSets) {
                    for (final int target : targets) {
                        assertMatchesOracle(target, startWork, history, rules);
                    }
                }
            }
        }
    }

    /** Asserts that the solver agrees with {@link #oracleMinPresses} on both feasibility and press count. */
    private static void assertMatchesOracle(int target, int startWork, List<Rule> rules) {
        assertMatchesOracle(target, startWork, List.of(), rules);
    }

    /** As above, for a run that starts with presses already in the rule window. */
    private static void assertMatchesOracle(int target, int startWork, List<Step> history, List<Rule> rules) {
        final Solution s = ForgeSim.solve(target, startWork, history, rules);
        final int expected = oracleMinPresses(target, startWork, history, rules);
        final String where = "target " + target + " from work " + startWork
            + " after " + history + " with rules " + rules;

        if (expected < 0) {
            assertFalse(s.feasible(), where + " should be infeasible");
            return;
        }
        assertTrue(s.feasible(), where + " should be feasible in " + expected + " presses");
        assertEquals(expected, s.pressCount(), where + ": press count is not minimal");
        assertValid(s, target, startWork, history, rules);
    }

    /**
     * Independent oracle for the true minimum press count, for any press history.
     *
     * <p>This deliberately does not re-implement {@code ForgeSim}'s BFS over
     * {@code (work, last-three window)} states - re-running the same algorithm would prove nothing.
     * It is the approach the original JavaScript tool used, so agreement between the two is a
     * genuine cross-check of two independent implementations:
     *
     * <ul>
     *   <li>Lengths 0, 1 and 2 are brute-forced outright (1 + 8 + 64 = 73 sequences). These are the
     *       only lengths where the rule window still contains presses that were <em>not</em>
     *       planned - either history, or nothing at all. The brute force therefore starts from the
     *       history rather than from an empty list, so the window it checks is
     *       {@code history + planned} exactly as the real solver's would be. Where the combined
     *       sequence is still shorter than three, the missing slots satisfy nothing, which
     *       {@link #rulesOkOn} already models by passing null.</li>
     *   <li>For length 3 or more, the rule window is exactly the final three presses and nothing
     *       else, so all 8x8x8 = 512 possible finales are enumerated. History cannot influence this
     *       branch at all - three planned presses push every historical press out of the window -
     *       which is why {@code history} appears nowhere below this point. A finale that satisfies
     *       the rules fixes the work value {@code P} the sequence must be at before it starts
     *       ({@code target} minus the finale's total delta); the shortest way to reach {@code P} is
     *       then a plain work-only BFS with no window and no rules at all.</li>
     * </ul>
     *
     * @param startWork the work value <em>after</em> the history has been applied, matching what
     *                  {@code ForgeSim.solve} is given
     * @param history   presses already performed, oldest first; may be empty
     * @return the minimum number of presses, or -1 if no valid sequence exists
     */
    private static int oracleMinPresses(int target, int startWork, List<Step> history, List<Rule> rules) {
        if (target < 0 || target > Step.LIMIT || startWork < 0 || startWork > Step.LIMIT) {
            return -1;
        }

        // Lengths 0-2 first: any hit here beats anything the length->3 search can return. The
        // scratch buffer is seeded with the history so that a short plan is rule-checked against the
        // real combined window; it is copied because the search appends to and truncates it.
        for (int length = 0; length <= 2; length++) {
            if (anySequenceReaches(new ArrayList<>(history), length, startWork, target, rules)) {
                return length;
            }
        }

        // Many finales share the same required pre-finale work value, so cache the BFS per value.
        // Without this the BFS would run 512 times per call instead of at most once per work value.
        final Map<Integer, Integer> distanceToP = new HashMap<>();
        int best = -1;

        for (final Step third : Step.values()) {
            for (final Step second : Step.values()) {
                for (final Step last : Step.values()) {
                    final List<Step> finale = List.of(third, second, last);
                    if (!rulesOkOn(finale, rules)) {
                        continue;
                    }

                    final int delta = third.delta() + second.delta() + last.delta();
                    final int p = target - delta;
                    if (p < 0 || p > Step.LIMIT) {
                        continue;
                    }

                    // The finale itself must stay on the bar the whole way, not just end on target.
                    int work = p;
                    boolean inBounds = true;
                    for (final Step step : finale) {
                        work += step.delta();
                        if (work < 0 || work > Step.LIMIT) {
                            inBounds = false;
                            break;
                        }
                    }
                    if (!inBounds) {
                        continue;
                    }

                    final int prefix = distanceToP.computeIfAbsent(p, q -> bruteShortest(startWork, q));
                    if (prefix < 0) {
                        continue;
                    }
                    final int total = prefix + 3;
                    if (best < 0 || total < best) {
                        best = total;
                    }
                }
            }
        }
        return best;
    }

    /**
     * Exhaustive search for any in-bounds sequence of exactly {@code remaining} further presses that
     * ends on {@code target} and satisfies the rules. Used only for plans of length 0-2, where the
     * rule window is not made up purely of planned presses.
     *
     * <p>Counts down the presses still to choose rather than comparing against a total length, so
     * the caller is free to seed {@code prefix} with the press history: the rule check then sees
     * {@code history + planned}, while the recursion still stops after exactly {@code remaining}
     * planned presses.
     *
     * @param prefix    scratch buffer holding the history plus the presses chosen so far; left as
     *                  found on return
     * @param remaining how many more presses to choose
     * @param work      the work value after everything currently in {@code prefix}
     */
    private static boolean anySequenceReaches(
        List<Step> prefix, int remaining, int work, int target, List<Rule> rules
    ) {
        if (remaining == 0) {
            return work == target && rulesOkOn(prefix, rules);
        }
        for (final Step step : Step.values()) {
            final int next = work + step.delta();
            if (next < 0 || next > Step.LIMIT) {
                continue;
            }
            prefix.add(step);
            final boolean found = anySequenceReaches(prefix, remaining - 1, next, target, rules);
            prefix.remove(prefix.size() - 1);
            if (found) {
                return true;
            }
        }
        return false;
    }

    /** Verifies the solver contract: startWork already includes the history (the caller's current anvil work),
     *  so the planned presses must take startWork exactly to target while staying in bounds, and the whole
     *  sequence (history + presses) must satisfy the rules in its final last-three window. */
    private static void assertValid(Solution s, int target, int startWork, List<Step> history, List<Rule> rules) {
        assertTrue(s.feasible());
        assertEquals(target, s.finalWork());

        int work = startWork;
        for (final Step step : s.presses()) {
            work += step.delta();
            assertTrue(work >= 0 && work <= Step.LIMIT, "press " + step + " leaves work " + work + " out of bounds");
        }
        assertEquals(target, work);

        final List<Step> full = new ArrayList<>(history);
        full.addAll(s.presses());
        assertTrue(rulesOkOn(full, rules), "final three presses do not satisfy the rules");
    }

    /**
     * Whether a full press sequence satisfies the rules, judged on its last three presses.
     *
     * <p><strong>Read this before trusting what the tests in this file prove.</strong> This method
     * is a restatement of {@code ForgeSim.rulesOk} - the same five-arm switch over
     * {@code Slot}, with the same meaning for each arm. It is used both by
     * {@link #oracleMinPresses} and by {@link #assertValid}, so it is on both sides of every
     * comparison. Consequently these tests cross-check the <em>search strategy</em> (does the BFS
     * find the shortest sequence the rules allow?) and NOT the <em>rule semantics</em> (is that the
     * right reading of the rules in the first place?). If {@code NOT_LAST} were misinterpreted, this
     * method would misinterpret it identically and the whole suite would still be green.
     *
     * <p>What backs the semantics instead is source inspection, not these tests: TFC's own
     * {@code ForgeRule.matches(last, secondLast, thirdLast)} (branch {@code 1.21.x}) implements
     * exactly {@code ANY = last||second||third}, {@code NOT_LAST = second||third},
     * {@code LAST = last}, {@code SECOND_LAST = second}, {@code THIRD_LAST = third} - which is what
     * both this method and {@code ForgeSim.rulesOk} encode. So the semantics are confirmed correct,
     * just not by anything in this file. Re-check them against TFC's source, not against a green
     * test run, if that interpretation is ever in doubt.
     */
    private static boolean rulesOkOn(List<Step> sequence, List<Rule> rules) {
        final Step thirdLast = sequence.size() >= 3 ? sequence.get(sequence.size() - 3) : null;
        final Step secondLast = sequence.size() >= 2 ? sequence.get(sequence.size() - 2) : null;
        final Step last = sequence.isEmpty() ? null : sequence.get(sequence.size() - 1);
        for (final Rule rule : rules) {
            final boolean ok = switch (rule.slot()) {
                case ANY ->
                    matches(rule, last) || matches(rule, secondLast) || matches(rule, thirdLast);
                case NOT_LAST -> matches(rule, secondLast) || matches(rule, thirdLast);
                case LAST -> matches(rule, last);
                case SECOND_LAST -> matches(rule, secondLast);
                case THIRD_LAST -> matches(rule, thirdLast);
            };
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(Rule rule, Step step) {
        return step != null && rule.family().matches(step);
    }

    /** Independent shortest-path BFS over work values only (no rules), used to verify minimality. */
    private static int bruteShortest(int from, int to) {
        final int[] dist = new int[Step.LIMIT + 1];
        Arrays.fill(dist, -1);
        final ArrayDeque<Integer> queue = new ArrayDeque<>();
        dist[from] = 0;
        queue.add(from);
        while (!queue.isEmpty()) {
            final int work = queue.poll();
            if (work == to) {
                return dist[work];
            }
            for (final Step step : Step.values()) {
                final int next = work + step.delta();
                if (next >= 0 && next <= Step.LIMIT && dist[next] == -1) {
                    dist[next] = dist[work] + 1;
                    queue.add(next);
                }
            }
        }
        return -1;
    }
}
