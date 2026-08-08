package com.njguidi14.anvilsolver.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
 * plus, when the mix is not yet a valid alloy, how much of what to add - in a box alongside TFC's
 * crucible screen.
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
     * Two-space indent used on the continuation line under an out-of-range metal, so "add 112 mB"
     * visibly belongs to the metal named above it rather than reading as a separate metal.
     */
    private static final String INDENT = "  ";

    /**
     * How close the target fraction may get to 1.0 before {@link #amountToAdd} refuses to answer.
     *
     * <p>The formula divides by {@code 1 - f}. At {@code f == 1} that is a division by zero, and
     * past it the result flips sign and would print a confident negative "add" figure. A recipe
     * range of {@code 1.0 .. 1.0} - a single-component "alloy" - produces exactly that, and datapacks
     * are free to define one. Anything inside this window is treated as unanswerable rather than
     * answered wrongly.
     */
    private static final double TARGET_EPSILON = 1.0e-6;

    private CrucibleCalculator() {
    }

    /**
     * Draws the overlay for one frame. Called from {@code ClientEvents}'s
     * {@code ScreenEvent.Render.Post} handler.
     *
     * <p>Every early return here is a "render nothing at all" case, which is the correct answer for
     * an empty crucible: an empty box beside the GUI would look like a bug.
     *
     * <p>Nothing on this path may throw. It runs once per frame for as long as the crucible screen
     * is open, so a single escaping exception is not one crash - it is a crash on every frame,
     * forever. That is why the nullable TFC accessors below are checked explicitly rather than
     * trusted, even where the current TFC version cannot actually return null.
     */
    public static void render(CrucibleScreen screen, GuiGraphics graphics) {
        if (!AnvilSolverConfig.SHOW_ALLOY_CALCULATOR.get()) {
            return;
        }

        // BlockEntityContainer.getBlockEntity() is public, which makes this the supported way in -
        // the same path AnvilSolverClient uses for the anvil's forging data. Going through the menu's
        // slot list instead would depend on TFC's slot ordering, which is not a promise.
        final CrucibleBlockEntity crucible = screen.getMenu().getBlockEntity();
        if (crucible == null) {
            return;
        }

        final FluidAlloy alloy = crucible.getAlloy();
        if (alloy == null || alloy.isEmpty()) {
            return;
        }

        final Object2DoubleMap<Fluid> content = alloy.getContent();
        final int total = alloy.getAmount();
        // getContent() holds RAW AMOUNTS, not percentages, so every fraction below is amount/total.
        // A non-positive total with a non-empty alloy should be impossible, but it is the divisor for
        // every number this class prints, so it is checked rather than assumed.
        if (content == null || content.isEmpty() || total <= 0) {
            return;
        }

        // Read once, here, and passed down - never a static, and never re-read per line. Same rule as
        // the anvil overlay: a theme change mid-frame must not be able to draw half a box in one
        // palette and half in another.
        final OverlayTheme theme = AnvilSolverConfig.THEME.get();

        final List<Line> lines = buildLines(crucible, content, total, theme);
        if (lines.isEmpty()) {
            return;
        }
        drawBox(screen, graphics, lines, theme);
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
     * @param total the alloy's total amount in mB; guaranteed positive by the caller
     */
    private static List<Line> buildLines(
        CrucibleBlockEntity crucible, Object2DoubleMap<Fluid> content, int total, OverlayTheme theme
    ) {
        final List<Line> lines = new ArrayList<>();
        lines.add(new Line("Crucible  " + total + " mB", theme.muted()));

        // Sorted largest-first, then by name. getContent() is a hash map, so its iteration order is
        // an implementation detail; sorting is what stops the metal list reshuffling itself between
        // frames or between game sessions.
        final List<Fluid> present = new ArrayList<>();
        for (final Fluid fluid : content.keySet()) {
            if (fluid != null) {
                present.add(fluid);
            }
        }
        // Written as one explicit comparator rather than
        // Comparator.comparingDouble(...).reversed().thenComparing(...): thenComparing is overloaded
        // on both Comparator and Function, and resolving that against a method reference is a
        // well-known source of "reference to thenComparing is ambiguous". Two lines of Double.compare
        // are not worth the risk on a build that has to stay green.
        present.sort((left, right) -> {
            // Descending: right against left, not left against right.
            final int byAmount = Double.compare(content.getDouble(right), content.getDouble(left));
            return byAmount != 0 ? byAmount : fluidName(left).compareTo(fluidName(right));
        });

        for (final Fluid fluid : present) {
            lines.add(new Line(
                fluidName(fluid) + "  " + percent(content.getDouble(fluid) / total),
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

        appendCalculator(lines, content, total, theme);
        return lines;
    }

    /**
     * Appends the actual calculator: the nearest alloy still reachable by adding metal, each of its
     * metals' status, and how much to add to the ones that are short.
     *
     * <p><b>The interaction caveat, stated plainly.</b> Each "add" figure is computed as if that
     * metal were the only thing added. Add two of them at once and neither lands where it was
     * promised, because each addition raises the total and so dilutes the other. This is survivable
     * in practice for one specific reason: TFC's alloy ranges are symmetric about a nominal
     * composition, so the midpoints targeted below sum to 1.0 (bronze is 0.88-0.92 copper and
     * 0.08-0.12 tin, midpoints 0.90 and 0.10). That makes "add the one you are told to, let the
     * overlay re-read, add the next" a converging loop rather than a diverging one - and the overlay
     * does re-read, every frame, from live block entity state. The {@code "Add one, recheck"} hint
     * below says so whenever more than one metal is short.
     */
    private static void appendCalculator(
        List<Line> lines, Object2DoubleMap<Fluid> content, int total, OverlayTheme theme
    ) {
        final AlloyRecipe candidate = findBestCandidate(content, total);
        if (candidate == null) {
            // Either no recipe contains every metal already in the pot - the mix is a dead end that
            // only emptying the crucible can fix - or the recipe list could not be read at all.
            lines.add(new Line("No alloy reachable", theme.muted()));
            lines.add(new Line("by adding metal", theme.muted()));
            return;
        }

        lines.add(new Line("Closest: " + fluidName(candidate.result()), theme.muted()));

        int shortCount = 0;
        for (final AlloyRange range : candidate.contents()) {
            // findBestCandidate has already rejected any recipe with a null range or null fluid, so
            // these are safe to dereference.
            final Fluid fluid = range.fluid();
            final String name = fluidName(fluid);
            // Absent metals read 0.0 out of the map's default, which is exactly right: a metal the
            // recipe wants and the crucible does not have is at 0% and needs adding.
            final double have = content.getDouble(fluid);
            final double fraction = have / total;

            // isIn() is TFC's own test, epsilon and all. Reimplementing the comparison here would be
            // a second opinion on "in range" that could disagree with the one that actually decides
            // whether the alloy forms.
            if (range.isIn(fraction)) {
                lines.add(new Line(name + "  " + percent(fraction) + " ok", theme.text()));
                continue;
            }

            if (fraction > range.max()) {
                // Over its range. There is deliberately no "add" figure here: adding more of a metal
                // that is already too concentrated only pushes it further out. It is still fixable -
                // by adding the OTHER metals, which dilutes this one - and the "add" lines printed
                // for those metals do exactly that. So this line reports the overshoot and stops,
                // rather than inventing a number that would be negative and meaningless.
                lines.add(new Line(name + "  " + percent(fraction) + " HIGH", theme.error()));
                lines.add(new Line(INDENT + "over by " + percent(fraction - range.max()), theme.error()));
                continue;
            }

            lines.add(new Line(name + "  " + percent(fraction) + " LOW", theme.error()));

            // Target the MIDPOINT of the allowed range, not its minimum. Aiming at the boundary
            // would land the mix exactly on the edge of validity, where a rounding difference or the
            // next metal added tips it straight back out; the midpoint has half the range as slack on
            // either side.
            final double target = (range.min() + range.max()) / 2.0;
            final long add = amountToAdd(target, total, have);
            if (add > 0) {
                lines.add(new Line(INDENT + "add " + add + " mB", theme.next()));
                shortCount++;
            }
        }

        if (shortCount > 1) {
            lines.add(new Line("Add one, recheck", theme.muted()));
        }
    }

    /**
     * Solves {@code (have + x) / (total + x) = target} for {@code x}: how much of one metal to add so
     * that it ends up at {@code target} of the new total.
     *
     * <p>Rearranged, that is {@code x = (target * total - have) / (1 - target)}.
     *
     * <p>Rounded <em>up</em> to a whole mB. Telling someone to add 99.4 mB when the honest answer is
     * "at least 99.4" leaves them one unit short of the range they were aiming for, and a metal that
     * is one unit short is still short. Erring high costs a fraction of a percent of slack inside a
     * range that is several percent wide.
     *
     * @return the whole number of mB to add, or {@code -1} when there is no usable answer - a target
     *         at or above 100%, or a result that is not a positive finite number
     */
    private static long amountToAdd(double target, double total, double have) {
        final double denominator = 1.0 - target;
        // Written as "!(x > e)" rather than "x <= e" so a NaN target - which fails every comparison -
        // takes this branch instead of falling through into the arithmetic.
        if (!(denominator > TARGET_EPSILON)) {
            return -1L;
        }
        final double add = (target * total - have) / denominator;
        if (!Double.isFinite(add) || add <= 0.0) {
            return -1L;
        }
        return (long) Math.ceil(add);
    }

    /**
     * Picks the alloy this crucible is closest to reaching without emptying it.
     *
     * <p>Only recipes whose metal set is a <em>superset</em> of what is already in the pot are
     * eligible. Metal can be added but not taken out, so any recipe missing a metal the crucible
     * already contains is unreachable no matter what is poured in next, and offering it would be
     * advice that cannot be followed.
     *
     * <p>Among those, the winner is the one with the fewest metals still out of range - "closest to
     * done" - with ties broken by the fewest metals overall, since a two-metal alloy is less work
     * than a four-metal one at the same distance. A final tie-break on the result's display name
     * exists purely so the choice is stable: two equally-ranked recipes must not swap places between
     * frames and make the box flicker.
     *
     * @return the best candidate, or null if there is none, or if the recipe list cannot be read
     */
    @Nullable
    private static AlloyRecipe findBestCandidate(Object2DoubleMap<Fluid> content, int total) {
        // This runs from a render event, which can fire in situations where there is no world -
        // during a disconnect, or while a screen lingers over the main menu. Minecraft.level is
        // @Nullable for exactly that reason and dereferencing it blind would throw every frame.
        final ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        final RecipeManager recipes = level.getRecipeManager();
        if (recipes == null) {
            return null;
        }

        final List<RecipeHolder<AlloyRecipe>> holders;
        try {
            // TFCRecipeTypes.ALLOY is a deferred holder: get() throws if it is somehow unbound, which
            // is what a TFC version whose registration this mod no longer matches would look like.
            // The catch is narrow - only the two exceptions an unbound holder can raise - and its
            // effect is to fall back to "no candidate", which degrades the overlay to the composition
            // list rather than throwing out of the render event on every frame.
            holders = recipes.getAllRecipesFor(TFCRecipeTypes.ALLOY.get());
        } catch (final NullPointerException | IllegalStateException e) {
            return null;
        }
        if (holders == null) {
            return null;
        }

        AlloyRecipe best = null;
        int bestOutOfRange = Integer.MAX_VALUE;
        int bestSize = Integer.MAX_VALUE;
        String bestName = "";

        for (final RecipeHolder<AlloyRecipe> holder : holders) {
            if (holder == null) {
                continue;
            }
            final AlloyRecipe recipe = holder.value();
            if (recipe == null) {
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
            if (!usable || !recipeFluids.containsAll(content.keySet())) {
                continue;
            }

            int outOfRange = 0;
            for (final AlloyRange range : ranges) {
                if (!range.isIn(content.getDouble(range.fluid()) / total)) {
                    outOfRange++;
                }
            }

            final String name = fluidName(recipe.result());
            final boolean better = best == null
                || outOfRange < bestOutOfRange
                || (outOfRange == bestOutOfRange
                    && (ranges.size() < bestSize
                        || (ranges.size() == bestSize && name.compareTo(bestName) < 0)));
            if (better) {
                best = recipe;
                bestOutOfRange = outOfRange;
                bestSize = ranges.size();
                bestName = name;
            }
        }
        return best;
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
     */
    private static void drawBox(
        CrucibleScreen screen, GuiGraphics graphics, List<Line> lines, OverlayTheme theme
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
        int y = top + PADDING;
        for (final Line line : visible) {
            graphics.drawString(font, line.text(), left + PADDING, y, line.color(), false);
            y += row;
        }
        pose.popPose();
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
     * plain text. That is what keeps the measuring and drawing passes trivially in agreement.
     *
     * @param text  the text to draw
     * @param color ARGB colour, already resolved from the active theme
     */
    private record Line(String text, int color) {
    }
}
