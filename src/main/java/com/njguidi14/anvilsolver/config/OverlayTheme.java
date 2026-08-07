package com.njguidi14.anvilsolver.config;

import java.util.Locale;

import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.TranslatableEnum;

/**
 * The overlay's colour palette, selected by the {@code theme} config option.
 *
 * <p>Every colour the overlay draws comes from here. Nothing in the rendering code may branch on a
 * specific colour value - meaning is carried by which <em>role</em> a colour fills (next, error,
 * muted, ...), never by the ARGB number itself, or a theme swap would silently change behaviour
 * instead of only appearance.
 *
 * <p><b>Why more than one palette exists.</b> The overlay's primary semantic pair is
 * {@link #next()} ("do this press now") against {@link #error()} ("too cold / no path"). In the
 * original palette those are green and red, which is the single worst pairing for the most common
 * form of colour vision deficiency - red-green, affecting roughly 8% of men. {@link #COLORBLIND_SAFE}
 * exists so that pair is legible without any red-green discrimination at all, and
 * {@link #MONOCHROME} exists as the fallback for when hue carries no information whatsoever.
 *
 * <p>All values are ARGB, in the same {@code 0xAARRGGBB} form {@code GuiGraphics.fill} and
 * {@code drawString} take.
 *
 * <p>This file, like every other in the mod, is pure ASCII: {@code build.gradle} sets no
 * {@code compileJava.options.encoding}, so javac reads sources in the platform default charset.
 */
public enum OverlayTheme implements TranslatableEnum {

    /**
     * The original palette, unchanged to the last digit.
     *
     * <p>Green on near-black, tuned to sit next to TFC's own anvil GUI. This is the default, so
     * every existing user's overlay looks exactly as it did before themes were added.
     */
    TFC_GREEN(
        0xD00E1912, // background - near-black with a green cast, ~82% opaque
        0xFF274031, // border
        0xFF7F9A86, // muted
        0xFFB9CDBC, // text
        0xFF4CAF6A, // next
        0xFFF2B8B3, // error
        0x404CAF6A  // next fill - the next colour at ~25% alpha
    ),

    /**
     * Blue for "next", amber for "error": readable without telling red from green.
     *
     * <p>Blue against orange/amber is the standard safe substitution for a red-green pair - the two
     * stay clearly separate under both protanopia and deuteranopia, where green and red collapse
     * into each other. Hue alone is not relied on either: the two are separated by roughly 50 points
     * of perceived brightness (about 145 for the blue against about 197 for the amber, by the usual
     * 0.299R + 0.587G + 0.114B weighting), so they remain distinguishable in greyscale and therefore
     * under tritanopia as well, where the blue-yellow axis is the one that collapses.
     *
     * <p>The neutral greys are deliberately free of any colour cast, so no hue in the box competes
     * with the two that actually mean something.
     */
    COLORBLIND_SAFE(
        0xD0101418, // background - neutral near-black, same ~82% opacity as the default
        0xFF3B4756, // border
        0xFF93A1B2, // muted
        0xFFDCE4EE, // text - brightest neutral, well clear of both semantic colours
        0xFF4FA3E3, // next - medium blue, perceived brightness ~145
        0xFFFFC145, // error - amber, perceived brightness ~197
        0x404FA3E3  // next fill - the next colour at ~25% alpha
    ),

    /**
     * Maximum legibility over a busy or bright background.
     *
     * <p>Pure black background at higher opacity, a white border instead of a dim one, and white
     * body text. The semantic pair stays green/red - anyone who needs that pair broken up wants
     * {@link #COLORBLIND_SAFE} - but both are pushed to full saturation so they read at a glance.
     */
    HIGH_CONTRAST(
        0xF0000000, // background - pure black, ~94% opaque
        0xFFFFFFFF, // border
        0xFFC8C8C8, // muted
        0xFFFFFFFF, // text
        0xFF33FF66, // next
        0xFFFF4D4D, // error
        0x5533FF66  // next fill - slightly stronger than the other themes' 25%, still icon-legible
    ),

    /**
     * Greyscale only: the next press is marked by being the brightest thing in the box.
     *
     * <p>For anyone who finds the coloured overlay noisy, and the genuine fallback for when hue
     * cannot be relied on at all. The four text roles form a deliberate brightness ladder - next
     * (255) above error (212) above text (158) above muted (117) - so the ordering that colour used
     * to express survives intact. The words still carry the meaning too ("TOO COLD" says so in
     * letters), which is what keeps this theme honest rather than merely pretty.
     */
    MONOCHROME(
        0xD00A0A0A, // background
        0xFF4A4A4A, // border
        0xFF757575, // muted
        0xFF9E9E9E, // text
        0xFFFFFFFF, // next - brightest by design; this is the whole signal
        0xFFD4D4D4, // error - above body text, below next
        0x40FFFFFF  // next fill - the next colour at ~25% alpha
    );

    private final int background;
    private final int border;
    private final int muted;
    private final int text;
    private final int next;
    private final int error;
    private final int nextFill;

    OverlayTheme(
        int background, int border, int muted, int text, int next, int error, int nextFill
    ) {
        this.background = background;
        this.border = border;
        this.muted = muted;
        this.text = text;
        this.next = next;
        this.error = error;
        this.nextFill = nextFill;
    }

    /** Fill behind the overlay box. Partly transparent, so the GUI underneath still shows through. */
    public int background() {
        return background;
    }

    /** Outline of the overlay box, and the empty part of the temperature bar's track. */
    public int border() {
        return border;
    }

    /** Secondary text: the header line, the press count, the "select a plan" hint. */
    public int muted() {
        return muted;
    }

    /** Ordinary body text: press rows after the first, and the "+N more" line. */
    public int text() {
        return text;
    }

    /** The "do this now" colour: the first press row, the button highlight, a finished item. */
    public int next() {
        return next;
    }

    /** The "this will not work" colour: too cold, no path, unsupported forge data. */
    public int error() {
        return error;
    }

    /** {@link #next()} at low alpha, used to tint the highlighted step button without hiding its icon. */
    public int nextFill() {
        return nextFill;
    }

    /**
     * The label shown on the in-game config screen's theme button.
     *
     * <p>Without this, NeoForge's generated screen falls back to {@code Component.literal(name())}
     * and the button reads {@code COLORBLIND_SAFE}. Implementing {@link TranslatableEnum} is the
     * only hook it offers for nicer labels - see {@code ConfigurationScreen#createEnumValue}, which
     * does {@code displayvalue instanceof TranslatableEnum tenum ? tenum.getTranslatedName() : ...}.
     *
     * <p>Keys are {@code anvilsolver.configuration.theme.<lowercase constant name>} and live in
     * {@code assets/anvilsolver/lang/en_us.json}. Adding a constant here without adding its key
     * shows the raw key on the button.
     */
    @Override
    public Component getTranslatedName() {
        return Component.translatable("anvilsolver.configuration.theme." + name().toLowerCase(Locale.ROOT));
    }
}
