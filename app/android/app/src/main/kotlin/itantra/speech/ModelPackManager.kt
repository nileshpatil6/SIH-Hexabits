package itantra.speech

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

enum class PackKind { STT, TTS, VAD }

/**
 * A `.itpack` is a zip with `manifest.json` at its root plus model files.
 *
 * manifest.json:
 * {
 *   "name": "hi-stt", "kind": "stt" | "tts", "engine": "nemo_ctc" | "vits",
 *   "langs": ["hi"],                       // one or many (rasa13 pack lists six)
 *   "version": "1", "sampleRate": 16000,
 *   "files": {"model": "model.int8.onnx", "tokens": "tokens.txt", "lexicon": "", "dataDir": ""},
 *   "speakers": {"ta": 4, "te": 6},        // tts only, lang -> sid
 *   "sha256": {"model.int8.onnx": "..."}   // optional integrity check
 * }
 */
data class PackManifest(
    val name: String,
    val kind: PackKind,
    val engine: String,
    val langs: List<String>,
    val version: String,
    val sampleRate: Int,
    val files: Map<String, String>,
    val speakers: Map<String, Int>,
    val sha256: Map<String, String>,
    val dir: File,
) {
    fun file(key: String): File? = files[key]?.takeIf { it.isNotBlank() }?.let { File(dir, it) }
    fun sizeBytes(): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    fun speakerFor(lang: String): Int = speakers[lang] ?: 0

    companion object {
        fun parse(json: String, dir: File): PackManifest {
            val o = JSONObject(json)
            val files = HashMap<String, String>()
            o.optJSONObject("files")?.let { f -> f.keys().forEach { files[it] = f.getString(it) } }
            val speakers = HashMap<String, Int>()
            o.optJSONObject("speakers")?.let { s -> s.keys().forEach { speakers[it] = s.getInt(it) } }
            val sha = HashMap<String, String>()
            o.optJSONObject("sha256")?.let { s -> s.keys().forEach { sha[it] = s.getString(it) } }
            val langs = ArrayList<String>()
            o.optJSONArray("langs")?.let { a -> for (i in 0 until a.length()) langs.add(a.getString(i)) }
            if (langs.isEmpty()) o.optString("lang").takeIf { it.isNotBlank() }?.let { langs.add(it) }
            return PackManifest(
                name = o.optString("name", dir.name),
                kind = PackKind.valueOf(o.getString("kind").uppercase()),
                engine = o.optString("engine", "nemo_ctc"),
                langs = langs,
                version = o.optString("version", "1"),
                sampleRate = o.optInt("sampleRate", 16000),
                files = files, speakers = speakers, sha256 = sha, dir = dir,
            )
        }
    }
}

class ModelPackManager(private val context: Context) {
    val root: File = File(context.filesDir, "models").apply { mkdirs() }

    fun installed(): List<PackManifest> =
        root.listFiles()?.filter { it.isDirectory }?.mapNotNull { load(it) }?.sortedBy { it.name } ?: emptyList()

    fun find(lang: String, kind: PackKind): PackManifest? {
        val all = installed().filter { it.kind == kind && lang in it.langs }
        // Prefer a single-language pack over a multi pack (usually higher quality for that lang).
        return all.minByOrNull { it.langs.size }
    }

    private fun load(dir: File): PackManifest? = runCatching {
        val m = File(dir, "manifest.json")
        if (!m.exists()) return null
        PackManifest.parse(m.readText(), dir)
    }.getOrNull()

    fun delete(lang: String, kind: PackKind) {
        installed().filter { it.kind == kind && it.langs == listOf(lang) }.forEach { it.dir.deleteRecursively() }
    }

    /** Import from a content:// URI or a filesystem path. */
    fun import(uriOrPath: String, progress: (Double) -> Unit = {}): PackManifest {
        val input: InputStream = if (uriOrPath.startsWith("content://")) {
            context.contentResolver.openInputStream(Uri.parse(uriOrPath)) ?: error("cannot open $uriOrPath")
        } else File(uriOrPath).inputStream()
        val total = if (uriOrPath.startsWith("content://")) -1L else File(uriOrPath).length()
        return importStream(input, total, progress)
    }

