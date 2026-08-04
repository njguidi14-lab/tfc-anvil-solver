package com.njguidi14.anvilsolver.solver;

/**
 * The positional constraint of a forging rule, evaluated against the last three
 * presses of the whole sequence. Names mirror TerraFirmaCraft's {@code ForgeRule} orders.
 */
public enum Slot {
    /** Present anywhere in the last three presses. */
    ANY,
    /** The final press. */
    LAST,
    /** Present in the second-to-last or third-to-last press. */
    NOT_LAST,
    /** The second-to-last press. */
    SECOND_LAST,
    /** The third-to-last press. */
    THIRD_LAST
}
