package arrow.check.gen

import arrow.core.toT

/**
 * Produce a sequence of [Long] values, starting from the destination and gradually approach the original value (in halves)
 */
fun Long.shrinkTowards(destination: Long): Sequence<Long> = when (destination) {
    this -> emptySequence()
    else -> {
        val diff = (this / 2) - (destination / 2) // overflow protection...
        sequenceOf(destination) + halves(diff).map { this - it }
    }
}

/**
 * Create a sequence of the original value halved over and over again until it reaches 0
 *
 * Used to implement [shrinkTowards]
 */
fun halves(i: Long): Sequence<Long> =
    generateSequence(i) { it / 2 }.takeWhile { it != 0L }


/**
 * Produce a sequence of [Double] values, starting from the destination and gradually approach the original value (in halves)
 * Stops as soon as it hit's itself, NaN or is infinite.
 *
 * TODO should this stop condition depend on an epsilon to prevent floating point inaccuracies that occur with large doubles?
 */
fun Double.shrinkTowards(destination: Double): Sequence<Double> = when (destination) {
    this -> emptySequence()
    else -> {
        val diff = this - destination
        val ok = { d: Double -> d != this && d.isNaN().not() && d.isInfinite().not() }
        sequenceOf(destination) + generateSequence(diff) { it / 2 }
            .map { this - it }
            .takeWhile(ok)
    }
}

/**
 * Shrink a list to smaller lists without shrinking the values in it.
 *
 * This starts with an emptylist and works it's way up the the original list's size (in halves)
 */
fun <A> List<A>.shrink(): Sequence<List<A>> = halves(size.toLong())
    .flatMap { removes(it.toInt()) }

/**
 * Remove n elements from a list
 *
 * Used to implement [shrink]
 */
fun <A> List<A>.removes(n: Int): Sequence<List<A>> = loopRemove(n, size)

private fun <A>List<A>.loopRemove(k: Int, n: Int): Sequence<List<A>> =
    (take(k) toT drop(k)).let { (head, tail) ->
        when {
            k > n -> emptySequence()
            tail.isEmpty() -> sequenceOf(emptyList())
            else -> sequenceOf(tail) + sequenceOf(Unit).flatMap {
                tail.loopRemove(k, n - k).map { head + it }
            }
        }
    }
