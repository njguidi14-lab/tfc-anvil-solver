package com.njguidi14.anvilsolver.solver;

/**
 * The press "family" a rule refers to. {@link #HIT} matches any of the three
 * hit steps, mirroring TerraFirmaCraft's {@code ForgeRule} semantics where a
 * {@code HIT_*} rule is satisfied by any hit strength.
 */
public enum Family {
    HIT,
    DRAW,
    PUNCH,
    BEND,
    UPSET,
    SHRINK;

    public boolean matches(Step step) {
        return step.family() == this;
    }
}
