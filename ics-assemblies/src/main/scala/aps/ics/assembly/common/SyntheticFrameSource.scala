package aps.ics.assembly.common

import java.util.Random

/**
 * Manufactures a synthetic detector frame in memory (no Detector HCD, no
 * memory-mapped-file transfer this cut). The frame is a smooth 2-D Gaussian spot
 * over a low-amplitude noise floor; the spot drifts slowly with `frameIndex` so a
 * guiding loop produces visibly moving data rather than a static image. The
 * pattern is deterministic in `frameIndex` (seeded RNG) so runs are reproducible.
 *
 * Pixel values are arbitrary "counts" in float; nothing in the mock interprets
 * them — the stub publisher only reports the size — so the goal is simply a
 * plausible, cheap-to-generate image of the requested ROI dimensions.
 */
object SyntheticFrameSource:

  /** Generate one `width` x `height` frame for the given (0-based) frame index. */
  def generate(width: Int, height: Int, frameIndex: Long): Frame =
    val w    = math.max(1, width)
    val h    = math.max(1, height)
    val data = new Array[Float](w * h)
    val rng  = new Random(frameIndex) // deterministic per frame

    // Spot centre drifts on a slow circle around the frame centre.
    val phase = frameIndex * 0.05
    val cx    = w / 2.0 + (w * 0.15) * math.cos(phase)
    val cy    = h / 2.0 + (h * 0.15) * math.sin(phase)
    val sigma = math.max(2.0, math.min(w, h) / 8.0)
    val twoSigma2 = 2.0 * sigma * sigma
    val peak  = 60000.0f // near a 16-bit full-well, for a visible spot

    var y = 0
    while y < h do
      var x = 0
      while x < w do
        val dx     = x - cx
        val dy     = y - cy
        val gauss  = peak * math.exp(-(dx * dx + dy * dy) / twoSigma2)
        val noise  = rng.nextDouble() * 400.0 // background + read noise floor
        data(y * w + x) = (gauss + noise).toFloat
        x += 1
      y += 1

    Frame(w, h, data)
