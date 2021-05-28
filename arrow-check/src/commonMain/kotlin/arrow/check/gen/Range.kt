package arrow.check.gen

import arrow.check.property.Size
import arrow.core.andThen
import kotlin.math.max
import kotlin.math.min

/**
 * [Range] reflects a growable range from an origin out towards a lower and upper bound.
 *
 * @param origin The origin of a range will only be used to shrink towards that origin
 * @param bounds A function that generates an lower and upper bound depending on the [Size].
 */
data class Range<A>(val origin: A, val bounds: (Size) -> Pair<A, A>) {

  /**
   * Change the [Range] by mapping over it
   */
  fun <B> map(f: (A) -> B): Range<B> = Range(
    f(origin),
    bounds andThen { (a, b) -> f(a) to f(b) }
  )

  /**
   * Extract the lower bound using the given [Size]
   */
  fun lowerBound(s: Size): A = bounds(s).first

  /**
   * Extract the lower bound using the given [Size]
   */
  fun upperBound(s: Size): A = bounds(s).first

  companion object {

    /**
     * Singleton range which always has [a] as a bound.
     */
    fun <A> singleton(a: A): Range<A> = Range(a) { a to a }

    /**
     * Create a constant range that does not change with [Size].
     *
     * [start] will also be used as [origin].
     */
    fun <A> constant(start: A, end: A): Range<A> = Range(start) { start to end }

    /**
     * Create a constant range with a specific origin.
     */
    fun <A> constant(origin: A, start: A, end: A): Range<A> = Range(origin) { start to end }

    fun constant(range: IntRange): Range<Int> = constant(range.first, range.last)
    fun constant(range: CharRange): Range<Char> = constant(range.first, range.last)
    fun constant(range: LongRange): Range<Long> = constant(range.first, range.last)
    fun constant(range: IntProgression): Range<Int> = constant(range.first, range.last)
    fun constant(range: CharProgression): Range<Char> = constant(range.first, range.last)
    fun constant(range: LongProgression): Range<Long> = constant(range.first, range.last)

    /**
     * Create a range that grows linear with the [Size].
     *
     * The [origin] of this range will be the [start].
     */
    fun linear(start: Int, end: Int): Range<Int> = linearFrom(start, start, end)

    /**
     * Create a range that grows linear with the [Size] from a specific [origin].
     */
    fun linearFrom(origin: Int, start: Int, end: Int): Range<Int> = Range(origin) { s ->
      val xSized = scaleLinear(s, origin, start).clamp(start.toLong(), end.toLong()).toInt()
      val ySized = scaleLinear(s, origin, end).clamp(start.toLong(), end.toLong()).toInt()
      xSized to ySized
    }

    fun linear(start: Char, end: Char): Range<Char> = linearFrom(start, start, end)
    fun linearFrom(origin: Char, start: Char, end: Char): Range<Char> =
      linearFrom(origin.toInt(), start.toInt(), end.toInt()).map { it.toChar() }

    fun linear(start: Byte, end: Byte): Range<Byte> = linearFrom(start, start, end)
    fun linearFrom(origin: Byte, start: Byte, end: Byte): Range<Byte> =
      linearFrom(origin.toInt(), start.toInt(), end.toInt()).map { it.toByte() }

    fun linear(start: Long, end: Long): Range<Long> = linearFrom(start, start, end)

    // This needs extra work to prevent overflows, the above just convert to long to avoid those
    fun linearFrom(origin: Long, start: Long, end: Long): Range<Long> = Range(origin) { s ->
      val xSized = scaleLinear(s, origin, start).also(::println).clamp(start, end)
      val ySized = scaleLinear(s, origin, end).also(::println).clamp(start, end)
      xSized to ySized
    }

    fun linear(start: Float, end: Float): Range<Float> = linearFrom(start, start, end)
    fun linearFrom(origin: Float, start: Float, end: Float): Range<Float> =
      linearFrom(origin.toDouble(), start.toDouble(), end.toDouble()).map { it.toFloat() }

    fun linear(start: Double, end: Double): Range<Double> = linearFrom(start, start, end)

    // This needs extra work to prevent overflows, the above just convert to double to avoid those
    fun linearFrom(origin: Double, start: Double, end: Double): Range<Double> = Range(origin) { s ->
      val xSized = scaleLinear(s, origin, start).clamp(start, end)
      val ySized = scaleLinear(s, origin, end).clamp(start, end)
      xSized to ySized
    }

    // TODO exponential scaling
  }
}

internal fun scaleLinear(size: Size, origin: Int, target: Int): Long {
  val sz = max(size.unSize, min(99, size.unSize))
  val z = origin.toLong()
  val n = target.toLong()
  val diff = ((n - z) * (sz.toDouble() / 99)).toLong()
  return z + diff
}

fun scaleLinear(size: Size, origin: Long, target: Long): Long {
  val sz = max(size.unSize, min(99, size.unSize))
  val frac = sz.toDouble() / 99

  if (frac == 1.0) return target
  if (frac == 0.0) return origin

  val z = (origin * frac).toLong()
  val n = (target * frac).toLong()

  if (n >= 0) {
    if (z >= 0) {
      // cannot overflow
      val diff = n - z
      return origin + diff
    } else {
      // may overflow
      val diff = n - z
      return if (diff < n) (origin + Long.MAX_VALUE) + (n - Long.MAX_VALUE - z)
      else origin + diff
    }
  } else {
    if (z <= 0) {
      // cannot overflow
      val diff = n - z
      return origin + diff
    } else {
      // may overflow
      val diff = n - z
      return if (diff > n) (origin + Long.MIN_VALUE) + (n - Long.MIN_VALUE - z)
      else origin + diff
    }
  }
}

internal fun scaleLinear(size: Size, origin: Double, target: Double): Double {
  val sz = max(size.unSize, min(99, size.unSize))
  val frac = sz.toDouble() / 99

  if (frac == 1.0) return target
  if (frac == 0.0) return origin

  val z = origin * frac
  val n = target * frac

  // TODO Handle precision errors towards the edges better...
  return origin + (n - z)
}

internal fun Long.clamp(x: Long, y: Long): Long =
  if (x > y) min(x, max(y, this))
  else min(y, max(x, this))

internal fun Double.clamp(x: Double, y: Double): Double =
  if (x > y) min(x, max(y, this))
  else min(y, max(x, this))
