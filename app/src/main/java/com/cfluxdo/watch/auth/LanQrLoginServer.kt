package com.cfluxdo.watch.auth

import android.util.Log
import com.cfluxdo.watch.net.DiscourseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/**
 * Tiny zero-dependency LAN HTTP server running ONLY while QrLoginActivity is open.
 *
 * Supports:
 * 1) Phone QR login: upload CFluxDo QR screenshot or paste fluxdo:// / _t cookie.
 * 2) Same-Wi-Fi CF credential sync: submit cf_clearance + browser User-Agent from a modern PC/phone
 *    browser (auto-filled via navigator.userAgent) to work around outdated watch WebView (e.g. Chrome 83).
 *
 * Uses zero external servers and closes immediately after completion or screen exit.
 */
class LanQrLoginServer(
    private val protocol: QrLoginProtocol,
    private val discourse: DiscourseClient,
    private val cfSyncFirst: Boolean = false,
    private val onStatusChange: (String) -> Unit,
    private val onLoginSuccess: (String) -> Unit,
    private val onCfSyncSuccess: () -> Unit = {}
) {
    companion object {
        private const val TAG = "LanQrLoginServer"
        private const val MAX_BODY_BYTES = 5 * 1024 * 1024 // 5MB cap for QR screenshot
    }

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val token: String = randomToken()

    val url: String?
        get() {
            val ip = findLanIpv4() ?: return null
            val port = serverSocket?.localPort ?: return null
            return "http://$ip:$port/$token"
        }

    fun start(scope: CoroutineScope): String? {
        stop()
        val ip = findLanIpv4() ?: return null
        val ss = try {
            ServerSocket(0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind LAN server", e)
            return null
        }
        serverSocket = ss
        val fullUrl = "http://$ip:${ss.localPort}/$token"

        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive && !ss.isClosed) {
                val client = try {
                    ss.accept()
                } catch (_: Exception) {
                    break
                }
                launch(Dispatchers.IO) {
                    handleClient(client)
                }
            }
        }
        return fullUrl
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
    }

    private suspend fun handleClient(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 15_000
            val input = BufferedInputStream(s.getInputStream())
            val out = s.getOutputStream()

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore('?')

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] =
                        line.substring(idx + 1).trim()
                }
            }

            if (!path.startsWith("/$token")) {
                writeResponse(out, 404, "text/plain; charset=utf-8", "Not Found".toByteArray())
                return
            }

            val subPath = path.removePrefix("/$token")
            when {
                method == "GET" && (subPath.isEmpty() || subPath == "/") -> {
                    onStatusChange(
                        if (cfSyncFirst) "浏览器已连接，等待提交 CF 凭证…" else "设备已连接，等待提交凭证…"
                    )
                    writeResponse(out, 200, "text/html; charset=utf-8", buildMobileHtml().toByteArray(Charsets.UTF_8))
                }
                method == "POST" && subPath == "/upload-qr" -> {
                    val contentLen = headers["content-length"]?.toIntOrNull() ?: 0
                    if (contentLen <= 0 || contentLen > MAX_BODY_BYTES) {
                        writeJson(out, 400, false, "图片大小无效（请小于 5MB）")
                        return
                    }
                    val body = readExactBytes(input, contentLen)
                    onStatusChange("正在手表端解析二维码…")
                    val qrText = protocol.decodeQrFromImageBytes(body)
                    if (qrText.isNullOrBlank()) {
                        writeJson(out, 400, false, "未识别到二维码，请确保截图清晰完整")
                        return
                    }
                    processQrOrCookie(out, qrText = qrText, tCookie = null, cfCookie = null, userAgent = null)
                }
                method == "POST" && subPath == "/submit" -> {
                    val json = readJsonBody(input, headers, out) ?: return
                    processQrOrCookie(
                        out = out,
                        qrText = json.optString("qrText").trim().takeIf { it.isNotEmpty() },
                        tCookie = json.optString("tCookie").trim().takeIf { it.isNotEmpty() },
                        cfCookie = json.optString("cfCookie").trim().takeIf { it.isNotEmpty() },
                        userAgent = json.optString("userAgent").trim().takeIf { it.isNotEmpty() }
                    )
                }
                method == "POST" && subPath == "/sync-cf" -> {
                    val json = readJsonBody(input, headers, out) ?: return
                    val cfCookie = json.optString("cfCookie").trim()
                    val userAgent = json.optString("userAgent").trim().takeIf { it.isNotEmpty() }
                        ?: headers["user-agent"]?.trim()?.takeIf { it.isNotEmpty() }
                    processCfSync(out, cfCookie, userAgent)
                }
                else -> {
                    writeResponse(out, 404, "text/plain; charset=utf-8", "Not Found".toByteArray())
                }
            }
        }
    }

    private fun readJsonBody(
        input: BufferedInputStream,
        headers: Map<String, String>,
        out: OutputStream
    ): JSONObject? {
        val contentLen = headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLen <= 0 || contentLen > 64 * 1024) {
            writeJson(out, 400, false, "请求内容无效")
            return null
        }
        val bodyStr = String(readExactBytes(input, contentLen), Charsets.UTF_8)
        return try {
            JSONObject(bodyStr)
        } catch (_: Exception) {
            writeJson(out, 400, false, "JSON 格式错误")
            null
        }
    }

    private suspend fun processCfSync(
        out: OutputStream,
        cfCookie: String,
        userAgent: String?
    ) {
        try {
            onStatusChange("正在同步 cf_clearance 与 UA，并重启本地网关…")
            discourse.applySyncedCfCredentials(cfCookie, userAgent)
            writeJson(out, 200, true, "同步成功！手表已更新 cf_clearance 与浏览器 UA，即将自动刷新。")
            onCfSyncSuccess()
        } catch (e: Exception) {
            val msg = e.message ?: "同步失败"
            onStatusChange("同步失败：$msg")
            writeJson(out, 400, false, msg)
        }
    }

    private suspend fun processQrOrCookie(
        out: OutputStream,
        qrText: String?,
        tCookie: String?,
        cfCookie: String?,
        userAgent: String?
    ) {
        try {
            if (!cfCookie.isNullOrBlank()) {
                discourse.applySyncedCfCredentials(cfCookie, userAgent)
            } else if (!userAgent.isNullOrBlank() && !tCookie.isNullOrBlank()) {
                discourse.setExactUserAgent(userAgent)
            }
            val username = when {
                !qrText.isNullOrBlank() -> {
                    onStatusChange("正在通过本地网关兑换登录令牌…")
                    protocol.loginWithQrContent(qrText)
                }
                !tCookie.isNullOrBlank() -> {
                    onStatusChange("正在验证登录会话…")
                    protocol.loginWithCookie(tCookie, cfCookie)
                }
                else -> throw IllegalArgumentException("请上传二维码截图或填写登录凭证")
            }
            writeJson(out, 200, true, "手表登录成功：@$username")
            onLoginSuccess(username)
        } catch (e: Exception) {
            val msg = e.message ?: "登录失败"
            onStatusChange("登录失败：$msg")
            writeJson(out, 400, false, msg)
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val baos = ByteArrayOutputStream()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (baos.size() == 0) null else String(baos.toByteArray(), Charsets.ISO_8859_1)
            }
            if (b == '\n'.code) {
                val arr = baos.toByteArray()
                val len = if (prev == '\r'.code && arr.isNotEmpty()) arr.size - 1 else arr.size
                return String(arr, 0, len, Charsets.ISO_8859_1)
            }
            baos.write(b)
            prev = b
        }
    }

    private fun readExactBytes(input: BufferedInputStream, len: Int): ByteArray {
        val buf = ByteArray(len)
        var offset = 0
        while (offset < len) {
            val read = input.read(buf, offset, len - offset)
            if (read <= 0) break
            offset += read
        }
        return if (offset == len) buf else buf.copyOf(offset)
    }

    private fun writeJson(out: OutputStream, status: Int, ok: Boolean, message: String) {
        val obj = JSONObject().apply {
            put("ok", ok)
            put("message", message)
        }
        writeResponse(out, status, "application/json; charset=utf-8", obj.toString().toByteArray(Charsets.UTF_8))
    }

    private fun writeResponse(out: OutputStream, status: Int, contentType: String, body: ByteArray) {
        val statusText = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            else -> "Not Found"
        }
        val header = "HTTP/1.1 $status $statusText\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    private fun findLanIpv4(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            var fallback: String? = null
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses.toList()) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (iface.name.startsWith("wlan", ignoreCase = true)) return ip
                        if (fallback == null) fallback = ip
                    }
                }
            }
            fallback
        } catch (_: Exception) {
            null
        }
    }

    private fun randomToken(): String {
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        val rng = SecureRandom()
        return buildString(10) {
            repeat(10) { append(chars[rng.nextInt(chars.length)]) }
        }
    }

    private fun buildMobileHtml(): String {
        val cfCardHtml = """
<div class="card${if (cfSyncFirst) " card-accent" else ""}">
  <h2>同步 Cloudflare 过盾凭证（解决手表 WebView 过旧）</h2>
  <p>1. 确保本机（电脑/手机）与手表连在<b>同一 Wi-Fi</b>，且手表「设置 → IP 协议偏好」设为<b>IPv4 优先</b>（确保公网出口 IP 一致）。<br>
  2. 在当前浏览器打开 <a href="https://linux.do" target="_blank" style="color:#e0a24d">https://linux.do</a> 手动完成人机验证。<br>
  3. 按 <code>F12 → Application(应用) → Cookies → https://linux.do</code> 复制 <code>cf_clearance</code> 值粘贴到下方（支持直接粘贴整段 Cookie；User-Agent 已自动读取当前浏览器）。</p>
  <textarea id="cfSyncInput" placeholder="粘贴 cf_clearance 值（或包含 cf_clearance=... 的整段 Cookie）"></textarea>
  <input id="uaSyncInput" type="text" placeholder="浏览器 User-Agent（默认自动填入当前浏览器）" style="margin-top:8px">
  <button class="${if (cfSyncFirst) "" else "btn-sub"}" id="btnSyncCf" onclick="syncCf()">同步 CF 凭证与 UA 到手表</button>
</div>
        """.trimIndent()

        val loginCardsHtml = """
<div class="card">
  <h2>方式一：上传 CFluxDo 登录二维码截图（推荐）</h2>
  <p>在手机/电脑版 CFluxDo 打开「扫码登录」展示二维码并截图，或拍照后在此选择，手表会自动解码并完成 OTP 兑换与焚毁。</p>
  <input id="fileInput" type="file" accept="image/*">
  <button id="btnUpload" onclick="uploadQr()">上传二维码并登录手表</button>
</div>

<div class="card">
  <h2>方式二：粘贴 fluxdo:// 链接 或 _t Cookie</h2>
  <p>支持直接粘贴 <code>fluxdo://qr-login?v=2&amp;...</code>，或浏览器 <code>_t</code> Cookie。</p>
  <textarea id="pasteInput" placeholder="粘贴 fluxdo://qr-login?... 或 _t 值"></textarea>
  <input id="cfInput" type="text" placeholder="可选：cf_clearance 值（如遇盾可一起填，将同步当前浏览器 UA）" style="margin-top:8px">
  <button class="btn-sub" id="btnSubmit" onclick="submitText()">发送到手表登录</button>
</div>
        """.trimIndent()

        val cardsOrder = if (cfSyncFirst) "$cfCardHtml\n$loginCardsHtml" else "$loginCardsHtml\n$cfCardHtml"
        val titleText = if (cfSyncFirst) "CFluxDo Watch · 同步 CF 凭证" else "CFluxDo Watch · 扫码登录与凭证同步"

        return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<title>$titleText</title>
<style>
*{box-sizing:border-box}
body{margin:0 auto;max-width:520px;padding:18px;background:#0d0d0f;color:#ede9e0;font-family:-apple-system,BlinkMacSystemFont,sans-serif;line-height:1.55}
.card{background:#17171b;border:1px solid #28282f;border-radius:16px;padding:16px;margin-bottom:14px}
.card-accent{border-color:#e0a24d}
h1{font-size:19px;margin:0 0 6px;color:#e0a24d}
h2{font-size:15px;margin:0 0 8px}
p{font-size:13px;color:#9a9892;margin:0 0 10px}
code{background:#23232a;padding:1px 5px;border-radius:5px;color:#ede9e0;font-size:12px}
input[type=file],textarea,input[type=text]{width:100%;background:#101014;color:#ede9e0;border:1px solid #2e2e36;border-radius:10px;padding:10px;font-size:13px}
textarea{min-height:74px;resize:vertical}
button{width:100%;margin-top:10px;padding:12px;border:none;border-radius:12px;background:#e0a24d;color:#111;font-weight:700;font-size:14px;cursor:pointer}
button:disabled{opacity:.5}
.btn-sub{background:#25252c;color:#ede9e0}
#msg{padding:10px 12px;border-radius:10px;font-size:14px;display:none;margin-bottom:14px}
.ok{background:#153322;color:#6ee7a0;border:1px solid #2a6642}
.err{background:#38191e;color:#f58b94;border:1px solid #6b2931}
</style>
</head>
<body>
<h1>$titleText</h1>
<p>局域网直连手表（不经过任何第三方服务器），关闭页面后服务立即停止。</p>
<div id="msg"></div>
$cardsOrder
<script>
const base = location.pathname.replace(/\/$/, '');
document.getElementById('uaSyncInput').value = navigator.userAgent || '';
function showMsg(text, ok){
  const el = document.getElementById('msg');
  el.textContent = text;
  el.className = ok ? 'ok' : 'err';
  el.style.display = 'block';
  window.scrollTo({top: 0, behavior: 'smooth'});
}
async function syncCf(){
  const cf = document.getElementById('cfSyncInput').value.trim();
  const ua = document.getElementById('uaSyncInput').value.trim();
  if(!cf){ showMsg('请先粘贴 cf_clearance 值', false); return; }
  const btn = document.getElementById('btnSyncCf');
  btn.disabled = true; btn.textContent = '同步中…';
  try{
    const res = await fetch(base + '/sync-cf', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({ cfCookie: cf, userAgent: ua })
    });
    const data = await res.json();
    showMsg(data.message || (data.ok ? '成功' : '失败'), data.ok);
  }catch(e){
    showMsg('连接手表失败：' + e, false);
  }finally{
    btn.disabled = false; btn.textContent = '同步 CF 凭证与 UA 到手表';
  }
}
async function uploadQr(){
  const f = document.getElementById('fileInput').files[0];
  if(!f){ showMsg('请先选择二维码图片', false); return; }
  const btn = document.getElementById('btnUpload');
  btn.disabled = true; btn.textContent = '上传并登录中…';
  try{
    const buf = await f.arrayBuffer();
    const res = await fetch(base + '/upload-qr', {
      method: 'POST',
      headers: {'Content-Type': 'application/octet-stream'},
      body: buf
    });
    const data = await res.json();
    showMsg(data.message || (data.ok ? '成功' : '失败'), data.ok);
  }catch(e){
    showMsg('连接手表失败：' + e, false);
  }finally{
    btn.disabled = false; btn.textContent = '上传二维码并登录手表';
  }
}
async function submitText(){
  const val = document.getElementById('pasteInput').value.trim();
  const cf = document.getElementById('cfInput').value.trim();
  if(!val){ showMsg('请先粘贴内容', false); return; }
  const btn = document.getElementById('btnSubmit');
  btn.disabled = true; btn.textContent = '发送中…';
  const payload = val.startsWith('fluxdo://')
    ? { qrText: val }
    : { tCookie: val, cfCookie: cf, userAgent: navigator.userAgent || '' };
  try{
    const res = await fetch(base + '/submit', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(payload)
    });
    const data = await res.json();
    showMsg(data.message || (data.ok ? '成功' : '失败'), data.ok);
  }catch(e){
    showMsg('连接手表失败：' + e, false);
  }finally{
    btn.disabled = false; btn.textContent = '发送到手表登录';
  }
}
</script>
</body>
</html>
        """.trimIndent()
    }
}
