package com.njguidi14.anvilsolver.client;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

import com.mojang.blaze3d.vertex.PoseStack;
import com.njguidi14.anvilsolver.config.AnvilSolverConfig;
import com.njguidi14.anvilsolver.config.OverlayTheme;
import com.njguidi14.anvilsolver.solver.ForgeSim;
import com.njguidi14.anvilsolver.solver.Solution;
import com.njguidi14.anvilsolver.solver.Step;
import net.dries007.tfc.client.screen.AnvilScreen;
import net.dries007.tfc.common.blockentities.AnvilBlockEntity;
import net.dries007.tfc.common.component.forge.ForgeStep;
import net.dries007.tfc.common.component.forge.Forging;
import net.dries007.tfc.common.component.heat.HeatCapability;
import net.dries007.tfc.common.component.heat.IHeatView;
import net.dries007.tfc.common.recipes.AnvilRecipe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Reads the live forging state from the anvil block entity and renders the
 * solver's press plan in a box alongside the TFC anvil screen.
 */
public final class AnvilSolverClient {

    // Every colour used here now comes from the configured OverlayTheme, which is read exactly once
    // per render call in render() and passed down. Two rules keep that honest:
    //
    //   1. It is read into a LOCAL, never a static. A static would have to be refreshed when the
    //      config changed, and would go stale the one time somebody forgot.
    //   2. No code branches on a colour value. Colours are chosen by role (next, error, muted, ...)
    //      and only ever handed to GuiGraphics, so swapping themes cannot change what the mod does -
    //      only what it looks like.

    /** Size of a TFC step button and of a step icon; both are 16x16. */
    private static final int ICON_SIZE = 16;
    /** Row height for a press line, so the 16px icon has a pixel of breathing room above and below. */
    private static final int ICON_LINE_HEIGHT = 18;
    /** Horizontal gap on each side of an inline icon. */
    private static final int ICON_GAP = 1;
    /** Width and height of TFC's anvil GUI texture sheet, as used by its own blit calls. */
    private static final int TEXTURE_SIZE = 256;

    /** Width of the temperature bar, in pixels. Narrow on purpose - see {@link #buildLines}. */
    private static final int BAR_WIDTH = 24;
    /** Height of the temperature bar. Sits inside a normal text row without making it any taller. */
    private static final int BAR_HEIGHT = 5;
    /** Horizontal gap between a bar line's text and its bar. */
    private static final int BAR_GAP = 3;

    /**
     * U+00B0 DEGREE SIGN, built from its code point instead of typed as a literal.
     *
     * <p>Every source file in this mod is pure ASCII and {@code build.gradle} sets no
     * {@code compileJava.options.encoding}, so javac reads sources in the platform default charset -
     * which on Windows is not UTF-8. Typing the character directly would make this file's meaning
     * depend on the charset of whoever compiles it; a numeric code point cannot be mangled by any
     * encoding, because every character of it is ASCII.
     *
     * <p>The glyph itself is safe to draw: it is part of Minecraft's built-in font, not a fallback.
     */
    private static final char DEGREE_SIGN = (char) 0x00B0;
    /** Temperature unit suffix, e.g. the {@code "&deg;C"} in {@code "812&deg;C"}. */
    private static final String DEGREES_C = DEGREE_SIGN + "C";

    /** Inner margin between the box border and its content, on every side. */
    private static final int PADDING = 5;
    /**
     * Pixels of clearance kept between the bottom of the box and the bottom of the window.
     * Small on purpose - this is breathing room, not a layout constraint.
     */
    private static final int SCREEN_MARGIN = 2;
    /**
     * Number of plain text lines {@link #buildLines} <em>always</em> puts above the press list
     * ("Target N Work N" and "K presses"). Named so the vertical-fit maths and the code that emits
     * those lines cannot drift apart unnoticed.
     *
     * <p>The optional temperature line sits between those two and is counted separately, by the
     * caller of {@link #pressRowsToShow}, since it is only present when the item can be heated and
     * the config option is on.
     */
    private static final int HEADER_LINES = 2;

    // Single-slot memoization: ForgeSim.solve() runs a BFS that, in the infeasible
    // case, must exhaust the whole reachable state space before concluding there's
    // no solution - expensive to redo on every single render frame. Cache the last
    // computed answer and only recompute when the inputs actually change.
    private static Integer cachedTarget;
    private static Integer cachedWork;
    private static List<Step> cachedHistory;
    private static List<com.njguidi14.anvilsolver.solver.Rule> cachedRules;
    private static Solution cachedSolution;

    /**
     * Temperature history behind the "~Ns" countdown.
     *
     * <p>Deliberately NOT part of the solve cache above, and it must never become part of it. That
     * cache is keyed on {@code (target, work, history, rules)}; temperature changes several times a
     * second, so folding it into the key would invalidate the cache on nearly every frame and put
     * the BFS back on the render thread - which is the exact cost that cache exists to avoid. The
     * solve is cached, the temperature is read fresh, and the two never touch.
     *
     * <p><b>Every frame must report to it - including the frames with nothing to report.</b> It is
     * static, so it outlives any single screen, item or session, and it can only detect a
     * <em>change</em> of subject, never a gap. Left uninformed while the anvil sits empty, it holds
     * on to the last item's samples; drop in a second item within the two-second window whose
     * subject key happens to match (same item type, same target, same work, same history) and those
     * samples are fitted together with the new ones. A hot ingot swapped for an identical cold one
     * produced a confident "~1s" on an item with most of a minute of working time left, because the
     * jump was a fall and the estimator's rising-sample guard only catches climbs. So every path
     * that does not take a reading calls {@link CoolingEstimator#clear()}, and there is no path that
     * does neither.
     */
    private static final CoolingEstimator COOLING = new CoolingEstimator();

