package com.mohamed.routerdoctor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

val NeonGreen = Color(0xFF39FF14)
val DarkBg = Color(0xFF0A0A0A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                App(getRouterIp(this))
            }
        }
    }
}

// ---------- أدوات الفحص ----------

fun getRouterIp(context: Context): String {
    return try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val gw = wm.dhcpInfo.gateway
        if (gw != 0) {
            "${gw and 0xFF}.${(gw shr 8) and 0xFF}.${(gw shr 16) and 0xFF}.${(gw shr 24) and 0xFF}"
        } else "192.168.1.1"
    } catch (e: Exception) {
        "192.168.1.1"
    }
}

fun currentDns(context: Context): String {
    return try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val list = cm.getLinkProperties(cm.activeNetwork)?.dnsServers
        if (list.isNullOrEmpty()) "غير معروف" else list.joinToString(" , ") { it.hostAddress ?: "" }
    } catch (e: Exception) {
        "غير معروف"
    }
}

fun pingMs(host: String): Long? {
    return try {
        val t = System.currentTimeMillis()
        val ok = InetAddress.getByName(host).isReachable(2000)
        if (ok) System.currentTimeMillis() - t else null
    } catch (e: Exception) {
        null
    }
}

// قياس زمن استعلام DNS حقيقي
fun dnsQueryTime(server: String): Long? {
    return try {
        val q = ByteArrayOutputStream()
        q.write(byteArrayOf(0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        for (part in listOf("google", "com")) {
            q.write(part.length)
            q.write(part.toByteArray())
        }
        q.write(0)
        q.write(byteArrayOf(0, 1, 0, 1))
        val data = q.toByteArray()
        val socket = DatagramSocket()
        socket.soTimeout = 1500
        val addr = InetAddress.getByName(server)
        val start = System.nanoTime()
        socket.send(DatagramPacket(data, data.size, addr, 53))
        val buf = ByteArray(512)
        socket.receive(DatagramPacket(buf, buf.size))
        val ms = (System.nanoTime() - start) / 1_000_000
        socket.close()
        ms
    } catch (e: Exception) {
        null
    }
}

fun dnsAverage(server: String): Long? {
    val times = (1..3).mapNotNull { dnsQueryTime(server) }
    return if (times.size >= 2) times.average().toLong() else null
}

fun freqToChannel(f: Int): Int = when {
    f in 2412..2472 -> (f - 2412) / 5 + 1
    f == 2484 -> 14
    f in 5000..5900 -> (f - 5000) / 5
    else -> 0
}

// اختيار أقل قناة 2.4GHz تشويشًا
fun bestChannel(context: Context): String {
    if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        != PackageManager.PERMISSION_GRANTED
    ) return "اسمح للتطبيق بالموقع لفحص القنوات"
    return try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION", "MissingPermission")
        val results = wm.scanResults.filter { it.frequency in 2400..2500 }
        val candidates = listOf(1, 6, 11)
        val scores = candidates.associateWith { c ->
            results.sumOf { r ->
                val ch = freqToChannel(r.frequency)
                if (Math.abs(ch - c) <= 4) maxOf(0, 100 + r.level) else 0
            }
        }
        val best = scores.minByOrNull { it.value }?.key ?: 1
        "شبكات قريبة: ${results.size}\nأفضل قناة: $best"
    } catch (e: Exception) {
        "تعذر فحص القنوات"
    }
}

// قياس سرعة التحميل (ميجابت/ثانية)
fun downloadMbps(): Double? {
    return try {
        val conn = URL("https://speed.cloudflare.com/__down?bytes=25000000")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        val start = System.nanoTime()
        var total = 0L
        val ins = conn.inputStream
        val buf = ByteArray(65536)
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            total += n
            if (System.nanoTime() - start > 8_000_000_000L) break
        }
        ins.close()
        val sec = (System.nanoTime() - start) / 1e9
        total * 8 / 1e6 / sec
    } catch (e: Exception) {
        null
    }
}

// ---------- الواجهة ----------

@Composable
fun App(routerIp: String) {
    var screen by remember { mutableStateOf(0) }
    var ip by remember { mutableStateOf(routerIp) }
    if (screen == 0) {
        HomeScreen(ip, { ip = it }, { screen = 1 })
    } else {
        RebootScreen(ip) { screen = 0 }
    }
}

@Composable
fun Header() {
    Spacer(Modifier.height(30.dp))
    Text(
        text = "محمد إبراهيم",
        style = TextStyle(
            color = NeonGreen,
            fontSize = 30.sp,
            fontWeight = FontWeight.ExtraBold,
            shadow = Shadow(color = NeonGreen, offset = Offset(0f, 0f), blurRadius = 30f)
        )
    )
    Spacer(Modifier.height(6.dp))
    Text("Router Doctor", color = Color.Gray, fontSize = 14.sp)
    Spacer(Modifier.height(24.dp))
}

