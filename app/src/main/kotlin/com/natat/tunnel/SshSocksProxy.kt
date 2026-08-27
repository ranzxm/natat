package com.natat.tunnel

import android.net.VpnService
import android.util.Base64
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Proxy
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import com.jcraft.jsch.UserInfo
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Exposes a bounded, authenticated loopback SOCKS5 endpoint over one SSH session. */
class SshSocksProxy(
    private val config: TunnelConfig,
    private val vpnService: VpnService
) : Closeable {
    private val token = UUID.randomUUID().toString().replace("-", "")
    private val workers = ThreadPoolExecutor(
        1, MAX_WORKERS, 30, TimeUnit.SECONDS, LinkedBlockingQueue(MAX_QUEUE)
    )
    private var server: ServerSocket? = null
    private var session: Session? = null
    private var acceptThread: Thread? = null

    fun start(): LocalSocksEndpoint {
        require(config.sshHost.isNotBlank()) { "SSH host belum diisi" }
        require(config.sshUsername.isNotBlank()) { "SSH username belum diisi" }
        require(config.sshHostKeyFingerprint.isNotBlank()) { "SSH host key fingerprint wajib diisi" }
        require(!config.useWebSocket) { "WebSocket transport belum tersedia" }
        require(!config.skipCertificateVerification) { "Melewati verifikasi sertifikat tidak didukung" }
        val jsch = JSch().apply {
            setHostKeyRepository(FingerprintHostKeyRepository(config.sshHostKeyFingerprint))
            if (config.privateKey.isNotBlank()) {
                addIdentity(
                    "natat-profile-key",
                    config.privateKey.toByteArray(),
                    null,
                    config.privateKeyPassphrase.toByteArray().takeIf { config.privateKeyPassphrase.isNotBlank() }
                )
            }
        }
        session = jsch.getSession(config.sshUsername, config.sshHost, config.sshPort).apply {
            if (config.sshPassword.isNotBlank()) setPassword(config.sshPassword)
            setConfig("StrictHostKeyChecking", "yes")
            setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive")
            setServerAliveInterval(config.keepAliveSeconds * 1_000)
            setProxy(ProtectedSocketProxy(vpnService, config))
            connect(config.connectTimeoutMs)
        }
        server = ServerSocket(0, MAX_QUEUE, InetAddress.getByName("127.0.0.1"))
        acceptThread = Thread(::acceptConnections, "natat-ssh-socks").apply { start() }
        return LocalSocksEndpoint("127.0.0.1", requireNotNull(server).localPort, token)
    }

    private fun acceptConnections() {
        while (!Thread.currentThread().isInterrupted) {
            val client = try {
                requireNotNull(server).accept()
            } catch (_: Exception) {
                break
            }
            runCatching { workers.execute { handle(client) } }.onFailure { client.close() }
        }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            socket.tcpNoDelay = true
            socket.soTimeout = config.connectTimeoutMs
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            try {
                negotiate(input, output)
                val destination = readRequest(input)
                val channel = (requireNotNull(session).openChannel("direct-tcpip") as ChannelDirectTCPIP).apply {
                    setHost(destination.host)
                    setPort(destination.port)
                    setOrgIPAddress(socket.inetAddress.hostAddress)
                    setOrgPort(socket.port)
                    setInputStream(input)
                    setOutputStream(output)
                    connect(config.connectTimeoutMs)
                }
                reply(output, 0)
                while (channel.isConnected && !socket.isClosed) Thread.sleep(100)
                channel.disconnect()
            } catch (_: Exception) {
                runCatching { reply(output, 1) }
            }
        }
    }

    private fun negotiate(input: InputStream, output: OutputStream) {
        check(input.read() == 5) { "SOCKS version tidak valid" }
        val count = input.read().takeIf { it >= 0 } ?: throw EOFException()
        val methods = input.readExact(count)
        check(methods.any { it.toInt() == 2 }) { "SOCKS authentication diperlukan" }
        output.write(byteArrayOf(5, 2))
        output.flush()
        check(input.read() == 1) { "SOCKS auth version tidak valid" }
        val user = input.readUtf8()
        val password = input.readUtf8()
        check(user == "natat" && password == token) { "SOCKS authentication gagal" }
        output.write(byteArrayOf(1, 0))
        output.flush()
    }

    private fun readRequest(input: InputStream): SocksDestination {
        check(input.read() == 5 && input.read() == 1 && input.read() == 0) { "SOCKS request tidak didukung" }
        val addressType = input.read()
        val host = when (addressType) {
            1 -> InetAddress.getByAddress(input.readExact(4)).hostAddress
            3 -> input.readUtf8()
            4 -> InetAddress.getByAddress(input.readExact(16)).hostAddress
            else -> error("SOCKS address type tidak didukung")
        }
        val port = (input.read().takeIf { it >= 0 } ?: throw EOFException()) shl 8 or
            (input.read().takeIf { it >= 0 } ?: throw EOFException())
        return SocksDestination(host, port)
    }

    private fun reply(output: OutputStream, code: Int) {
        output.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
        output.flush()
    }

    override fun close() {
        runCatching { server?.close() }
        runCatching { session?.disconnect() }
        workers.shutdownNow()
        acceptThread?.interrupt()
        server = null
        session = null
    }

    private data class SocksDestination(val host: String, val port: Int)

    companion object {
        private const val MAX_WORKERS = 32
        private const val MAX_QUEUE = 64
    }
}

