package com.njguidi14.anvilsolver.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class AnvilSolverConfig {

    public static final ModConfigSpec CLIENT_SPEC;
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.BooleanValue HIGHLIGHT_NEXT_BUTTON;
    public static final ModConfigSpec.BooleanValue SHOW_TEMPERATURE;
    public static final ModConfigSpec.BooleanValue SHOW_ALLOY_CALCULATOR;
    public static final ModConfigSpec.IntValue INGOT_VOLUME;
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
                "Lists what is in the crucible as a percentage of the total, names the alloy it",
                "currently makes, and works out the fewest whole ingots to melt in to reach a target",
                "alloy - listed per metal, so the answer is something you can actually go and do.",
                "The target defaults to Automatic, which picks the alloy the mix is closest to -",
                "measured as how far each metal sits outside that alloy's allowed range, added up.",
                "Ingot count only breaks ties, and alloys with no answer at all are listed last.",
                "On an empty crucible Automatic picks nothing: with nothing in the pot no alloy is",
                "closer than any other, so it asks you to choose instead of guessing.",
                "Click a row in the list to choose a different target, scroll the wheel over the box",
                "to reach the ones below it, or press the 'Cycle alloy target' key (default G,",
                "rebindable under Controls) to step through them all and back to automatic.",
                "The list shows up to five alloys at a time, fewer if the game window is too short,",
                "with an 'Auto' row at the bottom to hand the choice back to the mod. The currently",
                "selected row is the highlighted one. Anything past the five is counted on a",
                "'+N more' line and reached by scrolling or by the cycle key.",
                "Only alloys that contain everything already in the pot are offered, since metal can",
                "be added but not taken back out. An empty crucible can reach any alloy, so it lists",
                "the full recipe from scratch.",
                "Every ingot count in one list is part of the same plan: melt them all in and the mix",
                "lands in range. They are not separate one-at-a-time suggestions.",
                "Uses the same theme, overlayGap and overlayY settings as the anvil overlay.",
                "Independent of the 'enabled' option above, which governs the anvil overlay only."
            )
            .define("showAlloyCalculator", true);

        INGOT_VOLUME = builder
            .comment(
                "FALLBACK volume of one metal ingot in mB, used by the alloy calculator to answer in",
                "whole ingots instead of raw millibuckets.",
                "This is not normally used. The calculator detects each metal's ingot volume from",
                "your own pack, by reading the heating recipe that melts that metal's ingot - the",
                "same data the game itself uses - so a datapack or addon that changes ingot volumes",
                "is followed automatically and this option never comes into it.",
                "It applies only where that detection cannot answer: no ingot item for the metal, no",
                "heating recipe for it, or a recipe that melts into a different metal than expected.",
                "100 is TFC's standard ingot volume and is the right fallback for almost every pack.",
                "Detected values are cached per world and refreshed when you change worlds."
            )
            .defineInRange("ingotVolume", 100, 1, 10000);

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
                "MONOCHROME: no colour at all - the next press is simply the brightest line.",
                "EMBER: warm amber on near-black, the colour of hot metal.",
                "SLATE: the cool counterpart to EMBER - cyan on blue-grey.",
                "DIM: deliberately unobtrusive, for leaving the overlay open all the time."
            )
            .defineEnum("theme", OverlayTheme.TFC_GREEN);

        CLIENT_SPEC = builder.build();
    }

    private AnvilSolverConfig() {
    }
}
