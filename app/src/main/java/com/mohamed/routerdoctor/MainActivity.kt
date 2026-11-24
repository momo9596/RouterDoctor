package com.mohamed.routerdoctor

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.Inet4Address

val NeonGreen = Color(0xFF39FF14)
val DarkBg = Color(0xFF0A0A0A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                HomeScreen(getRouterIp(this))
            }
        }
    }
}

// اكتشاف IP الراوتر (Gateway) تلقائيًا
fun getRouterIp(context: Context): String {
    return try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val props = cm.getLinkProperties(cm.activeNetwork)
        val gateway = props?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway?.hostAddress
        gateway ?: "192.168.1.1"
    } catch (e: Exception) {
        "192.168.1.1"
    }
}

@Composable
fun HomeScreen(routerIp: String) {
    var ip by remember { mutableStateOf(routerIp) }
    var user by remember { mutableStateOf("admin") }
    var pass by remember { mutableStateOf("admin") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(30.dp))

        // اسم المطور - خط عريض فسفوري مضيء
        Text(
            text = "محمد إبراهيم",
            style = TextStyle(
                color = NeonGreen,
                fontSize = 30.sp,
                fontWeight = FontWeight.ExtraBold,
                shadow = Shadow(
                    color = NeonGreen,
                    offset = Offset(0f, 0f),
                    blurRadius = 30f
                )
            )
        )

        Spacer(Modifier.height(6.dp))
        Text("Router Doctor", color = Color.Gray, fontSize = 14.sp)
        Spacer(Modifier.height(30.dp))

        OutlinedTextField(
            value = ip,
            onValueChange = { ip = it },
            label = { Text("Router IP") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { /* الخطوة القادمة: الفحص والإصلاح */ },
            colors = ButtonDefaults.buttonColors(
                containerColor = NeonGreen,
                contentColor = Color.Black
            ),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text("فحص وإصلاح", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
    }
}
