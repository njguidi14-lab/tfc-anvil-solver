package com.njguidi14.anvilsolver.solver;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the fewest-press sequence that lands exactly on the target work value
 * while satisfying the recipe's forging rules.
 *
 * <p>Pure Java with no Minecraft dependencies so it can be unit-tested in
 * isolation. The search is a breadth-first walk over states
 * {@code (work value, last-three presses)}, seeded with the item's current
 * forging state. Starting from the live state means mid-forge tracking works
 * for free: already-performed presses are fixed history, and only the window's
 * last three presses matter for the rules.</p>
 */
public final class ForgeSim {

    private static final Step[] STEPS = Step.values();

    private ForgeSim() {
    }

    /**
     * Solves for the shortest press sequence from a forging state.
     *
     * @param target      the target work value to land on
     * @param currentWork the item's current work value
     * @param history     the presses already performed, ordered oldest-first as
     *                    {@code [thirdLast, secondLast, last]} (TFC's
     *                    {@code Forging.lastSteps()} ordering); may be empty
     * @param rules       the recipe's forging rules; may be empty
     * @return the optimal solution, or an infeasible solution if none exists
     */
    public static Solution solve(int target, int currentWork, List<Step> history, List<Rule> rules) {
        if (target < 0 || target > Step.LIMIT || currentWork < 0 || currentWork > Step.LIMIT) {
            return Solution.infeasible(target, currentWork);
        }

        // Right-align the existing presses so the last-three window reflects the
        // real sequence: [null, null, last] for one press, [null, secondLast, last]
        // for two, [thirdLast, secondLast, last] for three. This keeps fixed history
        // in the window as future presses slide it forward.
        //
        // If more than 3 entries are passed in, only the MOST RECENT 3 matter (the
        // rules only ever look at the last three presses) - take the tail of the
        // list, not the head, since history is ordered oldest-first.
        final List<Step> tail = history.size() > 3
            ? history.subList(history.size() - 3, history.size())
            : history;
        final Step[] window = new Step[3];
        final int historySize = tail.size();
        for (int i = 0; i < historySize; i++) {
            window[3 - historySize + i] = tail.get(i);
        }

        final State start = new State(currentWork, window[0], window[1], window[2]);
        if (start.work == target && rulesOk(start, rules)) {
            return new Solution(true, List.of(), target, currentWork);
        }

        final Map<State, ParentLink> previous = new HashMap<>();
        final ArrayDeque<State> queue = new ArrayDeque<>();
        // Sentinel (not null) so putIfAbsent's null return still means "genuinely new":
        // if the start state is ever rediscovered, it must not be re-enqueued.
        previous.put(start, ROOT);
        queue.add(start);

        State goal = null;
        while (!queue.isEmpty()) {
            final State current = queue.poll();
            if (current.work == target && rulesOk(current, rules)) {
                goal = current;
                break;
            }
            for (final Step step : STEPS) {
                final int nextWork = current.work + step.delta();
                if (nextWork < 0 || nextWork > Step.LIMIT) {
                    continue;
                }
                final State next = new State(nextWork, current.secondLast, current.last, step);
                if (previous.putIfAbsent(next, new ParentLink(current, step)) == null) {
                    queue.add(next);
                }
            }
        }

        if (goal == null) {
            return Solution.infeasible(target, currentWork);
        }

        final List<Step> presses = new ArrayList<>();
        for (State cursor = goal; previous.get(cursor) != ROOT; cursor = previous.get(cursor).parent) {
            presses.add(previous.get(cursor).step);
        }
        Collections.reverse(presses);
        return new Solution(true, presses, target, goal.work);
    }

    /** Sentinel parent link marking the start state, so it is never mistaken for undiscovered. */
    private static final ParentLink ROOT = new ParentLink(null, null);

    /** A single BFS state: the work value plus the sliding last-three window. */
    private record State(int work, Step thirdLast, Step secondLast, Step last) {
    }

    /** Parent link used to reconstruct the winning path. */
    private record ParentLink(State parent, Step step) {
    }

    // Verified against TFC's real ForgeRule.matches(last, secondLast, thirdLast) (1.21.x,
    // ForgeRule.java on GitHub): ANY = last||secondLast||thirdLast, NOT_LAST =
    // secondLast||thirdLast (never last), LAST = last only, SECOND_LAST = secondLast only,
    // THIRD_LAST = thirdLast only. The switch below already matches these semantics exactly.
    private static boolean rulesOk(State state, List<Rule> rules) {
        for (final Rule rule : rules) {
            final boolean ok = switch (rule.slot()) {
                case ANY ->
                    matches(rule.family(), state.last)
                        || matches(rule.family(), state.secondLast)
                        || matches(rule.family(), state.thirdLast);
                case NOT_LAST ->
                    matches(rule.family(), state.secondLast)
                        || matches(rule.family(), state.thirdLast);
                case LAST -> matches(rule.family(), state.last);
                case SECOND_LAST -> matches(rule.family(), state.secondLast);
                case THIRD_LAST -> matches(rule.family(), state.thirdLast);
            };
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(Family family, Step step) {
        return step != null && family.matches(step);
    }
}
