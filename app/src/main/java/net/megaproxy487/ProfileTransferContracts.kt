package net.megaproxy487

import net.megaproxy487.data.*

internal fun requireExportContent(content: String?): String {
    if (content == null) throw UiException(R.string.export_expired)
    if (content.isEmpty()) throw UiException(R.string.export_empty)
    return content
}

internal sealed interface ParsedProfileImport {
    data class Configuration(val value: PortableConfiguration) : ParsedProfileImport
    data class ProxyList(val value: ProxyListImportResult, val summaryRes: Int) : ParsedProfileImport
}

/** Read/parse off the main thread before presenting review dialogs or committing changes. */
internal fun parseProfileImport(text: String, mimeType: String?, fileName: String): ParsedProfileImport {
    val json = mimeType == "application/json" || fileName.substringAfterLast('.', "").equals("json", true) ||
        text.trimStart().startsWith('{')
    if (json) {
        val root = boundedJsonObject(text)
        return if (ConfigTransfer.isSupportedSchema(root.optString("schema"))) {
            ParsedProfileImport.Configuration(ConfigTransfer.importJson(text))
        } else ParsedProfileImport.ProxyList(FoxyProxyParser.parse(text).getOrThrow(), R.string.imported_foxyproxy)
    }
    return if (SuperProxyParser.matches(text)) {
        ParsedProfileImport.ProxyList(SuperProxyParser.parse(text).getOrThrow(), R.string.imported_super_proxy)
    } else ParsedProfileImport.ProxyList(ProxyListParser.parse(text).getOrThrow(), R.string.imported_https_profiles)
}