    /**
     * Runtime visibility of the <em>text box</em>, flipped by the toggle keybind. Session-only on
     * purpose: it is deliberately NOT written back to {@link AnvilSolverConfig#ENABLED}, so hiding
     * the overlay for one forging session never silently rewrites the on-disk config. The config
     * option remains the persistent "off" switch; this is the temporary one. Defaults to visible.
     *
     * <p><b>This hides the box only, not the button highlight.</b> The highlight is the more useful
     * half of the two once you know the mechanic: it sits on the anvil's own button, so following it
     * needs no reading and no glancing sideways. Hiding the box and keeping the highlight turns the
     * mod into a pure "press the lit one" guide, which is a legitimate way to use it and the reason
     * the toggle exists at all - the box is what takes up screen space, not the outline.
     *
     * <p>The highlight keeps its own switch, {@link AnvilSolverConfig#HIGHLIGHT_NEXT_BUTTON}, so
     * every combination is still reachable: box + highlight, highlight alone (toggle off), box alone
     * (option off), or nothing at all ({@code enabled = false}).
     */
    private static boolean overlayVisible = true;

    private AnvilSolverClient() {
    }

    /** Flips the session-only visibility of the overlay. Called from the keybind handler. */
    public static void toggleOverlay() {
        overlayVisible = !overlayVisible;
    }

    public static void render(AnvilScreen screen, GuiGraphics graphics) {
        // Only the config option stops the mod entirely. The keybind toggle is checked further down,
        // at the box itself, because it hides the box and deliberately leaves the button highlight
        // drawn - see overlayVisible's field comment for why that is the useful split.
        if (!AnvilSolverConfig.ENABLED.get()) {
            // Nothing is being read this frame, so the cooling history is now about a past the
            // estimator can no longer see. See COOLING's field comment for why silence is not
            // enough; every early return below does the same.
            COOLING.clear();
            return;
        }

        // Read once, here, and passed down. Not a static, and not re-read per line: a theme change
        // mid-frame would otherwise be able to draw half a box in one palette and half in another.
        final OverlayTheme theme = AnvilSolverConfig.THEME.get();

        // Mirrors TFC's own AnvilScreen.renderBg, which reads the forging state as
        // blockEntity.getMainInputForging(). Going through the block entity rather than indexing
        // the menu's slot list keeps us on TFC's source of truth: the previous approach looked up
        // the menu slot at AnvilBlockEntity.SLOT_INPUT_MAIN, which only worked because
        // AnvilContainer.addContainerSlots() happens to add that slot first. Any reordering there
        // would have silently pointed us at the wrong item.
        final AnvilBlockEntity anvil = screen.getMenu().getBlockEntity();
        final Forging forging = anvil.getMainInputForging();

        final AnvilRecipe recipe = forging.getRecipe();
        if (recipe == null) {
            // No recipe means one of two very different things, and they must not be treated alike:
            // an empty anvil (nothing to say - stay silent) or an item sitting there with no plan
            // picked yet. The second case used to render nothing at all, which is indistinguishable
            // from the mod being broken, so it gets an explicit hint instead.
            //
            // No temperature is read on either branch, so the estimator has to be told. Without
            // this, pulling a hot item out and dropping a colder one of the same type back in would
            // fit a slope straight across the gap.
            COOLING.clear();
            if (!inputStack(screen).isEmpty()) {
                renderBox(screen, graphics, List.of(
                    new Line("Select a plan", theme.muted()),
                    new Line("in the anvil", theme.muted())), theme);
            }
            // Returns either way: with no recipe there is no solution and therefore no next press,
            // so the step-button highlight must not be drawn on this path.
            return;
        }

        final int target = forging.target();
        final int work = forging.work();

        // The try covers the TFC -> solver translation and NOTHING else. That translation is the
        // only step here that can legitimately fail on data we do not control: TfcMapping.map()
        // throws IllegalArgumentException for any rule it does not recognize (a future TFC update,
        // or another mod registering custom forge rules with an unexpected serialized name), and
        // map(ForgeStep) switches on an enum, which throws NullPointerException on a null element.
        // TFC's ForgeSteps builds its list with List.of(), which rejects nulls, so that second case
        // is not currently reachable - but "fails soft" has to mean it, so it is caught rather than
        // left as a claim in a comment. This runs every render frame, so anything escaping here
        // would throw out of ScreenEvent.Render.Post for as long as the anvil screen stays open.
        final List<Step> history;
        final List<com.njguidi14.anvilsolver.solver.Rule> rules;
        try {
            history = forging.lastSteps().stream().map(TfcMapping::map).toList();
            rules = recipe.getRules().stream().map(TfcMapping::map).toList();
        } catch (final IllegalArgumentException | NullPointerException e) {
            // Same reasoning as the no-recipe path: no reading is taken on this frame, so the
            // history must not be allowed to survive the gap.
            COOLING.clear();
            renderBox(screen, graphics, List.of(
                new Line("Unsupported", theme.error()),
                new Line("forge data", theme.error())), theme);
            return;
        }

        // Deliberately OUTSIDE the try. The solver is this mod's own code, so an IllegalArgumentException
        // thrown anywhere down that path is a bug here, not unrecognised TFC data - swallowing it and
        // blaming "unsupported forge data" on screen would point debugging straight at a TFC
        // compatibility problem that does not exist. Let it surface.
        final Solution solution = solveCached(target, work, history, rules);

        // Read fresh every frame - never cached, never part of the solve key. Null only when there
        // is genuinely no heat information (no item, an item that cannot be heated, or one whose
        // working temperature is not a usable number), in which case every path below behaves
        // exactly as it did before this feature existed.
        //
        // Deliberately NOT gated on showTemperature. Whether the item is too cold to work is a fact
        // about the world, not a display preference: the mod needs the answer to avoid highlighting
        // a button that provably does nothing. showTemperature decides whether the temperature LINE
        // is drawn, and nothing else.
        final TempReadout temperature = readTemperature(screen, target, work, history);

        // The box obeys the keybind toggle; the highlight does not, and that asymmetry is the whole
        // point of the toggle. Someone who knows the mechanic does not need the press list read out -
        // they need to know which button to hit, and the outline says that on the button itself,
        // taking up no screen space and needing no glance sideways. Hiding the box while keeping the
        // outline turns the mod into a pure "press the lit one" guide.
        //
        // The solve still runs when the box is hidden, because the highlight is derived from it. It
        // is memoised on the anvil's state, so a hidden box costs a map lookup per frame, not a BFS.
        if (overlayVisible) {
            final List<Line> lines = buildLines(
                screen, solution, target, work,
                temperature, AnvilSolverConfig.SHOW_TEMPERATURE.get(), theme);
            renderBox(screen, graphics, lines, theme);
        }

        // Outside the toggle deliberately. Still gated on its own config option, on the solution
        // being feasible, on there being a press left, and on the item being warm enough to register
        // one - all inside renderNextButtonHighlight, unchanged.
        renderNextButtonHighlight(screen, graphics, solution, temperature, theme);
    }

