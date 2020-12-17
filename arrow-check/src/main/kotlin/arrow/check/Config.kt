package arrow.check

/**
 * Top level config which controls how tests are run, but mostly deals with test output
 */
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
suspend fun detectColor(): UseColor = UseColor.EnableColor

suspend fun detectVerbosity(): Verbose = Verbose.Quiet

suspend fun detectConfig(): Config =
    Config(
        detectColor(),
        detectVerbosity()
    )
