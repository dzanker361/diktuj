package cz.dl.diktuj

import android.content.Context
import java.io.File

/** Nastavení: adresa počítače s Archem a token. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("diktuj", Context.MODE_PRIVATE)
    fun host(c: Context): String = sp(c).getString("host", "") ?: ""
    fun token(c: Context): String = sp(c).getString("token", "") ?: ""
    fun save(c: Context, host: String, token: String) =
        sp(c).edit().putString("host", host.trim()).putString("token", token.trim()).apply()
}

object Sessions {
    /** Minimální velikost segmentu, který má smysl odesílat. */
    const val MIN_BYTES = 600L

    /** Název složky právě nahrávané relace (ta se neodesílá). */
    @Volatile
    var activeName: String? = null

    fun root(ctx: Context): File = File(ctx.filesDir, "sessions").apply { mkdirs() }

    fun segments(dir: File): List<File> =
        (dir.listFiles { f -> f.name.startsWith("seg_") && (f.name.endsWith(".ogg") || f.name.endsWith(".m4a")) }
            ?: emptyArray()).sortedBy { it.name }

    /** Relace, které ještě nebyly kompletně odeslány na Arch. */
    fun unsent(ctx: Context): List<File> =
        (root(ctx).listFiles() ?: emptyArray())
            .filter { it.isDirectory && it.name != activeName && !File(it, "uploaded").exists() }
            .filter { d -> segments(d).any { it.length() >= MIN_BYTES } }
            .sortedBy { it.name }

    /** Odeslané relace se z telefonu mažou po 14 dnech, prázdné po jednom dni. */
    fun cleanup(ctx: Context) {
        val now = System.currentTimeMillis()
        (root(ctx).listFiles() ?: emptyArray()).filter { it.isDirectory && it.name != activeName }.forEach { d ->
            val up = File(d, "uploaded")
            val dead = if (up.exists()) up.lastModified() < now - 14L * 24 * 3600 * 1000
            else segments(d).none { it.length() >= MIN_BYTES } && d.lastModified() < now - 24L * 3600 * 1000
            if (dead) d.deleteRecursively()
        }
    }
}