@Composable
fun HomeScreen(ip: String, onIpChange: (String) -> Unit, goReboot: () -> Unit) {
    val context = LocalContext.current
    var user by remember { mutableStateOf("admin") }
    var pass by remember { mutableStateOf("admin") }
    var log by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Header()

        OutlinedTextField(
            value = ip, onValueChange = onIpChange,
            label = { Text("Router IP") }, singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = user, onValueChange = { user = it },
            label = { Text("Username") }, singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = pass, onValueChange = { pass = it },
            label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(20.dp))
        Button(
            enabled = !running,
            onClick = {
                running = true
                done = false
                log = "جاري الفحص..."
                scope.launch {
                    val sb = StringBuilder()

                    val dnsNow = currentDns(context)
                    sb.append("DNS الحالي: $dnsNow\n\n")
                    log = sb.toString() + "فحص الـ Ping..."

                    val rp = withContext(Dispatchers.IO) { pingMs(ip) }
                    val np = withContext(Dispatchers.IO) { pingMs("8.8.8.8") }
                    sb.append("Ping الراوتر: ${rp?.let { "$it ms" } ?: "لا يستجيب"}\n")
                    sb.append("Ping الإنترنت: ${np?.let { "$it ms" } ?: "لا يوجد اتصال"}\n\n")
                    log = sb.toString() + "فحص DNS..."

                    val servers = listOf(
                        "Cloudflare" to "1.1.1.1",
                        "Google" to "8.8.8.8",
                        "Quad9" to "9.9.9.9",
                        "OpenDNS" to "208.67.222.222"
                    )
                    val dnsResults = withContext(Dispatchers.IO) {
                        servers.map { Triple(it.first, it.second, dnsAverage(it.second)) }
                    }
                    sb.append("نتائج DNS:\n")
                    dnsResults.forEach {
                        sb.append("${it.first} (${it.second}): ${it.third?.let { t -> "$t ms" } ?: "فشل"}\n")
                    }
                    val best = dnsResults.filter { it.third != null }.minByOrNull { it.third!! }
                    if (best != null) {
                        val second = dnsResults.filter { it.third != null && it.second != best.second }
                            .minByOrNull { it.third!! }
                        sb.append("\n✅ الأساسي: ${best.second} (${best.first})")
                        if (second != null) sb.append("\n✅ الثانوي: ${second.second} (${second.first})")
                        sb.append("\n")
                    }
                    log = sb.toString() + "\nفحص القنوات..."

                    val ch = withContext(Dispatchers.IO) { bestChannel(context) }
                    sb.append("\nالقنوات:\n$ch\n")
                    log = sb.toString() + "\nقياس السرعة..."

                    val sp = withContext(Dispatchers.IO) { downloadMbps() }
                    sb.append("\nسرعة التحميل: ${sp?.let { String.format("%.1f", it) + " Mbps" } ?: "تعذر القياس"}\n")

                    log = sb.toString() + "\nجاري تطبيق الإصلاحات على الراوتر..."
val ranked = dnsResults.filter { it.third != null }.sortedBy { it.third }
val d1 = ranked.getOrNull(0)?.second
val d2 = ranked.getOrNull(1)?.second
val chNum = withContext(Dispatchers.IO) { bestChannelNumber(context) }
val fixLog = withContext(Dispatchers.IO) {
    TpLinkClient(ip, user, pass).applyAll(d1, d2, chNum)
}
sb.append("\n").append(fixLog)
                    log = sb.toString()
                    running = false
                    done = true
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = Color.Black),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text(if (running) "جاري العمل..." else "فحص وإصلاح", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }

        Spacer(Modifier.height(16.dp))
        Text(log, color = NeonGreen, fontSize = 15.sp)

        if (done) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = goReboot, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("الذهاب لإعادة تشغيل الراوتر", color = NeonGreen, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
fun RebootScreen(ip: String, back: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Header()
        Text("إعادة تشغيل الراوتر", color = NeonGreen, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text(
            "1) اضغط الزر بالأسفل لفتح صفحة الراوتر\n" +
            "2) غيّر DNS والقناة بالقيم اللي ظهرت لك\n" +
            "3) من قسم System أو Management اختر Reboot\n" +
            "4) أو افصل كهرباء الراوتر 30 ثانية ثم شغله\n" +
            "5) انتظر دقيقتين ثم أعد الفحص",
            color = Color.White, fontSize = 16.sp
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("http://$ip")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
            colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = Color.Black),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("افتح إعدادات الراوتر", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = back, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("رجوع", color = NeonGreen)
        }
    }
}
