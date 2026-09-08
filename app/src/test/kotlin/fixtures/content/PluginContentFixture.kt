package fixtures.content

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponentData
import io.nightfish.lightnovelreader.api.content.component.ComponentDataJsonElementSerializer
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.userdata.UserDataRepositoryApi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.dom4j.DocumentHelper

// Deliberately imports only plugin APIs, not host implementation classes.
data class FixtureData(val text: String) : AbstractContentComponentData() {
    override val id = Identifier("fixture", "text")
    override fun toJsonElement() = buildJsonObject { put("text", text) }
    override fun toHtmlElement(context: Context) = DocumentHelper.createElement("p").addText(text)
}

class FixtureSerializer(
    private val onDecode: (String) -> Unit = {},
) : ComponentDataJsonElementSerializer<FixtureData> {
    override fun toJsonElement(data: FixtureData) = data.toJsonElement()
    override fun fromJsonElement(json: JsonElement): FixtureData {
        val text = json.jsonObject.getValue("text").jsonPrimitive.content
        onDecode(text)
        return FixtureData(text)
    }
}

class InjectedFixtureComponent(
    data: FixtureData,
    val settings: UserDataRepositoryApi,
) : AbstractContentComponent<FixtureData>(data) {
    init { settings.intUserData("fixture/created/${data.text}") }
    override val id = data.id
    @Composable override fun Content(modifier: Modifier) = Unit
}

object SingletonFixtureComponent : AbstractContentComponent<FixtureData>(FixtureData("singleton")) {
    override val id = data.id
    @Composable override fun Content(modifier: Modifier) = Unit
}

class NoArgFixtureComponent() : AbstractContentComponent<FixtureData>(FixtureData("no-arg")) {
    constructor(data: FixtureData, settings: UserDataRepositoryApi) : this() {
        settings.intUserData("fixture/secondary/${data.text}")
    }
    override val id = data.id
    @Composable override fun Content(modifier: Modifier) = Unit
}

class UnresolvableFixtureComponent(data: FixtureData, val missing: Runnable) :
    AbstractContentComponent<FixtureData>(data) {
    override val id = data.id
    @Composable override fun Content(modifier: Modifier) = Unit
}

class ThrowingFixtureComponent(data: FixtureData) : AbstractContentComponent<FixtureData>(data) {
    init { error("fixture constructor failed") }
    override val id = data.id
    @Composable override fun Content(modifier: Modifier) = Unit
}
