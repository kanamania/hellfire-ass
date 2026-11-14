package com.hellfire.ass.reports

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.OutputStream

/** Everything an export needs: title, scope, KPI lines, the visible table and the underlying records. */
data class ExportSpec(
    val title: String,
    val subtitle: String,
    val kpis: List<Pair<String, String>>,
    val columns: List<String>,
    val rows: List<List<String>>,
    val totals: List<String>?,
    val records: List<String>,
    val recordRows: List<List<String>>,
    /** Index of the source-ID column in [records], removed when the owner turns IDs off. */
    val sourceColumn: Int? = null,
    /** Numeric value per table row for the PDF bar chart, parallel to [rows]. */
    val chart: List<Long> = emptyList(),
)

object Csv {
    fun cell(value: String): String {
        // Neutralise spreadsheet formulas, then quote per RFC 4180.
        val safe = if (value.isNotEmpty() && value[0] in "=+-@\t\r" && value.toDoubleOrNull() == null) "'$value" else value
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }
    fun write(headers: List<String>, rows: List<List<String>>): String =
        (listOf(headers) + rows).joinToString("\r\n", postfix = "\r\n") { row -> row.joinToString(",") { cell(it) } }
    fun table(spec: ExportSpec): String = write(spec.columns, spec.rows + listOfNotNull(spec.totals))
    fun records(spec: ExportSpec, includeIds: Boolean): String {
        val drop = spec.sourceColumn.takeIf { !includeIds }
        fun strip(r: List<String>) = if (drop == null) r else r.filterIndexed { i, _ -> i != drop }
        return write(strip(spec.records), spec.recordRows.map(::strip))
    }
}

/** A4 PDF: title, scope and filters, KPIs, a bar chart of the table's main figure, then the table with totals. */
object PdfReport {
    fun write(spec: ExportSpec, out: OutputStream) {
        val doc = PdfDocument()
        val width = 595; val height = 842; val margin = 40f
        val title = Paint().apply { textSize = 18f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); isAntiAlias = true }
        val body = Paint().apply { textSize = 9.5f; isAntiAlias = true }
        val bold = Paint(body).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val muted = Paint(body).apply { color = 0xFF5B6470.toInt() }
        val bar = Paint().apply { color = 0xFFC2410C.toInt() }
        val rule = Paint().apply { color = 0xFFE4E7EB.toInt(); strokeWidth = 0.8f }
        var pageNo = 0
        var page = doc.startPage(PdfDocument.PageInfo.Builder(width, height, ++pageNo).create())
        var y = margin
        fun newPage() { doc.finishPage(page); page = doc.startPage(PdfDocument.PageInfo.Builder(width, height, ++pageNo).create()); y = margin }
        fun line(text: String, paint: Paint, x: Float = margin, step: Float = 14f) {
            if (y + step > height - margin) newPage()
            y += step; page.canvas.drawText(text.take(110), x, y, paint)
        }
        line(spec.title, title, step = 20f)
        line(spec.subtitle, muted)
        line("HellFire Ass · generated on this phone · amounts in TZS unless stated", muted)
        y += 8f
        spec.kpis.forEach { (k, v) -> line("$k: $v", body) }
        if (spec.chart.isNotEmpty() && spec.chart.any { it > 0 }) {
            y += 10f; line("Chart", bold)
            val max = spec.chart.max().coerceAtLeast(1)
            spec.rows.zip(spec.chart).take(12).forEach { (row, v) ->
                if (y + 16f > height - margin) newPage()
                y += 16f
                page.canvas.drawText(row.first().take(24), margin, y, body)
                page.canvas.drawRect(margin + 130f, y - 8f, margin + 130f + 300f * v / max, y, bar)
                page.canvas.drawText(row.getOrNull(spec.columns.size - 1).orEmpty().take(16), margin + 440f, y, muted)
            }
        }
        y += 14f
        val colWidth = (width - 2 * margin) / spec.columns.size.coerceAtLeast(1)
        fun tableRow(cells: List<String>, paint: Paint) {
            if (y + 15f > height - margin) newPage()
            y += 15f
            cells.forEachIndexed { i, c -> page.canvas.drawText(c.take((colWidth / 5.2f).toInt().coerceAtLeast(4)), margin + i * colWidth, y, paint) }
            page.canvas.drawLine(margin, y + 4f, width - margin, y + 4f, rule)
        }
        tableRow(spec.columns, bold)
        spec.rows.forEach { tableRow(it, body) }
        spec.totals?.let { tableRow(it, bold) }
        y += 10f
        line("Only confirmed records are included where the report says so. Source messages are kept unchanged as evidence.", muted)
        doc.finishPage(page)
        try { doc.writeTo(out) } finally { doc.close() }
    }
}
