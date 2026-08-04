package com.njguidi14.anvilsolver.client;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.njguidi14.anvilsolver.config.AnvilSolverConfig;
import com.njguidi14.anvilsolver.solver.ForgeSim;
import com.njguidi14.anvilsolver.solver.Solution;
import com.njguidi14.anvilsolver.solver.Step;
import net.dries007.tfc.client.screen.AnvilScreen;
import net.dries007.tfc.common.blockentities.AnvilBlockEntity;
import net.dries007.tfc.common.component.forge.ForgeStep;
import net.dries007.tfc.common.component.forge.Forging;
import net.dries007.tfc.common.recipes.AnvilRecipe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.jetbrains.annotations.Nullable;

/**
 * Reads the live forging state from the anvil block entity and renders the
 * solver's press plan in a box alongside the TFC anvil screen.
 */
public final class AnvilSolverClient {

    private static final int COLOR_BG = 0xD00E1912;
    private static final int COLOR_BORDER = 0xFF274031;
    private static final int COLOR_MUTED = 0xFF7F9A86;
    private static final int COLOR_TEXT = 0xFFB9CDBC;
    private static final int COLOR_NEXT = 0xFF4CAF6A;
    private static final int COLOR_ERR = 0xFFF2B8B3;
    /** Same green as {@link #COLOR_NEXT} at ~25% alpha - tints the highlighted button without hiding its icon. */
    private static final int COLOR_NEXT_FILL = 0x404CAF6A;

    /** Size of a TFC step button and of a step icon; both are 16x16. */
    private static final int ICON_SIZE = 16;
    /** Row height for a press line, so the 16px icon has a pixel of breathing room above and below. */
    private static final int ICON_LINE_HEIGHT = 18;
    /** Horizontal gap on each side of an inline icon. */
    private static final int ICON_GAP = 1;
    /** Width and height of TFC's anvil GUI texture sheet, as used by its own blit calls. */
    private static final int TEXTURE_SIZE = 256;

    /** Inner margin between the box border and its content, on every side. */
    private static final int PADDING = 5;
    /**
     * Pixels of clearance kept between the bottom of the box and the bottom of the window.
     * Small on purpose - this is breathing room, not a layout constraint.
     */
    private static final int SCREEN_MARGIN = 2;
    /**
     * Number of plain text lines {@link #buildLines} puts above the press list ("Target N Work N"
     * and "K presses"). Named so the vertical-fit maths and the code that emits those lines cannot
     * drift apart unnoticed.
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
     * Runtime visibility, flipped by the toggle keybind. Session-only on purpose: it is deliberately
     * NOT written back to {@link AnvilSolverConfig#ENABLED}, so hiding the overlay for one forging
     * session never silently rewrites the on-disk config. The config option remains the persistent
     * "off" switch; this is the temporary one. Defaults to visible.
     */
    private static boolean overlayVisible = true;

    private AnvilSolverClient() {
    }

    /** Flips the session-only visibility of the overlay. Called from the keybind handler. */
    public static void toggleOverlay() {
        overlayVisible = !overlayVisible;
    }

    public static void render(AnvilScreen screen, GuiGraphics graphics) {
        // Both switches must be on: the config value is the persistent setting, overlayVisible is
        // the per-session keybind toggle.
        if (!AnvilSolverConfig.ENABLED.get() || !overlayVisible) {
            return;
        }

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
            if (hasInputItem(screen)) {
                renderBox(screen, graphics, List.of(
                    new Line("Select a plan", COLOR_MUTED),
                    new Line("in the anvil", COLOR_MUTED)));
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
            renderBox(screen, graphics, List.of(
                new Line("Unsupported", COLOR_ERR),
                new Line("forge data", COLOR_ERR)));
            return;
        }

        // Deliberately OUTSIDE the try. The solver is this mod's own code, so an IllegalArgumentException
        // thrown anywhere down that path is a bug here, not unrecognised TFC data - swallowing it and
        // blaming "unsupported forge data" on screen would point debugging straight at a TFC
        // compatibility problem that does not exist. Let it surface.
        final Solution solution = solveCached(target, work, history, rules);

        renderBox(screen, graphics, buildLines(screen, solution, target, work));
        renderNextButtonHighlight(screen, graphics, solution);
    }

