package ai.upet.app

import android.content.Context
import android.util.AtomicFile
import ai.upet.core.SuggestionLedger
import ai.upet.core.SuggestionOffer
import ai.upet.core.SuggestionReply
import java.io.File
import java.io.RandomAccessFile

/** One app-wide transaction: budget + scoped response + pending offer; never contains questions. */
internal class SuggestionStore(context: Context) {
    private val file=AtomicFile(File(context.filesDir,"suggestions_v1.json"))
    private val lockFile=File(context.filesDir,"suggestions_v1.lock")
    var notice=""; private set
    private fun read(): SuggestionLedger = if (!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists())
        SuggestionLedger() else SuggestionLedger.fromJson(String(file.readFully(),Charsets.UTF_8))
    private fun write(value: SuggestionLedger): Boolean {
        val bytes=value.toJson().toByteArray(Charsets.UTF_8)
        var stream: java.io.FileOutputStream?=null
        return try {
            stream=file.startWrite();stream.write(bytes);file.finishWrite(stream);stream=null
            check(file.readFully().contentEquals(bytes));true
        } catch (_: Exception) { stream?.let { file.failWrite(it) };notice="제안 이력을 저장하지 못해 새 제안을 멈췄어요.";blocked=notice;false }
    }
    private fun <T> transaction(fallback: T, action: ()->T): T = synchronized(monitor) {
        if(blocked!=null) { notice=blocked!!; return@synchronized fallback }
        try { RandomAccessFile(lockFile,"rw").use { f -> f.channel.lock().use { action() } } }
        catch (_: Exception) { notice="제안 이력을 읽지 못해 새 제안을 멈췄어요.";blocked=notice;fallback }
    }
    fun excluded(scope: String,id: String,day: String): Boolean = transaction(true) { read().excluded(scope,id,day) }
    fun reserve(scope: String,id: String,day: String): SuggestionOffer? = transaction(null) {
        val (next,offer)=read().reserve(scope,id,day)
        if(offer!=null && write(next)) offer else null
    }
    fun reply(id: String,response: SuggestionReply): Boolean = transaction(false) {
        val previous=read();val next=previous.reply(id,response)
        next!=previous && write(next)
    }
    companion object { private val monitor=Any(); @Volatile private var blocked: String? = null }
}
