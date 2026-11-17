package com.dilinkauto.protocol

/**
 * Single source of truth for "make this dimension legal for H.264".
 *
 * H.264 requires even width/height. Before this object existed the same
 * operation was written out five times across four modules, with three
 * *mutually incompatible* behaviours (see DRY-1 in docs/audit-srp-dry.md):
 *
 *  - `x and 0x7FFFFFFE` — clear the low bit. 0 stays 0, 1 becomes 0.
 *    Used by [VdDeployArgs], `VdDimensions`, `CarViewport.size` (car side).
 *  - floor-to-even then `coerceAtLeast(2)` — same as above but floored at 2,
 *    because the encoder rejects anything below 2. Used by the desktop
 *    [com.dilinkauto.desktop.HandshakeFactory].
 *  - "widen the nav bar by one so the *viewport* lands even" — rounds the
 *    subtraction *up*, the opposite direction. Used by
 *    `CarViewport.navBarWidthPx`.
 *
 * The three disagree on 0/1 inputs and on negatives, and the nav-bar variant
 * can never produce a 0/1 dimension — which is exactly why its divergence was
 * the hardest to detect.
 *
 * **Callers must pick the variant that matches their existing behaviour.**
 * [even] preserves the historical "clear the low bit" semantics;
 * [evenMin2] is the only one that guarantees a usable dimension for an encoder.
 */
object DimAlign {

    /**
     * Clear the low bit — round *down* to even.
     *
     * 0 -> 0, 1 -> 0, 2 -> 2, 1281 -> 1280.
     *
     * Note this can legitimately return 0 (for 0 or 1), and returns a large
     * *positive* number for negative input (the mask clears the sign bit).
     * That is the historical behaviour of every pre-existing call site except
     * the desktop handshake, and it is preserved deliberately: changing it
     * would alter the encoded command line, which [VdDeployCommandLineTest] and
     * `AdbDeployTest` lock. **Any caller feeding this value to a real encoder
     * must use [evenMin2] instead** — that is the whole reason the two exist.
     */
    fun even(value: Int): Int = value and 0x7FFFFFFE.toInt()

    /**
     * Round down to even, then floor at 2 so the result is always a legal
     * encoder dimension.
     *
     * 0 -> 2, 1 -> 2, 2 -> 2, 1281 -> 1280, -1 -> 2.
     *
     * This is byte-for-byte the behaviour the desktop `HandshakeFactory.evenAlign`
     * had; `DesktopConnectionServiceTest` locks those exact values.
     *
     * **Why the `value <= 2` short-circuit and not just `even(value).coerceAtLeast(2)`:**
     * `0x7FFFFFFE` is an *unsigned* mask. Applied to a negative Int it clears the
     * sign bit instead of rounding down, so `even(-1)` returns **2147483646**, not
     * `-2` — a positive dimension large enough to be nonsense for an encoder.
     * The legacy implementation avoided this only by accident: Kotlin's `%` is
     * negative for negative operands, so `-1 % 2 == -1` floored to `-2` and the
     * clamp produced 2. Routing negatives through [even] would silently change
     * that to 2147483646, so they are short-circuited explicitly.
     */
    fun evenMin2(value: Int): Int = if (value <= 2) 2 else even(value)

    /**
     * Adjust [offset] by at most one pixel so that `base - offset` is even.
     *
     * The car's persistent nav bar subtracts its own width from the display to
     * get the video viewport. Because that width is an independent value, the
     * way to land on an even viewport is to *widen the bar*, not to shrink the
     * viewport: if `base - offset` is odd, `offset + 1` makes it even.
     *
     * Note this rounds the *offset up* — the opposite direction from [even] —
     * which is why it cannot be expressed in terms of [even].
     *
     * @return `offset`, or `offset + 1` when that is what it takes to make
     *   `base - offset` even.
     */
    fun offsetForEvenRemainder(base: Int, offset: Int): Int =
        if ((base - offset) % 2 != 0) offset + 1 else offset
}