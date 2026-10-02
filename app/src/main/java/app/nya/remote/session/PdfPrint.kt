package app.nya.remote.session

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import java.io.File

/** Print jobs from the host (PDF) through Android's printing (any installed print service, or "Save as PDF"). */
object PdfPrint {
    fun print(context: Context, pdf: File) {
        val pm = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        pm.print(pdf.nameWithoutExtension, Adapter(pdf), null)
    }

    private class Adapter(private val pdf: File) : PrintDocumentAdapter() {
        override fun onLayout(
            old: PrintAttributes?,
            new: PrintAttributes?,
            cancel: CancellationSignal?,
            callback: LayoutResultCallback,
            extras: Bundle?,
        ) {
            if (cancel?.isCanceled == true) {
                callback.onLayoutCancelled()
                return
            }
            val info = PrintDocumentInfo.Builder(pdf.name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build()
            callback.onLayoutFinished(info, old != new)
        }

        override fun onWrite(pages: Array<out PageRange>?, dest: ParcelFileDescriptor, cancel: CancellationSignal?, callback: WriteResultCallback) {
            try {
                pdf.inputStream().use { input -> ParcelFileDescriptor.AutoCloseOutputStream(dest).use { input.copyTo(it) } }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback.onWriteFailed(e.message)
            }
        }
    }
}
