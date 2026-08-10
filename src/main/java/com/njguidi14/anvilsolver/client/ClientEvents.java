package com.njguidi14.anvilsolver.client;

import com.njguidi14.anvilsolver.AnvilSolverMod;
import net.dries007.tfc.client.screen.AnvilScreen;
import net.dries007.tfc.client.screen.CrucibleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Client-side event wiring for the solver overlay.
 *
 * <p>This replaces the previous {@code AnvilOverlayMixin}, which tried to inject at the TAIL of
 * {@code AnvilScreen#render}. TFC's {@code AnvilScreen} does not <em>declare</em> {@code render} -
 * it inherits it from {@code AbstractContainerScreen} - and Mixin only matches methods declared on
 * the target class, so that injection point never existed and the game hard-crashed on startup.
 * {@link ScreenEvent.Render.Post} is NeoForge's first-class, supported hook for "draw something on
 * top of a screen" and fires at exactly the same point the TAIL injection was aiming for, with no
 * fragile target matching involved.
 */
// ScreenEvent is a game-bus event (NeoForge.EVENT_BUS), which is this annotation's default bus.
// The explicit bus = Bus.GAME parameter is deliberately omitted: it is deprecated and marked for
// removal in NeoForge 21.1, and setting it produces a compiler warning for no behavioural gain.
@EventBusSubscriber(modid = AnvilSolverMod.MOD_ID, value = Dist.CLIENT)
public final class ClientEvents {

    private ClientEvents() {
    }

    /**
     * Fires after a screen has finished rendering (including its tooltips), so the overlay is
     * drawn last and nothing in the vanilla/TFC screen paints over it.
     *
     * <p>The event's {@code GuiGraphics} is handed to us outside the container screen's translated
     * pose stack, which is why {@link AnvilSolverClient} positions the box in absolute screen
     * coordinates via {@code getGuiLeft()}/{@code getGuiTop()}.
     *
     * <p>TFC's {@code CrucibleScreen} is dispatched from here for the same reason the anvil is: like
     * {@code AnvilScreen}, it does not <em>declare</em> {@code render}, so there is no method on it
     * for a mixin to inject into. The two branches are mutually exclusive - a screen cannot be both -
     * and they share nothing but this event, by design: see {@link CrucibleCalculator}'s class
     * javadoc for why the crucible overlay deliberately does not reuse the anvil's box renderer.
     */
    @SubscribeEvent
    public static void onScreenRenderPost(final ScreenEvent.Render.Post event) {
        if (event.getScreen() instanceof AnvilScreen anvilScreen) {
            AnvilSolverClient.render(anvilScreen, event.getGuiGraphics());
        } else if (event.getScreen() instanceof CrucibleScreen crucibleScreen) {
            // The mouse position is passed for one purpose only: highlighting the target row under
            // the cursor, so the list reads as clickable. It is in the same scaled screen coordinate
            // space the box is drawn in, being the very arguments the screen's own render was called
            // with. Nothing about the overlay depends on it - if these accessors ever go away, pass
            // -1.0, -1.0 instead and the only thing lost is the hover highlight.
            CrucibleCalculator.render(
                crucibleScreen, event.getGuiGraphics(), event.getMouseX(), event.getMouseY());
        }
    }

    /**
     * Selects an alloy target when the player clicks one of the calculator's rows.
     *
     * <p><b>Why {@code Pre} and not {@code Post}.</b> Only {@code Pre} is cancellable, and cancelling
     * is the entire reason this handler exists in the form it does: a click that hits one of the
     * overlay's rows must not <em>also</em> reach the crucible screen beneath it. On {@code Post} the
     * screen has already handled the click and there is nothing left to prevent.
     *
     * <p><b>Why it is cancelled conditionally.</b> {@link CrucibleCalculator#clickAt} returns whether
     * the click actually landed on a row, and only then is the event cancelled. A handler that
     * cancelled every click while the overlay was visible would break the crucible itself - its
     * slots, its scroll, its inventory - so "did not hit us" has to mean "we were never here".
     * Cancelling is done through {@code ICancellableEvent}, which
     * {@code ScreenEvent.MouseButtonPressed.Pre} implements and {@code Post} does not.
     *
     * <p>Left button only. Right-click in a container screen is a real action - half-stack pickup and
     * placement - and stealing it over the overlay would be a bug even where the click hits a row;
     * middle-click is creative-mode stack cloning. Neither means "choose this", so both fall through
     * untouched.
     *
     * <p>Unlike the keybind handlers below this needs no press/release latch. Mouse buttons do not
     * auto-repeat, so one physical click produces exactly one event, and re-selecting an
     * already-selected target is a no-op in any case.
     */
    @SubscribeEvent
    public static void onScreenMouseButtonPressed(final ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return;
        }
        if (event.getScreen() instanceof CrucibleScreen crucibleScreen
            && CrucibleCalculator.clickAt(crucibleScreen, event.getMouseX(), event.getMouseY())) {
            event.setCanceled(true);
        }
    }

    /**
     * Scrolls the alloy calculator's target list when the wheel is turned over the overlay box.
     *
     * <p>Deliberately built to the same shape as {@link #onScreenMouseButtonPressed} above, down to
     * the order of the conditions: {@code Pre} because only {@code Pre} is cancellable, and
     * cancelled conditionally on what the calculator reports, because the crucible screen has
     * scrolling of its own and a handler that ate the wheel whenever the overlay was open would
     * break it. {@link CrucibleCalculator#scrollAt} returns false for a cursor outside the box, for
     * a box that was not drawn, and for a list that cannot move any further in the direction asked -
     * and every one of those falls straight through to the screen underneath.
     *
     * <p><b>Why this exists.</b> The target list shows at most eight candidates at a time and an
     * empty crucible reaches far more than eight, so anything below the fold was reachable only by
     * the cycle key - which is to say, not reachable by mouse at all, in a list whose entire point
     * is that it is clicked. This is the missing gesture.
     *
     * <p>Only the vertical delta is read. {@code getScrollDeltaX} exists on the event and is
     * non-zero on trackpads and tilt wheels, but the list has one axis, and consuming a horizontal
     * scroll to do nothing with it would take it away from whatever else might want it.
     *
     * <p>No press/release latch, for the same reason the click handler needs none: a wheel notch is
     * one event, not a held state that repeats.
     */
    @SubscribeEvent
    public static void onScreenMouseScrolled(final ScreenEvent.MouseScrolled.Pre event) {
        if (event.getScreen() instanceof CrucibleScreen crucibleScreen
            && CrucibleCalculator.scrollAt(
                crucibleScreen, event.getMouseX(), event.getMouseY(), event.getScrollDeltaY())) {
            event.setCanceled(true);
        }
    }

    /**
     * Toggles the overlay when the mod's keybind is pressed with the anvil screen open.
     *
     * <p>This is the only workable place to read that key. {@code KeyMapping.consumeClick()} polled
     * from a client-tick handler does not fire while any screen has focus, and the overlay only
     * exists while the anvil screen has focus, so a tick-based toggle would never trigger even once.
     * {@link ScreenEvent.KeyPressed.Post} is the screen-focused equivalent and lives on the game bus,
     * the same bus as the render event above.
     *
     * <p>Using the {@code Post} variant rather than {@code Pre} means the screen gets first refusal
     * on the key, so rebinding the toggle onto a key the anvil screen already uses degrades to "the
     * screen's behaviour wins" instead of silently stealing it. The event is deliberately not
     * cancelled: with the default binding nothing else consumes the key, and leaving it uncancelled
     * keeps this handler purely additive to whatever else is listening.
     *
     * <p>The toggle is edge-triggered via {@link AnvilSolverKeys#beginTogglePress()}: this event
     * also fires for OS key auto-repeat, so holding the key down would otherwise strobe the overlay.
     * See that method's class-level state for the full explanation.
     *
     * <p>The crucible branch below is a separate {@code if} rather than an {@code else if} on
     * purpose. A screen cannot be both an {@code AnvilScreen} and a {@code CrucibleScreen}, so the
     * two are already mutually exclusive, and keeping them independent means the shipped anvil
     * branch is untouched by the crucible feature - not even by a change of control flow.
     */
    @SubscribeEvent
    public static void onScreenKeyPressed(final ScreenEvent.KeyPressed.Post event) {
        if (event.getScreen() instanceof AnvilScreen
            && AnvilSolverKeys.isTogglePress(event.getKeyCode(), event.getScanCode())
            && AnvilSolverKeys.beginTogglePress()) {
            AnvilSolverClient.toggleOverlay();
        }

        // Cycles the alloy calculator's target. This is now the keyboard alternative to clicking a
        // row in the target list, not the only way in - kept because it costs nothing and because
        // some players would rather not take their hand off the keyboard. It is no longer the only
        // route to a candidate below the visible window either: the wheel handler above scrolls the
        // list, which is what that job should have been from the start.
        //
        // Edge-triggered through its own latch for the same reason the toggle is, and a more pressing
        // one: this event repeats at the OS key-repeat rate, so an unlatched handler would run the
        // target through every reachable alloy for as long as the key was held and land on an
        // essentially arbitrary one.
        if (event.getScreen() instanceof CrucibleScreen
            && AnvilSolverKeys.isCycleTargetPress(event.getKeyCode(), event.getScanCode())
            && AnvilSolverKeys.beginCycleTargetPress()) {
            CrucibleCalculator.cycleTarget();
        }
    }

    /**
     * Releases the edge-trigger latch set by {@link #onScreenKeyPressed}, so the next press of the
     * toggle key toggles again.
     *
     * <p>Deliberately mirrors the press handler exactly - same game bus, same {@code Post} variant,
     * same {@code instanceof AnvilScreen} and {@code isTogglePress} guards - because the two must
     * agree on what counts as "the toggle key" or the latch would be set by one and never cleared by
     * the other. It performs no toggling of its own; a physical press-and-release is one toggle, on
     * the press.
     */
    @SubscribeEvent
    public static void onScreenKeyReleased(final ScreenEvent.KeyReleased.Post event) {
        if (event.getScreen() instanceof AnvilScreen
            && AnvilSolverKeys.isTogglePress(event.getKeyCode(), event.getScanCode())) {
            AnvilSolverKeys.endTogglePress();
        }

        // Mirrors the crucible press branch exactly - same bus, same Post variant, same guards -
        // because the two must agree on what counts as "the cycle key" or the latch would be set by
        // one and never cleared by the other, and the target would advance exactly once per session.
        if (event.getScreen() instanceof CrucibleScreen
            && AnvilSolverKeys.isCycleTargetPress(event.getKeyCode(), event.getScanCode())) {
            AnvilSolverKeys.endCycleTargetPress();
        }
    }
}
