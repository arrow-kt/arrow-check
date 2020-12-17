package arrow.check.gen

import arrow.check.property.Size
import arrow.core.Tuple2
import arrow.core.andThen
import arrow.core.toT
import java.math.BigDecimal
import kotlin.math.max
import kotlin.math.min

/**
 * [Range] reflects a growable range from an origin out towards a lower and upper bound.
 *
 * @param origin The origin of a range will only be used to shrink towards that origin
 * @param bounds A function that generates an lower and upper bound depending on the [Size].
 */
data class Range<A>(val origin: A, val bounds: (Size) -> Tuple2<A, A>) {

    /**
     * Change the [Range] by mapping over it
     */
    fun <B> map(f: (A) -> B): Range<B> = Range(
        f(origin),
        bounds andThen { (a, b) -> f(a) toT f(b) }
    )

    /**
     * Extract the lower bound using the given [Size]
     */
    fun lowerBound(s: Size): A = bounds(s).a

    /**
     * Extract the lower bound using the given [Size]
     */
    fun upperBound(s: Size): A = bounds(s).b

    companion object {

        /**
         * Singleton range which always has [a] as a bound.
         */
        fun <A> singleton(a: A): Range<A> = Range(a) { a toT a }

        /**
         * Create a constant range that does not change with [Size].
         *
         * [start] will also be used as [origin].
         */
        fun <A> constant(start: A, end: A): Range<A> = Range(start) { start toT end }

        /**
         * Create a constant range with a specific origin.
         */
        fun <A> constant(origin: A, start: A, end: A): Range<A> = Range(origin) { start toT end }

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
            xSized toT ySized
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
            val xSized = scaleLinear(s, origin, start).clamp(start, end)
            val ySized = scaleLinear(s, origin, end).clamp(start, end)
            xSized toT ySized
        }

        fun linear(start: Float, end: Float): Range<Float> = linearFrom(start, start, end)
        fun linearFrom(origin: Float, start: Float, end: Float): Range<Float> =
            linearFrom(origin.toDouble(), start.toDouble(), end.toDouble()).map { it.toFloat() }

        fun linear(start: Double, end: Double): Range<Double> = linearFrom(start, start, end)
        // This needs extra work to prevent overflows, the above just convert to double to avoid those
        fun linearFrom(origin: Double, start: Double, end: Double): Range<Double> = Range(origin) { s ->
            val xSized = scaleLinear(s, origin, start).clamp(start, end)
            val ySized = scaleLinear(s, origin, end).clamp(start, end)
            xSized toT ySized
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

// TODO mpp compatible option
internal fun scaleLinear(size: Size, origin: Long, target: Long): BigDecimal {
    val sz = max(size.unSize, min(99, size.unSize))

    val z = origin.toBigDecimal()
    val n = target.toBigDecimal()
    val diff = (n - z) * (sz.toDouble() / 99).toBigDecimal()
    return z + diff
}

internal fun scaleLinear(size: Size, origin: Double, target: Double): BigDecimal {
    val sz = max(size.unSize, min(99, size.unSize))

    val z = origin.toBigDecimal()
    val n = target.toBigDecimal()
    val diff = (n - z) * (sz.toDouble() / 99).toBigDecimal()
    return z + diff
}

internal fun Long.clamp(x: Long, y: Long): Long =
    if (x > y) min(x, max(y, this))
    else min(y, max(x, this))

internal fun BigDecimal.clamp(x: Long, y: Long): Long =
    if (x > y) max(y.toBigDecimal()).min(x.toBigDecimal()).toLong()
    else max(x.toBigDecimal()).min(y.toBigDecimal()).toLong()

internal fun BigDecimal.clamp(x: Double, y: Double): Double =
    if (x > y) max(y.toBigDecimal()).min(x.toBigDecimal()).toDouble()
    else max(x.toBigDecimal()).min(y.toBigDecimal()).toDouble()
