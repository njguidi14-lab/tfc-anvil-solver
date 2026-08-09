package com.njguidi14.anvilsolver.client;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.mojang.blaze3d.vertex.PoseStack;
import com.njguidi14.anvilsolver.config.AnvilSolverConfig;
import com.njguidi14.anvilsolver.config.OverlayTheme;
import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import net.dries007.tfc.client.screen.CrucibleScreen;
import net.dries007.tfc.common.blockentities.CrucibleBlockEntity;
import net.dries007.tfc.common.recipes.AlloyRecipe;
import net.dries007.tfc.common.recipes.TFCRecipeTypes;
import net.dries007.tfc.util.AlloyRange;
import net.dries007.tfc.util.FluidAlloy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

/**
 * Reads the live alloy state from the crucible block entity and renders a composition breakdown -
 * plus, for a chosen target alloy, the exact whole ingots to melt in to reach it - in a box
 * alongside TFC's crucible screen.
 *
 * <p><b>Why ingots and not mB.</b> The player never adds millibuckets. They drop ingots into a
 * crucible and those ingots melt, so an answer of "add 112 mB of tin" has to be divided by an ingot
 * volume in the player's head before it means anything, and the division rarely comes out whole.
 * This class therefore answers in the unit the player actually acts in: the fewest whole ingots,
 * of which metals, that land every component of the target inside its range simultaneously. The
 * arithmetic for that is in {@link #solveIngots}.
 *
 * <p><b>Why the target is selectable.</b> The old behaviour picked the "closest" alloy itself,
 * which answers a question ("what is this mix nearly?") that is not the one being asked in front of
 * an empty or single-metal crucible ("I want bronze - what do I melt?"). Auto-pick is still the
 * default and still uses the exact same ranking, but the reachable alloys are listed and the player
 * chooses among them.
 *
 * <p><b>Why the list is clicked rather than cycled.</b> Selecting one item out of a set is a
 * pointing task, and the first version made it a keyboard one: every press of the cycle key moved
 * the target on by one, so reaching the alloy you wanted from a crucible of pure copper - which
 * reaches nearly every recipe in the game - took a string of presses with no way to aim. The
 * candidates are now drawn as rows and {@link #clickAt} maps a click to one directly. The key is
 * kept as an alternative rather than removed: it costs nothing, some players prefer it, and it is
 * the only way to reach a candidate past the {@link #MAX_TARGET_ROWS} the list shows at once.
 *
 * <p><b>Why the clickable rectangles are recorded during drawing.</b> {@link #drawBox} appends a
 * rectangle to {@link #clickRows} inside the same loop iteration that draws the row, from the same
 * local {@code left}/{@code y}/{@code width}/{@code row} values. Recomputing the layout inside the
 * click handler instead would be a second measuring pass free to disagree with the drawing pass -
 * the exact class of bug the box renderer is built to make impossible - and the player would see
 * rows highlight and respond an increasing distance away from where they actually are.
 *
 * <p><b>Why this duplicates layout code from {@link AnvilSolverClient}.</b> Deliberately. The anvil
 * overlay is shipped, in players' hands, and its box measuring/drawing has produced visible bugs
 * twice already; every one of those was a measuring pass and a drawing pass disagreeing about a
 * line's size. Extracting a shared box renderer would mean editing that code to generalise it, on a
 * feature that has nothing to do with the anvil. The duplication here is roughly sixty lines of
 * plain rectangle-and-text drawing, and this box only ever contains uniform plain text lines - so it
 * cannot reproduce the anvil's icon/bar/text height mismatch at all. <em>This is a future refactor
 * candidate</em>: once the anvil overlay has gone a few releases without a layout regression, the
 * two box renderers are worth merging behind one helper. Not before.
 *
 * <p>What is shared is {@link OverlayTheme}, which is a pure data enum with no behaviour, and the
 * {@code overlayGap}/{@code overlayY} config values, so both overlays sit in the same place relative
 * to their GUI and change together.
 *
 * <p>This file, like every other in the mod, is pure ASCII: {@code build.gradle} sets no
 * {@code compileJava.options.encoding}, so javac reads sources in the platform default charset.
 * Nothing here needs a non-ASCII glyph - the arrow is {@code "->"}, the selected-row marker is
 * {@code "> "} and the percent sign is ASCII - so unlike {@code AnvilSolverClient}'s degree sign
 * there is no code-point constant to build.
 */
public final class CrucibleCalculator {

    /** Inner margin between the box border and its content, on every side. */
    private static final int PADDING = 5;
    /** Pixels of clearance kept between the bottom of the box and the bottom of the window. */
    private static final int SCREEN_MARGIN = 2;
    /** Extra leading added to the font's line height, so rows are not jammed together. */
    private static final int LINE_LEADING = 2;

    /**
     * Two-space indent used on the ingot lines under a target, so "Copper 9 ingots" visibly belongs
     * to the target named above it rather than reading as part of the composition list.
     */
    private static final String INDENT = "  ";

    /**
     * Prefix on the currently selected row of the target list. Sized to match {@link #INDENT} closely
     * enough that the names still read as a column, so the marker is the thing that stands out.
     *
     * <p>The marker exists <em>as well as</em> the colour, not instead of it. Colour alone would put
     * the whole "which one am I on?" signal on a single palette role, and one of the four themes
     * ({@code MONOCHROME}) carries no hue at all. A literal {@code ">"} says it in characters.
     */
    private static final String ACTIVE_MARKER = "> ";

    /**
     * How many candidate alloys the target list shows at once, not counting the "Auto" row.
     *
     * <p>There has to be a ceiling: an empty crucible reaches every alloy in the pack, and TFC alone
     * ships enough of them to run the box off the bottom of the screen before {@link #drawBox}'s
     * vertical trim - which cuts from the end - could take anything less useful first. Eight is
     * chosen because it comfortably covers the alloys a player is realistically choosing between
     * while keeping the whole box inside a 1080p window at GUI scale 3, and because the list scrolls
     * to follow the selection, so the cap limits what is <em>visible</em> at once, never what is
     * reachable. Anything past the cap is reported as a "+N more" line and can still be reached with
     * the cycle key.
     */
    private static final int MAX_TARGET_ROWS = 8;

    /**
     * Hard ceiling on the number of ingots {@link #solveIngots} will consider adding.
     *
     * <p>This is what makes the search finite, and it is generous rather than tight. The worst
     * honest case is an empty crucible and a four-component alloy whose smallest component is a
     * couple of percent: filling that from nothing still lands well inside forty ingots at TFC's
     * 100 mB ingots, and a real TFC crucible only holds a few thousand mB in the first place. A
     * pack that shrinks {@code ingotVolume} to something tiny is the one configuration that can hit
     * this legitimately, and hitting it prints "no mix found within N ingots" rather than a partial
     * answer.
     */
    private static final int MAX_INGOTS_TO_ADD = 64;

