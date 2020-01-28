package arrow.check.gen

import arrow.core.Tuple2
import arrow.core.andThen
import arrow.core.toT
import arrow.check.property.Size
import java.math.BigDecimal
import kotlin.math.max
import kotlin.math.min

/**
 * A range represents an origin and size-dependent bounds for any value.
 *
 * It is used in generators to control various things, mostly the size and range of generated data.
 *
 * The origin is used as a target when shrinking. For example numbers will shrink towards the origin rather than plain 0.
 *
 * To create a range take a look at the helpers on the [Range.Companion] object.
 */
data class Range<A>(val origin: A, val bounds: (Size) -> Tuple2<A, A>) {

    fun <B> map(f: (A) -> B): Range<B> = Range(
        f(origin),
        bounds andThen { (a, b) -> f(a) toT f(b) }
    )

    fun lowerBound(s: Size): A = bounds(s).a
    fun upperBound(s: Size): A = bounds(s).b

    companion object {

        /**
         * Create a singleton range that has [a] as the origin, lower- and upper-bound.
         *
         * When used on, e.g. [MonadGen.int] this will yield a constant number which will not shrink.
         */
        fun <A> singleton(a: A): Range<A> = Range(a) { a toT a }

        /**
         * Create a constant generator which ignores the size parameter and outputs [start] and [end] as lower- and upper-bound respectively.
         *
         * The origin will be [start].
         */
        fun <A> constant(start: A, end: A): Range<A> = Range(start) { start toT end }

        /**
         * Create a constant generator which ignores the size parameter and outputs [start] and [end] as lower- and upper-bound respectively and also has a custom origin.
         */
        fun <A> constant(origin: A, start: A, end: A): Range<A> = Range(origin) { start toT end }

        /**
         * Create a constant generator using the [IntRange]'s first and last elements for its bounds.
         *
         * The origin will be the first element.
         */
        fun constant(range: IntRange): Range<Int> = constant(range.first, range.last)

        /**
         * Create a constant generator using the [CharRange]'s first and last elements for its bounds.
         *
         * The origin will be the first element.
         */
        fun constant(range: CharRange): Range<Char> = constant(range.first, range.last)

        /**
         * Create a constant generator using the [LongRange]'s first and last elements for its bounds.
         *
         * The origin will be the first element.
         */
        fun constant(range: LongRange): Range<Long> = constant(range.first, range.last)

        /**
         * Create a constant generator using the [IntProgression]'s first and last elements for its bounds.
         *
         * The origin will be the first element.
         *
         * Note: This ignores step
         */
        fun constant(range: IntProgression): Range<Int> = constant(range.first, range.last)

        /**
         * Create a constant generator using the [CharProgression]'s first and last elements for its bounds.
         *
         * The origin will be the first element.
         *
         * Note: This ignores step
         */
        fun constant(range: CharProgression): Range<Char> = constant(range.first, range.last)

        /**
         * Create a constant generator using the [LongProgression]'s first and last elements for its bounds.
         *
         * The origin will be the first element.
         *
         * Note: This ignores step
         */
        fun constant(range: LongProgression): Range<Long> = constant(range.first, range.last)

        /**
         * Create an int range which will advance its bounds linear relative to the size parameter.
         *
         * Note: The size parameter as of now is fixed to a 0-99 range. [linear] will distribute `start..end` properly.
         *
         * The origin will be [start].
         */
        fun linear(start: Int, end: Int): Range<Int> = linearFrom(start, start, end)

        /**
         * Create an int range which will advance its bounds linear relative to the size parameter.
         *
         * Note: The size parameter as of now is fixed to a 0-99 range. [linear] will distribute `start..end` properly.
         */
        fun linearFrom(origin: Int, start: Int, end: Int): Range<Int> = Range(origin) { s ->
            val xSized = scaleLinear(s, origin, start).clamp(start.toLong(), end.toLong()).toInt()
            val ySized = scaleLinear(s, origin, end).clamp(start.toLong(), end.toLong()).toInt()
            xSized toT ySized
        }

        /**
         * Create a char range which will advance its bounds linear relative to the size parameter.
         *
         * Note: The size parameter as of now is fixed to a 0-99 range. [linear] will distribute `start..end` properly.
         *
         * The origin will be [start].
         */
        fun linear(start: Char, end: Char): Range<Char> = linearFrom(start, start, end)

        /**
         * Create a char range which will advance its bounds linear relative to the size parameter.
         *
         * Note: The size parameter as of now is fixed to a 0-99 range. [linear] will distribute `start..end` properly.
         */
        fun linearFrom(origin: Char, start: Char, end: Char): Range<Char> =
            linearFrom(origin.toInt(), start.toInt(), end.toInt()).map { it.toChar() }

        /**
         * Create a byte range which will advance its bounds linear relative to the size parameter.
         *
         * Note: The size parameter as of now is fixed to a 0-99 range. [linear] will distribute `start..end` properly.
         *
         * The origin will be [start].
         */
        fun linear(start: Byte, end: Byte): Range<Byte> = linearFrom(start, start, end)

        /**
         * Create a byte range which will advance its bounds linear relative to the size parameter.
         *
         * Note: The size parameter as of now is fixed to a 0-99 range. [linear] will distribute `start..end` properly.
         */
        fun linearFrom(origin: Byte, start: Byte, end: Byte): Range<Byte> =
            linearFrom(origin.toInt(), start.toInt(), end.toInt()).map { it.toByte() }

        /**
         * Create a long range which will advance its bounds linear relative to the size parameter.
         *
         * Note: The size parameter as of now is fixed to a 0-99 range. [linear] will distribute `start..end` properly.
         *
         * The origin will be [start].
         */
        fun linear(start: Long, end: Long): Range<Long> = linearFrom(start, start, end)

        /**
         * Create a long range which will advance its bounds linear relative to the size parameter.
         *
         * Note: The size parameter as of now is fixed to a 0-99 range. [linear] will distribute `start..end` properly.
         */
        fun linearFrom(origin: Long, start: Long, end: Long): Range<Long> = Range(origin) { s ->
            val xSized = scaleLinear(s, origin, start).clamp(start, end)
            val ySized = scaleLinear(s, origin, end).clamp(start, end)
            xSized toT ySized
        }

        /**
         * Create a float range which will advance its bounds linear relative to the size parameter.
         *
         * Note: The size parameter as of now is fixed to a 0-99 range. [linear] will distribute `start..end` properly.
         *
         * The origin will be [start].
         */
        fun linear(start: Float, end: Float): Range<Float> = linearFrom(start, start, end)

        /**
         * Create a float range which will advance its bounds linear relative to the size parameter.
         *
         * Note: The size parameter as of now is fixed to a 0-99 range. [linear] will distribute `start..end` properly.
         */
        fun linearFrom(origin: Float, start: Float, end: Float): Range<Float> =
            linearFrom(origin.toDouble(), start.toDouble(), end.toDouble()).map { it.toFloat() }

        /**
         * Create a double range which will advance its bounds linear relative to the size parameter.
         *
         * Note: The size parameter as of now is fixed to a 0-99 range. [linear] will distribute `start..end` properly.
         *
         * The origin will be [start].
         */
        fun linear(start: Double, end: Double): Range<Double> = linearFrom(start, start, end)

        /**
         * Create a double range which will advance its bounds linear relative to the size parameter.
         *
         * Note: The size parameter as of now is fixed to a 0-99 range. [linear] will distribute `start..end` properly.
         */
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
