package arrow.check.gen

import arrow.core.Tuple2
import arrow.core.toT
import kotlinx.coroutines.flow.*

typealias Shrinks<A> = Flow<Rose<A>?>

internal fun <A, B> Shrinks<A>.mapRose(f: suspend (Rose<A>) -> Rose<B>?): Shrinks<B> =
    map { it?.let { f(it) } }

/**
 * A rose tree. Includes a result together with all its recursive shrinks.
 */
data class Rose<out A>(val res: A, val shrinks: Shrinks<A> = emptyFlow()) {
    /**
     * Map over the rose tree, which changes the result and all shrinks
     */
    fun <B> map(f: (A) -> B): Rose<B> = Rose(f(res), shrinks.map { it?.map(f) })

    /**
     * Zip two rose trees together, this does parallel shrinking.
     */
    fun <B, C> zip(other: Rose<B>, f: (A, B) -> C): Rose<C> =
        Rose(
            f(res, other.res),
            shrinks.mapRose { roseA ->
                roseA.zip(other, f)
            }.onCompletion {
                emitAll(other.shrinks.mapRose { roseB -> this@Rose.zip(roseB, f) })
            }
        )

    /**
     * FlatMap over a rose tree.
     *
     * This needs suspend in some cases and inline does not like recursive invocations.
     *
     * Shrinking from the result of [f] is appended to the shrinking of the initial generator.
     */
    suspend fun <B> flatMap(f: suspend (A) -> Rose<B>?): Rose<B>? = f(res)?.let { roseB ->
        Rose(roseB.res, shrinks.mapRose { it.flatMap(f) }.onCompletion { emitAll(roseB.shrinks) })
    }

    /**
     * Throw away the lowest [n] levels of shrinking.
     */
    fun prune(n: Int): Rose<A> =
        if (n <= 0) Rose(res)
        else Rose(res, shrinks.mapRose { it.prune(n - 1) })

    /**
     * Map and filter a rose tree.
     */
    fun <B> filterMap(f: (A) -> B?): Rose<B>? =
        f(res)?.let { Rose(it, shrinks.mapRose { it.filterMap(f) }) }
}

/**
 * Add new shrinks onto the [Rose] tree by "re-shrinking" the elements.
 */
fun <A> Rose<A>.expand(f: (A) -> Flow<A>): Rose<A> =
    Rose(res, shrinks.onCompletion { emitAll(f(res).map { Rose(it).expand(f) } as Shrinks<A>) })

internal fun <A> List<A>.uncons(): Tuple2<A, List<A>> = this[0] toT drop(1)
