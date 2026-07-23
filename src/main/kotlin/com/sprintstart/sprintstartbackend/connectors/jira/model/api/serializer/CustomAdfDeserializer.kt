package com.sprintstart.sprintstartbackend.connectors.jira.model.api.serializer

import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ValueDeserializer

/**
 * Defines custom deserialization behavior that can be used for extracting text from a deeply nested adf json node.
 *
 * In practise, the Jira Cloud api often returns complex text sections that include special formats like paragraphs,
 * lists, enumerations, ... with all metadata, resulting in a nested response, like the following:
 *
 * ```json
 *{
 *  "type": "bulletList",
 *  "content": [{
 *      "type": "listItem",
 *      "content": [{
 *          "type": "paragraph",
 *          "content": [{
 *              "type": "text",
 *              "text": "JavaDoc is incomplete"
 *          }]
 *      }]
 *    },
 *    {
 *      "type": "listItem",
 *      "content": [{
 *          "type": "paragraph",
 *          "content": [{
 *              "type": "text",
 *              "text": "No transaction handling (usage of mongodb)"
 *          }]
 *      }]
 *    },
 *  ]
 *}
 * ```
 *
 * The above example, deserialized using this class, becomes a simple flat String:
 *
 * ```txt
 * JavaDoc is incomplete
 * No transaction handling (usage of mongodb)
 * ```
 *
 */
class CustomAdfDeserializer : ValueDeserializer<String>() {
    /**
     * Defines a custom deserialization behavior for fields, for extracting actual text value from a deeply nested adf
     * [JsonNode].
     *
     * @param parser The json parser used.
     * @param context The deserialization context (included for compatibility).
     * @return the text that was extracted.
     */
    override fun deserialize(parser: JsonParser, context: DeserializationContext): String {
        val node: JsonNode = parser.readValueAsTree()
        return extractTextValue(node)
    }

    /**
     * Extracts the actual text values from a deeply nested adf [JsonNode] and puts it into the given [StringBuilder].
     *
     * @param node The json node to search recursively.
     * @return the extracted string value.
     */
    private fun extractTextValue(node: JsonNode): String {
        val sb = StringBuilder()
        return extractTextValue(node, sb).toString().trim()
    }

    /**
     * Extracts the actual text values from a deeply nested adf [JsonNode] and puts it into the given [StringBuilder].
     *
     * @param node The json node to search recursively.
     * @param sb The sb to build the actual string content into.
     * @return the extracted string value.
     */
    private fun extractTextValue(node: JsonNode, sb: StringBuilder) {
        when {
            node.isObject -> {
                // Append text nodes
                if (node.has("type") && node["type"].toString() == "text" && node.has("text")) {
                    sb.append(node["text"].toString())
                }

                // Split element blocks properly
                val type = node["type"]?.toString()
                if (type in setOf("paragraph", "listItem", "heading")) {
                    if (sb.isNotEmpty() && !sb.endsWith("\n")) {
                        sb.append("\n")
                    }
                }

                node["content"]?.let { extractTextValue(it, sb) }
            }

            node.isArray -> {
                node.forEach { extractTextValue(it, sb) }
            }
        }
    }
}