    /**
     * Slack applied to the {@code ceil}/{@code floor} that turn the range bounds into whole ingot
     * counts.
     *
     * <p>{@code min * total} is a product of two doubles and lands a hair either side of the integer
     * it mathematically is - so a bound that is exactly 9 ingots can compute as 9.0000000001 and
     * {@code ceil} to 10, silently discarding the correct answer. The tolerance is applied so that
     * it always <em>widens</em> the candidate interval, never narrows it: a spurious extra candidate
     * is caught and rejected by {@link #verify}, whereas a wrongly discarded one is gone for good.
     */
    private static final double EDGE_TOLERANCE = 1.0e-7;

    /**
     * The player's chosen target alloy, identified by the fluid its recipe produces, or null for
     * "auto" - let {@link #findCandidates} rank them and take the winner.
     *
     * <p>Identified by result fluid rather than by list position or by the {@code AlloyRecipe}
     * object. Position is meaningless across frames because the candidate ranking depends on the
     * crucible's contents and so reorders as metal goes in; the recipe object is not guaranteed to
     * survive a datapack reload. Fluids are registry singletons, stable for the session, and the
     * candidate list is deduplicated by result fluid so the mapping back to a recipe is unique.
     *
     * <p>Session state, never written to config - same rule as the anvil overlay's visibility
     * toggle. It deliberately persists across crucibles: someone working towards bronze is usually
     * working towards bronze in the next crucible too. It is reset to auto by {@link #resolveTarget}
     * the moment the contents make the selection unreachable.
     *
     * <p>Client-side single-threaded state - render events and key events both arrive on the render
     * thread - so no synchronisation is needed.
     */
    @Nullable
    private static Fluid selectedTarget;

    /**
     * The result fluids of the candidates drawn on the last frame, in the order the overlay ranks
     * them - i.e. exactly what {@link #cycleTarget()} steps through.
     *
     * <p>Cached because the keybind fires outside the render pass and has no access to the crucible,
     * the recipe manager, or the level. Staleness is not a correctness problem: the render pass
     * re-checks {@link #selectedTarget} against a freshly computed list every frame and falls back
     * to auto if it is not there, so the worst a stale cache can do is select something that reverts
     * to auto one frame later.
     */
    private static List<Fluid> cycleOptions = List.of();

    /**
     * The clickable rectangle of every target row drawn on the last frame, in screen coordinates.
     *
     * <p>Written only by {@link #drawBox}, from the same values it draws those rows with, and read
     * only by {@link #clickAt}. {@link #render} empties it unconditionally on entry, so every path
     * that draws no box - option off, unreadable crucible, no lines, no vertical room - leaves it
     * empty rather than leaving last frame's rectangles behind for a click to land on after the box
     * has gone.
     *
     * <p>Replaced wholesale rather than cleared and refilled in place: the click handler and the
     * render pass both run on the render thread, but a list that is only ever swapped for a finished
     * one can never be observed half-built even if that ever stops being true.
     */
    private static List<ClickRow> clickRows = List.of();

    /**
     * The screen those rectangles were measured against, held weakly.
     *
     * <p>A rectangle is only meaningful for the screen instance that produced it, and a click is only
     * honoured when the two match. This closes the one gap the "clear on every render" rule does not:
     * a crucible screen closing and another opening in its place, with a click arriving before the
     * new screen's first render pass has replaced the rectangles. Without the check that click would
     * be answered - and, worse, <em>cancelled</em> - using a previous screen's layout.
     *
     * <p>Weak because this is a static field and a {@code Screen} holds its menu, which holds the
     * block entity. A strong reference here would keep the last crucible the player opened alive for
     * as long as the game runs.
     */
    @Nullable
    private static WeakReference<CrucibleScreen> rowsScreen;

    private CrucibleCalculator() {
    }

    /**
     * Advances the target selection: auto to the first candidate, then along the list, then back to
     * auto. Called from the keybind handler in {@code ClientEvents}.
     *
     * <p>Wrapping through auto rather than straight from the last candidate to the first is the
     * point of the mode: auto is a real, distinct state - "keep telling me whatever is closest" -
     * and a cycle that skipped it would leave no way back to it.
     */
    public static void cycleTarget() {
        final List<Fluid> options = cycleOptions;
        if (options.isEmpty()) {
            // Nothing is reachable (or nothing has been drawn yet). Auto is the only honest state.
            selectedTarget = null;
            return;
        }
        // An unknown current selection reads as -1 and so advances to 0, which is the right
        // behaviour for a selection that has just been invalidated: start again from the top.
        final int next = (selectedTarget == null ? -1 : options.indexOf(selectedTarget)) + 1;
        selectedTarget = next >= options.size() ? null : options.get(next);
    }

    /**
     * Applies a click at the given screen coordinates to the target list, if it landed on a row.
     *
     * <p><b>The return value is the whole contract.</b> The caller cancels the event when this
     * returns true and does nothing at all when it returns false, so a false is what keeps the
     * crucible's real slots working: a click anywhere but on one of this overlay's own rows must
     * reach the screen underneath untouched. That is why this reports a hit rather than, say,
     * cancelling on any click while the overlay is visible - the overlay sits beside the GUI, not
     * over it, but the player also clicks a great deal of screen that is neither.
     *
     * <p>Nothing here may throw, for the same reason nothing on the render path may: it runs from an
     * event handler on every mouse press with the crucible open. It is arithmetic over a list of
     * records with no dereference in it, which is the simplest way to guarantee that rather than
     * assert it.
     *
     * @param screen the screen the click arrived on; rectangles measured against a different screen
     *               instance are ignored - see {@link #rowsScreen}
     * @param mouseX click X in the same scaled screen coordinates the box is drawn in
     * @param mouseY click Y in the same scaled screen coordinates the box is drawn in
     * @return true if a row was hit and the target changed as a result, i.e. the click was consumed
     */
    public static boolean clickAt(CrucibleScreen screen, double mouseX, double mouseY) {
        final WeakReference<CrucibleScreen> owner = rowsScreen;
        if (owner == null || owner.get() != screen) {
            return false;
        }
        for (final ClickRow row : clickRows) {
            if (row.contains(mouseX, mouseY)) {
                // A null target is the "Auto" row, which is exactly what a null selectedTarget means.
                // Re-clicking the row already selected is a deliberate no-op rather than a toggle:
                // "click the thing you want" should not sometimes mean "click the thing you want to
                // stop wanting".
                selectedTarget = row.target();
                return true;
            }
        }
        return false;
    }

