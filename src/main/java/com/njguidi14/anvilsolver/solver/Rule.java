package com.njguidi14.anvilsolver.solver;

/**
 * A single forging rule: a press family that must appear in a given slot of the
 * last three presses.
 *
 * @param slot   which position in the last-three window the family must occupy
 * @param family which press family must satisfy the rule
 */
public record Rule(Slot slot, Family family) {
}