    /**
     * Reads the input item's heat and works out what, if anything, to say about it.
     *
     * <p>Runs regardless of the {@code showTemperature} option. That option controls whether the
     * temperature <em>line</em> is drawn; it must not control whether the mod knows the item is too
     * cold to work, because that fact also decides whether the next-button highlight is a helpful
     * pointer or a lie. Turning the line off and being told to click a dead button was the exact
     * failure this split fixes.
     *
     * <p>Every path that returns null first clears the cooling history. That is not tidiness: the
     * estimator can only notice a <em>change</em> of subject, never a gap, so a hot item removed and
     * replaced within the sample window by a colder one with an identical subject key would have its
     * old samples fitted together with the new ones. See {@link CoolingEstimator#clear()}.
     *
     * @return the readout, or null when there is no usable heat information at all - the slot is
     *         empty, the item is not heatable, or its working temperature is not a usable number
     */
    @Nullable
    private static TempReadout readTemperature(
        AnvilScreen screen, int target, int work, List<Step> history
    ) {
        final ItemStack stack = inputStack(screen);
        if (stack.isEmpty()) {
            COOLING.clear();
            return null;
        }

        // HeatCapability.view is @Nullable and returns null for anything that cannot hold heat,
        // which an anvil can absolutely be holding. This guard sits outside the try/catch further
        // up - that one covers the TFC -> solver mapping and nothing else - so it has to be an
        // explicit check: an NPE here would be thrown out of ScreenEvent.Render.Post on every
        // single frame for as long as the screen stayed open.
        final IHeatView heat = HeatCapability.view(stack);
        if (heat == null) {
            COOLING.clear();
            return null;
        }

        final float temperature = heat.getTemperature();
        final float working = heat.getWorkingTemperature();

        // A working temperature of zero (or worse) is not "workable from ice cold" - it is an item
        // whose heat data this mod cannot say anything sensible about, and every consumer below
        // degenerates on it: the bar fraction divides by it, "Reheat to 0C" is nonsense, and the
        // countdown becomes a countdown to absolute zero ("900C ~225s"). Vanilla TFC derives the
        // working temperature from the melt temperature so this is not expected, but an addon or a
        // datapack recipe is free to produce it, and there is no honest number to show if one does.
        // Dropping the whole temperature module for that item is the only answer that cannot lie:
        // the press plan is unaffected and renders exactly as it did before this feature existed.
        //
        // The isFinite checks belong to the same judgement rather than being extra paranoia. NaN
        // fails every comparison, so a NaN working temperature would slip straight past "<= 0" and
        // then be cast to 0 by Math.ceil, putting "Reheat to 0C" back on screen through the door
        // this check was added to close - and a NaN reading fed to the estimator would poison the
        // regression for the whole sample window.
        if (!Float.isFinite(temperature) || !Float.isFinite(working) || working <= 0) {
            COOLING.clear();
            return null;
        }

        // The subject key is what stops a trend leaking across items. It intentionally uses the
        // Item rather than the whole stack: a stack's own equality includes its heat component,
        // which changes every tick, so keying on the stack would reset the history every frame and
        // no estimate could ever accumulate. Target/work/history are folded in so that swapping in
        // a different item of the same type, or landing a press, also starts a clean history.
        COOLING.observe(
            new HeatSubject(stack.getItem(), target, work, history),
            temperature,
            System.currentTimeMillis()
        );

        // Bar fraction is progress *towards being workable*, capped at full by the drawing code.
        // Above the working temperature the bar is simply full and the countdown carries the detail;
        // below it, the bar is the useful half - it fills visibly as the item reheats. Nothing here
        // models how hot the item could get, because that ceiling has not been verified.
        //
        // The divisor needs no guard: the working <= 0 check above has already returned.
        final float fill = temperature / working;

        String estimate = null;
        if (heat.canWork()) {
            // Only meaningful while the item is still workable: once it is too cold, the number
            // being counted down to is already behind us.
            final OptionalDouble seconds = COOLING.secondsUntil(working);
            if (seconds.isPresent()) {
                // Never "~0s". Sub-second precision is not something this estimate has earned, and
                // a zero would read as "already cold" while the item is still perfectly workable.
                estimate = "~" + Math.max(1L, Math.round(seconds.getAsDouble())) + "s";
            }
        }

        return new TempReadout(
            // Rounded DOWN, not to nearest. The too-cold decision is made by TFC on the unrounded
            // float, so a nearest-rounded display could show a number the decision does not agree
            // with: at working = 600.0 and temperature = 599.7 the overlay used to read "600C",
            // "TOO COLD" and "Reheat to 600C" all at once, which reads as a broken mod. Flooring
            // guarantees the displayed number never claims to have reached a threshold it has not.
            (int) Math.floor(temperature),
            // Rounded UP, for the mirror-image reason: this number is a goal to reach, and rounding
            // 600.4 down to 600 would tell the player to stop short of workable. Floor for what you
            // have, ceiling for what you need - both err on the side of "not there yet".
            (int) Math.ceil(working),
            heat.canWork(),
            fill,
            estimate
        );
    }

    /**
     * The stack in the anvil's main input slot, or {@link ItemStack#EMPTY} if there is none.
     *
     * <p>Single source for both things that need the item: telling "empty anvil" apart from "item
     * present, no plan selected", and the heat lookup. Reading the slot in two places would be two
     * chances to disagree about which slot the item is even in.
     *
     * <p>This deliberately goes through the <em>menu</em> rather than the block entity, which is the
     * opposite of what the forging read above does. {@code AnvilBlockEntity.getMainInputForging()}
     * hands back forging data, not the stack, and there is no accessor on the block entity that is
     * verifiably public across TFC versions for reading the stack itself - guessing at one would
     * risk a compile break. {@code AnvilContainer.addContainerSlots()} adds {@code SLOT_INPUT_MAIN}
     * first, so index 0 is the main input; if TFC ever reordered those slots the worst outcome is a
     * hint or a temperature shown for the wrong slot's item - never a wrong press plan, since the
     * plan itself still comes from the block entity.
     *
     * <p>The size guard covers the theoretical case of the menu having no slots at all, which would
     * otherwise throw out of {@code getSlot} on every render frame.
     */
    private static ItemStack inputStack(AnvilScreen screen) {
        final var menu = screen.getMenu();
        if (menu.slots.size() <= AnvilBlockEntity.SLOT_INPUT_MAIN) {
            return ItemStack.EMPTY;
        }
        return menu.getSlot(AnvilBlockEntity.SLOT_INPUT_MAIN).getItem();
    }

