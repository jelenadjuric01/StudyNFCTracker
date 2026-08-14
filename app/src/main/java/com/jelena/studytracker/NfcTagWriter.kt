package com.jelena.studytracker

import android.content.Context
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import java.io.IOException

/**
 * Writes one of the two payloads onto a physical tag.
 *
 * Separate from [MainActivity] because it is the one genuinely fiddly piece of NFC in the app and
 * has nothing to do with the screen: connect, check, write, close, in the right order, on the right
 * thread.
 */
class NfcTagWriter(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Writes [payload] to [tag] as a single NDEF record carrying [TAG_MIME_TYPE], formatting the tag
     * first if it has never been written to.
     *
     * The MIME type is the important part: it is what makes Android launch this app when the tag is
     * tapped. A plain text or URL record would be handed to some other app instead.
     *
     * Blocking radio I/O, so it must be called from a background thread — in practice
     * [MainActivity.onTagDiscovered], which Android already calls off the main thread.
     *
     * @throws IOException if the tag is locked, is not NDEF capable, or the write fails partway
     *   through — including when the tag is pulled out of range mid-write.
     */
    fun write(tag: Tag, payload: String) {
        val record = NdefRecord.createMime(TAG_MIME_TYPE, payload.toByteArray(Charsets.UTF_8))
        val message = NdefMessage(arrayOf(record))

        // Ndef.get returns non-null only for a tag that already carries an NDEF structure, i.e. one
        // that has been written to before.
        //
        // `use` is Kotlin's try-with-resources — tag technologies are Closeable, so the radio
        // connection closes even if something throws. The `return` is a non-local return: it exits
        // write() entirely rather than just the lambda, which is legal because `use` is inline, and
        // the connection still closes on the way out.
        Ndef.get(tag)?.use { ndef ->
            ndef.connect()
            if (!ndef.isWritable) throw IOException(appContext.getString(R.string.tag_locked))
            ndef.writeNdefMessage(message)
            return
        }

        // A factory-blank tag has no NDEF structure yet, so it needs formatting and writing in one
        // operation. Skipping this branch is a common bug — every brand-new tag would fail for no
        // visible reason.
        val formatable = NdefFormatable.get(tag)
            ?: throw IOException(appContext.getString(R.string.tag_unsupported))

        formatable.use {
            it.connect()
            it.format(message)
        }
    }
}
