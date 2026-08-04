package com.njguidi14.anvilsolver.client;

import com.njguidi14.anvilsolver.solver.Family;
import com.njguidi14.anvilsolver.solver.Rule;
import com.njguidi14.anvilsolver.solver.Slot;
import com.njguidi14.anvilsolver.solver.Step;
import net.dries007.tfc.common.component.forge.ForgeRule;
import net.dries007.tfc.common.component.forge.ForgeStep;

/**
 * Converts TFC's {@code ForgeStep}/{@code ForgeRule} enums into the solver's
 * framework-independent representations.
 */
final class TfcMapping {

    private TfcMapping() {
    }

    static Step map(ForgeStep step) {
        return switch (step) {
            case HIT_LIGHT -> Step.HIT_LIGHT;
            case HIT_MEDIUM -> Step.HIT_MEDIUM;
            case HIT_HARD -> Step.HIT_HARD;
            case DRAW -> Step.DRAW;
            case PUNCH -> Step.PUNCH;
            case BEND -> Step.BEND;
            case UPSET -> Step.UPSET;
            case SHRINK -> Step.SHRINK;
        };
    }

    /**
     * Inverse of {@link #map(ForgeStep)}: turns a solver step back into TFC's own enum constant.
     *
     * <p>Rendering needs TFC's button and icon coordinates ({@code buttonX/buttonY/iconX/iconY}).
     * Those numbers deliberately are NOT copied onto our {@link Step} enum - going back through
     * {@code ForgeStep} keeps TFC as the single source of truth, so a coordinate change on their
     * side can never silently disagree with a stale copy on ours.
     */
    static ForgeStep toForgeStep(Step step) {
        return switch (step) {
            case HIT_LIGHT -> ForgeStep.HIT_LIGHT;
            case HIT_MEDIUM -> ForgeStep.HIT_MEDIUM;
            case HIT_HARD -> ForgeStep.HIT_HARD;
            case DRAW -> ForgeStep.DRAW;
            case PUNCH -> ForgeStep.PUNCH;
            case BEND -> ForgeStep.BEND;
            case UPSET -> ForgeStep.UPSET;
            case SHRINK -> ForgeStep.SHRINK;
        };
    }

    static Rule map(ForgeRule rule) {
        // TFC's serialized name is "<family>_<order>", e.g. "hit_last", "draw_second_last".
        // Verified against TFC's actual ForgeRule enum (1.21.x, ForgeRule.java on GitHub):
        // the family token is always a single word (HIT/DRAW/PUNCH/BEND/UPSET/SHRINK) and
        // the order is everything after it (ANY/NOT_LAST/LAST/SECOND_LAST/THIRD_LAST), so the
        // split MUST be on the FIRST underscore. Splitting on the last underscore instead would
        // incorrectly split e.g. "draw_second_last" into "draw_second" / "last". Do not "fix"
        // this back to lastIndexOf without re-checking the real enum first.
        final String name = rule.getSerializedName();
        final int separator = name.indexOf('_');
        if (separator <= 0 || separator == name.length() - 1) {
            throw new IllegalArgumentException("Unrecognized forge rule: " + name);
        }
        final Family family = switch (name.substring(0, separator)) {
            case "hit" -> Family.HIT;
            case "draw" -> Family.DRAW;
            case "punch" -> Family.PUNCH;
            case "bend" -> Family.BEND;
            case "upset" -> Family.UPSET;
            case "shrink" -> Family.SHRINK;
            default -> throw new IllegalArgumentException("Unrecognized forge rule family: " + name);
        };
        final Slot slot = switch (name.substring(separator + 1)) {
            case "any" -> Slot.ANY;
            case "last" -> Slot.LAST;
            case "not_last" -> Slot.NOT_LAST;
            case "second_last" -> Slot.SECOND_LAST;
            case "third_last" -> Slot.THIRD_LAST;
            default -> throw new IllegalArgumentException("Unrecognized forge rule order: " + name);
        };
        return new Rule(slot, family);
    }
}