    /**
     * Draws the overlay for one frame. Called from {@code ClientEvents}'s
     * {@code ScreenEvent.Render.Post} handler.
     *
     * <p>An <em>empty</em> crucible is no longer an early return. It is the single most useful case
     * for this overlay - there is no mix to infer anything from, so every alloy is reachable and the
     * player is asking what to melt from scratch. The early returns that remain are the ones where
     * the crucible genuinely cannot be read.
     *
     * <p>Nothing on this path may throw. It runs once per frame for as long as the crucible screen
     * is open, so a single escaping exception is not one crash - it is a crash on every frame,
     * forever. That is why the nullable TFC accessors below are checked explicitly rather than
     * trusted, even where the current TFC version cannot actually return null.
     *
     * @param mouseX cursor X in scaled screen coordinates, used only to highlight the row under the
     *               cursor; any coordinate outside the box (the caller passes a negative when it has
     *               none) simply highlights nothing
     * @param mouseY cursor Y, as {@code mouseX}
     */
    public static void render(
        CrucibleScreen screen, GuiGraphics graphics, double mouseX, double mouseY
    ) {
        // Emptied unconditionally, before any early return can be taken, so "the box is not on screen"
        // and "there is nothing clickable" are the same statement rather than two that have to be kept
        // in step by hand. drawBox is the only thing that ever puts rectangles back.
        clickRows = List.of();
        rowsScreen = null;

        if (!AnvilSolverConfig.SHOW_ALLOY_CALCULATOR.get()) {
            // Nothing is on screen to cycle through, so the keybind must not act on a list left over
            // from before the option was switched off.
            cycleOptions = List.of();
            return;
        }

        // BlockEntityContainer.getBlockEntity() is public, which makes this the supported way in -
        // the same path AnvilSolverClient uses for the anvil's forging data. Going through the menu's
        // slot list instead would depend on TFC's slot ordering, which is not a promise.
        final CrucibleBlockEntity crucible = screen.getMenu().getBlockEntity();
        if (crucible == null) {
            cycleOptions = List.of();
            return;
        }

        final FluidAlloy alloy = crucible.getAlloy();
        final Map<Fluid, Double> amounts = snapshot(alloy);
        // Total is taken from the alloy only when the snapshot actually found metal in it, so that
        // "empty" is a single unambiguous state (no metals AND zero total) rather than two that have
        // to be tested for separately everywhere downstream. The null test is redundant - a null
        // alloy always snapshots empty - but it is written out so this line cannot be read as a
        // dereference that depends on knowing what snapshot() does.
        final int total = (alloy == null || amounts.isEmpty()) ? 0 : alloy.getAmount();

        // Read once, here, and passed down - never a static, and never re-read per line. Same rule as
        // the anvil overlay: a theme change mid-frame must not be able to draw half a box in one
        // palette and half in another.
        final OverlayTheme theme = AnvilSolverConfig.THEME.get();

        final List<Line> lines = buildLines(crucible, amounts, total, theme);
        if (lines.isEmpty()) {
            return;
        }
        drawBox(screen, graphics, lines, theme, mouseX, mouseY);
    }

    // ---------------------------------------------------------------------------------------------
    // Reading the crucible
    // ---------------------------------------------------------------------------------------------

    /**
     * Copies the alloy's per-metal amounts into a plain map, dropping anything unusable.
     *
     * <p>Copying rather than passing TFC's {@code Object2DoubleMap} around buys two things. It
     * normalises every "cannot read this" case - null alloy, null content map, a non-positive total
     * reported against non-empty content - into the same empty map, which is exactly the empty
     * crucible state that the rest of the class already has to handle properly. And it means the
     * numbers used to build one frame's lines are a snapshot: block entity state cannot shift
     * underneath the calculation between the ranking pass and the ingot solve.
     *
     * <p>The map is small by nature - TFC alloys top out at a handful of components - so the
     * per-frame allocation is noise next to the recipe scan that follows it.
     *
     * @return raw amounts in mB per metal, never null, empty when the crucible holds nothing usable
     */
    private static Map<Fluid, Double> snapshot(@Nullable FluidAlloy alloy) {
        final Map<Fluid, Double> amounts = new HashMap<>();
        if (alloy == null) {
            return amounts;
        }
        final Object2DoubleMap<Fluid> content = alloy.getContent();
        // getContent() holds RAW AMOUNTS, not percentages, so every fraction computed later is
        // amount/total. A non-positive total with non-empty content should be impossible, but it is
        // the divisor for every number this class prints, so it is checked rather than assumed - and
        // failing it here means the crucible is simply treated as empty.
        if (content == null || alloy.getAmount() <= 0) {
            return amounts;
        }
        for (final Fluid fluid : content.keySet()) {
            if (fluid == null) {
                continue;
            }
            final double amount = content.getDouble(fluid);
            // A zero or negative entry is not a metal that is present; carrying it would put a metal
            // in the "already in the pot" set that the player cannot see and cannot remove, which
            // would wrongly rule out every alloy that does not contain it.
            if (amount > 0.0 && Double.isFinite(amount)) {
                amounts.put(fluid, amount);
            }
        }
        return amounts;
    }

    /** An amount looked up safely, treating "not in the pot" as the zero it is. */
    private static double amountOf(Map<Fluid, Double> amounts, @Nullable Fluid fluid) {
        if (fluid == null) {
            return 0.0;
        }
        return amounts.getOrDefault(fluid, 0.0);
    }

    /** A metal's share of the crucible, defined as zero for an empty crucible rather than NaN. */
    private static double fractionOf(double amount, int total) {
        return total <= 0 ? 0.0 : amount / total;
    }

    // ---------------------------------------------------------------------------------------------
    // Content
    // ---------------------------------------------------------------------------------------------

