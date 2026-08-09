package com.njguidi14.anvilsolver.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

/**
 * The mod's key mappings.
 *
 * <p>{@link RegisterKeyMappingsEvent} is a <em>mod</em>-bus event, while {@code ScreenEvent} is a
 * <em>game</em>-bus event. Rather than annotate this class with {@code @EventBusSubscriber(bus =
 * Bus.MOD)} - whose {@code bus()} element is deprecated and marked for removal in NeoForge 21.1 -
 * {@link #onRegisterKeyMappings} is registered explicitly against the mod bus from
 * {@code AnvilSolverMod}'s constructor, which is the supported route for mod-bus listeners.
 *
 * <p>The press itself is <strong>not</strong> handled here. A keybind polled with
 * {@code consumeClick()} from a client-tick handler only fires while no GUI screen is open, and this
 * overlay exists exclusively while the anvil screen <em>is</em> open - so that approach can never
 * work for this mod. The press is read from {@code ScreenEvent.KeyPressed.Post} in
 * {@link ClientEvents} instead, which is the hook that actually fires with a screen focused.
 *
 * <p>What this class does own is deciding whether a given key event <em>is</em> one of the mod's
 * bindings ({@link #isTogglePress}, {@link #isCycleTargetPress}) and the press/release latches that
 * make one physical press mean one action ({@code begin...Press()} / {@code end...Press()}). Every
 * handler in {@code ClientEvents} is a thin wrapper over these, so all the keybind state lives in
 * one place.
 */
public final class AnvilSolverKeys {

    /**
     * Translation key for the mod's own Controls-menu category. Must match the entry in
     * {@code assets/anvilsolver/lang/en_us.json} exactly, or the Controls menu shows the raw key.
     */
    private static final String CATEGORY = "key.categories.anvilsolver";

    /** Translation key for the binding itself. Same lang-file requirement as {@link #CATEGORY}. */
    private static final String TOGGLE_NAME = "key.anvilsolver.toggle";

    /** Translation key for the crucible target binding. Same lang-file requirement as {@link #CATEGORY}. */
    private static final String CYCLE_TARGET_NAME = "key.anvilsolver.cycletarget";

    /**
     * Toggles the overlay's visibility for the current session while the anvil screen is open.
     *
     * <p>Bound to H by default: it is unused by vanilla 1.21.1, so the binding does not show up as a
     * conflict out of the box, and unlike leaving it unbound it works the moment the mod is
     * installed - which is the point, since the alternative to a working default is editing a config
     * file, exactly what this feature exists to avoid.
     *
     * <p>Uses the plain vanilla three-argument constructor (implicitly {@code InputConstants.Type.KEYSYM}).
     * The conflict context is therefore the default UNIVERSAL rather than GUI-only; that is harmless
     * here because the binding is never polled outside the anvil screen, so an in-game press of the
     * same key does nothing.
     */
    public static final KeyMapping TOGGLE_OVERLAY =
        new KeyMapping(TOGGLE_NAME, GLFW.GLFW_KEY_H, CATEGORY);

    /**
     * Steps the alloy calculator's target through the reachable alloys, and back round to auto,
     * while the crucible screen is open.
     *
     * <p>Bound to G by default. The requirements are narrow: it has to be free in vanilla 1.21.1 so
     * the binding is not a conflict out of the box, and it has to be free <em>inside a container
     * screen</em>, which rules out more keys than it looks like. TAB is the obvious "cycle" key and
     * is exactly wrong here - screens use it for widget focus traversal - and E closes the screen,
     * 1-9 are the hotbar swap keys, and Q/Ctrl-Q drop items. G is used by neither vanilla nor TFC's
     * crucible screen, and it is next to H so the mod's two bindings sit together on the keyboard.
     *
     * <p>Same plain three-argument constructor as {@link #TOGGLE_OVERLAY}, for the same reason: the
     * binding is only ever read from the crucible screen, so the default UNIVERSAL conflict context
     * cannot cause it to fire anywhere it matters.
     */
    public static final KeyMapping CYCLE_TARGET =
        new KeyMapping(CYCLE_TARGET_NAME, GLFW.GLFW_KEY_G, CATEGORY);

    /**
     * Whether the toggle key is currently held down, tracked so the toggle is edge-triggered.
     *
     * <p>This is not an optimisation, it is a correctness fix. Minecraft's {@code KeyboardHandler}
     * routes both {@code GLFW_PRESS} and {@code GLFW_REPEAT} into {@code Screen#keyPressed}, so
     * {@code ScreenEvent.KeyPressed.Post} fires again and again at the OS key-repeat rate for as
     * long as the key is held. An unconditional flip on every one of those events strobed the
     * overlay roughly 25 times a second and left its final state decided by whether the number of
     * repeats happened to be odd or even - i.e. effectively random. Toggling only on the transition
     * from "not held" to "held" makes one physical press mean exactly one toggle.
     *
     * <p>Client-side single-threaded state (all key events arrive on the render thread), so no
     * synchronisation is needed. It is static for the same reason the mapping itself is: there is
     * exactly one keyboard.
     */
    private static boolean toggleHeld;

