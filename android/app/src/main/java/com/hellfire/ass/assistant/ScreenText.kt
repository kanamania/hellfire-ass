package com.hellfire.ass.assistant

import android.app.assist.AssistStructure
import android.text.InputType

/**
 * Text on the screen the owner invoked HellFire on (audit HF-065). Only read when the owner turned on
 * "Read the screen when I call HellFire" and Android's own "Use text from screen" allows it; apps that mark their
 * screens secure provide nothing. Kept in the session only; saving it is an explicit tap.
 */
object ScreenText {
    data class Node(val text: String?, val password: Boolean, val children: List<Node> = emptyList())
    const val LIMIT = 4000

    /** Visible text, password fields skipped, duplicates dropped, at most [LIMIT] characters. Pure, for tests. */
    fun collect(roots: List<Node>): String {
        val out = LinkedHashSet<String>()
        fun walk(node: Node, depth: Int) {
            if (depth > 60 || out.sumOf { it.length } > LIMIT) return
            if (!node.password) node.text?.trim()?.takeIf { it.length >= 2 }?.let { out += it }
            node.children.forEach { walk(it, depth + 1) }
        }
        roots.forEach { walk(it, 0) }
        return out.joinToString("\n").take(LIMIT)
    }

    private fun AssistStructure.ViewNode.password(): Boolean {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT && variation in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) ||
            (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }
    private fun AssistStructure.ViewNode.node(): Node =
        Node(text?.toString() ?: contentDescription?.toString(), password(), (0 until childCount).map { getChildAt(it).node() })

    fun from(structure: AssistStructure?): String =
        if (structure == null) "" else collect((0 until structure.windowNodeCount).map { structure.getWindowNodeAt(it).rootViewNode.node() })

    fun enabled(context: android.content.Context) = context.getSharedPreferences("capture", 0).getBoolean("assist_screen", false)
}