data class LocalSocksEndpoint(val host: String, val port: Int, val password: String)

private class ProtectedSocketProxy(
    private val vpnService: VpnService,
    private val config: TunnelConfig
) : Proxy {
    private var socket: Socket? = null

    override fun connect(socketFactory: SocketFactory?, host: String, port: Int, timeout: Int) {
        val endpointHost = if (config.useHttpPayload) config.host.ifBlank { host } else host
        val endpointPort = if (config.useHttpPayload) config.port else port
        socket = if (config.useTls) {
            val sni = config.sni.ifBlank { endpointHost }
            // Android's API 26 SSLSocketFactory cannot wrap an existing protected Socket.
            // This connection is made before this service establishes its VPN interface.
            (SSLSocketFactory.getDefault().createSocket(sni, endpointPort) as SSLSocket).apply {
                soTimeout = timeout
                sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                startHandshake()
            }
        } else Socket().also {
            check(vpnService.protect(it)) { "Gagal mengecualikan SSH socket dari VPN" }
            it.connect(java.net.InetSocketAddress(endpointHost, endpointPort), timeout)
            it.soTimeout = timeout
        }
        if (config.useHttpPayload) sendPayload(requireNotNull(socket), host, port)
    }

    override fun getInputStream(): InputStream = requireNotNull(socket).getInputStream()

    override fun getOutputStream(): OutputStream = requireNotNull(socket).getOutputStream()

    override fun getSocket(): Socket = requireNotNull(socket)

    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun sendPayload(socket: Socket, sshHost: String, sshPort: Int) {
        require(config.httpPayload.isNotBlank()) { "HTTP payload belum diisi" }
        val payload = config.httpPayload
            .replace("[host]", sshHost)
            .replace("[port]", sshPort.toString())
            .replace("[crlf]", "\r\n")
            .replace("[cr]", "\r")
            .replace("[lf]", "\n")
            .replace("\\r\\n", "\r\n")
        socket.getOutputStream().write(payload.toByteArray(Charsets.ISO_8859_1))
        socket.getOutputStream().flush()
        val response = socket.getInputStream().readHttpHeaders()
        check(response.startsWith("HTTP/") && response.contains(" 2")) { "HTTP payload ditolak oleh server" }
    }
}

private class FingerprintHostKeyRepository(fingerprint: String) : HostKeyRepository {
    private val expected = fingerprint.trim().removePrefix("SHA256:").trimEnd('=')

    override fun check(host: String, key: ByteArray): Int {
        val actual = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(key), Base64.NO_WRAP).trimEnd('=')
        return if (actual == expected) HostKeyRepository.OK else HostKeyRepository.CHANGED
    }

    override fun add(hostkey: HostKey, ui: UserInfo?) = Unit

    override fun remove(host: String?, type: String?) = Unit

    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit

    override fun getKnownHostsRepositoryID(): String = "natat-fingerprint"

    override fun getHostKey(): Array<HostKey> = emptyArray()

    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}

private fun InputStream.readExact(size: Int): ByteArray {
    val data = ByteArray(size)
    var offset = 0
    while (offset < size) {
        val count = read(data, offset, size - offset)
        if (count < 0) throw EOFException()
        offset += count
    }
    return data
}

private fun InputStream.readUtf8(): String {
    val length = read().takeIf { it >= 0 } ?: throw EOFException()
    return readExact(length).toString(Charsets.UTF_8)
}

private fun InputStream.readHttpHeaders(): String {
    val response = StringBuilder()
    var end = ""
    while (response.length < 16_384) {
        val value = read()
        if (value < 0) break
        response.append(value.toChar())
        end = (end + value.toChar()).takeLast(4)
        if (end == "\r\n\r\n") break
    }
    return response.toString()
}