    /**
     * The same edge-trigger latch as {@link #toggleHeld}, for the target-cycle binding.
     *
     * <p>A separate flag rather than a shared one: the two bindings can be rebound to the same key,
     * and can in principle be held at the same time, so one shared flag would let a release of
     * either clear the other's latch.
     *
     * <p>The auto-repeat problem is worse for this binding than for the toggle. A held toggle key
     * strobes the overlay between two states; a held cycle key would sweep the target through every
     * reachable alloy at the OS repeat rate and stop on whichever one the player's finger happened
     * to leave it on.
     */
    private static boolean cycleTargetHeld;

    private AnvilSolverKeys() {
    }

    /** Mod-bus: hands the mappings to the game so they appear in, and are rebindable from, Controls. */
    public static void onRegisterKeyMappings(final RegisterKeyMappingsEvent event) {
        event.register(TOGGLE_OVERLAY);
        event.register(CYCLE_TARGET);
    }

    /**
     * Whether the given key event matches the toggle binding, modifiers included.
     *
     * <p>The explicit unbound check keeps a cleared binding from being triggered by anything: a
     * cleared {@code KeyMapping} holds {@code InputConstants.UNKNOWN}, and this makes "unbound means
     * off" a property of this method rather than an implementation detail of the match.
     *
     * <p>Uses {@code isActiveAndMatches} rather than {@code matches(keyCode, scanCode)}. The latter
     * compares only the keysym, so a user who rebinds the toggle to {@code CTRL+H} would still have
     * plain {@code H} fire it - the binding's modifier would be displayed in the Controls menu and
     * then ignored at runtime. {@code isActiveAndMatches} additionally requires the binding's
     * {@code KeyModifier} and conflict context to be satisfied, and is the same call vanilla itself
     * makes in {@code AbstractContainerScreen#keyPressed}
     * ({@code keyInventory.isActiveAndMatches(InputConstants.getKey(keyCode, scanCode))}).
     */
    public static boolean isTogglePress(int keyCode, int scanCode) {
        return !TOGGLE_OVERLAY.isUnbound()
            && TOGGLE_OVERLAY.isActiveAndMatches(InputConstants.getKey(keyCode, scanCode));
    }

    /**
     * Marks the toggle key as held and reports whether this is the <em>first</em> event of that
     * hold - i.e. a real key-down rather than an OS auto-repeat.
     *
     * <p>Call only once the event has already been matched with {@link #isTogglePress}.
     *
     * @return true if the caller should perform the toggle
     */
    public static boolean beginTogglePress() {
        if (toggleHeld) {
            return false;
        }
        toggleHeld = true;
        return true;
    }

    /**
     * Clears the held flag so the next key-down toggles again. Called from the key-release handler.
     *
     * <p>Known, self-correcting edge case: the release is matched with the same modifier-aware
     * {@link #isTogglePress} as the press, so a user who binds the toggle to a modifier combo and
     * then lets go of the <em>modifier</em> before the key releases with the combo no longer active
     * and this never runs. The flag then swallows exactly one subsequent press, and the release of
     * that press (with the modifier still down, the normal case) clears it again. That is a lost
     * keystroke in an unusual release order, not a stuck overlay, and it is the price of keeping the
     * press and release guards identical.
     */
    public static void endTogglePress() {
        toggleHeld = false;
    }

    /**
     * Whether the given key event matches the target-cycle binding, modifiers included.
     *
     * <p>Deliberately identical in form to {@link #isTogglePress} - unbound check, then
     * {@code isActiveAndMatches} - so both bindings behave the same way when cleared or bound to a
     * modifier combo. See that method for why {@code matches(keyCode, scanCode)} is not used.
     */
    public static boolean isCycleTargetPress(int keyCode, int scanCode) {
        return !CYCLE_TARGET.isUnbound()
            && CYCLE_TARGET.isActiveAndMatches(InputConstants.getKey(keyCode, scanCode));
    }

    /**
     * Marks the cycle key as held and reports whether this is the <em>first</em> event of that hold
     * - i.e. a real key-down rather than an OS auto-repeat.
     *
     * <p>Call only once the event has already been matched with {@link #isCycleTargetPress}.
     *
     * @return true if the caller should advance the target
     */
    public static boolean beginCycleTargetPress() {
        if (cycleTargetHeld) {
            return false;
        }
        cycleTargetHeld = true;
        return true;
    }

    /**
     * Clears the cycle latch so the next key-down advances the target again. Called from the
     * key-release handler.
     *
     * <p>Carries the same known, self-correcting modifier-release edge case as
     * {@link #endTogglePress()}, for the same reason: the press and release guards are identical on
     * purpose.
     */
    public static void endCycleTargetPress() {
        cycleTargetHeld = false;
    }
}
