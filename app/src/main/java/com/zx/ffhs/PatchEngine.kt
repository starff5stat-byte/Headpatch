package com.zx.ffhs

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File

object PatchEngine {
    const val FF = "com.dts.freefiremax"

    fun load(ctx: Context): PatchSet {
        val o = JSONObject(ctx.assets.open("patches.json").bufferedReader().readText())
        val arr = o.optJSONArray("patches")
        val list = ArrayList<PatchEntry>()
        if (arr != null) for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            list.add(PatchEntry(p.getString("name"), p.getString("rva"),
                p.getString("orig"), p.getString("patch"), p.optBoolean("enabled", true)))
        }
        val sarr = o.optJSONArray("strings")
        val slist = ArrayList<PatchEntry>()
        if (sarr != null) for (i in 0 until sarr.length()) {
            val p = sarr.getJSONObject(i)
            slist.add(PatchEntry(p.getString("name"), p.getString("find"),
                p.getString("repl"), "", p.optBoolean("enabled", true)))
        }
        return PatchSet(o.getString("gameVersion"), o.optString("lib", "libil2cpp.so"), list, slist)
    }

    fun installedVersion(ctx: Context): String? = try {
        ctx.packageManager.getPackageInfo(FF, 0).versionName
    } catch (e: PackageManager.NameNotFoundException) { null }

    private fun spawn(cmd: Array<String>): Process {
        val m = Shizuku::class.java.declaredMethods.firstOrNull { it.name == "newProcess" }
            ?: throw IllegalStateException("newProcess missing")
        m.isAccessible = true
        return when (m.parameterTypes.size) {
            3 -> m.invoke(null, *arrayOf<Any?>(cmd, null, null)) as Process
            1 -> m.invoke(null, cmd as Any) as Process
            else -> throw IllegalStateException("arity " + m.parameterTypes.size)
        }
    }

    fun run(ctx: Context, action: String, set: PatchSet): String {
        val src = HelperInstaller.ensure(ctx)
        val tmp = "ffhs_" + Integer.toHexString(kotlin.random.Random.nextInt(0xFFFFF))
        val sb = StringBuilder()
        for (e in set.entries) {
            if (!e.enabled) continue
            sb.append("B|").append(e.rva.removePrefix("0x")).append('|')
                .append(e.orig).append('|').append(e.patch).append('\n')
        }
        for (e in set.strings) {
            if (!e.enabled) continue
            sb.append("S|").append(e.rva).append('|').append(e.orig).append('\n')
        }
        val cmd = "cp '$src' /data/local/tmp/$tmp && chmod 755 /data/local/tmp/$tmp && " +
                "/data/local/tmp/$tmp --action $action --lib ${set.lib}; rm -f /data/local/tmp/$tmp"
        val p = try {
            spawn(arrayOf("/system/bin/sh", "-c", cmd))
        } catch (e: Exception) {
            return "{\"ok\":false,\"err\":\"spawn:" + (e.message ?: "?") + "\"}"
        }
        p.outputStream.use { it.write(sb.toString().toByteArray()) }
        val out = p.inputStream.bufferedReader().readText().trim()
        val err = p.errorStream.bufferedReader().readText().trim()
        p.waitFor()
        return if (out.isNotEmpty()) out else "{\"ok\":false,\"err\":\"$err\"}"
    }
}

object HelperInstaller {
    @Synchronized
    fun ensure(ctx: Context): File {
        val f = File(ctx.getExternalFilesDir(null), "ffhs")
        val stamp = File(f.parentFile, "ffhs.stamp")
        if (f.exists() && stamp.exists()) return f
        ctx.assets.open("ffhs").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        f.setExecutable(true)
        stamp.writeText(System.currentTimeMillis().toString())
        return f
    }
}
