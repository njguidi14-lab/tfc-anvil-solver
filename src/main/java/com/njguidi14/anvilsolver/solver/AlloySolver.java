package com.njguidi14.anvilsolver.solver;

/**
 * Finds the fewest whole units of one fixed volume to add to a mixture so that every component
 * finishes inside its own fractional range at the same time.
 *
 * <p>Pure Java with no Minecraft dependencies, so it can be unit-tested in isolation - the same
 * rule the rest of this package follows, and the whole reason this arithmetic now lives here.
 * It used to sit inside {@code client.CrucibleCalculator}, where its signature took TFC's
 * {@code AlloyRange} and Minecraft's {@code Fluid}; neither can be built outside a running game,
 * so the maths shipped with no tests at all while the anvil solver next door had a suite. The
 * maths never needed either type. A range is two doubles, a component is an index, and what is
 * already in the pot is a third double - which is exactly what the old method's own local
 * variables were, so nothing below has been rewritten, only moved.
 *
 * <p><b>The arithmetic.</b> Write {@code C_i} for what is in the mixture now, {@code T0} for the
 * current total, {@code V} for one unit's volume, and {@code n_i} for the units of component
 * {@code i} to add. Adding {@code K} units in total fixes the <em>final</em> total at
 * {@code T = T0 + K*V} before anything else is decided - which is what makes this tractable,
 * because the ranges are fractions of that final total and the total is now a known number rather
 * than something that moves as each component goes in. Component {@code i} must finish inside
 * {@code [min_i*T, max_i*T]}, and it finishes at {@code C_i + V*n_i}, so
 *
 * <pre>
 *   lo_i = max(0, ceil((min_i*T - C_i) / V))
 *   hi_i =        floor((max_i*T - C_i) / V)
 * </pre>
 *
 * <p>bracket the only unit counts that work for that {@code K}. If any bracket is empty the
 * {@code K} is impossible. If the brackets' minima already exceed {@code K}, or their maxima
 * cannot reach it, the {@code K} is impossible too. Otherwise every component starts at
 * {@code lo_i} and the leftover units are spread into the remaining headroom, which cannot break
 * anything because no component is ever pushed past its own {@code hi_i}.
 *
 * <p>Searching {@code K} upwards from zero and returning the first success is what makes the
 * answer minimal: any smaller {@code K} was tested and rejected.
 *
 * <p><b>{@code V} is one number for the whole solve, not one per component.</b> That is
 * load-bearing, not an oversight: it is precisely because {@code V} is scalar that {@code K} fixes
 * {@code T} up front, and it is because {@code T} is known up front that the ranges become the
 * independent integer brackets above. Per-component volumes would make {@code T} depend on which
 * components the units went to and the derivation would not hold. Where the caller's one number
 * comes from, and what it does on the packs whose metals disagree about it, is
 * {@code CrucibleCalculator.chooseIngotVolume}'s problem, not this class's.
 *
 * <p><b>A counter-intuitive consequence, pinned by the tests.</b> From an <em>empty</em> mixture
 * the answer depends only on the ratios and not on {@code V} at all: every bound above is
 * {@code (frac * K * V) / V}, so {@code V} cancels. Bronze from empty is 8 copper + 1 tin at
 * {@code V = 100} and at {@code V = 15} alike. It stops being true the moment {@code T0 > 0},
 * because then {@code C_i} and {@code V} no longer scale together.
 *
 * <p>This file, like every other in the mod, is pure ASCII - see the note in
 * {@code CrucibleCalculator}'s class javadoc for why.
 */
public final class AlloySolver {

    /**
     * Slack applied to the {@code ceil}/{@code floor} that turn the range bounds into whole unit
     * counts.
     *
     * <p>{@code min * total} is a product of two doubles and lands a hair either side of the
     * integer it mathematically is - so a bound that is exactly 9 units can compute as
     * 9.0000000001 and {@code ceil} to 10, silently discarding the correct answer. The tolerance is
     * applied so that it always <em>widens</em> the candidate interval, never narrows it: a
     * spurious extra candidate is caught and rejected by {@link #verify}, whereas a wrongly
     * discarded one is gone for good.
     */
    private static final double EDGE_TOLERANCE = 1.0e-7;