    /**
     * Outlines the anvil's own step button for the next press, so the plan can be executed by
     * clicking the lit button instead of reading a name off the list and finding it by eye.
     *
     * <p>The button rectangle is derived from TFC's {@code ForgeStep} rather than from the
     * {@code AnvilStepButton} widget: that widget keeps its own {@code step} in a private field with
     * no getter, so there is no supported way to ask the screen which button belongs to which step.
     * TFC constructs every step button at {@code guiLeft + step.buttonX(), guiTop + step.buttonY()}
     * with a fixed 16x16 size, which is exactly what is reproduced here.
     *
     * <p>Called only on the feasible-with-presses path - never for an infeasible plan, a finished
     * item, or the unsupported-rule error, since in none of those cases is there a button to press.
     *
     * @param temperature the item's heat readout, or null when the item has none at all; a button is
     *                    not highlighted while the item is too cold to work
     * @param theme       the palette read once for this frame
     */
    private static void renderNextButtonHighlight(
        AnvilScreen screen, GuiGraphics graphics, Solution solution,
        @Nullable TempReadout temperature, OverlayTheme theme
    ) {
        // Every reason not to draw the highlight lives here, so there is one place to check rather
        // than a condition at the call site and another in the body.
        //
        // The temperature clause is not cosmetic: below the working temperature TFC ignores presses
        // entirely, so lighting up a button would be telling the player to do something that
        // provably does nothing. A missing highlight reads as "not yet"; a wrong one reads as "the
        // mod is broken" when clicking it changes nothing.
        //
        // This works because readTemperature no longer consults showTemperature. It used to, which
        // meant a player with showTemperature=false and highlightNextButton=true got a bright
        // highlight on a dead button - the exact failure the paragraph above says is prevented. Two
        // independent config options must not be able to cancel each other's guarantees.
        if (!AnvilSolverConfig.HIGHLIGHT_NEXT_BUTTON.get()
            || !solution.feasible()
            || solution.presses().isEmpty()
            || (temperature != null && !temperature.canWork())) {
            return;
        }

        final ForgeStep next = TfcMapping.toForgeStep(solution.presses().get(0));
        final int left = screen.getGuiLeft() + next.buttonX();
        final int top = screen.getGuiTop() + next.buttonY();

        final PoseStack pose = graphics.pose();
        pose.pushPose();
        // Same z-translate the overlay box uses: this event fires after the screen has drawn, but
        // the highlight sits directly on top of a widget, so lift it clear of the GUI's own layers.
        pose.translate(0, 0, 400);
        // Translucent tint first, then a two-pixel-thick border (outer ring plus an inset ring).
        // The icon underneath stays legible through the tint, and the thick border reads clearly at
        // any GUI scale.
        // highlight(), not next(). This outline is drawn on TFC's own step buttons, and those buttons
        // are themselves red and green - the pushes and the hits - so painting a green next() around
        // a green button made the highlight all but vanish on half of them. highlight() is a separate
        // palette role chosen to contrast with red and green at the same time.
        graphics.fill(left, top, left + ICON_SIZE, top + ICON_SIZE, theme.highlightFill());
        graphics.renderOutline(left, top, ICON_SIZE, ICON_SIZE, theme.highlight());
        graphics.renderOutline(left + 1, top + 1, ICON_SIZE - 2, ICON_SIZE - 2, theme.highlight());
        pose.popPose();
    }

    /** Returns the cached solution if the inputs are unchanged, otherwise recomputes and caches it. */
    private static Solution solveCached(
        int target, int work, List<Step> history, List<com.njguidi14.anvilsolver.solver.Rule> rules
    ) {
        if (cachedSolution != null
            && cachedTarget != null && cachedTarget == target
            && cachedWork != null && cachedWork == work
            && cachedHistory != null && cachedHistory.equals(history)
            && cachedRules != null && cachedRules.equals(rules)) {
            return cachedSolution;
        }

        final Solution solution = ForgeSim.solve(target, work, history, rules);
        cachedTarget = target;
        cachedWork = work;
        cachedHistory = history;
        cachedRules = rules;
        cachedSolution = solution;
        return solution;
    }

    private static void renderBox(
        AnvilScreen screen, GuiGraphics graphics, List<Line> lines, OverlayTheme theme
    ) {
        final Font font = Minecraft.getInstance().font;
        final int y = boxTop(screen);

        // Vertical fit, applied before anything is measured. buildLines already trims the press
        // list to the space available, so normally every line survives; this is the backstop for
        // everything it cannot control - a tiny window, a large configured overlayY, or the fixed
        // message boxes ("Select a plan", the error box) which have no optional lines to drop.
        // Running it here, ahead of the measuring pass, is what guarantees the drawn border and the
        // drawn text agree: both are derived from the same surviving list.
        final List<Line> visible = fitVertically(font, lines, y, screen.height);
        if (visible.isEmpty()) {
            // Not even one line fits above the bottom of the window. Drawing nothing is the honest
            // outcome; drawing the box anyway would put a border and a background off-screen.
            return;
        }

        // Measuring pass. Lines are no longer a uniform height (icon lines are taller than text
        // lines), so both the width and the height come from the same per-line helpers the drawing
        // pass below uses - the border would be misdrawn if the two ever disagreed.
        int width = PADDING * 2;
        int height = PADDING * 2;
        for (final Line line : visible) {
            width = Math.max(width, PADDING * 2 + contentWidth(font, line));
            height += lineHeight(font, line);
        }

        final int x = computeBoxX(screen, width);

        graphics.fill(x, y, x + width, y + height, theme.background());
        graphics.renderOutline(x, y, width, height, theme.border());

        final PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(0, 0, 400); // draw above the GUI background widgets
        int lineTop = y + PADDING;
        for (final Line line : visible) {
            final int rowHeight = lineHeight(font, line);
            // Each line carries its own colour, so there is no index math to keep in sync with
            // however buildLines happened to lay the list out.
            //
            // These three cases must be tested in the same order as in contentWidth, or a line
            // would be measured as one kind and drawn as another - and the box border, sized from
            // the measuring pass, would not match what is inside it.
            if (line.icon() != null) {
                drawIconLine(graphics, font, line, x + PADDING, lineTop, rowHeight);
            } else if (line.bar() != null) {
                drawBarLine(graphics, font, line, x + PADDING, lineTop, rowHeight, theme);
            } else {
                graphics.drawString(font, line.text(), x + PADDING, lineTop, line.color(), false);
            }
            lineTop += rowHeight;
        }
        pose.popPose();
    }

