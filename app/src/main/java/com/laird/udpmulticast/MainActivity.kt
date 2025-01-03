package com.laird.udpmulticast

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.laird.udpmulticast.ui.theme.UDPMulticastTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.MulticastSocket
import java.net.InetAddress
import android.net.wifi.WifiManager
import android.widget.Toast
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Send
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.io.IOException
import java.net.SocketException
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.material.icons.filled.Clear

private val Context.dataStore by preferencesDataStore(name = "settings")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            UDPMulticastTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MulticastScreen(
                        modifier = Modifier.padding(innerPadding),
                        context = this
                    )
                }
            }
        }
    }
}

@Composable
fun MulticastScreen(modifier: Modifier = Modifier, context: Context) {
    var address by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf<List<String>>(emptyList()) }
    var isJoined by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val socketRef = remember { mutableStateOf<MulticastSocket?>(null) }
    val multicastLock = remember { 
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiManager.createMulticastLock("multicastLock")
    }

    // 添加网络状态检查
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val networkCallback = remember { object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            Log.d("Network", "网络可用")
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            Log.d("Network", "网络断开")
        }
    }}

    DisposableEffect(Unit) {
        val request = NetworkRequest.Builder().build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
        onDispose {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        }
    }

    LaunchedEffect(Unit) {
        // 从DataStore读取保存的设置
        context.dataStore.data.collect { preferences ->
            address = preferences[stringPreferencesKey("address")] ?: "239.255.255.250"
            port = preferences[stringPreferencesKey("port")] ?: "1900"
        }
    }

    fun checkNetworkState(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        
        if (capabilities == null) {
            Log.e("Network", "无网络连接")
            return false
        }
        
        val hasWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        Log.d("Network", "WiFi状态: $hasWifi")
        return hasWifi
    }

    // 添加 LazyListState 用于控制滚动
    val listState = rememberLazyListState()
    
    // 添加自动滚动效果
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = address,
                onValueChange = { 
                    address = it
                    scope.launch {
                        context.dataStore.edit { preferences ->
                            preferences[stringPreferencesKey("address")] = it
                        }
                    }
                },
                label = { Text("组播地址") },
                singleLine = true,
                modifier = Modifier.weight(0.6f),
                isError = address.isNotEmpty() && !address.matches(Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$"))
            )

            OutlinedTextField(
                value = port,
                onValueChange = { 
                    if (it.length <= 5 && it.all { char -> char.isDigit() }) {
                        port = it
                        scope.launch {
                            context.dataStore.edit { preferences ->
                                preferences[stringPreferencesKey("port")] = it
                            }
                        }
                    }
                },
                label = { Text("端口") },
                singleLine = true,
                modifier = Modifier.weight(0.4f),
                isError = port.toIntOrNull() !in 1..65535,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    if (!isJoined) {
                        if (!checkNetworkState()) {
                            Toast.makeText(context, "请确保WiFi已连接", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        
                        isJoined = true
                        scope.launch {
                            try {
                                multicastLock.acquire()
                                socketRef.value = startMulticastListener(address, port.toInt()) { message ->
                                    messages = messages + message
                                }
                            } catch (e: Exception) {
                                Log.e("Multicast", "加入组播失败: ${e.message}")
                                Toast.makeText(context, "加入组播失败: ${e.message}", Toast.LENGTH_SHORT).show()
                                isJoined = false
                                multicastLock.release()
                            }
                        }
                    }
                },
                enabled = !isJoined,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (isJoined) "已加入组播" else "加入组播")
            }

            Button(
                onClick = {
                    scope.launch {
                        socketRef.value?.let { socket ->
                            withContext(Dispatchers.IO) {
                                try {
                                    socket.leaveGroup(InetAddress.getByName(address))
                                    socket.close()
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                } finally {
                                    multicastLock.release()
                                }
                            }
                        }
                        socketRef.value = null
                        isJoined = false
                    }
                },
                enabled = isJoined,
                modifier = Modifier.weight(1f)
            ) {
                Text("退出组播")
            }

            IconButton(
                onClick = { messages = emptyList() },
                enabled = messages.isNotEmpty()
            ) {
                Icon(
                    imageVector = Icons.Default.Clear,
                    contentDescription = "清除消息",
                    tint = if (messages.isNotEmpty()) 
                        MaterialTheme.colorScheme.error 
                    else 
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            }
        }

        LazyColumn(
            state = listState,  // 添加状态控制
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(8.dp)
        ) {
            items(messages.size) { index ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                    ) {
                        val (message, timestamp) = remember(messages[index]) {
                            messages[index].split("\n", limit = 2)
                        }
                        
                        Text(  // 发送者信息
                            text = message,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        
                        Text(  // 消息内容
                            text = timestamp,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        
                        Text(  // 时间戳
                            text = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                                .format(Date()),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                            modifier = Modifier
                                .align(Alignment.End)
                                .padding(top = 4.dp)
                        )
                    }
                }
            }
        }

        Button(
            onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        try {
                            val message = "测试消息 ${System.currentTimeMillis()}"
                            val socket = DatagramSocket()
                            val group = InetAddress.getByName(address)
                            val data = message.toByteArray()
                            val packet = DatagramPacket(data, data.size, group, port.toInt())
                            socket.send(packet)
                            socket.close()
                            Log.d("Multicast", "发送测试消息: $message")
                        } catch (e: Exception) {
                            Log.e("Multicast", "发送测试消息失败: ${e.message}")
                        }
                    }
                }
            },
            enabled = isJoined,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "发送",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("发送测试消息")
            }
        }
    }
}

