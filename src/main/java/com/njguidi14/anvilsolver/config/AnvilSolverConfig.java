package com.njguidi14.anvilsolver.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class AnvilSolverConfig {

    public static final ModConfigSpec CLIENT_SPEC;
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.BooleanValue HIGHLIGHT_NEXT_BUTTON;
    public static final ModConfigSpec.BooleanValue SHOW_TEMPERATURE;
    public static final ModConfigSpec.BooleanValue SHOW_ALLOY_CALCULATOR;
    public static final ModConfigSpec.IntValue OVERLAY_GAP;
    public static final ModConfigSpec.IntValue OVERLAY_Y;
    public static final ModConfigSpec.IntValue MAX_PRESSES;
    public static final ModConfigSpec.EnumValue<OverlayTheme> THEME;

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
                "Only shown while a solution exists and at least one press is still needed.",
                "Also hidden while the item is too cold to work, since pressing then does nothing.",
                "That happens whether or not showTemperature is on: the mod always reads the item's",
                "heat, and showTemperature only decides whether the temperature line is drawn."
            )
            .define("highlightNextButton", true);

        SHOW_TEMPERATURE = builder
            .comment(
                "Show the item's temperature line in the overlay.",
                "Adds one line with the current temperature and a bar showing it against the",
                "temperature the item must reach before presses register at all, plus a rough",
                "countdown to that point when the item is measurably cooling.",
                "This option controls that line only. The mod reads the item's heat either way, so",
                "the too-cold warning that replaces the press list, and the hidden next-button",
                "highlight that goes with it, still happen when this is off.",
                "Has no effect on items that cannot be heated."
            )
            .define("showTemperature", true);

        SHOW_ALLOY_CALCULATOR = builder
            .comment(
                "Show the alloy calculator overlay in the TFC crucible screen.",
                "Lists what is in the crucible as a percentage of the total and names the alloy it",
                "currently makes. When the mix is not a valid alloy, it also names the closest alloy",
                "still reachable by adding metal - one that contains everything already in the pot,",
                "since metal can be added but not taken back out - and, for each metal that is short,",
                "how many mB of it to add.",
                "Add one metal at a time and let the numbers refresh: each figure assumes only that",
                "metal is being added, so adding two at once dilutes both.",
                "Uses the same theme, overlayGap and overlayY settings as the anvil overlay.",
                "Independent of the 'enabled' option above, which governs the anvil overlay only."
            )
            .define("showAlloyCalculator", true);

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

        THEME = builder
            .comment(
                "Colour theme for the overlay. Affects colours only - never layout or behaviour.",
                "TFC_GREEN: the original green-on-black palette (default).",
                "COLORBLIND_SAFE: blue for the next press, amber for warnings, so the two colours",
                "  that carry meaning never rely on telling red from green. They also differ",
                "  clearly in brightness, so they stay apart in greyscale too.",
                "HIGH_CONTRAST: white text and border on a more opaque black box, for reading the",
                "  overlay over a busy background.",
                "MONOCHROME: no colour at all - the next press is simply the brightest line."
            )
            .defineEnum("theme", OverlayTheme.TFC_GREEN);

        CLIENT_SPEC = builder.build();
    }

    private AnvilSolverConfig() {
    }
}