    /**
     * Draws a press line as {@code <index> [icon] <delta>}, e.g. {@code 1. [] -3}.
     *
     * <p>Showing TFC's own icon instead of the step's name is both instantly recognisable (it is
     * the same art on the button that has to be clicked) and far narrower than spelling out
     * "Medium Hit", which keeps the box slim enough to sit beside the GUI at default window size.
     *
     * @param left      left edge of the line's content, padding already applied
     * @param top       top edge of the line's row
     * @param rowHeight the row's full height, used to centre the text against the taller icon
     */
    private static void drawIconLine(
        GuiGraphics graphics, Font font, Line line, int left, int top, int rowHeight
    ) {
        final Step step = line.icon();
        final ForgeStep forgeStep = TfcMapping.toForgeStep(step);
        final String index = line.text();
        final String delta = signed(step.delta());

        // Centre the 9px-tall text and the 16px-tall icon independently within the row so the
        // number, the icon and the delta all sit on the same visual centre line.
        final int textY = top + (rowHeight - font.lineHeight) / 2;
        final int iconY = top + (rowHeight - ICON_SIZE) / 2;
        final int iconX = left + font.width(index) + ICON_GAP;

        graphics.drawString(font, index, left, textY, line.color(), false);
        // Blit form copied verbatim from TFC's AnvilScreen.renderBg, including the iconY() - 16
        // (the enum's iconY is the BOTTOM of the icon in the sheet) and the explicit 256x256
        // texture size. Only the destination x/y differ.
        graphics.blit(
            AnvilScreen.BACKGROUND, iconX, iconY, ICON_SIZE, ICON_SIZE,
            forgeStep.iconX(), forgeStep.iconY() - ICON_SIZE,
            ICON_SIZE, ICON_SIZE, TEXTURE_SIZE, TEXTURE_SIZE
        );
        graphics.drawString(font, delta, iconX + ICON_SIZE + ICON_GAP, textY, line.color(), false);
    }

    /**
     * Draws a bar line as {@code <text> [====  ]} - for the temperature line, {@code 812&deg;C ~14s}
     * followed by a small meter.
     *
     * <p>The bar is drawn as two filled rectangles rather than written into the text with block
     * characters. Block glyphs would have to come from the font's fallback provider, which is a
     * gamble on a published mod - a missing glyph renders as a white box and would make the overlay
     * look broken. Rectangles also make the line's width exactly predictable, which is what lets
     * {@link #contentWidth} agree with this method to the pixel.
     *
     * @param left      left edge of the line's content, padding already applied
     * @param top       top edge of the line's row
     * @param rowHeight the row's full height, used to centre the short bar against the text
     * @param theme     the palette read once for this frame; supplies the bar's track colour
     */
    private static void drawBarLine(
        GuiGraphics graphics, Font font, Line line, int left, int top, int rowHeight,
        OverlayTheme theme
    ) {
        // Text sits at the row's top, exactly as a plain text line does, so the temperature line's
        // baseline matches every other line in the box.
        graphics.drawString(font, line.text(), left, top, line.color(), false);

        final int barLeft = left + font.width(line.text()) + BAR_GAP;
        final int barTop = top + (rowHeight - BAR_HEIGHT) / 2;

        // Clamped because the fraction is temperature over working temperature, which is well over
        // 1 for a freshly heated item. NaN, were the inputs ever to produce it, survives both
        // clamps and rounds to 0 - an empty bar, not an exception and not a bar of random width.
        final float fraction = Math.max(0f, Math.min(1f, line.bar()));
        final int filled = Math.round(BAR_WIDTH * fraction);

        // Track first, then the filled portion over it. Reusing the box's own border colour for the
        // track keeps the empty part of the bar reading as part of the frame rather than as content.
        graphics.fill(barLeft, barTop, barLeft + BAR_WIDTH, barTop + BAR_HEIGHT, theme.border());
        if (filled > 0) {
            graphics.fill(barLeft, barTop, barLeft + filled, barTop + BAR_HEIGHT, line.color());
        }
    }

    /** Height of a single rendered line: icon lines need room for the 16px icon, text lines do not. */
    private static int lineHeight(Font font, Line line) {
        return line.icon() == null ? textLineHeight(font) : ICON_LINE_HEIGHT;
    }

    /** Row height of a plain text line (no icon): the glyphs plus a pixel of leading above and below. */
    private static int textLineHeight(Font font) {
        return font.lineHeight + 2;
    }

    /** Absolute y of the overlay box's top edge. Single source of truth for the box's position. */
    private static int boxTop(AnvilScreen screen) {
        return screen.getGuiTop() + AnvilSolverConfig.OVERLAY_Y.get();
    }

    /**
     * Vertical space in pixels available to the box's <em>content</em> - padding on both sides
     * already deducted - between the box's top edge and the bottom of the window.
     *
     * <p>May be zero or negative when the box starts at or past the bottom of the window (a large
     * {@code overlayY}, or a very short window); callers must not assume it is positive.
     */
    private static int availableContentHeight(AnvilScreen screen) {
        return (screen.height - SCREEN_MARGIN) - boxTop(screen) - PADDING * 2;
    }

