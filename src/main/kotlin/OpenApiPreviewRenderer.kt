package dev.vmonot

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

private val resourceCache = ConcurrentHashMap<String, String>()

internal fun renderScalarPreviewHtml(
    fileName: String,
    specification: String,
    extension: String?,
    darkTheme: Boolean,
): String {
    val encodedSpecification = Base64.getEncoder().encodeToString(specification.toByteArray(StandardCharsets.UTF_8))
    val mimeType = when (extension?.lowercase()) {
        "json" -> "application/json"
        else -> "application/yaml"
    }

    val hiddenClients = OpenApiPreviewSettings.instance.hiddenScalarClients()
    val hiddenClientsJson = if (hiddenClients.isEmpty()) {
        "{}"
    } else {
        val grouped = hiddenClients.map { it.split(":") }.groupBy({ it[0] }, { it[1] })
        grouped.entries.joinToString(separator = ", ", prefix = "{", postfix = "}") { (lang, clients) ->
            "\"$lang\": [${clients.joinToString(", ") { "\"$it\"" }}]"
        }
    }

    return renderRendererTemplate(
        templateName = "scalar.html",
        fileName = fileName,
        encodedSpecification = encodedSpecification,
        mimeType = mimeType,
        darkTheme = darkTheme,
        rendererValues = mapOf(
            "VENDOR_SCALAR_JS" to inlineScript("preview/vendor/scalar-api-reference-1.55.0.js"),
            "SCALAR_INIT_JS" to inlineScript("preview/scripts/scalar-init.js", mapOf("HIDDEN_CLIENTS_JSON" to hiddenClientsJson)),
        ),
    )
}

internal fun renderErrorHtml(title: String, message: String, darkTheme: Boolean): String {
    return renderTemplate(
        "preview/templates/error.html",
        baseTemplateValues("OpenAPI Preview Error", darkTheme) + mapOf(
            "ERROR_TITLE" to escapeHtml(title),
            "ERROR_MESSAGE" to escapeHtml(message),
        ),
    )
}

private fun renderRendererTemplate(
    templateName: String,
    fileName: String,
    encodedSpecification: String,
    mimeType: String,
    darkTheme: Boolean,
    rendererValues: Map<String, String>,
): String {
    val configScript = renderResource(
        "preview/scripts/config.js",
        mapOf(
            "ENCODED_SPECIFICATION" to encodedSpecification,
            "MIME_TYPE" to mimeType,
            "DARK_THEME" to darkTheme.toString(),
        ),
    )

    return renderTemplate(
        "preview/templates/$templateName",
        baseTemplateValues(fileName, darkTheme) + mapOf(
            "CONFIG_JS" to escapeScript(configScript),
            "COMMON_PREVIEW_JS" to inlineScript("preview/scripts/common-preview.js"),
        ) + rendererValues,
    )
}

private fun baseTemplateValues(fileName: String, darkTheme: Boolean): Map<String, String> {
    val themeClass = if (darkTheme) {
        "dark-mode openapi-preview-dark"
    } else {
        "light-mode openapi-preview-light"
    }

    return mapOf(
        "TITLE" to escapeHtml(fileName),
        "HTML_CLASS" to themeClass,
        "BODY_CLASS" to themeClass,
        "COMMON_CSS" to inlineStyle("preview/styles/common.css", themeStyleValues(darkTheme)),
    )
}

private fun themeStyleValues(darkTheme: Boolean): Map<String, String> {
    return if (darkTheme) {
        mapOf(
            "COLOR_SCHEME" to "dark",
            "PAGE_BACKGROUND" to "#1e1f22",
            "TEXT_PRIMARY" to "#ced0d6",
            "TEXT_SECONDARY" to "#a9adb7",
            "BORDER_COLOR" to "#3d414a",
            "PANEL_BACKGROUND" to "#25282f",
        )
    } else {
        mapOf(
            "COLOR_SCHEME" to "light",
            "PAGE_BACKGROUND" to "#ffffff",
            "TEXT_PRIMARY" to "#1f2328",
            "TEXT_SECONDARY" to "#4b5563",
            "BORDER_COLOR" to "#d0d7de",
            "PANEL_BACKGROUND" to "#ffffff",
        )
    }
}

private fun inlineScript(path: String, values: Map<String, String> = emptyMap()): String =
    escapeScript(renderResource(path, values))

private fun inlineStyle(path: String, values: Map<String, String> = emptyMap()): String =
    escapeStyle(renderResource(path, values))

private fun renderTemplate(path: String, values: Map<String, String>): String =
    renderResource(path, values)

private fun renderResource(path: String, values: Map<String, String>): String {
    return values.entries.fold(readResource(path)) { text, (key, value) ->
        text.replace("{{$key}}", value)
    }
}

private fun readResource(path: String): String {
    val normalizedPath = if (path.startsWith("/")) path else "/$path"

    return resourceCache.computeIfAbsent(normalizedPath) {
        val stream = openResourceStream(normalizedPath)
            ?: error("Missing OpenAPI preview resource: $normalizedPath")

        stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }
}

private fun openResourceStream(normalizedPath: String) =
    PreviewResourceAnchor::class.java.getResourceAsStream(normalizedPath)
        ?: PreviewResourceAnchor::class.java.classLoader.getResourceAsStream(normalizedPath.removePrefix("/"))
        ?: Thread.currentThread().contextClassLoader?.getResourceAsStream(normalizedPath.removePrefix("/"))

private fun escapeScript(value: String): String =
    value.replace("</script", "<\\/script", ignoreCase = true)

private fun escapeStyle(value: String): String =
    value.replace("</style", "<\\/style", ignoreCase = true)

private fun escapeHtml(value: String): String =
    value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#039;")

private object PreviewResourceAnchor
