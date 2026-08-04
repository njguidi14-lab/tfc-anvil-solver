package com.njguidi14.anvilsolver.solver;

import java.util.List;

/**
 * Result of running {@link ForgeSim#solve}.
 *
 * @param feasible  whether a valid press sequence exists from the given state
 * @param presses   the press sequence to execute (empty when already complete or infeasible)
 * @param target    the requested target value
 * @param finalWork the work value the sequence ends on (equals target when feasible)
 */
public record Solution(boolean feasible, List<Step> presses, int target, int finalWork) {

    public static Solution infeasible(int target, int finalWork) {
        return new Solution(false, List.of(), target, finalWork);
    }

    /** Total number of presses in the planned sequence. */
    public int pressCount() {
        return presses.size();
    }
}
