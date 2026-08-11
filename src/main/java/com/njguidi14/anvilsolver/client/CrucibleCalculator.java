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
import net.dries007.tfc.common.recipes.HeatingRecipe;
import net.dries007.tfc.common.recipes.TFCRecipeTypes;
import net.dries007.tfc.util.AlloyRange;
import net.dries007.tfc.util.FluidAlloy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
 * default, but the reachable alloys are listed and the player chooses among them.
 *
 * <p><b>What "auto" means.</b> It means the target the mix is already closest to - the fewest of
 * whose components are currently outside their range - with the ingot count used only to separate
 * targets that are equally close. See {@link #findCandidates}. And from an <em>empty</em> crucible
 * it deliberately means nothing at all: auto resolves to no target, no plan is shown, and the box
 * asks the player to pick one off the list. See {@link #appendCalculator}.
 *
 * <p><b>Why it is not "fewest ingots to finish".</b> It was, for one release, and that was worse
 * than the alphabetical ordering it replaced. TFC's alloy ranges vary enormously in width - sterling
 * silver takes copper anywhere from 20% to 40%, bronze takes tin only from 8% to 12% - so the alloy
 * that costs the fewest ingots is simply the one with the loosest tolerances, whatever is or is not
 * in the pot. From empty, sterling silver needs 3 ingots, black bronze and rose gold 4, bronze and
 * brass 9; auto therefore answered "sterling silver" essentially always. That number never measured
 * how near the mix was to anything - it measured how forgiving the recipe was - and it did so while
 * looking principled, which is the worst way for a default to be wrong. Out-of-range count is the
 * only one of the two criteria that reads the pot at all, so it is the one that leads.
 *
 * <p><b>Why the list is clicked rather than cycled.</b> Selecting one item out of a set is a
 * pointing task, and the first version made it a keyboard one: every press of the cycle key moved
 * the target on by one, so reaching the alloy you wanted from a crucible of pure copper - which
 * reaches nearly every recipe in the game - took a string of presses with no way to aim. The
 * candidates are now drawn as rows and {@link #clickAt} maps a click to one directly. The key is
 * kept as an alternative rather than removed: it costs nothing and some players prefer it.
 *
 * <p><b>Why the list scrolls.</b> The visible window is capped at {@link #MAX_TARGET_ROWS}, and for
 * one release the <em>only</em> way past that cap was the cycle key - which reintroduced, for the
 * candidates below the fold, exactly the "no way to aim" problem that making the list clickable was
 * meant to solve. A player looking at a list whose last visible row is Weak Blue Steel has no mouse
 * gesture that reveals what is under it. {@link #scrollAt} adds one, over the whole box rather than
 * over the rows alone, because the box is what the cursor is plausibly resting on.
 *
 * <p><b>How scrolling and the selection share one window.</b> They are treated as two different
 * events, not two readings of one piece of state, because a state comparison cannot tell them
 * apart. A <em>selection change</em> - the cycle key, or a click - raises {@link #followSelection},
 * and the next draw re-centres the window on the selection and lowers it again; so cycling to
 * candidate twelve always brings candidate twelve into view. A <em>scroll</em> moves the window and
 * nothing else, and is deliberately not undone by the selection sitting off-screen: an
 * unconditional "keep the selection visible" rule would snap the window back one frame after every
 * scroll, which is to say scrolling would not work at all.
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
 * <p><b>Why the active row is marked by colour alone.</b> It used to carry a literal {@code "> "}
 * prefix as well, on the theory that a theme with no hue needs a character to fall back on. That is
 * not what {@link OverlayTheme#MONOCHROME} actually does - its text roles are a brightness ladder,
 * and {@code next()} is pure white against {@code text()}'s mid grey - so the marker was a second
 * copy of a signal that already survives greyscale. It also cost the list its alignment: the marker
 * and the plain indent are different widths in a proportional font, so every name shifted a pixel or
 * two sideways as the selection moved over it. Every row now shares one {@link #INDENT} and differs
 * only in colour.
 *
 * <p>This file, like every other in the mod, is pure ASCII: {@code build.gradle} sets no
 * {@code compileJava.options.encoding}, so javac reads sources in the platform default charset.
 * Nothing here needs a non-ASCII glyph - the arrow is {@code "->"} and the percent sign is ASCII -
 * so unlike {@code AnvilSolverClient}'s degree sign there is no code-point constant to build.
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
     * The <em>most</em> candidate alloys the target list will show at once, not counting the "Auto"
     * row. An upper bound, not a fixed count - see {@link #appendTargetRows}, which shows fewer
     * whenever fewer will fit.
     *
     * <p>There has to be a ceiling even on a tall window: an empty crucible reaches every alloy in
     * the pack, and TFC alone ships enough of them that an uncapped list would be a wall of names to
     * read through. Five is deliberately short of the number of alloys a player might plausibly be
     * choosing between, because the cap governs what is <em>visible</em> at once and never what is
     * reachable: the window scrolls under the mouse wheel, follows the selection, and the cycle key
     * steps through the whole list regardless. Anything past the cap is counted on a "+N more" line,
     * so a shorter window costs a glance rather than an option. Eight was the previous value and made
     * the box tall enough to dominate the screen next to a crucible GUI that is itself only nine rows
     * of slots.
     */
    private static final int MAX_TARGET_ROWS = 5;

    /**
     * Candidate rows the window moves by per wheel notch.
     *
     * <p>One, not a page. The list is at most five rows tall and its rows are single words, so a
     * page-sized jump would replace the entire visible set with an entirely unfamiliar one on every
     * notch - there would be nothing left on screen to tell the player which direction they had just
     * gone. Row-at-a-time keeps a shared edge between one frame and the next.
     */
    private static final int SCROLL_ROWS_PER_NOTCH = 1;

    /**
     * Hard ceiling on the number of ingots {@link #solveIngots} will consider adding.
     *
     * <p>This is what makes the search finite, and it is generous rather than tight. The worst
     * honest case is an empty crucible and a four-component alloy whose smallest component is a
     * couple of percent: filling that from nothing still lands well inside forty ingots at TFC's
     * 100 mB ingots, and a real TFC crucible only holds a few thousand mB in the first place. A pack
     * whose ingots are tiny - see {@link #ingotVolumeOf}, which reads that from the pack rather than
     * assuming it - is the one configuration that can hit this legitimately, and hitting it prints
     * "no mix found within N ingots" rather than a partial answer.
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

    /**
     * The whole overlay box's rectangle as drawn on the last frame, or null when no box was drawn.
     *
     * <p>Separate from {@link #clickRows} because scroll and click have deliberately different
     * targets. A click must hit a row - anything else has to fall through to the crucible - whereas
     * a scroll is aimed at the <em>list</em>, and the player aiming at a list rests the cursor
     * roughly over it, not precisely over one of its rows. Hit-testing the scroll against rows only
     * would leave the heading, the composition lines, the ingot plan and the box's own padding as
     * dead strips inside a box that visibly scrolls everywhere else.
     *
     * <p>Governed by {@link #rowsScreen} exactly as the rows are: written by {@link #drawBox} at the
     * same moment, cleared by {@link #render} at the same moment, and only honoured for the screen
     * instance it was measured against.
     */
    @Nullable
    private static BoxRect boxBounds;

    /**
     * Index of the first candidate row in the visible window - i.e. how far the list is scrolled.
     *
     * <p>Held as an absolute list position rather than a delta from wherever the selection is, so it
     * means the same thing whether the player got there by scrolling or by selecting, and so the
     * only thing that has to be true of it is that it is a valid window start. Every read clamps it
     * to {@code [0, size - window]} against that frame's own list and window size, which is what
     * guarantees the window can never show a blank row or run off either end even when the candidate
     * list, the cap, or the game window's height changes underneath it.
     */
    private static int scrollOffset;

    /**
     * The largest valid {@link #scrollOffset} for the window drawn on the last frame, i.e.
     * {@code candidates - visibleRows}. Zero when nothing is scrollable, which includes every frame
     * that drew no picker at all.
     *
     * <p>Recorded during drawing for the same reason {@link #clickRows} is: only the drawing pass
     * knows how many rows the window actually got, because that depends on the height budget. Reset
     * by {@link #render} on entry, so a frame that draws no list cannot leave a scrollable range
     * behind for {@link #scrollAt} to move through.
     */
    private static int scrollMax;

    /**
     * Set when the selection changes, cleared by the next draw that has a window to place.
     *
     * <p>A one-shot event flag, not a mode. It is the whole of the "the window follows the
     * selection" rule: {@link #cycleTarget} and {@link #clickAt} raise it, {@link #appendTargetRows}
     * consumes it by re-centring, and {@link #scrollAt} does not raise it - which is precisely why a
     * scrolled window stays where it was put instead of being dragged back to the selection on the
     * very next frame.
     *
     * <p>Consumed by the draw rather than acted on at the moment of the selection change because the
     * window size is not known outside the draw: the key handler has no idea whether the list has
     * eight rows or two.
     */
    private static boolean followSelection;

    /**
     * The candidate list {@link #scrollOffset} was last valid for, as result fluids in rank order.
     *
     * <p>A scroll position is a position <em>in a particular list</em>. Melting a metal in reorders
     * and re-filters the candidates, at which point "row four" is a different alloy and keeping the
     * offset would silently move the player somewhere they did not ask to be. Comparing the list
     * itself catches that, whereas comparing its length would miss a reorder of the same size.
     *
     * <p>Deliberately not {@link #cycleOptions}, even though the two hold the same value most of the
     * time: {@code cycleOptions} is cleared to empty at the top of every {@link #buildLines} and
     * refilled further down, so a comparison against it would report "the list changed" on every
     * single frame.
     */
    private static List<Fluid> scrollListKey = List.of();

    /**
     * Detected ingot volume in mB per metal fluid, or {@code 0} for "this pack does not say".
     *
     * <p>Cached because {@link #ingotVolumeOf} runs from the render path, several times a frame - once
     * per component of the target alloy - and each miss costs a registry lookup plus a recipe lookup.
     * The answer cannot change without the recipes changing, so caching it is free correctness-wise
     * and turns a per-frame cost into a one-off.
     *
     * <p>The failure answer is cached too, deliberately. Without that, a metal with no detectable
     * volume - which is every metal in a pack that strips heating recipes - would re-run the whole
     * lookup on every frame forever, which is the one case where the lookup is both useless and
     * guaranteed to repeat. {@code 0} never becomes a volume of zero: {@link #ingotVolumeOf} reads
     * the configured fallback instead, and reads it live, so changing the option still takes effect
     * immediately.
     */
    private static final Map<Fluid, Integer> ingotVolumeCache = new HashMap<>();

    /**
     * The level those detections were made against, held weakly.
     *
     * <p>Recipes are per-world data: joining a different world, or a different server, can change
     * what an ingot melts into. Keying the cache to the level instance and clearing it when the
     * instance changes is what stops one pack's numbers being printed inside another's.
     *
     * <p>Weak for the same reason {@link #rowsScreen} is: a static strong reference to a
     * {@code ClientLevel} would keep an entire disconnected world alive for the rest of the session.
     *
     * <p>Known limit: a {@code /reload} that changes heating recipes does not replace the level, so
     * the cache survives it. Rejoining the world picks the new numbers up. This is a deliberate stop
     * rather than an oversight - the alternative is hashing the recipe set every frame to catch a
     * case that only arises while a pack is being authored.
     */
    @Nullable
    private static WeakReference<ClientLevel> ingotVolumeLevel;

    /**
     * One finished ingot plan per candidate recipe, valid only for the crucible state recorded in
     * the four fields below.
     *
     * <p><b>Why this exists now and did not before.</b> {@link #findCandidates} used to rank without
     * solving anything; ranking by ingots means it solves <em>every</em> candidate, so the solve went
     * from once a frame to once per candidate per frame. Memoised, it is once per candidate per
     * change of crucible contents instead - and the selected target's plan, which
     * {@link #appendPlan} needs in full, is then a cache hit rather than a second solve of a recipe
     * that was solved moments earlier in the same frame.
     *
     * <p><b>Worst case on a miss.</b> Roughly {@code candidates * (MAX_INGOTS_TO_ADD + 1) *
     * MAX_INGOTS_TO_ADD * components} floating-point operations - about 20 x 65 x 64 x 5, so a few
     * hundred thousand, once, on the frame the contents change. That is comfortably inside a frame,
     * and it is a cost paid when the player drops an ingot in, not sixty times a second while they
     * stare at the screen.
     *
     * <p>Keyed by the {@code AlloyRecipe} object rather than by its result fluid: two datapack
     * recipes may produce the same alloy from different component ranges, and those two have
     * different answers. {@code AlloyRecipe} does not override {@code equals}, so this is identity
     * keyed, which is exactly right - the entries are only ever meant to match the very objects the
     * recipe manager handed out this frame.
     */
    private static final Map<AlloyRecipe, SolvePlan> solveCache = new HashMap<>();

    /**
     * The crucible contents {@link #solveCache} was computed against, or null when it holds nothing.
     *
     * <p>A copy, not the caller's snapshot. The snapshot happens not to be mutated after it is
     * built, but a cache key that silently changes with the thing it is keying is the one failure
     * mode that produces a stale answer with no symptom, and a map of at most a handful of entries
     * is not worth being clever about.
     */
    @Nullable
    private static Map<Fluid, Double> solveCacheAmounts;

    /** The total in mB {@link #solveCache} was computed against. Part of the key, not a hint. */
    private static int solveCacheTotal = -1;

    /**
     * The configured ingot-volume fallback at the time {@link #solveCache} was filled.
     *
     * <p>In the key because {@link #ingotVolumeOf} reads that option live, specifically so that
     * editing it takes effect on the next frame. On a pack where the fallback is actually in use,
     * every cached plan was computed from it, so a cache that ignored it would keep serving answers
     * from the old value - and the option would appear to do nothing.
     */
    private static int solveCacheFallback = -1;

    /**
     * The level {@link #solveCache} was filled against, held weakly for the same reason
     * {@link #ingotVolumeLevel} is.
     *
     * <p>Recipes and detected ingot volumes are both per-world, so a plan computed in one world says
     * nothing about the same recipe object in another. Same known limit as the ingot volume cache: a
     * {@code /reload} does not replace the level, so it does not invalidate this either.
     */
    @Nullable
    private static WeakReference<ClientLevel> solveCacheLevel;

    private CrucibleCalculator() {
    }

    /**
     * Advances the target selection: auto to the first candidate, then along the list, then back to
     * auto. Called from the keybind handler in {@code ClientEvents}.
     *
     * <p>Wrapping through auto rather than straight from the last candidate to the first is the
     * point of the mode: auto is a real, distinct state - "keep telling me whatever is closest" -
     * and a cycle that skipped it would leave no way back to it.
     *
     * <p>Raises {@link #followSelection} on every path, including the "nothing is reachable" one.
     * The whole reason the key still exists alongside the clickable rows is that it reaches
     * candidates below the visible window, and a key that moved the target somewhere the player
     * cannot see would be worse than no key at all.
     */
    public static void cycleTarget() {
        // Raised before any return: every path here either changes the selection or asserts that
        // auto is the only valid one, and both want the window placed to match on the next draw.
        followSelection = true;

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
                // A clicked row is by definition already visible, so re-centring on it looks like
                // nothing most of the time. It is raised anyway because the ranking can move the
                // clicked alloy on the very next frame - clicking a target changes nothing about the
                // crucible, but selecting one near the edge of the window and then dropping an ingot
                // in does - and "the thing I chose is on screen" should not depend on that.
                followSelection = true;
                return true;
            }
        }
        return false;
    }

    /**
     * Applies a mouse-wheel scroll over the overlay to the target list.
     *
     * <p><b>The return value is the whole contract</b>, exactly as it is for {@link #clickAt}: the
     * caller cancels the event when this returns true and does nothing whatsoever when it returns
     * false. The crucible screen has its own scrolling, and a handler that swallowed the wheel
     * whenever the overlay happened to be open would break it.
     *
     * <p>Four separate things each return false on their own, and all four are cases where this
     * overlay would otherwise be eating an input it does not use:
     * <ul>
     *   <li>the rectangles belong to a different screen instance - see {@link #rowsScreen};</li>
     *   <li>no box was drawn this frame, or the cursor is not inside the one that was;</li>
     *   <li>the wheel reported no vertical movement at all (a horizontal-only scroll, or a NaN);</li>
     *   <li>the window cannot actually move - the list fits, or it is already against the end the
     *       player is scrolling towards.</li>
     * </ul>
     *
     * <p>That last one makes scrolling up at the top of the list fall through to the crucible while
     * scrolling down does not, which is asymmetric but is the honest reading of "consume only what
     * you use": at the top of the list there is nothing up there to show, so nothing happened, so
     * nothing was consumed.
     *
     * <p>Nothing here may throw. It runs from an event handler on every wheel notch with the
     * crucible open, and like {@link #clickAt} it is deliberately nothing but arithmetic over
     * already-validated state.
     *
     * @param screen the screen the scroll arrived on
     * @param mouseX cursor X in the same scaled screen coordinates the box is drawn in
     * @param mouseY cursor Y in the same scaled screen coordinates the box is drawn in
     * @param deltaY vertical wheel delta; positive is scroll-up, which moves the window towards the
     *               start of the list
     * @return true if the window moved, i.e. the scroll was consumed
     */
    public static boolean scrollAt(
        CrucibleScreen screen, double mouseX, double mouseY, double deltaY
    ) {
        final WeakReference<CrucibleScreen> owner = rowsScreen;
        if (owner == null || owner.get() != screen) {
            return false;
        }
        final BoxRect box = boxBounds;
        if (box == null || !box.contains(mouseX, mouseY)) {
            return false;
        }
        // Written as two strict comparisons rather than "deltaY == 0.0" so that a NaN - which
        // compares false against everything, including itself - takes this exit instead of falling
        // through to a sign test it would also fail, and landing on an arbitrary direction.
        if (!(deltaY > 0.0) && !(deltaY < 0.0)) {
            return false;
        }
        if (scrollMax <= 0) {
            // The whole list is on screen. There is nothing to scroll, so there is nothing to eat.
            return false;
        }

        // Positive delta is the wheel rolling away from the player, which everywhere else in the
        // game means "towards the top", i.e. towards index 0.
        final int step = deltaY > 0.0 ? -SCROLL_ROWS_PER_NOTCH : SCROLL_ROWS_PER_NOTCH;
        final int next = Math.max(0, Math.min(scrollOffset + step, scrollMax));
        if (next == scrollOffset) {
            // Already against that end of the list.
            return false;
        }
        scrollOffset = next;
        // Explicitly NOT raising followSelection - see the field's own note. This line is the one
        // that makes free scrolling free: without it the next draw would re-centre on the selection
        // and the window would spring back before the player saw it move.
        followSelection = false;
        return true;
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
        //
        // The box rectangle and the scrollable range are cleared on the same line and for the same
        // reason: a frame that draws no box, or draws one with no target window in it, must leave
        // nothing behind for a scroll to hit. scrollOffset itself is deliberately NOT reset here -
        // it is a position in the candidate list, not a property of the frame, and it survives a
        // frame that happens not to draw (a config toggle, a moment with no block entity) so the
        // list is where the player left it when the box comes back.
        clickRows = List.of();
        rowsScreen = null;
        boxBounds = null;
        scrollMax = 0;

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

        final Content content = buildLines(crucible, amounts, total, theme);
        if (content.lines().isEmpty()) {
            return;
        }
        drawBox(screen, graphics, content, theme, mouseX, mouseY);
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
     * <p><b>What this does not build.</b> The clickable target rows. They are described here - as the
     * candidate list and the selected index, in {@link Content#targets()} - and turned into rows by
     * {@link #drawBox}, once it knows how many rows the window actually has room for. They cannot be
     * built here because that number is not known here, and building them anyway is what caused them
     * to be silently cut off the bottom of the box: they are appended last, and the trim cut last
     * lines first, so the rows the player was trying to click were the first thing to go.
     *
     * @param total the alloy's total amount in mB; zero exactly when {@code amounts} is empty
     */
    private static Content buildLines(
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
            return new Content(lines, appendCalculator(lines, amounts, total, theme));
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
            // Everything falls through to the calculator from here, including a finished alloy.
            //
            // An earlier version stopped at this line for any valid multi-metal alloy, on the theory
            // that a finished product needs no routes. That was wrong twice over. Practically: the
            // moment you succeed, the overlay stops telling you what the alloy IS, so topping the pot
            // up means guessing the ratio or leaving the screen - which is exactly what a player does
            // next, because one crucible rarely fills everything they are making. And factually: a
            // valid alloy is not always a dead end. A pot of Brass (copper + zinc) can still reach
            // Bismuth Bronze, whose components are a strict superset, by adding zinc and bismuth. The
            // old code refused to say so and gave no way to ask.
        } else {
            lines.add(new Line("Not a valid alloy", theme.error()));
        }

        return new Content(lines, appendCalculator(lines, amounts, total, theme));
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
     *
     * @return the target picker to draw underneath these lines, or null if there is nothing to pick
     *         between - see {@link #buildLines} for why it is described rather than drawn here
     */
    @Nullable
    private static TargetList appendCalculator(
        List<Line> lines, Map<Fluid, Double> amounts, int total, OverlayTheme theme
    ) {
        final List<AlloyRecipe> candidates = findCandidates(amounts, total);
        final List<Fluid> fluids = resultFluids(candidates);
        cycleOptions = fluids;

        if (!fluids.equals(scrollListKey)) {
            // A different list - a metal went in, a recipe reloaded, or the ranking moved. Row four
            // is now a different alloy, so an offset carried over from the old list would put the
            // player somewhere they never scrolled to. Back to the top, and let the selection place
            // the window: if a target is still selected it is the thing worth having on screen, and
            // if it is not, the top is where auto's own pick lives.
            scrollOffset = 0;
            followSelection = true;
            // Held separately from cycleOptions, which buildLines clears and refills every frame -
            // comparing against that would report a change on every single frame. Stored as the
            // freshly built list, which nothing else retains a reference to.
            scrollListKey = fluids;
        }

        if (candidates.isEmpty()) {
            // Either no recipe contains every metal already in the pot - the mix is a dead end that
            // only emptying the crucible can fix - or the recipe list could not be read at all.
            selectedTarget = null;
            lines.add(new Line("No alloy reachable", theme.muted()));
            lines.add(new Line("by adding metal", theme.muted()));
            return null;
        }

        final int index = resolveTarget(candidates);

        // An empty crucible with nothing chosen is the one case where auto must stay silent. Every
        // alloy is equally reachable from nothing, so no candidate is "closest" and the ranking's
        // leading criterion - components already out of range - cannot separate them: with no metal
        // in the pot, every component of every recipe is out of range. Whatever surfaced first would
        // be an arbitrary pick wearing the clothes of a recommendation, which is exactly how the
        // ingots-first ranking ended up insisting on Sterling Silver. The list below is still drawn,
        // still clickable and still scrollable; the player just has to say what they are making.
        if (index < 0 && total <= 0) {
            lines.add(new Line("Pick a target below", theme.muted()));
            return new TargetList(candidates, index);
        }

        final AlloyRecipe target = candidates.get(Math.max(index, 0));

        // "(auto)" versus "(2/5)" is the whole point of showing this: the player has to be able to
        // tell "the mod picked this" from "I picked this", and the fraction also says how many other
        // choices exist. It is kept even though the picker below repeats it, because the picker is
        // what shrinks on a short window and this one-line summary never does.
        final String mode = index < 0
            ? "(auto)"
            : "(" + (index + 1) + "/" + candidates.size() + ")";

        // A blank row between "what is in the pot" and "what to do about it". The box had grown to a
        // flat wall of a dozen-odd lines - composition, result, target, plan, caveats, picker - with
        // nothing telling the eye where one thought ended and the next began, and the user said as
        // much. One empty line is the cheapest possible fix and cannot go wrong: it is a plain Line,
        // so it measures and draws through the same path as every other row and costs one row of the
        // vertical budget like every other row, and it is not built with Line.row, so it records no
        // click rectangle and is not selectable.
        //
        // Deliberately only one, and deliberately not repeated above the picker. The picker used to
        // have a "Click a target:" heading separating it and no longer does, so a second blank line
        // is the obvious thing to reach for - but appendTargetRows budgets its rows to the exact
        // pixel out of what is left after these fixed lines, so every line added here is a row taken
        // straight off the bottom of the target list. Slipping an extra line into that arithmetic is
        // how the interactive rows got silently trimmed the first time.
        lines.add(new Line("", theme.muted()));
        lines.add(new Line("Target: " + fluidName(target.result()) + " " + mode, theme.muted()));

        // Plan first, picker second, and the order is deliberate: the answer - which ingots to go and
        // melt - sits above the chooser, and it is the picker that gives ground on a short window
        // rather than the plan. It also keeps the top of the box byte-for-byte the layout that
        // shipped, with the picker appended below it.
        appendPlan(lines, target, amounts, total, theme);
        return new TargetList(candidates, index);
    }

    /**
     * Appends the clickable target rows - a window over the candidate alloys, then "Auto" - sized to
     * a given number of rows and never exceeding it.
     *
     * <p>Only the rows themselves are marked selectable. The "+N more" line is ordinary text, so a
     * click on it does nothing and is not swallowed - a click that visibly lands on a label and
     * silently eats itself is worse than one that falls through.
     *
     * <p><b>Why there is no heading above the rows.</b> There was one - a muted "Click a target:" -
     * and it was removed because it was paying a whole row of a tight budget to say something the
     * rows themselves say the first time anyone moves the cursor over them, which is when they
     * highlight. Discoverability is worth a row exactly once per player; the row was being charged
     * for on every frame thereafter, and on a short game window it was charged out of the target list
     * it was labelling.
     *
     * <p><b>Why "Auto" is last.</b> It used to sit at the top, which read as a recommendation - the
     * first thing in a list is where the eye starts, and a list that opens on "Auto" reads as though
     * auto is what you are meant to want. It is not: it is the reset, the way back to letting the mod
     * choose, and lists conventionally put the reset at the bottom. Moving it there also means the
     * alloy names start at the top of the block, so the list the player is actually reading is not
     * indented one row down by a control.
     *
     * <p><b>Why a sliding window and not just the first five.</b> The cycle key steps through every
     * candidate, including the ones past the cap. If the window were pinned to the top of the list, a
     * player who cycled to candidate twelve would be looking at a list with nothing marked on it and
     * no clue where they were. The window instead follows the selection, so the selected row is
     * always the one on screen and always the one marked.
     *
     * <p><b>Where the window actually sits.</b> Two sources, and this method is where they are
     * reconciled. {@link #followSelection} - raised by a selection change and by a change of
     * candidate list - means "place the window around the selection", and is consumed here because
     * here is the first place that knows how many rows the window has. Otherwise the window is
     * simply {@link #scrollOffset}, wherever {@link #scrollAt} last put it. Both are clamped into
     * {@code [0, size - window]} against this frame's own numbers, so neither source can produce a
     * window with a blank row in it or one hanging off an end of the list.
     *
     * <p><b>Why {@code room} is an argument.</b> Because the alternative was the bug this replaces.
     * This block used to emit up to {@link #MAX_TARGET_ROWS} rows regardless, and {@link #drawBox}
     * trimmed the finished line list from the end to fit the window - so on any window too short for
     * the whole box, the rows cut were these ones: the selected row, the last candidate, and the
     * "+N more" indicator. They were the only clickable content in the box and they were the first
     * thing thrown away, which is what "cannot scroll to the last item" was. The cap is now an upper
     * bound and this method spends whatever budget it is handed, so the picker shrinks instead of
     * being cut.
     *
     * <p><b>What the budget buys, in priority order.</b> The selected row first - it is the one the
     * player is looking for, so it survives every other loss, and at one row of room it is the
     * <em>only</em> thing emitted. Then the "Auto" row, pinned at the bottom. Then as many candidates
     * around the selection as fit, up to the cap. Then, last and only if a row is genuinely spare,
     * the "+N more" indicator - which is paid for out of this budget rather than added on top of it,
     * so it can never push the box past the height it was measured for.
     *
     * <p><b>The row arithmetic, and why it is what it is.</b> {@code avail} used to be
     * {@code room - 1}: the heading was emitted unconditionally and took its row off the top before
     * anything else was counted. With the heading gone there is nothing to deduct, so
     * {@code avail == room} and every term below it - {@code candRoom}, the window, the "+N more"
     * decrement, {@code maxStart}, {@code scrollMax} - is unchanged and still adds up to exactly
     * {@code avail}. The one visible consequence is at the bottom of the range: a budget of one row
     * used to buy a heading and nothing under it, which is why {@link #layout} refused to draw a
     * picker below two rows, and now buys one real clickable row, which is why it draws one from one
     * row up.
     *
     * @param out       the visible line list being assembled; rows are appended to it
     * @param targets   the candidates and the selected index, as {@link #appendCalculator} produced
     * @param room      how many rows are available for the whole picker; the caller guarantees at
     *                  least 1, which is the least that buys a single row
     */
    private static void appendTargetRows(
        List<Line> out, TargetList targets, int room, OverlayTheme theme
    ) {
        final List<AlloyRecipe> candidates = targets.candidates();
        final int index = targets.index();
        // Non-empty: appendCalculator returns a null TargetList rather than an empty one.
        final int size = candidates.size();

        // The whole budget, with nothing deducted: there is no heading to pay for. At least 1 - see
        // the @param contract on room.
        final int avail = room;

        if (avail == 1) {
            // One row, and it goes to the selection. Everything else - the Auto row, the other
            // candidates, the "+N more" count - is a loss the player can work around with the cycle
            // key; a selected row they cannot see is not, because then nothing on screen says where
            // in the list they are. This is the case the whole method is shaped around.
            out.add(index < 0
                ? autoRow(true, theme)
                : candidateRow(candidates, index, true, theme));
            return;
        }

        // avail >= 2 from here, so the Auto row is affordable and so is at least one candidate above
        // it. Auto is a row like any other so that getting back to it is one click, the same gesture
        // as leaving it.
        //
        // Its row is RESERVED here and EMITTED at the end of this method, because it now sits below
        // the candidates. Reserving it up front is what keeps the budget arithmetic identical to when
        // it was emitted here: candRoom is what is left for the scrolling part of the list either
        // way, so the window sizing, the "+N more" decrement and scrollMax are all untouched by the
        // move. Nothing between here and the emission can return early, so the row cannot be
        // reserved and then not drawn.
        final int candRoom = avail - 1;

        // The window is the smallest of three things: how many candidates there are, the cap, and how
        // many rows are actually left. That last term is the fix - it is what makes MAX_TARGET_ROWS a
        // ceiling rather than a promise.
        int window = Math.min(Math.min(size, MAX_TARGET_ROWS), candRoom);
        if (window < size && window == candRoom && window > 1) {
            // Something is going to be hidden, so the "+N more" line is owed a row, and it has to come
            // out of this budget. Giving up one candidate row for an accurate count is worth it -
            // except when it would cost the last one, at which point the row wins and the count is
            // dropped instead.
            window--;
        }

        // window >= 1 on every path above - candRoom >= 1 because avail >= 2, size >= 1, and the
        // decrement is guarded on window > 1 - and window <= size, so this is never negative and
        // every offset in [0, maxStart] describes a full window of real candidates. That is the
        // whole of "no blank rows and no scrolling past either end": both the follow branch and the
        // free-scroll branch below are clamped into that interval, so neither can produce anything
        // else however wrong the value going in is.
        final int maxStart = size - window;
        final int start;
        if (followSelection) {
            // The selection just moved, so place the window around it rather than wherever the list
            // was scrolled to. Centred rather than merely dragged into view: landing the selection
            // on the very edge of the window is technically "visible" but tells the player nothing
            // about what is on the other side of it, and centring is what makes the next press of
            // the cycle key show its destination before it gets there.
            //
            // index < 0 is auto, and auto RESOLVES to candidate 0 - so the top of the list, which is
            // also where the clamp would have sent a negative anyway. That is about where auto's own
            // pick lives in the candidate list and has nothing to do with where the Auto row is
            // drawn; moving that row to the bottom does not move what it resolves to.
            final int centred = index < 0 ? 0 : index - (window - 1) / 2;
            start = Math.max(0, Math.min(centred, maxStart));
            // Consumed here rather than at the moment of the selection change, because this is the
            // first point that knows how big the window is. One draw honours it; the next is free
            // scrolling again.
            followSelection = false;
        } else {
            // Free scrolling: whatever scrollAt last set, clamped against this frame's own list and
            // window. The clamp is what absorbs a window that grew when the game window was resized,
            // or a list that got shorter without tripping the change check.
            start = Math.max(0, Math.min(scrollOffset, maxStart));
        }
        // Written back on both paths so scrollAt continues from what is actually on screen - after a
        // re-centre, the next notch moves one row from there rather than from a stale offset.
        scrollOffset = start;
        // Published for scrollAt only now the window is settled. Zero means "the list fits", which
        // is exactly when scrolling must fall through to the crucible.
        scrollMax = maxStart;

        final int end = start + window;
        for (int i = start; i < end; i++) {
            out.add(candidateRow(candidates, i, i == index, theme));
        }

        final int hidden = size - window;
        if (hidden > 0 && candRoom - window >= 1) {
            // Muted, unlike the anvil overlay's "+N more", precisely because everything around it
            // here is clickable and this is not. Dimmer reads as "not a row".
            //
            // "scroll" is on this line because this line is the only thing on screen that says there
            // is anything below the fold, and a player who cannot see that the list continues has no
            // reason to try the wheel over it. The count is of everything not currently shown, above
            // and below - it answers "how much of this list am I not looking at", which stays true
            // wherever the window happens to be sitting.
            //
            // Directly under the candidates and above Auto, not below it: it describes the scrolling
            // window, so it has to touch the thing it is counting. Under the Auto row it would read
            // as "there are N more after Auto", which is the one thing it does not mean.
            out.add(new Line(INDENT + "+" + hidden + " more (scroll)", theme.muted()));
        }

        // Last, in the row reserved for it above. A fixed footer under a list that scrolls: the
        // candidates above it change as the wheel turns, Auto never does, and the reset option
        // sitting still at the bottom is what makes that difference visible.
        out.add(autoRow(index < 0, theme));
    }

    /**
     * The "Auto" row: a real, clickable choice whose target is null, because null is exactly what
     * {@link #selectedTarget} holds for auto.
     *
     * <p>Split out of {@link #appendTargetRows} because that method emits it from two places - the
     * one-row case and the normal case - and two copies of a row's text and colour rules is two
     * places for them to drift apart.
     *
     * <p><b>Why the label is the bare word.</b> It has read {@code Auto (Brass)} and then
     * {@code Auto -> Brass}, both of which named the alloy auto currently resolves to. Naming it was
     * the wrong job for this row: the alloy is already named in full on the {@code Target:} line
     * above, with {@code (auto)} next to it saying who chose it, so the row was a second copy of that
     * answer competing for width in a box whose width is set by translated metal names. What the row
     * is for is being the thing you click to get back to auto, and {@code Auto} says that.
     *
     * <p><b>Where the "auto has not chosen anything" case went.</b> It used to be carried into here
     * as a flag, because {@code Auto -> <whatever sorted first>} on an empty crucible would have been
     * a false recommendation - a real bug, and the reason the flag existed. A label that never names
     * an alloy cannot reproduce it under any value of that flag, so the guard is now structural
     * rather than conditional. The information itself has not been dropped: on an empty crucible with
     * nothing selected {@link #appendCalculator} still prints "Pick a target below" and still prints
     * no {@code Target:} line, which says it plainer than a qualifier on this row ever did.
     */
    private static Line autoRow(boolean active, OverlayTheme theme) {
        return Line.row(
            INDENT + "Auto",
            active ? theme.next() : theme.text(),
            null);
    }

    /**
     * One candidate row. next() against text() is the same "this is the one that matters" pairing the
     * anvil overlay uses for its next press, so the two overlays mean the same thing by colour.
     *
     * <p>Colour is the whole of the active marking - the indent is the same on every row, active or
     * not. See the class javadoc for why the {@code "> "} prefix that used to sit in front of it is
     * gone.
     */
    private static Line candidateRow(
        List<AlloyRecipe> candidates, int i, boolean active, OverlayTheme theme
    ) {
        final Fluid result = candidates.get(i).result();
        return Line.row(
            INDENT + fluidName(result),
            active ? theme.next() : theme.text(),
            result);
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

        // The volume is recomputed here rather than carried out of the ranking pass because this is
        // the only place that needs the "the metals disagree" flag as well as the number. It is safe
        // to recompute: chooseIngotVolume is a pure function of the recipe's own component list, so
        // this and the volume the cached plan was solved with are the same number by construction.
        final VolumeChoice volume = chooseIngotVolume(ranges);
        // Cache hit in every ordinary case: findCandidates solved this very recipe object moments
        // ago, in this same frame, against this same crucible state.
        final int[] counts = solvePlan(target, ranges, amounts, total).counts();
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
            lines.add(new Line("In range", theme.next()));

            // ...and this is precisely when the ratio matters most. One crucible rarely fills
            // everything the player is making, so the next thing they do is top the pot up - and if
            // the overlay goes quiet the moment they succeed, they have to remember the ratio or go
            // and look it up. Printing the target's own ranges keeps "make more of this" on screen.
            //
            // The ranges rather than a top-up plan: a top-up is only one of many valid additions
            // (any amount in proportion works), whereas the range is the rule all of them obey, and
            // it costs one line per component instead of a second solve.
            lines.add(new Line("Ratio to keep:", theme.muted()));
            for (final AlloyRange range : ranges) {
                lines.add(new Line(
                    INDENT + fluidName(range.fluid()) + "  "
                        + percentRange(range.min(), range.max()),
                    theme.text()));
            }
        }

        if (volume.mixed()) {
            // The counts above are only right if every metal's ingot is the same size, because the
            // solve works from a single ingot volume - see solveIngots. This pack's metals disagree,
            // so rather than print a number that looks authoritative and is not, the box says which
            // metal's ingot the arithmetic assumed and lets the player judge it.
            lines.add(new Line(INDENT + "(assumes " + volume.metal() + "-size ingots)", theme.error()));
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
    // Ingot volume
    // ---------------------------------------------------------------------------------------------

    /**
     * Picks the one ingot volume {@link #solveIngots} will work in for this target, and reports
     * whether the target's metals actually agreed on it.
     *
     * <p><b>Why one volume and not one per metal.</b> {@code solveIngots} is tractable because adding
     * {@code K} ingots fixes the final total at {@code T0 + K*V} <em>before</em> anything else is
     * decided, which is what turns each component's range into a plain integer bracket. That only
     * holds while {@code V} is a single number: with a different volume per metal the final total
     * depends on which metals the ingots went to, the brackets stop being independent, and the search
     * is a different algorithm rather than the same one with another parameter. That solve is
     * verified and shipped, so the volume stays scalar and the disagreement is reported instead.
     *
     * <p>In practice this never fires. TFC gives every metal a 100 mB ingot, and a pack changing that
     * changes it for all of them; a pack with genuinely per-metal ingot sizes is the case this exists
     * to be honest about rather than the case it expects.
     *
     * <p>The first component is the one that wins, in the recipe's own component order, purely
     * because something has to and recipe order is stable from frame to frame. It is named on screen
     * when it matters, so the choice is visible rather than silent.
     *
     * @param ranges the target's components; non-empty, as the caller has already checked
     */
    private static VolumeChoice chooseIngotVolume(List<AlloyRange> ranges) {
        // Datapack data: a null element here would already have crashed containsFluid on a non-empty
        // crucible, but this path is also reached from an empty one, where that loop never runs.
        final AlloyRange first = ranges.get(0);
        final Fluid firstFluid = first == null ? null : first.fluid();
        final int volume = ingotVolumeOf(firstFluid);

        boolean mixed = false;
        for (int i = 1; i < ranges.size(); i++) {
            final AlloyRange range = ranges.get(i);
            if (range == null) {
                continue;
            }
            if (ingotVolumeOf(range.fluid()) != volume) {
                mixed = true;
                break;
            }
        }
        return new VolumeChoice(volume, mixed, fluidName(firstFluid));
    }

    /**
     * The volume in mB of one ingot of the given metal, detected from the pack's own data, falling
     * back to the configured value when the pack does not say.
     *
     * <p><b>Why this is detected at all.</b> TFC does not define ingot volume as a code constant - it
     * is a number in heating recipe data, and a datapack or addon is free to change it. Every ingot
     * count this overlay prints is that number divided into an mB figure, so a pack that changes it
     * and a mod that assumes 100 produce answers that are confidently, silently wrong. The alloy
     * recipes were already read live from the world; this was the last hardcoded number left, and now
     * it is not one.
     *
     * <p>Never throws and never returns zero or less. Every failure - no world, no ingot item, no
     * heating recipe, a recipe that melts into some other metal, a non-positive amount - lands on the
     * configured fallback, which is range-limited to 1 or more by the config spec.
     */
    private static int ingotVolumeOf(@Nullable Fluid fluid) {
        // Read live rather than captured, so editing the option in the config screen takes effect on
        // the next frame instead of the next launch.
        final int fallback = AnvilSolverConfig.INGOT_VOLUME.get();
        if (fluid == null) {
            return fallback;
        }

        // Same reason findCandidates checks it: this runs from a render event, which can fire with no
        // world behind it. No world means no recipes, and nothing is cached from that state, so the
        // first frame that does have one still detects properly.
        final ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return fallback;
        }

        final WeakReference<ClientLevel> owner = ingotVolumeLevel;
        if (owner == null || owner.get() != level) {
            // A different world is a different set of recipes. Clearing on the identity change is the
            // whole of the invalidation - see the field's own note on what that does not cover.
            ingotVolumeCache.clear();
            ingotVolumeLevel = new WeakReference<>(level);
        }

        Integer cached = ingotVolumeCache.get(fluid);
        if (cached == null) {
            cached = detectIngotVolume(fluid);
            ingotVolumeCache.put(fluid, cached);
        }
        // 0 is the cached "this pack does not say", not a volume. It defers to the fallback here so
        // that the fallback stays live even though the failure itself is remembered.
        return cached > 0 ? cached : fallback;
    }

    /**
     * Reads one metal's ingot volume out of the heating recipe that melts its ingot, or returns 0 if
     * it cannot be established beyond doubt.
     *
     * <p><b>How the ingot is found.</b> TFC names a metal's fluid {@code tfc:metal/<metal>} and its
     * ingot item {@code tfc:metal/ingot/<metal>}, so the item's id is the fluid's id with
     * {@code ingot/} inserted after the first path segment. Deriving it beats hardcoding a metal
     * table, which would go stale the moment an addon added a metal - and the derivation is only ever
     * a guess that the checks below either confirm or reject.
     *
     * <p><b>Why the output fluid is checked against the metal asked about.</b> Because the derived
     * name is a guess. If it happened to resolve to some unrelated item that also melts, its volume
     * would be adopted as this metal's and every count printed from it would be wrong in a way
     * nothing on screen could explain. Matching the recipe's own output fluid against the fluid this
     * was called for is what turns the guess into a fact: either the item melts into exactly this
     * metal, or the answer is discarded.
     *
     * @return the volume in mB, or 0 meaning "use the configured fallback"
     */
    private static int detectIngotVolume(Fluid fluid) {
        final ResourceLocation fluidId = BuiltInRegistries.FLUID.getKey(fluid);
        if (fluidId == null) {
            return 0;
        }
        final String path = fluidId.getPath();
        final int slash = path.indexOf('/');
        if (slash < 0) {
            // Not a "<group>/<metal>" name at all, so there is nothing to insert into and no reason
            // to think this fluid follows TFC's metal convention.
            return 0;
        }
        // "metal/copper" -> "metal/ingot/copper". tryBuild rather than a constructor: it returns null
        // on anything it will not accept instead of throwing, and nothing on this path may throw.
        final ResourceLocation ingotId = ResourceLocation.tryBuild(
            fluidId.getNamespace(),
            path.substring(0, slash + 1) + "ingot/" + path.substring(slash + 1));
        if (ingotId == null) {
            return 0;
        }

        // Both checks are needed. BuiltInRegistries.ITEM is a defaulted registry, so get() on an
        // unknown id hands back AIR rather than null - and AIR would sail straight into a recipe
        // lookup that has no business being made.
        if (!BuiltInRegistries.ITEM.containsKey(ingotId)) {
            return 0;
        }
        final Item item = BuiltInRegistries.ITEM.get(ingotId);
        if (item == null || item == Items.AIR) {
            return 0;
        }

        final HeatingRecipe recipe;
        try {
            // Same narrow catch, and for the same reason, as the alloy recipe lookup in
            // findCandidates: this reaches into TFC's own recipe cache, and a TFC whose internals this
            // mod no longer matches must degrade to the configured fallback rather than throw out of
            // a render event once per frame forever.
            recipe = HeatingRecipe.getRecipe(new ItemStack(item));
        } catch (final NullPointerException | IllegalStateException e) {
            return 0;
        }
        if (recipe == null) {
            return 0;
        }

        final FluidStack output = recipe.getDisplayOutputFluid();
        if (output == null || output.isEmpty()) {
            return 0;
        }
        if (output.getFluid() != fluid) {
            // The derived item melts into something other than the metal asked about, so the name
            // guess landed on the wrong item. Its volume says nothing about this metal.
            return 0;
        }
        final int amount = output.getAmount();
        return amount > 0 ? amount : 0;
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
     * <p><b>{@code V} is one number for the whole solve, not one per metal.</b> That is load-bearing,
     * not an oversight: it is precisely because {@code V} is scalar that {@code K} fixes {@code T}
     * up front, and it is because {@code T} is known up front that the ranges become the independent
     * integer brackets above. Per-metal volumes would make {@code T} depend on which metals the
     * ingots went to and the derivation would not hold. Where that one number comes from, and what
     * happens on the packs where the metals disagree, is {@link #chooseIngotVolume}'s problem.
     *
     * <p><b>Why the unit and the cap are parameters when there is only one caller.</b> Nothing in the
     * derivation above says the unit has to be an ingot - only that it is one fixed volume - so this
     * is written as a solve in whole units of size {@code V}, up to {@code maxUnits} of them, and
     * {@link #solvePlan} supplies an ingot volume and {@link #MAX_INGOTS_TO_ADD}. Keeping the
     * generalisation costs nothing: {@code maxUnits} is the loop's upper bound and {@code unitVolume}
     * the size of one unit, both of which the arithmetic already had to name. It also keeps the
     * proven bit provably unchanged - the search is byte-for-byte the one that was verified, whatever
     * unit is handed to it - rather than having the ingot case hardcoded back into it and re-argued.
     *
     * @param total the current total in mB, zero for an empty crucible
     * @param unitVolume one unit's volume in mB, in practice an ingot's; guaranteed positive by the
     *                   caller, and rejected here as well rather than trusted
     * @param maxUnits the most units to consider adding; a non-positive value simply finds nothing
     * @return units to add, indexed to match {@code ranges}, or null if there is no answer within
     *         {@code maxUnits}
     */
    @Nullable
    private static int[] solveIngots(
        List<AlloyRange> ranges, Map<Fluid, Double> amounts, int total, int unitVolume, int maxUnits
    ) {
        final int size = ranges.size();
        if (size == 0 || unitVolume <= 0) {
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

        for (int k = 0; k <= maxUnits; k++) {
            // long, not int, and now more than a nicety: the volume is read out of pack data rather
            // than clamped to the config's range, so k*unitVolume has no small upper bound to appeal
            // to. In long it cannot overflow for any int volume and any int k, and it costs nothing.
            final long finalTotal = (long) total + (long) k * unitVolume;
            if (finalTotal <= 0L) {
                // k == 0 on an empty crucible. Nothing is a valid alloy, and every fraction would be
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

            if (verify(ranges, have, counts, finalTotal, unitVolume)) {
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
        List<AlloyRange> ranges, double[] have, int[] counts, long finalTotal, int unitVolume
    ) {
        for (int i = 0; i < ranges.size(); i++) {
            final double amount = have[i] + (double) unitVolume * counts[i];
            if (!ranges.get(i).isIn(amount / finalTotal)) {
                return false;
            }
        }
        return true;
    }

    /**
     * One candidate's ingot plan, solved once per crucible state and reused until it changes.
     *
     * <p>Follows {@code AnvilSolverClient.solveCached}'s shape deliberately - compare the inputs the
     * answer depends on, reuse if they all match, otherwise recompute and record - because this mod
     * already has one memoised solver and two different patterns for the same job is two things to
     * reason about. The difference is only that there are many candidates rather than one anvil, so
     * the "reuse or recompute" test is split: {@link #refreshSolveCache} decides whether the whole
     * table is still valid, then the recipe picks its own entry out of it.
     *
     * <p><b>Why the key is the crucible state and not the frame.</b> Anything that changed every
     * frame would make this a cache that never hits, which is the same as not having one. The inputs
     * that actually decide the answer are the contents, the total, the ingot volume, and the world
     * the recipes came from - and every one of those is stable for as long as the player is not
     * putting metal in the pot.
     *
     * <p>Never returns null and never throws. {@link #solveIngots} returning null is a real answer -
     * "no mix within the cap" - and is cached as such, so an unsolvable candidate is not re-searched
     * to its full depth on every frame, which is the most expensive case there is.
     */
    private static SolvePlan solvePlan(
        AlloyRecipe recipe, List<AlloyRange> ranges, Map<Fluid, Double> amounts, int total
    ) {
        refreshSolveCache(amounts, total);

        final SolvePlan cached = solveCache.get(recipe);
        if (cached != null) {
            return cached;
        }
        // Computed only on a miss: chooseIngotVolume walks the component list and consults the
        // per-fluid volume cache for each one, which is cheap but not free at once per candidate per
        // frame.
        final SolvePlan plan = SolvePlan.of(solveIngots(
            ranges, amounts, total, chooseIngotVolume(ranges).volume(), MAX_INGOTS_TO_ADD));
        solveCache.put(recipe, plan);
        return plan;
    }

    /**
     * Drops every cached plan if anything the plans were computed from has changed.
     *
     * <p>All four inputs are compared, not just the obvious two. The contents and the total are what
     * the player changes by melting something in. The configured fallback volume is in here because
     * {@link #ingotVolumeOf} reads it live - on a pack where it is actually in use, every plan in the
     * table was computed from it. The level is in here because recipes and detected volumes are both
     * per-world.
     *
     * <p>Cleared wholesale rather than entry by entry: a change to any of these invalidates every
     * entry at once, and the table is a handful of small arrays.
     */
    private static void refreshSolveCache(Map<Fluid, Double> amounts, int total) {
        final ClientLevel level = Minecraft.getInstance().level;
        final int fallback = AnvilSolverConfig.INGOT_VOLUME.get();
        final WeakReference<ClientLevel> owner = solveCacheLevel;

        if (total == solveCacheTotal
            && fallback == solveCacheFallback
            && solveCacheAmounts != null && solveCacheAmounts.equals(amounts)
            && owner != null && owner.get() == level) {
            return;
        }

        solveCache.clear();
        // A copy, so the key cannot change with the map it was taken from - see the field.
        solveCacheAmounts = new HashMap<>(amounts);
        solveCacheTotal = total;
        solveCacheFallback = fallback;
        // Null level is stored as a null reference rather than a reference to null, so the check
        // above fails outright next time instead of matching a reference whose target has simply
        // been collected.
        solveCacheLevel = level == null ? null : new WeakReference<>(level);
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
     * <p><b>The ordering, and therefore what "auto" picks.</b> Fewest ingots to finish, ascending -
     * so element 0, which is what auto resolves to, is the target the player can reach with the
     * least metal. Candidates with no solution inside {@link #MAX_INGOTS_TO_ADD} sort last, which
     * they do by carrying {@code Integer.MAX_VALUE} as their count rather than by a separate rule.
     * The three old criteria - fewest components out of range, then fewest metals, then result name
     * - survive unchanged underneath, as tie-breaks only.
     *
     * <p>The previous ordering had those three criteria as the <em>whole</em> ranking, and the
     * result was that auto was decided alphabetically far more often than anyone realised: in front
     * of a crucible of pure copper, Bronze and Brass tie on out-of-range count and on metal count,
     * so the box said {@code Auto (Brass)} for no better reason than "Bra" &lt; "Bro". Ingots are
     * both the honest measure of near and a number this class was already computing for the target
     * it displays; the change is that it now computes it for all of them and sorts on it.
     *
     * <p>The name tie-break is kept, last, purely so the order is total: two candidates equal on
     * every real criterion must not swap places between frames and make the box flicker or the cycle
     * key land somewhere different on each press.
     *
     * <p><b>Cost.</b> This runs one {@link #solveIngots} per candidate, where it used to run none -
     * so the per-frame work would be tens of searches instead of one if it were not memoised. It is:
     * see {@link #solvePlan}, which keys on the crucible state and so recomputes only on the frame
     * the contents actually change.
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

            // How far the pot is from this alloy, measured as a DISTANCE rather than a count.
            //
            // Counting out-of-range components was the obvious measure and it does not work, which is
            // worth spelling out because it looked right and shipped a user-reported bug twice. The
            // count is degenerate on exactly the case people care about: a pot of one pure metal has
            // that metal at fraction 1.0 (above every TFC maximum) and every other component at 0.0
            // (below every minimum), so EVERY two-component alloy scores 2 and the key decides
            // nothing. Whatever tie-break sat underneath then chose - and when that was ingot count,
            // it chose whichever alloy had the loosest ranges, which is how Sterling Silver kept being
            // recommended for a crucible of copper.
            //
            // Summing how far outside its range each component sits separates them properly. From
            // 100 mB of pure copper: Bronze and Brass score 0.16, Bismuth Bronze 0.65, Sterling Silver
            // 1.20, Rose Gold 1.40 - which is the order a player would call "closest", and the count
            // could not express because all of them tie at 2 (or rank Bismuth Bronze *last* at 3,
            // despite it being nearer than both Sterling Silver and Rose Gold).
            double distance = 0.0;
            for (final AlloyRange range : ranges) {
                final double fraction = fractionOf(amountOf(amounts, range.fluid()), total);
                if (fraction < range.min()) {
                    distance += range.min() - fraction;
                } else if (fraction > range.max()) {
                    distance += fraction - range.max();
                }
                // In range contributes nothing, so a component already correct is free - which is what
                // makes "already partly right" beat "needs everything moved", as isIn() did before.
            }
            // The primary sort key, and the reason this loop now solves rather than just measures.
            // Memoised on the crucible state, so this is a map lookup on every frame but the one
            // where the contents changed - see solvePlan for what a miss actually costs.
            final int ingots = solvePlan(recipe, ranges, amounts, total).ingots();
            ranked.add(new Ranked(recipe, ingots, distance, ranges.size(),
                fluidName(recipe.result())));
        }

        ranked.sort((left, right) -> {
            // 1. Fewest components already out of range - i.e. how close the mix in the pot actually
            //    is to this alloy. This has to lead, and a previous version of this comparator that
            //    led with ingot count instead was a real, user-reported bug worth spelling out so it
            //    is not reintroduced.
            //
            //    TFC's alloy ranges differ enormously in width: Sterling Silver is Copper 0.20-0.40
            //    and Silver 0.60-0.80 (+/-20 percentage points), while Bronze is Copper 0.88-0.92 and
            //    Tin 0.08-0.12 (+/-4). A loose range is trivially cheap to satisfy, so ranking by
            //    ingots put Sterling Silver at 3, Black Bronze and Rose Gold at 4, and Bronze and
            //    Brass at 9 - from an EMPTY crucible, where nothing is closer to anything. Auto then
            //    recommended Sterling Silver essentially always. "Fewest ingots" does not find the
            //    nearest alloy; it finds whichever alloy has the loosest tolerances, which is worse
            //    than the arbitrary alphabetical order it replaced because it looks principled.
            final int byDistance = Double.compare(left.distance(), right.distance());
            if (byDistance != 0) {
                return byDistance;
            }
            // 2. Fewest ingots, now a genuine tie-break: between two alloys the mix is equally close
            //    to, the cheaper one to finish is the better suggestion.
            //
            //    It also carries "unsolvable sorts last" on its own. An unsolvable candidate's ingot
            //    count is Integer.MAX_VALUE and MAX_INGOTS_TO_ADD is 64, so no solvable candidate can
            //    tie with one - that requirement falls out of this comparison rather than needing a
            //    separate rule that could disagree with it.
            if (left.ingots() != right.ingots()) {
                return Integer.compare(left.ingots(), right.ingots());
            }
            if (left.size() != right.size()) {
                return Integer.compare(left.size(), right.size());
            }
            // Last, and never removed: it is what makes the order total, so two otherwise identical
            // candidates cannot swap places between frames and make the list flicker or the cycle
            // key land somewhere different on each press. It decides far less than it used to - it
            // is now reached only by an exact tie on all three real criteria - but it still has to
            // be here.
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

    /**
     * Formats an allowed range as whole percentages, e.g. {@code 0.88, 0.92} to {@code "88-92%"}.
     *
     * <p>Whole numbers rather than {@link #percent}'s one decimal, and one {@code %} rather than two.
     * TFC's ranges are round numbers, the decimals would always be {@code .0}, and this line sits in
     * a box whose width is set by its longest entry - so the precision would cost pixels and buy
     * nothing. The measured value above it keeps its decimal, because that one genuinely varies.
     */
    private static String percentRange(double min, double max) {
        return String.format(Locale.ROOT, "%.0f-%.0f%%", min * 100.0, max * 100.0);
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
     * - so it cannot describe a row at a different place or size from the one drawn a line later. And
     * because {@link #layout} decides how many target rows exist <em>before</em> anything is measured
     * or drawn, a row that will not fit is never created in the first place, rather than created and
     * then cut. Nothing between here and the drawing loop can drop a row, so no row can end up
     * measured but undrawn, or drawn but unclickable.
     */
    private static void drawBox(
        CrucibleScreen screen, GuiGraphics graphics, Content content, OverlayTheme theme,
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
        final List<Line> visible = layout(content, maxRows, theme);
        if (visible.isEmpty()) {
            return;
        }

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
        // Built from the very left/top/width/height the background and border were just drawn from,
        // in the same method, so "where the box looks like it is" and "where a scroll counts as
        // being over it" are one rectangle rather than two that could disagree. Half-open on the
        // right and bottom, matching ClickRow, so the two agree about their shared edges.
        boxBounds = new BoxRect(left, top, left + width, top + height);
        rowsScreen = new WeakReference<>(screen);
    }

    /**
     * Turns the frame's content into the exact list of lines to draw, fitted to the rows available.
     *
     * <p>This is the one place the height budget is spent, and it is spent in the order the content
     * is worth. The fixed lines - the header, the composition, the named result, the ingot plan -
     * come first and are trimmed from the end if even they overrun, because a window that short is
     * already showing the player everything it can. The target picker is fitted into whatever is left
     * by {@link #appendTargetRows}, which is handed the room rather than left to guess it.
     *
     * <p><b>Why one row is now enough.</b> This used to demand two, because the picker opened with a
     * "Click a target:" heading and one row therefore bought a label with nothing under it - a
     * caption announcing an interaction that was not on screen. With the heading gone the first row
     * of the budget is a real, clickable, correctly marked row, so there is no longer a size at which
     * the picker is a promise it cannot keep. Zero rows is still nothing at all, and it still fails
     * by dropping the block whole rather than by trimming it: the plan above stays on screen and the
     * cycle key still works.
     *
     * @param maxRows rows the window has room for; at least 1, as {@link #drawBox} has checked
     * @return the lines to draw, in order, never longer than {@code maxRows}
     */
    private static List<Line> layout(Content content, int maxRows, OverlayTheme theme) {
        final List<Line> head = content.lines();
        final TargetList targets = content.targets();
        // What is left for the picker once the fixed lines have taken their share. Negative when the
        // fixed lines alone overrun, which the >= 1 test below rejects along with a budget of exactly
        // zero.
        final int room = maxRows - head.size();

        if (targets != null && room >= 1) {
            final List<Line> visible = new ArrayList<>(head);
            appendTargetRows(visible, targets, room, theme);
            if (visible.size() <= maxRows) {
                return visible;
            }
            // Cannot fire: appendTargetRows spends at most the room it was given. It is checked
            // anyway because the alternative failure is the exact one being fixed - trimming this
            // list to fit would cut clickable rows off the bottom of it. If the budgeting is ever
            // wrong, the picker is dropped whole and the fixed lines are drawn on their own.
        }
        return head.size() <= maxRows ? head : head.subList(0, maxRows);
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
     * The overlay box's own screen rectangle, for hit-testing a scroll against the whole box rather
     * than against its rows.
     *
     * <p>A separate type from {@link ClickRow} rather than a {@code ClickRow} with a null target,
     * because a null target already means something specific there - it is the "Auto" row - and a
     * rectangle that answered {@code clickAt} with "select auto" from the box's padding would be a
     * genuinely wrong click, not a naming quibble. Same half-open edges, for the same reason.
     *
     * @param left   inclusive left edge, in scaled screen coordinates
     * @param top    inclusive top edge
     * @param right  exclusive right edge
     * @param bottom exclusive bottom edge
     */
    private record BoxRect(int left, int top, int right, int bottom) {

        /** Whether the cursor is inside the box. Pure arithmetic; cannot throw. */
        boolean contains(double x, double y) {
            return x >= left && x < right && y >= top && y < bottom;
        }
    }

    /**
     * One candidate's solved ingot plan, together with the total that ranks it.
     *
     * <p>A record rather than a bare {@code int[]} in the cache so that "solved, and the answer is
     * no" is a value like any other. A map holding null for that would make every read an
     * {@code containsKey} plus a {@code get}, and the one case that most needs caching - an
     * unsolvable candidate, which costs a full search to the cap before it gives up - is exactly the
     * one a null would leave uncached by accident.
     *
     * <p>{@code counts} is shared, not copied, on every read. {@link #solveIngots} already returns a
     * fresh array and nothing downstream writes to it; that is a rule this class keeps rather than a
     * property the type enforces. The generated {@code equals}/{@code hashCode} compare that array
     * by identity, which is why {@code SolvePlan} is only ever stored and read, never compared.
     *
     * @param counts ingots to add, indexed to match the recipe's component list, or null when there
     *               is no plan within {@link #MAX_INGOTS_TO_ADD}
     * @param ingots the sum of {@code counts}, or {@code Integer.MAX_VALUE} when there is no plan -
     *               a value no feasible plan can reach, which is what sorts the unsolvable last
     */
    private record SolvePlan(@Nullable int[] counts, int ingots) {

        /** The shared "no plan" answer. Immutable and stateless, so one instance serves every miss. */
        private static final SolvePlan NONE = new SolvePlan(null, Integer.MAX_VALUE);

        static SolvePlan of(@Nullable int[] counts) {
            if (counts == null) {
                return NONE;
            }
            int sum = 0;
            for (final int count : counts) {
                // Every count is already non-negative - solveIngots floors each at zero and only
                // ever increments - so this guard changes nothing today. It is here so the total can
                // never come out below the number of ingots the plan actually lists, which is the
                // one way this could rank a target as cheaper than it is.
                if (count > 0) {
                    sum += count;
                }
            }
            return new SolvePlan(counts, sum);
        }
    }

    /**
     * One frame's content: the lines whose text is already settled, and - separately - a description
     * of the target picker that has not been turned into lines yet.
     *
     * <p>The split exists because the two are decided at different times. The fixed lines depend only
     * on the crucible; the picker's size depends on the window, which is not known until
     * {@link #drawBox} measures it. Keeping the picker as a description until then is what lets it
     * shrink to fit instead of being built too big and cut - and being cut, as the last block in the
     * list, is what made the bottom target rows unreachable.
     *
     * @param lines   the settled lines, in order, top of the box first
     * @param targets the picker to fit underneath them, or null when there is nothing to pick between
     */
    private record Content(List<Line> lines, @Nullable TargetList targets) {
    }

    /**
     * Everything the target picker needs, held until there is a row budget to draw it against.
     *
     * <p>The index is carried rather than recomputed later because {@link #resolveTarget} has a side
     * effect - it resets a selection that has gone stale - and running that a second time against a
     * list built in a different pass is how the marked row and the selected target come to disagree.
     *
     * <p>It used to carry a third component, {@code autoResolves}, saying whether auto had actually
     * settled on a target - false only on an empty crucible with nothing selected. Its one consumer
     * was the Auto row's label, which named the resolved alloy and had to be stopped from naming one
     * that had not been chosen. That label is now the bare word {@code Auto} in every case, so there
     * is no longer anything for the flag to suppress, and a component nothing reads is state that can
     * only rot. The case it described is still reported, on the "Pick a target below" line
     * {@link #appendCalculator} prints instead of a {@code Target:} line.
     *
     * @param candidates the reachable alloys, best first; never empty
     * @param index      the selected candidate's index, or -1 for auto
     */
    private record TargetList(List<AlloyRecipe> candidates, int index) {
    }

    /**
     * The single ingot volume one solve works in, and whether the target's metals agreed on it.
     *
     * <p>{@code mixed} is not a detail to log and move past: it means the printed counts rest on an
     * assumption the pack contradicts, so it is drawn on screen. {@code metal} names whose ingot the
     * assumption came from, which is the only thing that makes the caveat actionable.
     *
     * @param volume the volume in mB, always positive
     * @param mixed  true when at least one other component's ingot is a different size
     * @param metal  display name of the metal {@code volume} was taken from
     */
    private record VolumeChoice(int volume, boolean mixed, String metal) {
    }

    /**
     * One candidate recipe with its sort keys precomputed.
     *
     * <p>Precomputed rather than recomputed inside the comparator: the display name is a translation
     * lookup through a temporary {@code FluidStack}, and a comparison-time lookup would run it
     * O(n log n) times per frame instead of once per recipe.
     *
     * @param recipe     the candidate itself
     * @param ingots     total ingots to reach it, the primary key, or {@code Integer.MAX_VALUE} when
     *                   it cannot be reached within {@link #MAX_INGOTS_TO_ADD}
     * @param distance   summed distance of every component from its allowed range, 0 when the pot already matches
     * @param size       how many components it has
     * @param name       its result's display name, the final tie-break
     */
    private record Ranked(AlloyRecipe recipe, int ingots, double distance, int size, String name) {
    }
}