    /**
     * The tolerance {@link #verify} judges a finished plan by.
     *
     * <p><b>This mirrors TFC's {@code FluidAlloy.EPSILON}</b>, which is what
     * {@code AlloyRange.isIn} - the exact call the game itself makes to decide whether an alloy
     * forms - compares with. Before the extraction, {@code verify} called {@code isIn} directly and
     * so inherited that tolerance for free; the number has to be restated here because this package
     * deliberately cannot see TFC's types.
     *
     * <p>Written as the same expression TFC uses rather than as a decimal literal, so it is checkable
     * by eye against the source it copies. Verbatim from {@code FluidAlloy}:
     *
     * <pre>
     *     public static final int MAX_ALLOY = Integer.MAX_VALUE - 2;
     *     public static final double EPSILON = 1d / (2 + MAX_ALLOY);
     * </pre>
     *
     * That works out to roughly {@code 4.66e-10} - a hair either side of an exact match, and nothing
     * like a slack tolerance. Worth stating plainly because the intuitive guess is far larger: an
     * earlier draft of this class used {@code 1.0e-4}, six orders of magnitude too loose, which would
     * have accepted mixes up to a hundredth of a percent outside their range and told the player to
     * melt in a plan the game then refuses to form.
     *
     * <p>It is a <em>copy of someone else's constant</em>, and the one line in this class that can
     * silently go stale. If a future TFC changes it, this must change with it: too small rejects
     * plans the game would have accepted, too large accepts plans that will not form. Re-check it
     * against TFC's {@code FluidAlloy} source rather than against a green test run - nothing in the
     * suite can tell the difference, for the same reason {@code ForgeSimTest.rulesOkOn} cannot check
     * TFC's rule semantics.
     */
    private static final double RANGE_EPSILON = 1.0d / (2.0d + (Integer.MAX_VALUE - 2));

    private AlloySolver() {
    }

    /**
     * Finds the fewest whole units to add so that every component lands inside its range.
     *
     * <p>The three arrays are parallel - index {@code i} of each describes the same component - and
     * are read, never written; the caller keeps ownership of them. The returned array is freshly
     * allocated on every call and is never shared with a later one.
     *
     * <p><b>Why the unit and the cap are parameters.</b> Nothing in the derivation in this class's
     * javadoc says the unit has to be an ingot - only that it is one fixed volume - so this is
     * written as a solve in whole units of size {@code unitVolume}, up to {@code maxUnits} of them,
     * and the crucible overlay supplies an ingot volume and its own cap. Keeping the generalisation
     * costs nothing: both are quantities the arithmetic already had to name.
     *
     * <p><b>Nothing here throws</b>, for any input, including hostile ones. Its one real caller is
     * on Minecraft's render path, where a single escaping exception is not one crash but a crash on
     * every frame forever. Bad data - mismatched array lengths, a non-finite bound, an inverted
     * range, a non-positive volume - is answered with "no plan" rather than an exception.
     *
     * @param min        each component's minimum share of the final total, as a fraction
     * @param max        each component's maximum share, as a fraction; must be at least its
     *                   {@code min} or there is no plan
     * @param have       how much of each component the mixture already holds, in the same units as
     *                   {@code total} (mB, for a crucible); zero for "none"
     * @param total      the current total, zero for an empty mixture
     * @param unitVolume one unit's volume, in practice an ingot's; a non-positive value finds
     *                   nothing rather than dividing by it
     * @param maxUnits   the most units to consider adding; a non-positive value simply finds
     *                   nothing
     * @return units to add, indexed to match the input arrays, or {@code null} if there is no plan
     *         within {@code maxUnits}. Null rather than an empty array, because an empty array is
     *         not free to mean "no answer" here: a zero-length component list and a plan of all
     *         zeroes are both legitimate states, and the all-zeroes plan - "everything is already
     *         in range, add nothing" - is a real and useful answer that a caller must be able to
     *         tell apart from failure.
     */
    public static int[] solve(
        double[] min, double[] max, double[] have, double total, int unitVolume, int maxUnits
    ) {
        final int size = min.length;
        if (size == 0 || unitVolume <= 0) {
            return null;
        }
        // The three arrays describe one component per index, so a length disagreement means the
        // caller has built them inconsistently and every bound below would be reading a bound that
        // belongs to some other component. Unreachable from CrucibleCalculator, which fills all
        // three from one loop over one list, but this is a public entry point.
        if (max.length != size || have.length != size) {
            return null;
        }
        // A non-finite total makes every fraction below NaN, and NaN comparisons are false in both
        // directions - so it would not fail any test loudly, it would quietly wander to the bottom
        // of the k loop and report "no plan" the slow way. Also unreachable from the overlay, whose
        // total is an int.
        if (!Double.isFinite(total)) {
            return null;
        }
        for (int i = 0; i < size; i++) {
            // Ranges come from datapacks. Anything that is not a sane interval would propagate NaN
            // through every bound below and produce a plan that looks authoritative and is not.
            // The amounts are checked for the same reason, though the crucible's own snapshot has
            // already dropped anything non-finite before it gets here.
            if (!Double.isFinite(min[i]) || !Double.isFinite(max[i]) || min[i] > max[i]
                || !Double.isFinite(have[i])) {
                return null;
            }
        }

        final long[] lo = new long[size];
        final long[] hi = new long[size];
        final int[] counts = new int[size];

        // The "k >= 0" term is a termination guard, not part of the search: it only ever fires on
        // the k++ that overflows, i.e. for maxUnits == Integer.MAX_VALUE, where the loop would
        // otherwise wrap to Integer.MIN_VALUE and run forever. Every terminating input reaches
        // exactly the same iterations it did before.
        for (int k = 0; k >= 0 && k <= maxUnits; k++) {
            // Widened to double before the multiply, exactly as the long arithmetic this replaces
            // was: the volume is read out of pack data rather than clamped to the config's range,
            // so k * unitVolume has no small upper bound to appeal to and must not be evaluated in
            // int. Every value this is called with in practice is a small integer, which a double
            // holds exactly.
            final double finalTotal = total + (double) k * (double) unitVolume;
            if (finalTotal <= 0.0) {
                // k == 0 on an empty mixture. Nothing is a valid alloy, and every fraction would be
                // 0/0, so this is skipped rather than allowed to produce NaN.
                continue;
            }

            boolean bracketsOk = true;
            long sumLo = 0L;
            long sumHi = 0L;
            for (int i = 0; i < size; i++) {
                lo[i] = (long) Math.ceil((min[i] * finalTotal - have[i]) / unitVolume - EDGE_TOLERANCE);
                hi[i] = (long) Math.floor((max[i] * finalTotal - have[i]) / unitVolume + EDGE_TOLERANCE);
                if (lo[i] < 0L) {
                    // Negative would mean "remove some to get down to the minimum", which is not a
                    // thing a crucible can do. Zero is the real floor.
                    lo[i] = 0L;
                }
                if (hi[i] > k) {
                    // No single component can take more than the whole budget. Clamping here also
                    // keeps hi small enough that the sums below cannot run away.
                    hi[i] = k;
                }
                if (lo[i] > hi[i]) {
                    // This component cannot be brought inside its range at this final total.
                    // Usually it means it is already over its maximum share and k is too small to
                    // have diluted it back down yet.
                    bracketsOk = false;
                    break;
                }
                sumLo += lo[i];
                sumHi += hi[i];
            }
            // sumLo > k: the minimum requirements alone need more units than this k allows.
            // sumHi < k: even filling every component to its maximum cannot absorb this many units.
            if (!bracketsOk || sumLo > k || sumHi < k) {
                continue;
            }

            for (int i = 0; i < size; i++) {
                counts[i] = (int) lo[i];
            }
            long surplus = k - sumLo;
            while (surplus > 0L) {
                // Each spare unit goes to whichever component with headroom left is currently
                // furthest BELOW the centre of its own range. Any distribution inside the brackets
                // is valid, so this is a free choice - and spending it on centring the mix is worth
                // doing, because a component parked on the exact edge of its range is one rounding
                // difference away from the alloy not forming. Ties go to the earlier component, so
                // the plan is identical from frame to frame.
                int pick = -1;
                double worst = 0.0;
                for (int i = 0; i < size; i++) {
                    if (counts[i] >= hi[i]) {
                        continue;
                    }
                    final double share = (have[i] + (double) unitVolume * counts[i]) / finalTotal;
                    final double offset = share - (min[i] + max[i]) / 2.0;
                    if (pick < 0 || offset < worst) {
                        pick = i;
                        worst = offset;
                    }
                }
                if (pick < 0) {
                    // Unreachable: sumHi >= k guarantees the headroom exists. Breaking rather than
                    // spinning means a broken invariant costs one rejected k, not a frozen client.
                    break;
                }
                counts[pick]++;
                surplus--;
            }
            if (surplus > 0L) {
                continue;
            }

            if (verify(min, max, have, counts, finalTotal, unitVolume)) {
                // Cloned because counts is reused by the next iteration of the k loop; the caller
                // must not be handed an array this method still writes to.
                return counts.clone();
            }
        }
        return null;
    }

