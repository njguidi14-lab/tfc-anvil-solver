package com.njguidi14.anvilsolver.client;

import com.njguidi14.anvilsolver.AnvilSolverMod;
import net.dries007.tfc.client.screen.AnvilScreen;
import net.dries007.tfc.client.screen.CrucibleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

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
            CrucibleCalculator.render(crucibleScreen, event.getGuiGraphics());
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
     */
    @SubscribeEvent
    public static void onScreenKeyPressed(final ScreenEvent.KeyPressed.Post event) {
        if (event.getScreen() instanceof AnvilScreen
            && AnvilSolverKeys.isTogglePress(event.getKeyCode(), event.getScanCode())
            && AnvilSolverKeys.beginTogglePress()) {
            AnvilSolverClient.toggleOverlay();
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
    }
}
