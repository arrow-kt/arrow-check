package arrow.check

/**
 * Top level config which controls how tests are run, but mostly deals with test output
 */
public data class Config(
  val useColor: UseColor,
  val verbose: Verbose
)

public sealed class Verbose {
  public object Quiet : Verbose()
  public object Normal : Verbose()
}

public sealed class UseColor {
  public object EnableColor : UseColor()
  public object DisableColor : UseColor()
}

public inline class TaskId(public val id: Int)

// look for env options and check terminal capabilities, env options have precedence
// TODO
internal suspend fun detectColor(): UseColor = UseColor.EnableColor

internal suspend fun detectVerbosity(): Verbose = Verbose.Quiet

internal suspend fun detectConfig(): Config =
  Config(
    detectColor(),
    detectVerbosity()
  )
