package io.github.psd2live.ui.state

/** Nanoseconds between frames at [fps]; 0 when unlimited. */
internal fun frameIntervalNanos(fps: Int): Long = if (fps > 0) 1_000_000_000L / fps else 0L

/**
 * Picks the display frames that keep a loop at [interval] nanoseconds (0 is every frame). Time carries over
 * between frames, so 120 FPS on a 144 Hz display averages 120 rather than rounding up to every frame, and a
 * frame due within half a display frame counts: 60 FPS on a 60 Hz display must not drop every other frame.
 */
internal class FramePacer(private val interval: Long) {
	private var last = 0L
	private var budget = 0L

	fun due(now: Long): Boolean {
		if (interval <= 0L || last == 0L) {
			last = now
			return true
		}
		val step = (now - last).coerceAtLeast(0L)
		last = now
		budget += step
		if (budget + step / 2 < interval) return false
		budget = (budget - interval).coerceIn(-interval, interval)
		return true
	}
}