    /**
     * Builds the overlay's text, each line already carrying the colour it should be drawn in.
     *
     * <p>Pairing text with colour at construction, rather than colouring by line index afterwards,
     * is copied from the anvil overlay for the reason that change was made there: index-based
     * colouring silently mis-colours the wrong line the moment the layout gains or loses a row, and
     * this layout is variable-length by nature.
     *
     * @param total the alloy's total amount in mB; zero exactly when {@code amounts} is empty
     */
    private static List<Line> buildLines(
        CrucibleBlockEntity crucible, Map<Fluid, Double> amounts, int total, OverlayTheme theme
    ) {
        // Cleared up front and refilled by appendCalculator. Any path below that returns without
        // reaching the calculator is a path with no target line on screen, and the keybind must not
        // silently cycle a selection the player cannot see.
        cycleOptions = List.of();

        final List<Line> lines = new ArrayList<>();
        final boolean empty = amounts.isEmpty() || total <= 0;
        lines.add(new Line("Crucible  " + (empty ? "empty" : total + " mB"), theme.muted()));

        if (empty) {
            // No composition to list and no result to name. Straight to "what do you want to make?",
            // which for an empty crucible is the entire question.
            appendCalculator(lines, amounts, total, theme);
            return lines;
        }

        // Sorted largest-first, then by name. The snapshot is a hash map, so its iteration order is
        // an implementation detail; sorting is what stops the metal list reshuffling itself between
        // frames or between game sessions.
        final List<Fluid> present = new ArrayList<>(amounts.keySet());
        // Written as one explicit comparator rather than
        // Comparator.comparingDouble(...).reversed().thenComparing(...): thenComparing is overloaded
        // on both Comparator and Function, and resolving that against a method reference is a
        // well-known source of "reference to thenComparing is ambiguous". Two lines of Double.compare
        // are not worth the risk on a build that has to stay green.
        present.sort((left, right) -> {
            // Descending: right against left, not left against right.
            final int byAmount = Double.compare(amountOf(amounts, right), amountOf(amounts, left));
            return byAmount != 0 ? byAmount : fluidName(left).compareTo(fluidName(right));
        });

        for (final Fluid fluid : present) {
            lines.add(new Line(
                fluidName(fluid) + "  " + percent(fractionOf(amountOf(amounts, fluid), total)),
                theme.text()));
        }

        final FluidStack result = crucible.getAlloyResult();
        final boolean hasResult = result != null && !result.isEmpty();

        if (hasResult) {
            lines.add(new Line("-> " + result.getHoverName().getString(), theme.next()));

            // TFC reports a single pure metal as its own result, so a crucible of nothing but copper
            // lands here with a perfectly true "-> Copper" and, if we stopped, would answer none of
            // the question the player actually has: how do I get from this to bronze? That case is
            // almost certainly the most common reason to look at a crucible at all.
            //
            // So the result line is kept - it is correct - but a lone metal falls through to the
            // calculator as well. A genuine multi-metal alloy is a finished product and needs no
            // routes; a single metal is a starting point and does.
            if (present.size() > 1) {
                return lines;
            }
        } else {
            lines.add(new Line("Not a valid alloy", theme.error()));
        }

        appendCalculator(lines, amounts, total, theme);
        return lines;
    }

    /**
     * Appends the target line and the ingots to melt in to reach it.
     *
     * <p>Unlike the mB version this replaces, the figures below are a <em>single joint plan</em>,
     * not a set of independent suggestions. The old output computed each metal's addition as if it
     * were the only thing added, which meant adding two of them at once landed neither where it was
     * promised, and the overlay had to tell the player to add one and re-check. The ingot solve
     * accounts for every addition raising the shared total, so the whole list can be melted in one
     * go and the result is in range.
     */
    private static void appendCalculator(
        List<Line> lines, Map<Fluid, Double> amounts, int total, OverlayTheme theme
    ) {
        final List<AlloyRecipe> candidates = findCandidates(amounts, total);
        cycleOptions = resultFluids(candidates);

        if (candidates.isEmpty()) {
            // Either no recipe contains every metal already in the pot - the mix is a dead end that
            // only emptying the crucible can fix - or the recipe list could not be read at all.
            selectedTarget = null;
            lines.add(new Line("No alloy reachable", theme.muted()));
            lines.add(new Line("by adding metal", theme.muted()));
            return;
        }

        final int index = resolveTarget(candidates);
        final AlloyRecipe target = candidates.get(Math.max(index, 0));

        // "(auto)" versus "(2/5)" is the whole point of showing this: the player has to be able to
        // tell "the mod picked this" from "I picked this", and the fraction also says how many other
        // choices exist. It is kept even though the list below repeats it, because the list is the
        // first thing drawBox's vertical trim takes off a short window and this summary is not.
        final String mode = index < 0
            ? "(auto)"
            : "(" + (index + 1) + "/" + candidates.size() + ")";
        lines.add(new Line("Target: " + fluidName(target.result()) + " " + mode, theme.muted()));

        // Plan first, list second, and the order is deliberate. drawBox trims from the end, so on a
        // window too short for everything the thing that survives is the answer - which ingots to go
        // and melt - rather than the chooser. It also keeps the top of the box byte-for-byte the
        // layout that shipped, with the picker appended below it.
        appendPlan(lines, target, amounts, total, theme);
        appendTargetList(lines, candidates, index, theme);
    }

    /**
     * Appends the clickable target rows: "Auto", then a window over the candidate alloys.
     *
     * <p>Only the rows themselves are marked selectable. The heading and the "+N more" line are
     * ordinary text, so a click on either does nothing and is not swallowed - a click that visibly
     * lands on a label and silently eats itself is worse than one that falls through.
     *
     * <p><b>Why a sliding window and not just the first eight.</b> The cycle key steps through every
     * candidate, including the ones past the cap. If the window were pinned to the top of the list, a
     * player who cycled to candidate twelve would be looking at a list with nothing marked on it and
     * no clue where they were. The window instead follows the selection, so the selected row is
     * always the one on screen and always the one marked.
     *
     * @param index the selected candidate's index, or -1 for auto - as {@link #resolveTarget} returns
     */
    private static void appendTargetList(
        List<Line> lines, List<AlloyRecipe> candidates, int index, OverlayTheme theme
    ) {
        // Says what the rows are AND that they are clickable. Discoverability is the whole reason the
        // rows exist; a list nobody realises is interactive is just a longer box.
        lines.add(new Line("Click a target:", theme.muted()));

        // Auto is a row like any other so that getting back to it is one click, the same gesture as
        // leaving it - previously it was only reachable by cycling off the end of the list. It names
        // what auto currently resolves to, which is what makes it a choice rather than a mystery.
        // candidates is non-empty here (the caller returned early otherwise), so element 0 exists.
        final boolean autoActive = index < 0;
        lines.add(Line.row(
            (autoActive ? ACTIVE_MARKER : INDENT) + "Auto (" + fluidName(candidates.get(0).result()) + ")",
            autoActive ? theme.next() : theme.text(),
            null));

        final int size = candidates.size();
        // Scrolled only as far as it takes to bring the selection into view, and never past the end.
        // index >= MAX_TARGET_ROWS implies start >= 1, and index < size implies end > start, so the
        // loop below always draws at least the selected row.
        final int start = index >= MAX_TARGET_ROWS ? index - MAX_TARGET_ROWS + 1 : 0;
        final int end = Math.min(size, start + MAX_TARGET_ROWS);

        for (int i = start; i < end; i++) {
            final boolean active = i == index;
            // next() against text() is the same "this is the one that matters" pairing the anvil
            // overlay uses for its next press, so the two overlays mean the same thing by colour.
            lines.add(Line.row(
                (active ? ACTIVE_MARKER : INDENT) + fluidName(candidates.get(i).result()),
                active ? theme.next() : theme.text(),
                candidates.get(i).result()));
        }

        final int hidden = size - (end - start);
        if (hidden > 0) {
            // Muted, unlike the anvil overlay's "+N more", precisely because everything around it
            // here is clickable and this is not. Dimmer reads as "not a row".
            lines.add(new Line(INDENT + "+" + hidden + " more", theme.muted()));
        }
    }

