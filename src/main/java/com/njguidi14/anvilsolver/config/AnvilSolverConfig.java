package com.njguidi14.anvilsolver.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class AnvilSolverConfig {

    public static final ModConfigSpec CLIENT_SPEC;
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.BooleanValue HIGHLIGHT_NEXT_BUTTON;
    public static final ModConfigSpec.IntValue OVERLAY_GAP;
    public static final ModConfigSpec.IntValue OVERLAY_Y;
    public static final ModConfigSpec.IntValue MAX_PRESSES;

    static {
        final ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        ENABLED = builder
            .comment("Show the anvil solver overlay in the TFC anvil screen.")
            .define("enabled", true);

        HIGHLIGHT_NEXT_BUTTON = builder
            .comment(
                "Highlight the anvil's own step button for the next press.",
                "Draws a bright outline over the button you should click next, so you can click the",
                "highlighted button directly instead of reading the step off the overlay list.",
                "Only shown while a solution exists and at least one press is still needed."
            )
            .define("highlightNextButton", true);

        OVERLAY_GAP = builder
            .comment(
                "Horizontal gap in pixels between the anvil GUI's right edge and the overlay box.",
                "Negative values pull the overlay back over the GUI itself.",
                "If the box will not fit on the right, it is mirrored to the left of the GUI and",
                "this gap is applied on that side instead."
            )
            .defineInRange("overlayGap", 4, -400, 400);

        OVERLAY_Y = builder
            .comment(
                "Vertical offset in pixels from the anvil GUI's top edge.",
                "0 aligns the top of the overlay box with the top of the GUI panel."
            )
            .defineInRange("overlayY", 0, -400, 400);

        MAX_PRESSES = builder
            .comment("Maximum number of upcoming presses to list at once.")
            .defineInRange("maxPresses", 10, 1, 30);

        CLIENT_SPEC = builder.build();
    }

    private AnvilSolverConfig() {
    }
}
