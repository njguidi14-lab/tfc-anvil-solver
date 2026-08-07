package com.njguidi14.anvilsolver.client;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.OptionalDouble;

import org.jetbrains.annotations.Nullable;

/**
 * Estimates how long an item has left above a temperature threshold, using only temperatures this
 * mod has actually observed.
 *
 * <p>Deliberately model-free. TFC's cooling depends on the item's heat capacity and its
 * environment, and that formula has not been verified against TFC's source here, so nothing about
 * it is assumed: the estimator only fits a line through readings it has seen with its own eyes and
 * extrapolates that. A wrong number on screen is worse than no number - the player would plan
 * presses around it - so every path that cannot justify an answer returns
 * {@link OptionalDouble#empty()} and the caller shows nothing at all.
 *
 * <p>Intentionally free of Minecraft and TFC types: it takes plain floats and millisecond stamps.
 * That keeps the trend logic readable on its own terms and means the caller owns every question
 * about <em>what</em> is being measured.
 *
 * <p>Not thread-safe, and does not need to be: it is driven from the client render thread only,
 * the same thread that owns {@code AnvilSolverClient}'s solve cache.
 */
final class CoolingEstimator {

    /**
     * Minimum spacing between accepted samples.
     *
     * <p>Sampling literally every frame would be useless: TFC's temperature advances once per game
     * tick (50ms), so at 200fps ten consecutive samples read the identical value and the fitted
     * slope over them is zero. Spacing samples out at 100ms guarantees each one is at least one or
     * two ticks of real movement apart.
     */
    private static final long SAMPLE_INTERVAL_MS = 100;

    /**
     * How far back the history reaches. Long enough to average out the per-tick staircase, short
     * enough that the trend still reflects what the item is doing <em>now</em> rather than what it
     * was doing before the player pulled it out of the forge.
     */
    private static final long WINDOW_MS = 2000;

    /** Hard cap on retained samples, so a pathological clock cannot grow the deque without bound. */
    private static final int MAX_SAMPLES = (int) (WINDOW_MS / SAMPLE_INTERVAL_MS) + 4;

    /** Fewest samples that may produce an estimate. Two points is a coincidence, not a trend. */
    private static final int MIN_SAMPLES = 4;

    /**
     * Shortest time the samples must span. This, not {@link #MIN_SAMPLES}, is the real guard: it
     * is what stops a burst of frames inside a single game tick from being read as a trend.
     */
    private static final long MIN_SPAN_MS = 500;

    /**
     * Slowest fall, in degrees per second, that still counts as cooling. Anything gentler is
     * treated as flat: the extrapolation would be dominated by rounding noise, and dividing by a
     * near-zero slope is exactly how an absurd "3000s left" gets on screen.
     */
    private static final double MIN_COOLING_RATE = 0.5;

    /**
     * Any sample rising more than this above the previous one ends the trend. Small on purpose -
     * it is float jitter tolerance, not a reheat tolerance. An item put back in the forge climbs by
     * far more than this, and the whole history is rejected the moment it does.
     */
    private static final float RISE_TOLERANCE = 0.05f;

    /**
     * Longest estimate worth showing. Past this the number is both useless ("you have five minutes")
     * and least trustworthy, since a small slope error scales up with the extrapolation distance.
     */
    private static final double MAX_ESTIMATE_SECONDS = 300;

    private final Deque<Sample> samples = new ArrayDeque<>();

    /**
     * What the current history is <em>about</em>. When the caller reports a different subject, the
     * history is thrown away rather than blended: a freshly swapped-in item must never inherit the
     * previous one's cooling trend.
     */
    @Nullable
    private Object subject;

    /**
     * Reports that there is nothing to measure right now, throwing away the whole history.
     *
     * <p>This is the other half of {@link #observe}, and it is not optional: without it the
     * estimator only ever hears about frames where a reading existed, so a subject that goes away
     * leaves its samples sitting in the deque. Insert something whose subject key happens to compare
     * equal - an identical item at a very different temperature - and those stale samples are
     * silently blended with the new ones, producing a confident countdown fitted across a
     * discontinuity that never physically happened. A fall is not caught by the rising-sample check
     * in {@link #secondsUntil} either, so the wrong answer is exactly the kind that gets believed.
     *
     * <p>The subject is nulled as well as the samples cleared, so the very next {@link #observe}
     * sees a subject change and starts clean no matter what key it carries. Cheap and idempotent -
     * callers are meant to call it on every frame that has no reading, not to track whether they
     * already have.
     */
    void clear() {
        samples.clear();
        subject = null;
    }