    /**
     * Re-checks a finished plan against the same range test the game itself applies.
     *
     * <p><b>Why the answer is verified at all.</b> {@code ceil} and {@code floor} sit directly on
     * top of floating-point products, and the numbers that decide whether an alloy forms are
     * computed by TFC with its own epsilon. Rather than trust that this class's rounding and TFC's
     * tolerance agree, the finished plan is fed back through a restatement of TFC's own
     * {@code AlloyRange.isIn}. A confidently wrong unit count is worse than no answer, so a plan
     * that fails this is discarded and the search moves on to the next {@code k}.
     */
    private static boolean verify(
        double[] min, double[] max, double[] have, int[] counts, double finalTotal, int unitVolume
    ) {
        for (int i = 0; i < min.length; i++) {
            final double amount = have[i] + (double) unitVolume * counts[i];
            if (!isIn(min[i], max[i], amount / finalTotal)) {
                return false;
            }
        }
        return true;
    }

    /**
     * TFC's {@code AlloyRange.isIn}, restated: a fraction counts as inside the range when it is
     * within {@link #RANGE_EPSILON} of it on either side.
     *
     * <p>Written as two comparisons against the value rather than as a distance, so that a NaN
     * fraction - which compares false against everything - is rejected rather than accepted.
     */
    private static boolean isIn(double min, double max, double value) {
        return value >= min - RANGE_EPSILON && value <= max + RANGE_EPSILON;
    }
}