    fun importStream(input: InputStream, totalBytes: Long, progress: (Double) -> Unit): PackManifest {
        val tmp = File(root, ".import-${System.nanoTime()}").apply { mkdirs() }
        var manifestJson: String? = null
        var read = 0L
        try {
            ZipInputStream(input.buffered()).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    val name = e.name.substringAfterLast('/').ifBlank { e.name }
                    if (!e.isDirectory && !name.startsWith("__MACOSX") && !name.startsWith(".")) {
                        val out = File(tmp, name)
                        out.outputStream().buffered().use { os ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                val n = zip.read(buf); if (n < 0) break
                                os.write(buf, 0, n); read += n
                                if (totalBytes > 0) progress((read.toDouble() / totalBytes).coerceIn(0.0, 0.99))
                            }
                        }
                        if (name == "manifest.json") manifestJson = out.readText()
                    }
                    zip.closeEntry(); e = zip.nextEntry
                }
            }
            val json = manifestJson ?: error("manifest.json missing in pack")
            val m = PackManifest.parse(json, tmp)
            for ((f, expected) in m.sha256) {
                val actual = sha256(File(tmp, f))
                require(actual.equals(expected, ignoreCase = true)) { "sha256 mismatch for $f" }
            }
            val dest = File(root, m.name)
            if (dest.exists()) dest.deleteRecursively()
            require(tmp.renameTo(dest)) { "cannot move pack into place" }
            progress(1.0)
            return m.copy(dir = dest)
        } catch (t: Throwable) {
            tmp.deleteRecursively()
            throw t
        }
    }

    /**
     * Download "<lang>-<kind>.itpack" from [PACKS_BASE_URL] into cache, then import it.
     * Progress: 0..0.9 download, 0.9..1 unpack. Throws [java.util.concurrent.CancellationException] when cancelled.
     */
    fun download(lang: String, kind: PackKind, progress: (Double) -> Unit, cancelled: () -> Boolean): PackManifest {
        val name = "$lang-${kind.name.lowercase()}"
        val tmp = File(context.cacheDir, "$name.itpack.part")
        try {
            var conn = java.net.URL("$PACKS_BASE_URL/$name.itpack").openConnection() as java.net.HttpURLConnection
            var hops = 0
            while (true) {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 20_000
                conn.readTimeout = 60_000
                val code = conn.responseCode
                if (code in 300..399) {
                    val next = java.net.URL(conn.url, conn.getHeaderField("Location"))
                    conn.disconnect()
                    require(++hops <= 5) { "too many redirects" }
                    conn = next.openConnection() as java.net.HttpURLConnection
                    continue
                }
                if (code == 404) error("No $name pack published yet")
                if (code != 200) error("Download failed: HTTP $code")
                break
            }
            val total = conn.contentLengthLong
            // Zip on disk + unpacked copy both exist briefly.
            if (total > 0 && root.usableSpace < total * 2 + 50_000_000L) {
                conn.disconnect()
                error("Not enough storage: need ${(total * 2) / 1_000_000} MB free")
            }
            conn.inputStream.use { input ->
                tmp.outputStream().buffered().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var read = 0L
                    var lastReport = 0L
                    while (true) {
                        if (cancelled()) throw java.util.concurrent.CancellationException("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0 && read - lastReport > 1_000_000) {
                            lastReport = read
                            progress(0.9 * read / total)
                        }
                    }
                    if (total > 0 && read != total) error("Download interrupted ($read of $total bytes)")
                }
            }
            conn.disconnect()
            return import(tmp.absolutePath) { p -> progress(0.9 + 0.1 * p) }
        } finally {
            tmp.delete()
        }
    }

    /** Debug/demo convenience: packs dropped under assets/models/<name>/ are copied on first run. */
    fun installBundledAssets() {
        val am = context.assets
        val names = runCatching { am.list("models")?.toList() }.getOrNull() ?: return
        for (n in names) {
            val files = runCatching { am.list("models/$n")?.toList() }.getOrNull() ?: continue
            if ("manifest.json" !in files) continue
            val dest = File(root, n)
            if (File(dest, "manifest.json").exists()) continue
            dest.mkdirs()
            for (f in files) am.open("models/$n/$f").use { i -> File(dest, f).outputStream().use { i.copyTo(it) } }
        }
    }

    companion object {
        const val PACKS_BASE_URL = "https://huggingface.co/datasets/Mr66/itantra-packs/resolve/main"
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered().use { i ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