private suspend fun startMulticastListener(
    address: String,
    port: Int,
    onMessageReceived: (String) -> Unit
): MulticastSocket {
    return withContext(Dispatchers.IO) {
        try {
            val group = InetAddress.getByName(address)
            Log.d("Multicast", "组播地址: $group")
            
            // 创建 socket 并绑定到所有可用接口
            val socket = MulticastSocket(null)
            socket.reuseAddress = true
            socket.bind(InetSocketAddress("0.0.0.0", port))
            
            // 查找并设置网络接口
            val networkInterfaces = NetworkInterface.getNetworkInterfaces()
            var foundInterface = false
            while (networkInterfaces.hasMoreElements()) {
                val networkInterface = networkInterfaces.nextElement()
                if (networkInterface.isUp && !networkInterface.isLoopback 
                    && networkInterface.supportsMulticast()
                    && networkInterface.name.startsWith("wlan")) {
                    try {
                        socket.networkInterface = networkInterface
                        Log.d("Multicast", "使用网络接口: ${networkInterface.displayName}")
                        networkInterface.interfaceAddresses.forEach { addr ->
                            Log.d("Multicast", "接口地址: ${addr.address}, 掩码: ${addr.networkPrefixLength}")
                        }
                        foundInterface = true
                        break
                    } catch (e: Exception) {
                        Log.e("Multicast", "设置网络接口失败: ${e.message}")
                    }
                }
            }
            
            if (!foundInterface) {
                Log.e("Multicast", "未找到可用的 WLAN 接口")
                throw IOException("未找到可用的 WLAN 接口")
            }

            // 设置 socket 选项
            socket.timeToLive = 255
            socket.loopbackMode = false
            socket.broadcast = true  // 允许广播
            
            // 使用新的加入组播组方法
            socket.joinGroup(InetSocketAddress(group, port), socket.networkInterface)
            Log.d("Multicast", "成功加入组播组: $address:$port")
            
            launch {
                val buffer = ByteArray(4096)  // 增大缓冲区
                while (true) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        Log.d("Multicast", "等待接收数据...")
                        socket.receive(packet)
                        val message = String(packet.data, 0, packet.length)
                        val sender = "${packet.address}:${packet.port}"
                        Log.d("Multicast", "收到数据: $message, 来自: $sender")
                        withContext(Dispatchers.Main) {
                            onMessageReceived("来自 $sender\n$message")
                        }
                    } catch (e: Exception) {
                        if (e is SocketException && e.message?.contains("Socket closed") == true) {
                            Log.d("Multicast", "Socket已关闭")
                            break
                        }
                        Log.e("Multicast", "接收数据出错: ${e.message}")
                        e.printStackTrace()
                    }
                }
            }
            
            socket
        } catch (e: Exception) {
            Log.e("Multicast", "初始化组播失败: ${e.message}")
            throw e
        }
    }
}