    /**
     * Whether there is an item in the anvil's main input slot.
     *
     * <p>Only used to tell "empty anvil" apart from "item present, no plan selected", so that the
     * hint is shown for the second case and nothing at all for the first.
     *
     * <p>This deliberately goes through the <em>menu</em> rather than the block entity, which is the
     * opposite of what the forging read above does. {@code AnvilBlockEntity.getMainInputForging()}
     * hands back forging data, not the stack, and there is no accessor on the block entity that is
     * verifiably public across TFC versions for reading the stack itself - guessing at one would
     * risk a compile break for a cosmetic check. The menu route is safe here because it is used
     * <em>only</em> for emptiness: {@code AnvilContainer.addContainerSlots()} adds
     * {@code SLOT_INPUT_MAIN} first, so index 0 is the main input, and even if TFC ever reordered
     * those slots the worst outcome is a hint shown or hidden a beat early - never a wrong plan,
     * since the plan itself still comes from the block entity.
     *
     * <p>The size guard covers the theoretical case of the menu having no slots at all, which would
     * otherwise throw out of {@code getSlot} on every render frame.
     */
    private static boolean hasInputItem(AnvilScreen screen) {
        final var menu = screen.getMenu();
        if (menu.slots.size() <= AnvilBlockEntity.SLOT_INPUT_MAIN) {
            return false;
        }
        return !menu.getSlot(AnvilBlockEntity.SLOT_INPUT_MAIN).getItem().isEmpty();
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
     */
    private static void renderNextButtonHighlight(
        AnvilScreen screen, GuiGraphics graphics, Solution solution
    ) {
        if (!AnvilSolverConfig.HIGHLIGHT_NEXT_BUTTON.get()
            || !solution.feasible()
            || solution.presses().isEmpty()) {
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
        graphics.fill(left, top, left + ICON_SIZE, top + ICON_SIZE, COLOR_NEXT_FILL);
        graphics.renderOutline(left, top, ICON_SIZE, ICON_SIZE, COLOR_NEXT);
        graphics.renderOutline(left + 1, top + 1, ICON_SIZE - 2, ICON_SIZE - 2, COLOR_NEXT);
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

    private static void renderBox(AnvilScreen screen, GuiGraphics graphics, List<Line> lines) {
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

        graphics.fill(x, y, x + width, y + height, COLOR_BG);
        graphics.renderOutline(x, y, width, height, COLOR_BORDER);

        final PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(0, 0, 400); // draw above the GUI background widgets
        int lineTop = y + PADDING;
        for (final Line line : visible) {
            final int rowHeight = lineHeight(font, line);
            // Each line carries its own colour, so there is no index math to keep in sync with
            // however buildLines happened to lay the list out.
            if (line.icon() == null) {
                graphics.drawString(font, line.text(), x + PADDING, lineTop, line.color(), false);
            } else {
                drawIconLine(graphics, font, line, x + PADDING, lineTop, rowHeight);
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
     * Returns the prefix of {@code lines} that fits between {@code top} and the bottom of the
     * window, dropping the rest.
     *
     * <p>Uses exactly the same bound as {@link #availableContentHeight}: a line is kept while the
     * running content height stays within {@code (screenHeight - SCREEN_MARGIN) - top - 2 * PADDING}.
     * {@code buildLines} sizes the press list against that same number, so in normal operation this
     * returns the whole list unchanged and the "+N more" count it computed remains accurate.
     */
    private static List<Line> fitVertically(Font font, List<Line> lines, int top, int screenHeight) {
        final int bottomLimit = screenHeight - SCREEN_MARGIN;
        final List<Line> visible = new ArrayList<>(lines.size());
        int height = PADDING * 2;
        for (final Line line : lines) {
            final int grown = height + lineHeight(font, line);
            if (top + grown > bottomLimit) {
                break;
            }
            height = grown;
            visible.add(line);
        }
        return visible;
    }

    /** Width of a line's drawn content, excluding the box padding. Must match {@link #drawIconLine}. */
    private static int contentWidth(Font font, Line line) {
        if (line.icon() == null) {
            return font.width(line.text());
        }
        // index text + gap + icon + gap + delta text
        return font.width(line.text())
            + ICON_GAP + ICON_SIZE + ICON_GAP
            + font.width(signed(line.icon().delta()));
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
     * so an over-long line runs off the right of the screen. Keeping every line under about twenty
     * characters (~110px including padding) is what keeps that from happening.
     */
    private static List<Line> buildLines(AnvilScreen screen, Solution solution, int target, int work) {
        final List<Line> lines = new ArrayList<>();
        if (!solution.feasible()) {
            // Three short lines rather than two long ones. The previous wording opened with
            // "A recent press already" (~130px with padding), which overran the space beside the
            // GUI at default window size and had its tail drawn off-screen.
            lines.add(new Line("No path from here", COLOR_ERR));
            lines.add(new Line("A past press", COLOR_ERR));
            lines.add(new Line("broke a rule.", COLOR_ERR));
            return lines;
        }

        if (solution.presses().isEmpty()) {
            // Was "Done - Perfectly Forged!" (~137px with padding), which had the same overrun.
            lines.add(new Line("Perfectly forged!", COLOR_NEXT));
            return lines;
        }

        // The header is split over two lines on purpose. As a single "Target N  Work N  (K
        // presses)" line it was far longer than any press entry, so it alone set the box width -
        // wide enough that on a small window the box no longer fit in the space beside the GUI and
        // got mirrored to the left. Split, the longest line is a press entry instead.
        // NOTE: exactly HEADER_LINES lines are added here; pressRowsToShow subtracts that many
        // text-line heights when working out how much vertical room the press rows have left.
        lines.add(new Line("Target " + target + "  Work " + work, COLOR_MUTED));
        final int pressCount = solution.pressCount();
        lines.add(new Line(pressCount + (pressCount == 1 ? " press" : " presses"), COLOR_MUTED));

        final int shown = pressRowsToShow(screen, solution.presses().size());
        for (int i = 0; i < shown; i++) {
            final Step step = solution.presses().get(i);
            // The first press is the action to perform right now, so it gets the highlight.
            // Only the index is text here - drawIconLine appends TFC's icon and the signed delta.
            lines.add(new Line((i + 1) + ".", i == 0 ? COLOR_NEXT : COLOR_TEXT, step));
        }
        final int remaining = solution.presses().size() - shown;
        if (remaining > 0) {
            lines.add(new Line("+" + remaining + " more", COLOR_TEXT));
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
     * @param pressCount the full length of the plan
     * @return the number of leading presses to list; may be 0, in which case only {@code "+N more"}
     *         reports the omission
     */
    private static int pressRowsToShow(AnvilScreen screen, int pressCount) {
        final int textLine = textLineHeight(Minecraft.getInstance().font);
        // Vertical room left for press rows once the header lines have taken their share.
        final int room = availableContentHeight(screen) - textLine * HEADER_LINES;

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
     * @param text  the text to draw, or just the index label on an icon line
     * @param color ARGB colour to draw it in
     * @param icon  the step whose 16x16 TFC icon and delta to draw, or null for a plain text line
     */
    private record Line(String text, int color, @Nullable Step icon) {

        /** A plain text line with no icon. */
        Line(String text, int color) {
            this(text, color, null);
        }
    }
}
