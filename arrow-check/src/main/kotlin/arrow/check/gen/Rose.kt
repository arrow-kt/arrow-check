package arrow.check.gen

import arrow.core.Tuple2
import arrow.core.toT
import kotlinx.coroutines.flow.*

typealias Shrinks<A> = Flow<Rose<A>?>

fun <A, B> Shrinks<A>.mapRose(f: suspend (Rose<A>) -> Rose<B>?): Shrinks<B> =
    map { it?.let { f(it) } }

data class Rose<out A>(val res: A, val shrinks: Shrinks<A> = emptyFlow()) {
    fun <B> map(f: (A) -> B): Rose<B> = Rose(f(res), shrinks.map { it?.map(f) })

    fun <B, C> zip(other: Rose<B>, f: (A, B) -> C): Rose<C> =
        Rose(
            f(res, other.res),
            shrinks.mapRose { roseA ->
                roseA.zip(other, f)
            }.onCompletion {
                emitAll(other.shrinks.mapRose { roseB -> this@Rose.zip(roseB, f) })
            }
        )

    suspend fun <B> flatMap(f: suspend (A) -> Rose<B>?): Rose<B>? = f(res)?.let { roseB ->
        Rose(roseB.res, shrinks.mapRose { it.flatMap(f) }.onCompletion { emitAll(roseB.shrinks) })
    }

    fun prune(n: Int): Rose<A> =
        if (n <= 0) Rose(res)
        else Rose(res, shrinks.mapRose { it?.prune(n - 1) })

    fun <B> filterMap(f: (A) -> B?): Rose<B>? =
        f(res)?.let { Rose(it, shrinks.mapRose { it?.filterMap(f) }) }
}

fun <A> Rose<A>.expand(f: (A) -> Flow<A>): Rose<A> =
    Rose(res, shrinks.onCompletion { emitAll(f(res).map { Rose(it).expand(f) } as Shrinks<A>) })

internal fun <A> List<A>.uncons(): Tuple2<A, List<A>> = this[0] toT drop(1)
