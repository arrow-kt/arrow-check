package arrow.check

import arrow.check.property.Property
import arrow.check.property.PropertyConfig
import arrow.check.property.PropertyName
import arrow.check.property.PropertyTestSyntax
import arrow.check.property.property
import arrow.core.Tuple2
import arrow.core.some
import io.kotest.core.spec.createTestCase
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestName
import io.kotest.core.test.TestType
import io.kotest.runner.junit.platform.IntelliMarker

abstract class AbstractPropertySpec(f: AbstractPropertySpec.() -> Unit = {}) : StringSpec() {
    private val lawTestCases = mutableListOf<TestCase>()

    init {
        f()
    }

    override fun materializeRootTests(): List<TestCase> =
        super.materializeRootTests() + lawTestCases

    operator fun String.invoke(props: List<Tuple2<String, Property>>): Unit =
        createTestCase(
            TestName(name = this),
            {
                checkGroup(this@invoke, props)
                    .unsafeRunSync()
                    .let {
                        if (it.not()) throw AssertionError("Some tests failed!")
                    }
            },
            defaultConfig(),
            TestType.Test
        ).let { lawTestCases.add(it); Unit }

    operator fun String.invoke(
        propertyConfig: PropertyConfig = PropertyConfig(),
        c: suspend PropertyTestSyntax.() -> Unit
    ): Unit =
        createTestCase(
            TestName(name = this),
            {
                checkReport(PropertyName(this@invoke).some(), property(propertyConfig, c))
                    .unsafeRunSync()
                    .toException()
            },
            defaultConfig(),
            TestType.Test
        ).let { lawTestCases.add(it); Unit }

    operator fun String.invoke(
        args: Config,
        propertyConfig: PropertyConfig = PropertyConfig(),
        c: suspend PropertyTestSyntax.() -> Unit
    ): Unit =
        createTestCase(
            TestName(name = this),
            {
                checkReport(
                    args,
                    PropertyName(this@invoke).some(),
                    property(propertyConfig, c)
                )
                    .unsafeRunSync()
                    .toException()
            },
            defaultConfig(),
            TestType.Test
        ).let { lawTestCases.add(it); Unit }

    operator fun String.invoke(f: Property): Unit =
        createTestCase(
            TestName(name = this),
            {
                checkReport(PropertyName(this@invoke).some(), f)
                    .unsafeRunSync()
                    .toException()
            },
            defaultConfig(),
            TestType.Test
        ).let { lawTestCases.add(it); Unit }

    operator fun String.invoke(args: Config, f: Property): Unit =
        createTestCase(
            TestName(name = this),
            {
                checkReport(args, PropertyName(this@invoke).some(), f)
                    .unsafeRunSync()
                    .toException()
            },
            defaultConfig(),
            TestType.Test
        ).let { lawTestCases.add(it); Unit }
}

fun Report<Result>.toException(): Unit = when (status) {
    is Result.Success -> Unit
    is Result.GivenUp -> throw AssertionError("GaveUp!")
    is Result.Failure -> throw AssertionError("Failed!")
}

abstract class PropertySpec(f: AbstractPropertySpec.() -> Unit = {}) : AbstractPropertySpec(f), IntelliMarker
