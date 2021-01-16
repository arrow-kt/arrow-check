package arrow.check

import arrow.check.gen.RandSeed
import arrow.check.property.Property
import arrow.check.property.PropertyConfig
import arrow.check.property.PropertyName
import arrow.check.property.PropertyTest
import arrow.check.property.Size
import arrow.check.property.property
import arrow.check.property.runProperty
import arrow.core.Tuple2
import arrow.core.toT
import kotlin.random.Random

suspend fun checkGroup(groupName: String, vararg props: Pair<String, Property>): Boolean =
    checkGroup(detectConfig(), groupName, *props)

suspend fun checkGroup(config: Config, groupName: String, vararg props: Pair<String, Property>): Boolean =
    checkGroup(config, groupName, *props.map { (n, p) -> n toT p }.toTypedArray())

suspend fun checkGroup(groupName: String, vararg props: Tuple2<String, Property>): Boolean =
    checkGroup(detectConfig(), groupName, *props)

suspend fun checkGroup(config: Config, groupName: String, vararg props: Tuple2<String, Property>): Boolean {
    println("━━━ $groupName ━━━")

    val summary =
        props.fold(Summary.monoid().empty().copy(waiting = PropertyCount(props.size))) { acc, (n, prop) ->
            val res = checkReport(config, PropertyName(n), prop)
            Summary.monoid().run {
                acc + empty().copy(waiting = PropertyCount(-1)) +
                        when (res.status) {
                            is Result.Failure -> empty().copy(failed = PropertyCount(1))
                            is Result.Success -> empty().copy(successful = PropertyCount(1))
                            is Result.GivenUp -> empty().copy(gaveUp = PropertyCount(1))
                        }
            }
        }

    return summary.failed.unPropertyCount == 0 && summary.gaveUp.unPropertyCount == 0
}

suspend fun check(
    propertyConfig: PropertyConfig = PropertyConfig(),
    c: suspend PropertyTest.() -> Unit
): Boolean =
    check(property(propertyConfig, c))

suspend fun check(
    config: Config,
    propertyConfig: PropertyConfig = PropertyConfig(),
    c: suspend PropertyTest.() -> Unit
): Boolean =
    check(config, property(propertyConfig, c))

suspend fun check(prop: Property): Boolean =
    check(detectConfig(), prop)

suspend fun check(config: Config, prop: Property): Boolean = check(config, null, prop)

suspend fun recheck(size: Size, seed: RandSeed, prop: Property): Unit =
    recheck(detectConfig(), size, seed, prop)

suspend fun recheck(
    size: Size,
    seed: RandSeed,
    propertyConfig: PropertyConfig = PropertyConfig(),
    c: suspend PropertyTest.() -> Unit
): Unit = recheck(size, seed, property(propertyConfig, c))

suspend fun recheck(
    config: Config,
    size: Size,
    seed: RandSeed,
    propertyConfig: PropertyConfig = PropertyConfig(),
    c: suspend PropertyTest.() -> Unit
): Unit = recheck(config, size, seed, property(propertyConfig, c))

suspend fun recheck(config: Config, size: Size, seed: RandSeed, prop: Property): Unit {
    checkReport(seed, size, config, null, prop)
}

suspend fun checkNamed(
    name: String,
    propertyConfig: PropertyConfig = PropertyConfig(),
    c: suspend PropertyTest.() -> Unit
): Boolean = checkNamed(name, property(propertyConfig, c))

suspend fun checkNamed(name: String, prop: Property): Boolean =
    check(detectConfig(), PropertyName(name), prop)

suspend fun checkNamed(
    config: Config,
    name: String,
    propertyConfig: PropertyConfig = PropertyConfig(),
    c: suspend PropertyTest.() -> Unit
): Boolean = check(config, PropertyName(name), property(propertyConfig, c))

suspend fun checkNamed(config: Config, name: String, prop: Property): Boolean =
    check(config, PropertyName(name), prop)

suspend fun check(config: Config, name: PropertyName?, prop: Property): Boolean =
    checkReport(config, name, prop).status is Result.Success

suspend fun checkReport(name: PropertyName?, prop: Property): Report<Result> =
    checkReport(RandSeed(Random.nextLong()), Size(0), detectConfig(), name, prop)

suspend fun checkReport(config: Config, name: PropertyName?, prop: Property): Report<Result> =
    checkReport(RandSeed(Random.nextLong()), Size(0), config, name, prop)

suspend fun checkReport(
    seed: RandSeed,
    size: Size,
    config: Config,
    name: PropertyName?,
    prop: Property
): Report<Result> {
    val report = runProperty(size, seed, prop.config, prop.prop) {
        // TODO Live update will come back once I finish concurrent output
    }
    println(report.renderResult(config.useColor, name))
    return report
}
