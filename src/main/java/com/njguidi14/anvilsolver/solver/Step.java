package com.njguidi14.anvilsolver.solver;

/**
 * The eight anvil press operations and the value they add to the forging work bar.
 * Values match TerraFirmaCraft's {@code ForgeStep} exactly.
 */
public enum Step {
    HIT_LIGHT(-3),
    HIT_MEDIUM(-6),
    HIT_HARD(-9),
    DRAW(-15),
    PUNCH(2),
    BEND(7),
    UPSET(13),
    SHRINK(16);

    /** Upper bound of the anvil work bar, inclusive - 150 itself is a valid/reachable work value. */
    public static final int LIMIT = 150;

    private final int delta;

    Step(int delta) {
        this.delta = delta;
    }

    /** The net change this press applies to the work value. */
    public int delta() {
        return delta;
    }

    /** The family this step belongs to. "Hit" rules match any of the three hit steps. */
    public Family family() {
        return switch (this) {
            case HIT_LIGHT, HIT_MEDIUM, HIT_HARD -> Family.HIT;
            case DRAW -> Family.DRAW;
            case PUNCH -> Family.PUNCH;
            case BEND -> Family.BEND;
            case UPSET -> Family.UPSET;
            case SHRINK -> Family.SHRINK;
        };
    }

    // displayName() used to live here, returning "Light Hit"/"Medium Hit"/etc. It was deleted when
    // the overlay switched to drawing TFC's own 16x16 step icons instead of spelling the step out,
    // which removed its only caller. It is not kept as "public solver API": nothing outside this mod
    // consumes the solver, the overlay deliberately shows TFC's art rather than strings of our own
    // (TFC's names are already translated, ours would not be), and its default branch derived a
    // label from name() - exactly the kind of unexercised code that quietly rots. If a text label is
    // ever needed again, the right source is TFC's ForgeStep, reachable via TfcMapping.toForgeStep.
}
