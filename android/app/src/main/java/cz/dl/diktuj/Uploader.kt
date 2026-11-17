package cz.dl.diktuj

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Odesílá nahrávky přes domácí síť na příjemce `diktuj receive` na Archu.
 * Segmenty se posílají jednotlivě (PUT), po posledním přijde potvrzení (POST /done) se seznamem velikostí.
 * Co už odešlo, si pamatuje souborem `<segment>.sent`; opakované volání je bezpečné.
 */
object Uploader {
    enum class Result { DONE, NOTHING, NO_CONFIG, UNREACHABLE, AUTH_ERROR, ERROR }

    private const val CONNECT_MS = 4_000
    private const val READ_MS = 60_000
    private const val DEFAULT_PORT = 8765

    fun message(r: Result): String = when (r) {
        Result.DONE -> "Odesláno na Arch ✓"
        Result.NOTHING -> "Není co odesílat"
        Result.NO_CONFIG -> "Vyplň adresu Archu a token"
        Result.UNREACHABLE -> "Arch není dosažitelný (je zapnutý? stejná Wi-Fi? běží diktuj-receiver? firewall?)"
        Result.AUTH_ERROR -> "Arch odmítl token"
        Result.ERROR -> "Odeslání se nepovedlo, zkusím znovu"
    }

    private fun baseUrl(ctx: Context): String? {
        val raw = Prefs.host(ctx).trim().removePrefix("http://").trimEnd('/')
        if (raw.isEmpty() || Prefs.token(ctx).isEmpty()) return null
        return "http://" + if (raw.contains(":")) raw else "$raw:$DEFAULT_PORT"
    }

    /** Ověří, že Arch odpovídá a token sedí. */
    fun ping(ctx: Context): Result {
        val base = baseUrl(ctx) ?: return Result.NO_CONFIG
        val conn = URL("$base/ping").openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = CONNECT_MS
            conn.readTimeout = READ_MS
            conn.setRequestProperty("X-Token", Prefs.token(ctx))
            when (conn.responseCode) {
                200 -> Result.DONE
                401 -> Result.AUTH_ERROR
                else -> Result.ERROR
            }
        } catch (e: IOException) {
            Result.UNREACHABLE
        } finally {
            conn.disconnect()
        }
    }

    fun uploadAll(ctx: Context, cancelled: () -> Boolean): Result {
        val base = baseUrl(ctx) ?: return Result.NO_CONFIG
        val token = Prefs.token(ctx)
        val sessions = Sessions.unsent(ctx)
        if (sessions.isEmpty()) return Result.NOTHING
        for (dir in sessions) {
            val r = uploadSession(base, token, dir, cancelled)
            if (r != Result.DONE) return r // při první chybě končíme, zkusí se později znovu
        }
        return Result.DONE
    }

    private fun uploadSession(base: String, token: String, dir: File, cancelled: () -> Boolean): Result {
        val segs = Sessions.segments(dir).filter { it.length() >= Sessions.MIN_BYTES }
        for (seg in segs) {
            if (cancelled()) return Result.ERROR
            val sent = File(dir, seg.name + ".sent")
            if (sent.exists()) continue
            val r = putSegment(base, token, dir.name, seg)
            if (r != Result.DONE) return r
            sent.createNewFile()
        }
        return postDone(base, token, dir, segs)
    }

    private fun putSegment(base: String, token: String, session: String, seg: File): Result {
        val conn = URL("$base/upload/$session/${seg.name}").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "PUT"
            conn.doOutput = true
            conn.connectTimeout = CONNECT_MS
            conn.readTimeout = READ_MS
            conn.setFixedLengthStreamingMode(seg.length())
            conn.setRequestProperty("X-Token", token)
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.outputStream.use { out -> seg.inputStream().use { it.copyTo(out) } }
            when (conn.responseCode) {
                200 -> Result.DONE
                401 -> Result.AUTH_ERROR
                else -> Result.ERROR
            }
        } catch (e: IOException) {
            Result.UNREACHABLE
        } finally {
            conn.disconnect()
        }
    }

    private fun postDone(base: String, token: String, dir: File, segs: List<File>): Result {
        val body = JSONObject()
            .put("session", dir.name)
            .put("segments", JSONArray().apply {
                segs.forEach { put(JSONObject().put("name", it.name).put("size", it.length())) }
            })
            .toString().toByteArray(Charsets.UTF_8)
        val conn = URL("$base/done").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = CONNECT_MS
            conn.readTimeout = READ_MS
            conn.setFixedLengthStreamingMode(body.size)
            conn.setRequestProperty("X-Token", token)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body) }
            when (conn.responseCode) {
                200 -> {
                    File(dir, "uploaded").createNewFile()
                    Result.DONE
                }
                401 -> Result.AUTH_ERROR
                409 -> {
                    // Arch něco nemá celé: smažeme značky "odesláno", ať se ty segmenty pošlou znovu
                    val txt = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                    try {
                        val missing = JSONObject(txt).getJSONArray("missing")
                        for (i in 0 until missing.length()) File(dir, missing.getString(i) + ".sent").delete()
                    } catch (e: Exception) {
                        segs.forEach { File(dir, it.name + ".sent").delete() }
                    }
                    Result.ERROR
                }
                else -> Result.ERROR
            }
        } catch (e: IOException) {
            Result.UNREACHABLE
        } finally {
            conn.disconnect()
        }
    }
}
