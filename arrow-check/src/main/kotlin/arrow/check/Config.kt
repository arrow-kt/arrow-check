package arrow.check

import arrow.fx.IO
import arrow.fx.extensions.io.applicative.applicative
import arrow.fx.fix

data class Config(
    val useColor: UseColor,
    val verbose: Verbose
)

sealed class Verbose {
    object Quiet : Verbose()
    object Normal : Verbose()
}

sealed class UseColor {
    object EnableColor : UseColor()
    object DisableColor : UseColor()
}

inline class TaskId(val id: Int)

// look for env options and check terminal capabilities, env options have precedence
// TODO
fun detectColor(): IO<Nothing, UseColor> = IO.just(UseColor.EnableColor)

fun detectVerbosity(): IO<Nothing, Verbose> = IO.just(Verbose.Quiet)

fun detectConfig(): IO<Nothing, Config> = IO.applicative<Nothing>().map(
    detectColor(),
    detectVerbosity()
) { (useColor, verbosity) -> Config(useColor, verbosity) }.fix()