    /**
     * Returns the lines that fit between {@code top} and the bottom of the window, in order,
     * dropping whatever does not fit and sacrificing droppable lines before essential ones.
     *
     * <p>Uses exactly the same bound as {@link #availableContentHeight}: lines are kept while the
     * total content height stays within {@code (screenHeight - SCREEN_MARGIN) - top - 2 * PADDING}.
     * {@code buildLines} sizes the press list against that same number, so in normal operation this
     * returns the whole list unchanged and the "+N more" count it computed remains accurate.
     *
     * <p><b>Why this is not simply a prefix.</b> It used to be, and that quietly broke the too-cold
     * state. Those lines are {@code [Target/Work, temp, "N presses", "TOO COLD", "Reheat to N"]},
     * and trimming purely from the end takes the explanation away first - leaving a box that shows a
     * press count and then nothing at all, which reads as the mod having given up rather than as the
     * item being cold. So the first pass removes lines that are not marked essential, last one
     * first, until the rest fits; the explanation is only ever lost if a box of just those two lines
     * still would not fit, and even then the truncating backstop keeps "TOO COLD" itself for as long
     * as one line fits at all.
     *
     * <p>On every other path nothing is marked essential, so "remove the last droppable line" is
     * simply "remove the last line" and the behaviour is identical to the prefix version it replaced.
     */
    private static List<Line> fitVertically(Font font, List<Line> lines, int top, int screenHeight) {
        // Content height available, padding on both sides already deducted. Can be zero or negative
        // on a very short window or a large overlayY, in which case both loops below yield nothing.
        final int budget = (screenHeight - SCREEN_MARGIN) - top - PADDING * 2;

        // Pass 1: give up droppable lines, from the bottom up, while the box is too tall. Removing
        // from the end first keeps the most important surviving content at the top of the box, where
        // the reading order already puts it.
        final List<Line> kept = new ArrayList<>(lines);
        while (contentHeight(font, kept) > budget) {
            final int droppable = lastDroppableIndex(kept);
            if (droppable < 0) {
                // Only essential lines are left and they still overflow. Pass 2 handles it.
                break;
            }
            kept.remove(droppable);
        }

        // Pass 2: the backstop. Truncates from the end, exactly as this method always did, for the
        // case where even the essential lines cannot all fit.
        final List<Line> visible = new ArrayList<>(kept.size());
        int height = 0;
        for (final Line line : kept) {
            final int grown = height + lineHeight(font, line);
            if (grown > budget) {
                break;
            }
            height = grown;
            visible.add(line);
        }
        return visible;
    }

    /** Total drawn height of a list of lines, excluding the box's own padding. */
    private static int contentHeight(Font font, List<Line> lines) {
        int height = 0;
        for (final Line line : lines) {
            height += lineHeight(font, line);
        }
        return height;
    }

