package com.amber.pad.input

import android.content.Context
import rikka.shizuku.Shizuku
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

object Activation {
    /** Shell command that starts the privileged server in the background. */
    fun serverCmd(ctx: Context, token: String): String =
        "CLASSPATH=\$(pm path ${ctx.packageName} | cut -d: -f2) nohup app_process /system/bin " +
            "--nice-name=amberpad_server com.amber.pad.server.AmberServer $token ${ServerClient.PORT} " +
            "${ctx.applicationInfo.nativeLibraryDir} >/dev/null 2>&1 &"

    private fun scriptFile(ctx: Context) = File(ctx.getExternalFilesDir(null), "start.sh")

    /** Writes start.sh to the app's external files dir and returns the one-line adb command for a computer. */
    fun prepareScript(ctx: Context, token: String): String {
        val f = scriptFile(ctx)
        f.parentFile?.mkdirs()
        f.writeText("#!/system/bin/sh\n${serverCmd(ctx, token)}\necho AmberPad server started\n")
        return "adb shell sh /sdcard/Android/data/${ctx.packageName}/files/start.sh"
    }

    /** Starts the server through Shizuku (private newProcess API, reached by reflection). */
    fun startViaShizuku(ctx: Context, token: String): Boolean = runCatching {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        m.invoke(null, arrayOf("sh", "-c", serverCmd(ctx, token)), null, null)
        true
    }.getOrDefault(false)

    fun startViaRoot(ctx: Context, token: String): Boolean = runCatching {
        Runtime.getRuntime().exec(arrayOf("su", "-c", serverCmd(ctx, token)))
        true
    }.getOrDefault(false)

    /** Phone's Wi-Fi IPv4 address, for the wireless adb instructions. */
    fun localIp(): String = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull() ?: "?"
}
