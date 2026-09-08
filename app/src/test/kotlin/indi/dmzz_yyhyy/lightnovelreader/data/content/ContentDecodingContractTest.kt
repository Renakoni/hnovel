package indi.dmzz_yyhyy.lightnovelreader.data.content

import android.app.Application
import fixtures.content.FixtureData
import fixtures.content.FixtureSerializer
import fixtures.content.InjectedFixtureComponent
import fixtures.content.NoArgFixtureComponent
import fixtures.content.SingletonFixtureComponent
import fixtures.content.ThrowingFixtureComponent
import fixtures.content.UnresolvableFixtureComponent
import indi.dmzz_yyhyy.lightnovelreader.data.content.component.ErrorContentComponent
import indi.dmzz_yyhyy.lightnovelreader.data.content.component.ImageComponent
import indi.dmzz_yyhyy.lightnovelreader.data.content.component.SimpleTextComponent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.nightfish.lightnovelreader.api.content.ContentComponentRepositoryApi
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponentData
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.InvocationTargetException
import kotlin.reflect.KClass

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class ContentDecodingContractTest {
    private val host = ContentTestHost()
    private val repository = host.repository

    @Test
    fun pluginApiRegistrationUsesActualReflectionAndPreservesPerComponentOrder() {
        val events = mutableListOf<String>()
        host.initializeInjector()
        every { host.settings.intUserData(any()) } answers {
            events += firstArg<String>()
            mockk()
        }
        register(serializer = FixtureSerializer { events += "decode/$it" })
        val result = render("""{"components":[${entry("first")},${entry("second")}]}""")

        assertEquals(listOf("decode/first", "fixture/created/first", "decode/second", "fixture/created/second"), events)
        assertEquals(listOf(FixtureData("first"), FixtureData("second")), result.map { it.data })
        result.forEach { assertSame(host.settings, (it as InjectedFixtureComponent).settings) }
    }

    @Test
    fun builtInConstructorSignaturesStillResolveThroughThePluginInjector() {
        host.initializeInjector()
        val components = render("""{"components":[{"id":"simple_text","data":{"text":"body"}},{"id":"image","data":{"uri":"https://example.org/cover.png"}}]}""")
        assertEquals("body", (components[0] as SimpleTextComponent).data.text)
        assertEquals("https://example.org/cover.png", (components[1] as ImageComponent).data.uri.toString())
    }

    @Test
    fun singletonAndNoArgConstructionTakePrecedenceOverInjectedConstructors() {
        host.initializeInjector()
        register(component = SingletonFixtureComponent::class)
        assertSame(SingletonFixtureComponent, renderOne())
        register(component = NoArgFixtureComponent::class)
        assertEquals(FixtureData("no-arg"), renderOne().data)
        verify(exactly = 0) { host.settings.intUserData(any()) }
    }

    @Test
    fun registrationOverwritesAllMappingsAndPreviouslyReadMapsStaySnapshots() {
        val serializers = repository.serializeMap
        val types = repository.dataKClassMap
        val api: ContentComponentRepositoryApi = repository
        val id = Identifier("fixture", "text")
        assertEquals("builder missing parameters", assertThrows(Error::class.java) { api.registrar.id(id).register() }.message)
        assertFalse(repository.serializeMap.containsKey(id.toString()))
        register()
        assertFalse(serializers.containsKey(id.toString()))
        assertFalse(types.containsKey(id.toString()))
        val replacement = FixtureSerializer()
        register(component = NoArgFixtureComponent::class, serializer = replacement)
        assertSame(replacement, repository.serializeMap[id.toString()])
        assertEquals(FixtureData::class, repository.dataKClassMap[id.toString()])
        host.initializeInjector()
        assertTrue(renderOne() is NoArgFixtureComponent)
    }

    @Test
    fun absentFieldsBecomeOrderedErrorComponentsAndEmptyArraysStayEmpty() {
        val messages = render("""{"components":[{}, {"id":"simple_text"}, {"id":"unknown","data":{}}]}""")
            .map { (it as ErrorContentComponent).data.message }
        assertEquals(listOf("component id not found", "component data not found\nid=lightnovelreader:simple_text", "component class not found\nid=lightnovelreader:unknown"), messages)
        assertEquals("error to load components from json", (render("{}").single() as ErrorContentComponent).data.message)
        assertTrue(render("""{"components":[]}""").isEmpty())
    }

    @Test
    fun unresolvedConstructorBecomesErrorAfterDecoding() {
        host.initializeInjector()
        val decoded = mutableListOf<String>()
        register(component = UnresolvableFixtureComponent::class, serializer = FixtureSerializer { decoded += it })
        assertEquals("failed to init component", (renderOne() as ErrorContentComponent).data.message)
        assertEquals(listOf("body"), decoded)
    }

    @Test
    fun dataOnlyDecodingNeedsNoInjectorAndSkipsMissingEntriesWithoutConstructing() {
        register(component = ThrowingFixtureComponent::class)
        val data = mutableListOf<AbstractContentComponentData>()
        repository.getDataFromJsonObject(json("""{"components":[{}, {"id":"fixture:text"}, {"id":"unknown","data":{}}, ${entry("body")}]}"""), data::add)
        assertEquals(listOf(FixtureData("body")), data)
        assertNull(host.provider.value)
    }

    @Test
    fun shortIdsExpandOnlyInTheRenderingEntryPoint() {
        host.initializeInjector()
        val content = json("""{"components":[{"id":"simple_text","data":{"text":"short"}},{"id":"lightnovelreader:simple_text","data":{"text":"full"}}]}""")
        assertEquals(listOf("short", "full"), repository.getContentDataFromJson(content).components.map { (it.data as SimpleTextComponentData).text })
        val data = mutableListOf<AbstractContentComponentData>()
        repository.getDataFromJsonObject(content, data::add)
        assertEquals(listOf(SimpleTextComponentData("full")), data)
    }

    @Test
    fun invalidJsonTypesStillThrowAndArrayObjectsAreValidatedBeforeAnyDecode() {
        host.initializeInjector()
        var decodes = 0
        register(serializer = FixtureSerializer { decodes++ })
        for (input in listOf("""{"components":{}}""", """{"components":[${entry("body")},1]}""", """{"components":[{"id":{},"data":{}}]}""", """{"components":[{"id":"fixture:text","data":[]}]}""")) {
            assertThrows(IllegalArgumentException::class.java) { render(input) }
            assertThrows(IllegalArgumentException::class.java) { repository.getDataFromJsonObject(json(input)) {} }
        }
        assertEquals(0, decodes)
    }

    @Test
    fun serializerAndConstructorFailuresAreNotConvertedToErrorComponents() {
        host.initializeInjector()
        val failure = IllegalStateException("fixture decoder failed")
        register(serializer = FixtureSerializer { throw failure })
        assertSame(failure, assertThrows(IllegalStateException::class.java) { renderOne() })
        assertSame(failure, assertThrows(IllegalStateException::class.java) { repository.getDataFromJsonObject(json("""{"components":[${entry("body")}]}""")) {} })
        register(component = ThrowingFixtureComponent::class)
        val thrown = assertThrows(InvocationTargetException::class.java) { renderOne() }
        assertEquals("fixture constructor failed", thrown.cause?.message)
    }

    @Test
    fun injectorIsRequiredBeforeTheSerializerIsInvokedForRendering() {
        var decodes = 0
        register(serializer = FixtureSerializer { decodes++ })
        assertThrows(NullPointerException::class.java) { renderOne() }
        assertEquals(0, decodes)
    }

    @Test
    fun constructionUsesTheInjectorCapturedBeforeDecodingEvenIfSerializerReplacesIt() {
        host.initializeInjector()
        register(serializer = FixtureSerializer { host.initializeInjector(mockk(relaxed = true)) })
        assertSame(host.settings, (renderOne() as InjectedFixtureComponent).settings)
    }

    private fun register(
        component: KClass<out AbstractContentComponent<out AbstractContentComponentData>> = InjectedFixtureComponent::class,
        serializer: FixtureSerializer = FixtureSerializer(),
    ) {
        val api: ContentComponentRepositoryApi = repository
        api.registrar.id(Identifier("fixture", "text"))
            .component(component).data(FixtureData::class).serializer(serializer).register()
    }

    private fun renderOne() = render("""{"components":[${entry("body")}]}""").single()
    private fun render(input: String) = repository.getContentDataFromJson(json(input)).components
    private fun json(input: String): JsonObject = Json.parseToJsonElement(input).jsonObject
    private fun entry(text: String) = """{"id":"fixture:text","data":{"text":"$text"}}"""
}