    /**
     * Records a reading, first discarding the history if this is a different subject than last time.
     *
     * <p>Callers must pair this with {@link #clear} on every frame where no reading is available.
     * Silence is not the same as absence: this method can only detect a change of subject, never a
     * gap during which the subject was gone.
     *
     * @param newSubject  identifies what is being measured; compared with {@link Objects#equals}
     * @param temperature the reading
     * @param nowMillis   wall-clock time of the reading
     */
    void observe(Object newSubject, float temperature, long nowMillis) {
        if (!Objects.equals(subject, newSubject)) {
            samples.clear();
            subject = newSubject;
        }

        final Sample last = samples.peekLast();
        if (last != null) {
            if (nowMillis < last.timeMillis()) {
                // Wall-clock time moved backwards (NTP correction, or the user changing the system
                // clock). Every retained timestamp is now meaningless relative to this one, and a
                // negative time delta would fit a nonsense slope, so start over.
                samples.clear();
            } else if (nowMillis - last.timeMillis() < SAMPLE_INTERVAL_MS) {
                return;
            }
        }

        samples.addLast(new Sample(temperature, nowMillis));

        while (samples.size() > MAX_SAMPLES) {
            samples.removeFirst();
        }
        // Drop anything that has aged out of the window. The size guard keeps the newest sample
        // whatever its age, which is what lets the history recover on its own after the screen has
        // been closed for a while: the stale sample is evicted by this same loop on the next call.
        while (samples.size() > 1 && nowMillis - samples.peekFirst().timeMillis() > WINDOW_MS) {
            samples.removeFirst();
        }
    }

    /**
     * Seconds until the observed value is projected to reach {@code threshold}, or empty when the
     * history does not support an answer.
     *
     * <p>Empty is returned - and is the correct result - whenever the samples are too few, span too
     * little time, are not consistently falling, are falling too slowly to extrapolate safely, or
     * project to a time that is already past or implausibly far away.
     */
    OptionalDouble secondsUntil(float threshold) {
        if (samples.size() < MIN_SAMPLES) {
            return OptionalDouble.empty();
        }

        final Sample first = samples.peekFirst();
        final Sample last = samples.peekLast();
        final long spanMs = last.timeMillis() - first.timeMillis();
        if (spanMs < MIN_SPAN_MS) {
            return OptionalDouble.empty();
        }

        // "Consistent" is checked before any line is fitted, because least squares happily fits a
        // falling line through a V-shape - an item that cooled, was reheated, and is now climbing
        // would otherwise produce a confident countdown while the temperature goes up.
        float previous = first.temperature();
        for (final Sample sample : samples) {
            if (sample.temperature() > previous + RISE_TOLERANCE) {
                return OptionalDouble.empty();
            }
            previous = sample.temperature();
        }

        final double slope = slopePerSecond(first);
        // Cooling means a negative slope. This rejects flat and rising alike, and guarantees the
        // divide below is by a magnitude of at least MIN_COOLING_RATE.
        if (slope > -MIN_COOLING_RATE) {
            return OptionalDouble.empty();
        }

        // Extrapolate from the most recent reading rather than from the fitted line's endpoint:
        // the latest observation is the one the player can see, so the countdown agrees with the
        // temperature displayed next to it.
        final double seconds = (last.temperature() - threshold) / -slope;
        if (!Double.isFinite(seconds) || seconds <= 0 || seconds > MAX_ESTIMATE_SECONDS) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(seconds);
    }

    /**
     * Least-squares slope of temperature against time, in degrees per second.
     *
     * <p>A regression rather than "first sample minus last sample over the elapsed time": TFC's
     * temperature moves in per-tick steps, so an endpoint difference is quantised by whichever tick
     * boundaries the two endpoints happened to land on, while a fit over the whole window uses
     * every sample and is far steadier frame to frame.
     *
     * @param first the oldest retained sample, used as the time origin to keep the sums small
     */
    private double slopePerSecond(Sample first) {
        double sumX = 0;
        double sumY = 0;
        double sumXX = 0;
        double sumXY = 0;
        final int n = samples.size();
        for (final Sample sample : samples) {
            final double x = (sample.timeMillis() - first.timeMillis()) / 1000.0;
            final double y = sample.temperature();
            sumX += x;
            sumY += y;
            sumXX += x * x;
            sumXY += x * y;
        }

        final double denominator = n * sumXX - sumX * sumX;
        if (denominator <= 1e-9) {
            // Every sample shares one timestamp, so there is no slope to speak of. Unreachable
            // given the span check in the caller, but returning 0 here means "flat", which that
            // caller already rejects - so the fallback is safe rather than merely unlikely.
            return 0;
        }
        return (n * sumXY - sumX * sumY) / denominator;
    }

    /** One observation: a value and when it was taken. */
    private record Sample(float temperature, long timeMillis) {
    }
}