    /**
     * Resolves the current selection against a freshly computed candidate list, resetting it to auto
     * if it no longer belongs there.
     *
     * <p>This is the whole of the "never leave a stale target selected" rule, and it lives here -
     * on the render path, against the live candidate list - rather than in some contents-changed
     * listener, because there is no such listener to hook: the crucible's alloy is polled every
     * frame. A selection stops being viable the instant a metal that the target does not contain
     * goes into the pot, at which point the target drops out of {@link #findCandidates} and this
     * finds nothing to match.
     *
     * @return the selected candidate's index, or -1 for auto (which means candidate 0)
     */
    private static int resolveTarget(List<AlloyRecipe> candidates) {
        if (selectedTarget == null) {
            return -1;
        }
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i).result() == selectedTarget) {
                return i;
            }
        }
        selectedTarget = null;
        return -1;
    }

    /**
     * Appends the per-metal ingot counts for one target, or the reason there are none.
     */
    private static void appendPlan(
        List<Line> lines, AlloyRecipe target, Map<Fluid, Double> amounts, int total, OverlayTheme theme
    ) {
        final List<AlloyRange> ranges = target.contents();
        // findCandidates rejected null and empty component lists on this very recipe object, in this
        // very frame, so this cannot fire. It is here because the alternative to a redundant check is
        // a NullPointerException thrown out of a render event, i.e. once per frame forever.
        if (ranges == null || ranges.isEmpty()) {
            lines.add(new Line("Recipe unreadable", theme.error()));
            return;
        }

        // Defensive, and deliberately kept even though findCandidates already enforces it: metal can
        // be added to a crucible but never taken out, so a pot containing anything the target does
        // not list can never become the target however much is poured in. If this ever fires, the
        // candidate filter and this check have disagreed, and printing "impossible" is the safe side
        // of that disagreement.
        for (final Fluid fluid : amounts.keySet()) {
            if (!containsFluid(ranges, fluid)) {
                lines.add(new Line("Cannot reach: pot has", theme.error()));
                lines.add(new Line("metal not in this alloy", theme.error()));
                return;
            }
        }

        final int ingotVolume = AnvilSolverConfig.INGOT_VOLUME.get();
        final int[] counts = solveIngots(ranges, amounts, total, ingotVolume);
        if (counts == null) {
            lines.add(new Line("No ingot mix found", theme.error()));
            lines.add(new Line(INDENT + "within " + MAX_INGOTS_TO_ADD + " ingots", theme.muted()));
            return;
        }

        int listed = 0;
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] <= 0) {
                // Metals already in range and needing nothing are omitted rather than printed as
                // "0 ingots": the box has a tight height budget and a zero is not an instruction.
                continue;
            }
            listed++;
            lines.add(new Line(
                INDENT + fluidName(ranges.get(i).fluid()) + "  " + counts[i]
                    + (counts[i] == 1 ? " ingot" : " ingots"),
                theme.next()));
        }
        if (listed == 0) {
            // A feasible plan of zero ingots means every component is already inside its range.
            lines.add(new Line("Already there", theme.next()));
        }
    }

    /** Whether a recipe's component list mentions the given fluid. */
    private static boolean containsFluid(List<AlloyRange> ranges, @Nullable Fluid fluid) {
        for (final AlloyRange range : ranges) {
            if (range.fluid() == fluid) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------
    // The ingot solve
    // ---------------------------------------------------------------------------------------------

    /**
     * Finds the fewest whole ingots to melt in so that every component of the target lands inside
     * its range at the same time.
     *
     * <p><b>The arithmetic.</b> Write {@code C_i} for what is in the pot now, {@code T0} for the
     * current total, {@code V} for one ingot's volume, and {@code n_i} for the ingots of metal
     * {@code i} to add. Adding {@code K} ingots in total fixes the <em>final</em> total at
     * {@code T = T0 + K*V} before anything else is decided - which is what makes this tractable,
     * because the ranges are fractions of that final total and the total is now a known number
     * rather than something that moves as each metal goes in. Component {@code i} must finish inside
     * {@code [min_i*T, max_i*T]}, and it finishes at {@code C_i + V*n_i}, so
     *
     * <pre>
     *   lo_i = max(0, ceil((min_i*T - C_i) / V))
     *   hi_i =        floor((max_i*T - C_i) / V)
     * </pre>
     *
     * <p>bracket the only ingot counts that work for that {@code K}. If any bracket is empty the
     * {@code K} is impossible. If the brackets' minima already exceed {@code K}, or their maxima
     * cannot reach it, the {@code K} is impossible too. Otherwise every metal starts at {@code lo_i}
     * and the leftover ingots are spread into the remaining headroom, which cannot break anything
     * because no metal is ever pushed past its own {@code hi_i}.
     *
     * <p>Searching {@code K} upwards from zero and returning the first success is what makes the
     * answer minimal: any smaller {@code K} was tested and rejected.
     *
     * <p><b>Why the answer is verified before it is returned.</b> {@code ceil} and {@code floor} sit
     * directly on top of floating-point products, and the numbers that decide whether an alloy forms
     * are computed by TFC with its own epsilon. Rather than trust that this class's rounding and
     * TFC's tolerance agree, the finished plan is fed back through {@code AlloyRange.isIn} - the
     * same call the game itself makes. A confidently wrong ingot count is worse than no answer, so a
     * plan that fails that check is discarded and the search moves on.
     *
     * @param total the current total in mB, zero for an empty crucible
     * @return ingots to add, indexed to match {@code ranges}, or null if there is no answer within
     *         {@link #MAX_INGOTS_TO_ADD}
     */
    @Nullable
    private static int[] solveIngots(
        List<AlloyRange> ranges, Map<Fluid, Double> amounts, int total, int ingotVolume
    ) {
        final int size = ranges.size();
        if (size == 0 || ingotVolume <= 0) {
            return null;
        }

        final double[] have = new double[size];
        final double[] min = new double[size];
        final double[] max = new double[size];
        for (int i = 0; i < size; i++) {
            final AlloyRange range = ranges.get(i);
            have[i] = amountOf(amounts, range.fluid());
            min[i] = range.min();
            max[i] = range.max();
            // Ranges come from datapacks. Anything that is not a sane 0-1 interval would propagate
            // NaN through every bound below and produce a plan that looks authoritative and is not.
            if (!Double.isFinite(min[i]) || !Double.isFinite(max[i]) || min[i] > max[i]) {
                return null;
            }
        }

        final long[] lo = new long[size];
        final long[] hi = new long[size];
        final int[] counts = new int[size];

        for (int k = 0; k <= MAX_INGOTS_TO_ADD; k++) {
            // long, not int: k*ingotVolume can reach 640000 at the configured maximum, and keeping
            // the whole expression in long costs nothing and removes the question entirely.
            final long finalTotal = (long) total + (long) k * ingotVolume;
            if (finalTotal <= 0L) {
                // k == 0 on an empty crucible. Nothing is a valid alloy, and every fraction would be
                // 0/0, so this is skipped rather than allowed to produce NaN.
                continue;
            }

            boolean bracketsOk = true;
            long sumLo = 0L;
            long sumHi = 0L;
            for (int i = 0; i < size; i++) {
                lo[i] = (long) Math.ceil((min[i] * finalTotal - have[i]) / ingotVolume - EDGE_TOLERANCE);
                hi[i] = (long) Math.floor((max[i] * finalTotal - have[i]) / ingotVolume + EDGE_TOLERANCE);
                if (lo[i] < 0L) {
                    // Negative would mean "remove metal to get down to the minimum", which is not a
                    // thing a crucible can do. Zero is the real floor.
                    lo[i] = 0L;
                }
                if (hi[i] > k) {
                    // No single metal can take more than the whole budget. Clamping here also keeps
                    // hi small enough that the sums below cannot run away.
                    hi[i] = k;
                }
                if (lo[i] > hi[i]) {
                    // This metal cannot be brought inside its range at this final total. Usually it
                    // means the metal is already over its maximum share and k is too small to have
                    // diluted it back down yet.
                    bracketsOk = false;
                    break;
                }
                sumLo += lo[i];
                sumHi += hi[i];
            }
            // sumLo > k: the minimum requirements alone need more ingots than this k allows.
            // sumHi < k: even filling every metal to its maximum cannot absorb this many ingots.
            if (!bracketsOk || sumLo > k || sumHi < k) {
                continue;
            }

            for (int i = 0; i < size; i++) {
                counts[i] = (int) lo[i];
            }
            long surplus = k - sumLo;
            while (surplus > 0L) {
                // Each spare ingot goes to whichever metal with headroom left is currently furthest
                // BELOW the centre of its own range. Any distribution inside the brackets is valid,
                // so this is a free choice - and spending it on centring the mix is worth doing,
                // because a component parked on the exact edge of its range is one rounding
                // difference away from the alloy not forming. Ties go to the earlier component, so
                // the plan is identical from frame to frame.
                int pick = -1;
                double worst = 0.0;
                for (int i = 0; i < size; i++) {
                    if (counts[i] >= hi[i]) {
                        continue;
                    }
                    final double share = (have[i] + (double) ingotVolume * counts[i]) / finalTotal;
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

            if (verify(ranges, have, counts, finalTotal, ingotVolume)) {
                // Cloned because counts is reused by the next iteration of the k loop; the caller
                // must not be handed an array this method still writes to.
                return counts.clone();
            }
        }
        return null;
    }

    /**
     * Re-checks a finished plan against TFC's own range test.
     *
     * <p>{@code AlloyRange.isIn} is the exact call that decides whether the alloy forms, epsilon and
     * all. Comparing against {@code min}/{@code max} by hand here instead would be a second opinion
     * that is free to disagree with the one that counts.
     */
    private static boolean verify(
        List<AlloyRange> ranges, double[] have, int[] counts, long finalTotal, int ingotVolume
    ) {
        for (int i = 0; i < ranges.size(); i++) {
            final double amount = have[i] + (double) ingotVolume * counts[i];
            if (!ranges.get(i).isIn(amount / finalTotal)) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------------
    // Candidates
    // ---------------------------------------------------------------------------------------------

    /**
     * Every alloy still reachable from the crucible's current contents, best first.
     *
     * <p>Only recipes whose metal set is a <em>superset</em> of what is already in the pot are
     * eligible. Metal can be added but not taken out, so any recipe missing a metal the crucible
     * already contains is unreachable no matter what is poured in next, and offering it would be
     * advice that cannot be followed. An empty crucible has an empty metal set, which every recipe
     * is trivially a superset of - so every alloy is a candidate, which is exactly right.
     *
     * <p>The ordering is the one the old single-answer version used, unchanged, so that "auto" -
     * element 0 - still picks precisely what this overlay has always picked: fewest metals still out
     * of range ("closest to done"), then fewest metals overall (a two-metal alloy is less work than
     * a four-metal one at the same distance), then result name. The name tie-break exists purely so
     * the choice is stable: two equally ranked recipes must not swap places between frames and make
     * the box flicker, or make the cycle key land somewhere different each press.
     *
     * <p>Deduplicated by result fluid, because {@link #selectedTarget} identifies a target by that
     * fluid. Two datapack recipes producing the same alloy would otherwise appear as two cycle
     * entries with identical labels, only one of which could ever be selected.
     *
     * @return the candidates, best first; empty if there are none or the recipe list cannot be read
     */
    private static List<AlloyRecipe> findCandidates(Map<Fluid, Double> amounts, int total) {
        final List<AlloyRecipe> candidates = new ArrayList<>();

        // This runs from a render event, which can fire in situations where there is no world -
        // during a disconnect, or while a screen lingers over the main menu. Minecraft.level is
        // @Nullable for exactly that reason and dereferencing it blind would throw every frame.
        final ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return candidates;
        }
        final RecipeManager recipes = level.getRecipeManager();
        if (recipes == null) {
            return candidates;
        }

        final List<RecipeHolder<AlloyRecipe>> holders;
        try {
            // TFCRecipeTypes.ALLOY is a deferred holder: get() throws if it is somehow unbound, which
            // is what a TFC version whose registration this mod no longer matches would look like.
            // The catch is narrow - only the two exceptions an unbound holder can raise - and its
            // effect is to fall back to "no candidates", which degrades the overlay to the
            // composition list rather than throwing out of the render event on every frame.
            holders = recipes.getAllRecipesFor(TFCRecipeTypes.ALLOY.get());
        } catch (final NullPointerException | IllegalStateException e) {
            return candidates;
        }
        if (holders == null) {
            return candidates;
        }

        final List<Ranked> ranked = new ArrayList<>();
        for (final RecipeHolder<AlloyRecipe> holder : holders) {
            if (holder == null) {
                continue;
            }
            final AlloyRecipe recipe = holder.value();
            if (recipe == null || recipe.result() == null) {
                continue;
            }
            final List<AlloyRange> ranges = recipe.contents();
            if (ranges == null || ranges.isEmpty()) {
                continue;
            }

            // Recipes are datapack-driven, so a malformed one is data this mod does not control.
            // Skipping it costs one candidate; dereferencing it would cost the whole overlay.
            final Set<Fluid> recipeFluids = new HashSet<>();
            boolean usable = true;
            for (final AlloyRange range : ranges) {
                if (range == null || range.fluid() == null) {
                    usable = false;
                    break;
                }
                recipeFluids.add(range.fluid());
            }
            if (!usable || !recipeFluids.containsAll(amounts.keySet())) {
                continue;
            }

            int outOfRange = 0;
            for (final AlloyRange range : ranges) {
                if (!range.isIn(fractionOf(amountOf(amounts, range.fluid()), total))) {
                    outOfRange++;
                }
            }
            ranked.add(new Ranked(recipe, outOfRange, ranges.size(), fluidName(recipe.result())));
        }

        ranked.sort((left, right) -> {
            if (left.outOfRange() != right.outOfRange()) {
                return Integer.compare(left.outOfRange(), right.outOfRange());
            }
            if (left.size() != right.size()) {
                return Integer.compare(left.size(), right.size());
            }
            return left.name().compareTo(right.name());
        });

        final Set<Fluid> seen = new HashSet<>();
        for (final Ranked entry : ranked) {
            if (seen.add(entry.recipe().result())) {
                candidates.add(entry.recipe());
            }
        }
        return candidates;
    }

    /** The result fluids of a candidate list, in order - the list {@link #cycleTarget()} walks. */
    private static List<Fluid> resultFluids(List<AlloyRecipe> candidates) {
        final List<Fluid> fluids = new ArrayList<>(candidates.size());
        for (final AlloyRecipe recipe : candidates) {
            fluids.add(recipe.result());
        }
        return fluids;
    }

    /**
     * A readable, translated name for a fluid.
     *
     * <p>Metal names are deliberately not hardcoded. TFC ships dozens of them, addons add more, and
     * a hardcoded table would show the wrong name - or none - the moment either changed.
     * {@code FluidStack.getHoverName()} is the same name the player sees in TFC's own tooltips, in
     * their own language.
     *
     * <p>{@code getString()} flattens the component for measuring and drawing. The result may well
     * contain non-ASCII characters at runtime, in a localised name - that is fine and unrelated to
     * this file's own ASCII-only rule, which is about how javac reads the <em>source</em>.
     */
    private static String fluidName(@Nullable Fluid fluid) {
        if (fluid == null) {
            return "?";
        }
        return new FluidStack(fluid, 1).getHoverName().getString();
    }

    /** Formats a 0-1 fraction as a percentage to one decimal, e.g. {@code 0.912} to {@code "91.2%"}. */
    private static String percent(double fraction) {
        // Locale.ROOT, not the default locale: a French client would otherwise render "91,2%", and
        // more to the point the decimal separator would change depending on who is playing.
        return String.format(Locale.ROOT, "%.1f%%", fraction * 100.0);
    }

    // ---------------------------------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------------------------------

    /**
     * Draws the box: background, border, then the lines.
     *
     * <p>Every line in this overlay is plain text of a single uniform height, which is what makes
     * this safe to keep simple. The width comes from {@link #textWidth} and the height from
     * {@link #rowHeight}, and the drawing loop below uses those exact same two functions - so the
     * border cannot be sized for a layout different from the one drawn inside it. That mismatch is
     * the specific failure that has bitten the anvil overlay twice, and it is designed out here
     * rather than merely avoided.
     *
     * <p>The same property is what makes the clickable rows trustworthy. A row's rectangle is built
     * in the drawing loop, from the loop's own {@code left}, {@code y}, {@code width} and {@code row}
     * - so it cannot describe a row at a different place or size from the one drawn a line later, and
     * a row cut by the vertical trim below is never reached and so never becomes clickable.
     */
    private static void drawBox(
        CrucibleScreen screen, GuiGraphics graphics, List<Line> lines, OverlayTheme theme,
        double mouseX, double mouseY
    ) {
        final Font font = Minecraft.getInstance().font;
        final int row = rowHeight(font);
        final int top = screen.getGuiTop() + AnvilSolverConfig.OVERLAY_Y.get();

        // Vertical fit, applied BEFORE anything is measured, so the box is sized from the lines that
        // actually survive. Space for content only - the box's own padding is already deducted.
        //
        // May be zero or negative on a short window or a large configured overlayY. Integer division
        // truncates toward zero, so a negative budget yields zero or a negative row count, and the
        // guard below turns both into "draw nothing" rather than a box with a negative height.
        final int budget = (screen.height - SCREEN_MARGIN) - top - PADDING * 2;
        final int maxRows = budget / row;
        if (maxRows <= 0) {
            return;
        }
        // Trimmed from the end. The header and the composition list are the lines a player can
        // always act on; the suggestions below them are the ones worth losing first if the window is
        // too short for everything.
        final List<Line> visible = lines.size() <= maxRows ? lines : lines.subList(0, maxRows);

        int width = PADDING * 2;
        for (final Line line : visible) {
            width = Math.max(width, PADDING * 2 + textWidth(font, line));
        }
        final int height = PADDING * 2 + row * visible.size();
        final int left = computeBoxX(screen, width);

        graphics.fill(left, top, left + width, top + height, theme.background());
        graphics.renderOutline(left, top, width, height, theme.border());

        final PoseStack pose = graphics.pose();
        pose.pushPose();
        // Lift clear of the GUI's own layers, matching what the anvil overlay does. This event fires
        // after the screen has drawn, but the container screen leaves the pose stack at a depth where
        // widgets can still win.
        pose.translate(0, 0, 400);

        final List<ClickRow> rows = new ArrayList<>();
        int y = top + PADDING;
        for (final Line line : visible) {
            if (line.selectable()) {
                // Built once and used for both the highlight and the hit test, so "where it lights
                // up" and "where it responds" are not two descriptions of a rectangle that could
                // disagree - they are one rectangle. Inset by a pixel on each side so the highlight
                // never paints over the box's own border; the row is still clickable across its full
                // width, which is far more forgiving than the text's own extent would be.
                final ClickRow clickRow =
                    new ClickRow(left + 1, y, left + width - 1, y + row, line.target());
                rows.add(clickRow);
                if (clickRow.contains(mouseX, mouseY)) {
                    // nextFill() is the palette's low-alpha "you can interact with this" wash - the
                    // same role the anvil overlay tints its next-press button with. Low alpha is also
                    // what makes this safe to draw next to the text at the same depth: whichever of
                    // the two the batched GUI renderer happens to flush last, the words stay legible.
                    graphics.fill(
                        clickRow.left(), clickRow.top(), clickRow.right(), clickRow.bottom(),
                        theme.nextFill());
                }
            }
            graphics.drawString(font, line.text(), left + PADDING, y, line.color(), false);
            y += row;
        }
        pose.popPose();

        // Published only now the frame is fully drawn, and only ever as a finished list.
        clickRows = rows;
        rowsScreen = new WeakReference<>(screen);
    }

    /** Height of one rendered row. Every line in this overlay is the same height - see {@link #drawBox}. */
    private static int rowHeight(Font font) {
        return font.lineHeight + LINE_LEADING;
    }

    /** Drawn width of a line's content, excluding the box padding. Paired with {@link #rowHeight}. */
    private static int textWidth(Font font, Line line) {
        return font.width(line.text());
    }

    /**
     * Returns the box's left edge in absolute screen coordinates, preferring the space to the right
     * of the crucible GUI.
     *
     * <p>Reuses {@code overlayGap} rather than adding a crucible-specific option: two overlays that
     * are never on screen at the same time do not need two knobs, and a player who has already moved
     * the anvil box will expect this one to have moved with it.
     *
     * <p>The mirror-to-the-left fallback matters more here than on the anvil, because this box's
     * width is set by translated metal names - a long localised name can make it noticeably wider
     * than anything the anvil overlay produces.
     */
    private static int computeBoxX(CrucibleScreen screen, int width) {
        final int gap = AnvilSolverConfig.OVERLAY_GAP.get();
        final int guiLeft = screen.getGuiLeft();

        // Free space outside each edge of the GUI panel, with the gap already taken out.
        final int rightEdge = guiLeft + screen.getXSize() + gap;
        final int rightSpace = screen.width - rightEdge;
        final int leftSpace = guiLeft - gap;

        final boolean fitsRight = width <= rightSpace;
        final boolean fitsLeft = width <= leftSpace;
        // Right is the preferred placement, so take it whenever the box fits. Otherwise take the left
        // if it fits there, and if it fits on neither, take the roomier side so the overrun is as
        // small as it can be.
        final boolean useRight = fitsRight || (!fitsLeft && rightSpace >= leftSpace);
        final int x = useRight ? rightEdge : guiLeft - width - gap;

        // A box wider than the whole window fits on neither side, and a large negative gap can push
        // it off the left edge. Pinning the left edge at 0 keeps the start of every line readable,
        // which matters more than an overrun on the right.
        return Math.max(0, x);
    }

    /**
     * One rendered line together with the colour it is drawn in.
     *
     * <p>There is no icon or bar variant, unlike the anvil overlay's equivalent - every line here is
     * plain text. That is what keeps the measuring and drawing passes trivially in agreement: a
     * selectable line is drawn exactly like any other and differs only in that {@code drawBox} also
     * remembers where it put it.
     *
     * <p>{@code selectable} is a flag in its own right rather than being inferred from
     * {@code target != null}, because the "Auto" row is selectable <em>and</em> has a null target -
     * null being precisely what {@code selectedTarget} holds for auto - and every ordinary line has
     * a null target too. The two cases are genuinely different and are stored as such.
     *
     * @param text       the text to draw
     * @param color      ARGB colour, already resolved from the active theme
     * @param selectable whether a click on this row selects a target
     * @param target     for a selectable row, the alloy it selects, or null for the "Auto" row;
     *                   always null on a row that is not selectable
     */
    private record Line(String text, int color, boolean selectable, @Nullable Fluid target) {

        /** An ordinary, non-clickable line. Keeps every existing call site reading as it always did. */
        Line(String text, int color) {
            this(text, color, false, null);
        }

        /**
         * A clickable target row.
         *
         * <p>A static factory rather than another constructor: a three-argument constructor taking
         * {@code (String, int, Fluid)} would be one {@code null} literal away from being ambiguous
         * with nothing at the call site to say which row kind was meant. It is named {@code row}
         * rather than {@code selectable} so it cannot be confused with the generated accessor of the
         * component by the same name.
         */
        static Line row(String text, int color, @Nullable Fluid target) {
            return new Line(text, color, true, target);
        }
    }

    /**
     * The screen rectangle of one drawn target row, and the target clicking it selects.
     *
     * <p>Half-open on the right and bottom edges - {@code >= left} but {@code < right} - so rows
     * stacked directly on top of one another share no pixel, and a click can therefore never match
     * two of them. The loop in {@code clickAt} takes the first match regardless, but "there is only
     * ever one" is a better guarantee than "we pick one of them".
     *
     * @param left   inclusive left edge, in scaled screen coordinates
     * @param top    inclusive top edge
     * @param right  exclusive right edge
     * @param bottom exclusive bottom edge
     * @param target the alloy this row selects, or null for the "Auto" row
     */
    private record ClickRow(int left, int top, int right, int bottom, @Nullable Fluid target) {

        /** Whether a click at these coordinates lands on this row. Pure arithmetic; cannot throw. */
        boolean contains(double x, double y) {
            return x >= left && x < right && y >= top && y < bottom;
        }
    }

    /**
     * One candidate recipe with its sort keys precomputed.
     *
     * <p>Precomputed rather than recomputed inside the comparator: the display name is a translation
     * lookup through a temporary {@code FluidStack}, and a comparison-time lookup would run it
     * O(n log n) times per frame instead of once per recipe.
     *
     * @param recipe     the candidate itself
     * @param outOfRange how many of its components the crucible currently has outside their range
     * @param size       how many components it has
     * @param name       its result's display name, the final tie-break
     */
    private record Ranked(AlloyRecipe recipe, int outOfRange, int size, String name) {
    }
}
