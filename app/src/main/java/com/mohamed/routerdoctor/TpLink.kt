package com.mohamed.routerdoctor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL

// أفضل قناة 2.4GHz (رقم)
fun bestChannelNumber(context: Context): Int? {
    if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        != PackageManager.PERMISSION_GRANTED
    ) return null
    return try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION", "MissingPermission")
        val results = wm.scanResults.filter { it.frequency in 2400..2500 }
        val scores = listOf(1, 6, 11).associateWith { c ->
            results.sumOf { r ->
                val ch = freqToChannel(r.frequency)
                if (Math.abs(ch - c) <= 4) maxOf(0, 100 + r.level) else 0
            }
        }
        scores.minByOrNull { it.value }?.key
    } catch (e: Exception) {
        null
    }
}

class TpLinkClient(private val ip: String, private val user: String, private val pass: String) {
    private val base = "http://$ip"
    private var prefix = ""

    private fun auth(): String =
        "Basic " + Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP)

    private fun get(path: String, referer: String): Pair<Int, String> {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 10000
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("Authorization", auth())
        conn.setRequestProperty("Cookie", "Authorization=" + auth().replace(" ", "%20"))
        conn.setRequestProperty("Referer", base + referer)
        val code = conn.responseCode
        val stream = if (code in 200..399) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        return code to body
    }

    private fun login(): String? {
        val (code, body) = get("/userRpm/LoginRpm.htm?Save=Save", "/")
        if (code == 401) return "❌ اسم المستخدم أو كلمة المرور غلط"
        if (code !in 200..399) return "❌ الراوتر رد بكود $code"
        val token = Regex("""/([A-Z]{16})/userRpm""").find(body)?.groupValues?.get(1)
        prefix = if (token != null) "/$token" else ""
        return null
    }

    private fun arrayTokens(html: String, name: String): List<String>? {
        val m = Regex(
            name + """\s*=\s*new Array\((.*?)\)\s*;""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        ).find(html) ?: return null
        return m.groupValues[1].split(",").map { it.trim().trim('"') }
    }

    private fun looksLikeIp(s: String) = Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(s)

    private fun setDns(d1: String, d2: String): String {
        val page = "$prefix/userRpm/LanDhcpServerRpm.htm"
        val (_, html) = get(page, page)
        val t = arrayTokens(html, "DHCPPara")
        if (t == null || t.size < 8 || !looksLikeIp(t[1]) || !looksLikeIp(t[2])) {
            return "⚠️ ما قدرتش أقرأ صفحة DHCP (لم يتم تغيير DNS)"
        }
        val q = "dhcpserver=1&ip1=${t[1]}&ip2=${t[2]}&Lease=${t[3]}&gateway=${t[4]}" +
                "&domain=${t[5]}&dnsserver=$d1&dnsserver2=$d2&Save=Save"
        val (code, _) = get("$page?$q", page)
        return if (code in 200..399) "✅ تم ضبط DNS: $d1 , $d2" else "❌ فشل ضبط DNS (كود $code)"
    }

    private fun setChannel(ch: Int): String {
        val page = "$prefix/userRpm/WlanNetworkRpm.htm"
        val (_, html) = get(page, page)
        val t = arrayTokens(html, "wlanPara")
        val ssid = t?.getOrNull(1)
        if (t == null || ssid.isNullOrEmpty()) {
            return "⚠️ ما قدرتش أقرأ صفحة الواي فاي (لم يتم تغيير القناة)"
        }
        val enc = java.net.URLEncoder.encode(ssid, "UTF-8")
        val q = "ssid1=$enc&channel=$ch&mode=5&chanWidth=2&ap=1&broadcast=2" +
                "&brlssid=&brlbssid=&addrType=1&keytype=1&wepindex=1&authtype=1&keytext=&Save=Save"
        val (code, _) = get("$page?$q", page)
        return if (code in 200..399) "✅ تم ضبط القناة: $ch" else "❌ فشل ضبط القناة (كود $code)"
    }

    private fun reboot(): String {
        val page = "$prefix/userRpm/SysRebootRpm.htm"
        val (code, _) = get("$page?Reboot=Reboot", page)
        return if (code in 200..399) "🔄 جاري إعادة تشغيل الراوتر... انتظر دقيقتين"
        else "❌ فشلت إعادة التشغيل (كود $code)"
    }

    fun applyAll(dns1: String?, dns2: String?, channel: Int?): String {
        val sb = StringBuilder()
        try {
            val err = login()
            if (err != null) return err
            sb.append("✅ تم تسجيل الدخول للراوتر\n")
            if (dns1 != null) sb.append(setDns(dns1, dns2 ?: dns1)).append("\n")
            if (channel != null) sb.append(setChannel(channel)).append("\n")
            sb.append(reboot())
        } catch (e: Exception) {
            sb.append("❌ خطأ في الاتصال بالراوتر: ${e.message}")
        }
        return sb.toString()
    }
}
