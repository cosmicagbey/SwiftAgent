package com.momo.swift.util

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.momo.swift.data.TransactionLogEntry
import com.momo.swift.data.TransactionStatus
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TransactionExporter {

    private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val CURRENCY_FMT = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }

    // ── CSV ──────────────────────────────────────────────────────────────────

    fun exportCsv(context: Context, entries: List<TransactionLogEntry>) {
        try {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, "momoswift_transactions.csv")

            file.bufferedWriter().use { w ->
                w.write("Date,Type,Phone number,Amount")
                w.newLine()
                entries.forEach { e ->
                    val date = DATE_FMT.format(Date(e.timestamp))
                    w.write("\"$date\",\"${e.name}\",\"${e.phone}\",\"${e.amount}\"")
                    w.newLine()
                }
            }

            shareFile(context, file, "text/csv", "Share CSV")
        } catch (e: Exception) {
            Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ── PDF ──────────────────────────────────────────────────────────────────

    fun exportPdf(context: Context, entries: List<TransactionLogEntry>) {
        try {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, "momoswift_transactions.pdf")

            val pageWidth = 595   // A4 width in points
            val pageHeight = 842  // A4 height in points
            val margin = 40f
            val lineHeight = 18f
            val usableWidth = pageWidth - 2 * margin
            val maxLinesPerPage = ((pageHeight - 2 * margin - 60) / lineHeight).toInt()

            val document = PdfDocument()

            val titlePaint = Paint().apply {
                textSize = 18f; isFakeBoldText = true; isAntiAlias = true
            }
            val headerPaint = Paint().apply {
                textSize = 10f; isFakeBoldText = true; isAntiAlias = true
            }
            val bodyPaint = Paint().apply {
                textSize = 9f; isAntiAlias = true
            }
            val linePaint = Paint().apply {
                strokeWidth = 0.5f; color = android.graphics.Color.LTGRAY
            }

            // Column widths (proportional)
            val cols = floatArrayOf(0.25f, 0.30f, 0.25f, 0.20f)
            val colHeaders = arrayOf("Date", "Type", "Phone number", "Amount")

            // Calculate totals for footer
            val successTotal = entries
                .filter { it.status == TransactionStatus.SUCCESS }
                .mapNotNull { it.amount.toDoubleOrNull() }
                .sum()

            var pageNum = 0
            var lineIndex = 0
            var page: PdfDocument.Page? = null
            var canvas: Canvas? = null
            var y = 0f

            fun startNewPage() {
                page?.let { document.finishPage(it) }
                pageNum++
                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
                page = document.startPage(pageInfo)
                canvas = page!!.canvas
                y = margin

                if (pageNum == 1) {
                    canvas!!.drawText("MomoSwift — Transaction Report", margin, y + 18f, titlePaint)
                    y += 30f
                    val dateStr = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date())
                    canvas!!.drawText("Generated: $dateStr  •  ${entries.size} transactions  •  Total: GHS ${CURRENCY_FMT.format(successTotal)}", margin, y + 10f, bodyPaint)
                    y += 24f
                    canvas!!.drawLine(margin, y, pageWidth - margin, y, linePaint)
                    y += 12f
                }

                // Draw column headers
                var x = margin
                cols.forEachIndexed { i, pct ->
                    canvas!!.drawText(colHeaders[i], x, y + 10f, headerPaint)
                    x += usableWidth * pct
                }
                y += lineHeight
                canvas!!.drawLine(margin, y, pageWidth - margin, y, linePaint)
                y += 6f
                lineIndex = 0
            }

            startNewPage()

            entries.forEach { entry ->
                if (lineIndex >= maxLinesPerPage) startNewPage()

                val date = DATE_FMT.format(Date(entry.timestamp))
                val row = arrayOf(
                    date,
                    entry.name.take(25),
                    entry.phone.take(15),
                    if (entry.amount.isNotBlank()) entry.amount else "-"
                )

                var x = margin
                cols.forEachIndexed { i, pct ->
                    canvas!!.drawText(row[i], x, y + 10f, bodyPaint)
                    x += usableWidth * pct
                }
                y += lineHeight
                lineIndex++
            }

            // Finish last page
            page?.let { document.finishPage(it) }

            FileOutputStream(file).use { document.writeTo(it) }
            document.close()

            shareFile(context, file, "application/pdf", "Share PDF")
        } catch (e: Exception) {
            Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ── Share helper ────────────────────────────────────────────────────────

    private fun shareFile(context: Context, file: File, mimeType: String, title: String) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, title))
    }
}