    /** Index of the last line that may be dropped to make room, or -1 if every line is essential. */
    private static int lastDroppableIndex(List<Line> lines) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (!lines.get(i).essential()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Width of a line's drawn content, excluding the box padding.
     *
     * <p>Must match {@link #drawIconLine} and {@link #drawBarLine} exactly, and must test the three
     * line kinds in the same order the drawing pass does. The box border is sized from this; if the
     * two ever disagree, the border is drawn around the wrong rectangle.
     */
    private static int contentWidth(Font font, Line line) {
        if (line.icon() != null) {
            // index text + gap + icon + gap + delta text
            return font.width(line.text())
                + ICON_GAP + ICON_SIZE + ICON_GAP
                + font.width(signed(line.icon().delta()));
        }
        if (line.bar() != null) {
            // text + gap + bar (the bar is a fixed width whatever its fill fraction)
            return font.width(line.text()) + BAR_GAP + BAR_WIDTH;
        }
        return font.width(line.text());
    }

    /**
     * Returns the overlay box's left edge, in absolute screen coordinates.
     *
     * <p>The box sits immediately outside the anvil GUI panel's right edge. {@code getXSize()} is
     * NeoForge's public accessor for {@code AbstractContainerScreen}'s {@code imageWidth} - it
     * comes from the same patch that supplies the {@code getGuiLeft()}/{@code getGuiTop()} already
     * used here. The literal {@code 176} is an exact substitute if it is ever needed: TFC's
     * {@code AnvilScreen} constructor only adjusts {@code imageHeight} ({@code += 41}), leaving the
     * width at the vanilla container-screen default.
     *
     * <p>{@code width} is measured from the longest text line, so on a small window (or at a high
     * GUI scale) a wide box can fail to fit beside the GUI. Both sides are measured and the box
     * goes wherever it actually fits, preferring the right.
     */
    private static int computeBoxX(AnvilScreen screen, int width) {
        final int gap = AnvilSolverConfig.OVERLAY_GAP.get();
        final int guiLeft = screen.getGuiLeft();

        // Free space outside each edge of the GUI panel, with the gap already taken out.
        final int rightEdge = guiLeft + screen.getXSize() + gap;
        final int rightSpace = screen.width - rightEdge;
        final int leftSpace = guiLeft - gap;

        final boolean fitsRight = width <= rightSpace;
        final boolean fitsLeft = width <= leftSpace;
        // Right is the requested placement, so take it whenever the box fits there. Otherwise use
        // the left if the box fits there, and if it fits on neither, fall back to the roomier side
        // so the overrun is as small as possible.
        final boolean useRight = fitsRight || (!fitsLeft && rightSpace >= leftSpace);

        final int x = useRight ? rightEdge : guiLeft - width - gap;

        // A box wider than the entire window fits on neither side, and a large negative gap can
        // push it off the left edge. Pinning the left edge at 0 keeps the start of every line
        // readable, which matters more than any overrun on the right.
        return Math.max(0, x);
    }

    /**
     * Builds the overlay's text, each line already carrying the colour it should be drawn in.
     *
     * <p>Every line here is written to fit the box's horizontal budget. That budget is genuinely
     * tight: at 854x480 with GUI scale 2 the logical screen is 427x240 and the anvil panel is 176
     * wide, leaving roughly 122px beside it. {@link #computeBoxX} only chooses which side the box
     * goes on and pins the left edge at 0 - nothing clips or wraps a line that is simply too wide,
     * so an over-long line runs off the right of the screen.
     *
     * <p>There is almost no headroom left. The widest line the overlay can currently produce is the
     * header, not anything optional: {@code "Target 150  Work 150"} measures about 120px including
     * padding, roughly 2px inside the ~122px available. Any new line, or any widening of an existing
     * one, has to be measured against that ~122px - not against the header's own width, which is
     * already at the limit.
     *
     * @param temperature            the item's heat readout, or null when the item has no usable
     *                               heat information at all; independent of the config option
     * @param temperatureLineEnabled the {@code showTemperature} option, read once by the caller. It
     *                               governs the temperature line and only that line - the too-cold
     *                               warning below is gated on the readout, never on this
     * @param theme                  the palette read once for this frame
     */
    private static List<Line> buildLines(
        AnvilScreen screen, Solution solution, int target, int work,
        @Nullable TempReadout temperature, boolean temperatureLineEnabled, OverlayTheme theme
    ) {
        final List<Line> lines = new ArrayList<>();
        if (!solution.feasible()) {
            // Three short lines rather than two long ones. The previous wording opened with
            // "A recent press already" (~130px with padding), which overran the space beside the
            // GUI at default window size and had its tail drawn off-screen.
            lines.add(new Line("No path from here", theme.error()));
            lines.add(new Line("A past press", theme.error()));
            lines.add(new Line("broke a rule.", theme.error()));
            return lines;
        }

        if (solution.presses().isEmpty()) {
            // Was "Done - Perfectly Forged!" (~137px with padding), which had the same overrun.
            lines.add(new Line("Perfectly forged!", theme.next()));
            return lines;
        }

        // The header is split over two lines on purpose. As a single "Target N  Work N  (K
        // presses)" line it was far longer than any press entry, so it alone set the box width -
        // wide enough that on a small window the box no longer fit in the space beside the GUI and
        // got mirrored to the left. Split, the longest line is a press entry instead.
        // NOTE: exactly HEADER_LINES lines are added below - this one and the press count - with the
        // optional temperature line between them. pressRowsToShow subtracts a text-line height for
        // each of them when working out how much vertical room the press rows have left, so any
        // line added or removed here has to be reflected in the headerLines count further down.
        lines.add(new Line("Target " + target + "  Work " + work, theme.muted()));

        // Tracked rather than recomputed: the null check has to sit right at the dereference below,
        // and the vertical-fit maths further down needs the same answer. One variable, set at the
        // one place that actually emits the line, is what keeps the two from drifting apart.
        boolean temperatureLineDrawn = false;
        if (temperature != null && temperatureLineEnabled) {
            // One line, high up, because it can invalidate everything under it. Width at the worst
            // realistic case: "1493" (24px) + degree sign and C (11px) + space (4px) + "~299s"
            // (30px) + gap (3px) + bar (24px) = 96px, plus 10px of padding = 106px. Comfortably
            // inside the ~122px available, but NOT the widest line in the box - the header above is,
            // at roughly 120px. See this method's javadoc before widening anything.
            final String text = temperature.estimate() == null
                ? temperature.temperature() + DEGREES_C
                : temperature.temperature() + DEGREES_C + " " + temperature.estimate();
            lines.add(Line.withBar(
                text, temperature.canWork() ? theme.next() : theme.error(), temperature.fill()));
            temperatureLineDrawn = true;
        }

        final int pressCount = solution.pressCount();
        lines.add(new Line(pressCount + (pressCount == 1 ? " press" : " presses"), theme.muted()));

        if (temperature != null && !temperature.canWork()) {
            // Below the working temperature TFC drops presses on the floor. Listing a plan here
            // would be actively misleading: the player would click the first step, see nothing
            // happen, and conclude the solver is wrong. The plan itself is still correct - it is
            // only unusable until the item is hot again - so the press count above stays and only
            // the step list is replaced.
            //
            // Gated on the readout, NOT on temperatureLineEnabled: turning the temperature line off
            // is a display preference and must not turn a plan that cannot be executed back into one
            // that looks like it can. The same reasoning governs the button highlight.
            //
            // Both lines are marked essential. They are the only explanation for why no plan is
            // shown, and they sit at the very bottom of the list, so a short window used to trim
            // them away and leave a box that simply looked broken. fitVertically gives up the press
            // count, the temperature line and even the header before it gives up these.
            lines.add(Line.essential("TOO COLD", theme.error()));
            lines.add(Line.essential(
                "Reheat to " + temperature.workingTemperature() + DEGREES_C, theme.error()));
            return lines;
        }

        // The temperature line is a text row like the headers, so it eats into the same vertical
        // budget the press rows are measured against. Counting it here, at the one place that knows
        // whether it was emitted, is what keeps the fit maths honest. Note this counts the line
        // being DRAWN, not the readout existing - with showTemperature off there is no row to pay
        // for, even though the readout above is still very much in use.
        final int headerLines = HEADER_LINES + (temperatureLineDrawn ? 1 : 0);
        final int shown = pressRowsToShow(screen, solution.presses().size(), headerLines);
        for (int i = 0; i < shown; i++) {
            final Step step = solution.presses().get(i);
            // The first press is the action to perform right now, so it gets the highlight.
            // Only the index is text here - drawIconLine appends TFC's icon and the signed delta.
            lines.add(new Line((i + 1) + ".", i == 0 ? theme.next() : theme.text(), step));
        }
        final int remaining = solution.presses().size() - shown;
        if (remaining > 0) {
            lines.add(new Line("+" + remaining + " more", theme.text()));
        }
        return lines;
    }

    /**
     * How many press rows to actually draw: the configured cap, further reduced to whatever fits
     * between the top of the box and the bottom of the window.
     *
     * <p>{@code maxPresses} is the user-facing cap and stays exactly that. This is a second,
     * automatic constraint on top of it, because the cap alone cannot know how much screen there
     * is. Press rows are {@value #ICON_LINE_HEIGHT}px tall since the step icons went in - nearly
     * double a text line - so a plan listed at the config's maximum of 30 presses wants roughly
     * 570px of box, well past the bottom of a 240px logical screen. Before this, those rows were
     * simply drawn off the bottom edge with nothing to indicate it.
     *
     * <p>The {@code "+N more"} line is paid for out of the same budget whenever anything is being
     * left out, so appending it can never be what pushes the box over the edge.
     *
     * @param pressCount  the full length of the plan
     * @param headerLines how many plain text lines were emitted above the press list: always
     *                    {@link #HEADER_LINES}, plus one when the temperature line is actually drawn
     * @return the number of leading presses to list; may be 0, in which case only {@code "+N more"}
     *         reports the omission
     */
    private static int pressRowsToShow(AnvilScreen screen, int pressCount, int headerLines) {
        final int textLine = textLineHeight(Minecraft.getInstance().font);
        // Vertical room left for press rows once the header lines have taken their share.
        final int room = availableContentHeight(screen) - textLine * headerLines;

        final int capped = Math.min(pressCount, AnvilSolverConfig.MAX_PRESSES.get());
        final int fitsWithoutMoreLine = Math.max(0, room / ICON_LINE_HEIGHT);
        if (capped == pressCount && fitsWithoutMoreLine >= pressCount) {
            // The whole plan is being listed, so there is no "+N more" line to make room for.
            return pressCount;
        }
        // Something is being left out either way, so reserve a text line for "+N more" first.
        // Integer division truncates toward zero, so a negative room yields 0 or less here, which
        // the clamp turns into "no press rows at all".
        return Math.max(0, Math.min(capped, (room - textLine) / ICON_LINE_HEIGHT));
    }

    private static String signed(int value) {
        return value > 0 ? "+" + value : String.valueOf(value);
    }

    /**
     * One rendered line of the overlay together with its colour and, for press lines, the step
     * whose icon it shows.
     *
     * <p>Pairing text and colour at construction replaces the old index-based colour lookup, which
     * had to know how many header lines {@code buildLines} emitted and silently mis-coloured the
     * wrong line whenever that layout changed.
     *
     * <p>On an icon line {@code text} is only the index label ({@code "1."}); the icon and the
     * signed work delta are both rendered from {@code icon}, so the number shown can never drift
     * from the step it belongs to. Every other line (headers, the finished-item line, the infeasible
     * lines, "+N more", the error lines) has a null {@code icon} and draws {@code text} as-is.
     *
     * <p>On a bar line {@code text} is drawn as-is and a small meter follows it, filled to
     * {@code bar}. At most one of {@code icon} and {@code bar} is ever set; the drawing and
     * measuring passes both test {@code icon} first, so a line with both would draw as an icon line
     * and its bar would be silently ignored rather than misdrawn.
     *
     * <p>{@code essential} is what {@link #fitVertically} consults when the box is taller than the
     * window. Almost every line is droppable, which is the historical behaviour - the list was
     * simply truncated from the bottom. A line is only marked essential when losing it would leave
     * the overlay actively misleading rather than merely shorter, which today means the too-cold
     * explanation and nothing else.
     *
     * @param text      the text to draw, or just the index label on an icon line
     * @param color     ARGB colour to draw it in, already resolved from the active theme
     * @param icon      the step whose 16x16 TFC icon and delta to draw, or null for a plain text line
     * @param bar       fill fraction of the trailing meter, clamped to 0..1 when drawn, or null for a
     *                  line with no meter
     * @param essential true if this line must survive vertical trimming for as long as any line does
     */
    private record Line(
        String text, int color, @Nullable Step icon, @Nullable Float bar, boolean essential
    ) {

        /** A plain text line with no icon and no bar. Droppable, like nearly everything. */
        Line(String text, int color) {
            this(text, color, null, null, false);
        }

        /** A press line: an index label, the step's icon, and its work delta. */
        Line(String text, int color, Step icon) {
            this(text, color, icon, null, false);
        }

        /** A text line with a small meter drawn after it. */
        static Line withBar(String text, int color, float fill) {
            return new Line(text, color, null, fill, false);
        }

        /**
         * A plain text line that vertical trimming must sacrifice droppable lines to keep.
         *
         * <p>Shares its name with the generated {@code essential()} accessor on purpose, so both
         * read naturally where they are used: {@code Line.essential("TOO COLD", ...)} at
         * construction and {@code line.essential()} at the test. The signatures differ, so this is
         * ordinary overloading - the same shape as {@code Integer.toString()} against the static
         * {@code Integer.toString(int)}.
         */
        static Line essential(String text, int color) {
            return new Line(text, color, null, null, true);
        }
    }

    /**
     * What to display about the input item's heat, already reduced to plain values.
     *
     * <p>Everything TFC-specific is resolved in {@link #readTemperature}, so the layout code below
     * it never touches a heat API and cannot reintroduce a null dereference. A null
     * {@code TempReadout} - not a zeroed one - is how "there is no heat information at all" is
     * expressed, which is what keeps a non-heatable item rendering exactly as it did before this
     * feature existed rather than claiming a confident "0&deg;C".
     *
     * <p>Its existence is independent of the {@code showTemperature} option: a readout is produced
     * whenever the item has usable heat data, because {@code canWork} is needed even when the
     * temperature line is hidden.
     *
     * <p>{@code temperature} is floored and {@code workingTemperature} is ceilinged, deliberately in
     * opposite directions, so neither displayed number can ever suggest a threshold has been met
     * that {@code canWork} says has not.
     *
     * @param temperature        current temperature, rounded DOWN to a whole degree for display
     * @param workingTemperature temperature presses start registering at, rounded UP
     * @param canWork            whether presses register right now; TFC's own answer, on the
     *                           unrounded values
     * @param fill               temperature as a fraction of the working temperature; may exceed 1
     * @param estimate           short "~Ns" countdown to going cold, or null when the observed data
     *                           does not justify one
     */
    private record TempReadout(
        int temperature, int workingTemperature, boolean canWork, float fill, @Nullable String estimate
    ) {
    }

    /**
     * Identifies which item the cooling history belongs to.
     *
     * <p>Two different things must both invalidate a trend: swapping the item, and changing its
     * forging state. Item type covers the first as far as it can be covered cheaply; target, work
     * and history cover the second and, in practice, also catch a swap between two items of the
     * same type that happen to be at different points in their plan.
     *
     * @param item    the item type in the input slot
     * @param target  the recipe's target work value
     * @param work    the item's current work value
     * @param history the presses already made
     */
    private record HeatSubject(Item item, int target, int work, List<Step> history) {
    }
}
