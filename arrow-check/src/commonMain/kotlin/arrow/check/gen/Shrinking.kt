package arrow.check.gen

/**
 * Shrink the number towards a destination.
 */
// TODO Use binary search algorithm instead of halves to reduce duplication
//  https://github.com/hedgehogqa/haskell-hedgehog/pull/413/
public fun Long.shrinkTowards(destination: Long): Sequence<Long> = when (destination) {
    this -> emptySequence()
    else -> {
        val diff = (this / 2) - (destination / 2)
        sequenceOf(destination) + halves(diff).map { this - it }
    }
}

/**
 * Shrink the number towards a destination.
 */
public fun Double.shrinkTowards(destination: Double): Sequence<Double> = when (destination) {
    this -> emptySequence()
    else -> {
        val diff = this - destination
        val ok = { d: Double -> d != this && d.isNaN().not() && d.isInfinite().not() }
        sequenceOf(destination) + iterate(diff) { it / 2 }
            .map { this - it }
            .takeWhile(ok)
    }
}

/**
 * Shrink the list, this will only shrink the size.
 *
 * This is only used to add shrinking to collections, it does not shrink individual elements.
 *
 * > To have elements itself also shrink you have to either manually write the shrinker
 *  (use [Gen.shrink]) or have shrinking already be present before.
 *  Or use [Gen.list] to generate the list, which already implements nested recursive shrinking.
 */
public fun <A> List<A>.shrink(): Sequence<List<A>> = halves(size.toLong())
    .flatMap { removes(it.toInt()) }

internal fun <A> List<A>.removes(n: Int): Sequence<List<A>> = loopRemove(n, size)

private fun <A> List<A>.loopRemove(k: Int, n: Int): Sequence<List<A>> =
    (take(k) to drop(k)).let { (head, tail) ->
        when {
            k > n -> emptySequence()
            tail.isEmpty() -> sequenceOf(emptyList())
            else -> sequenceOf(tail) + sequenceOf(Unit).flatMap {
                tail.loopRemove(k, n - k).map { head + it }
            }
        }
    }

internal fun <T : Any> iterate(start: T, f: (T) -> T) = generateSequence(start) { f(it) }

private fun halves(i: Long): Sequence<Long> =
    generateSequence(i) { it / 2 }.takeWhile { it != 0L }
