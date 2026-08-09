package com.explorer.fileexplorer.feature.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShareServerProtocolTest {

    @Test
    fun httpAuthenticatesAndKeepsRequestsInsideTheShare() {
        val root = Files.createTempDirectory("share-server-http")
        val serverSocket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val config = ShareServerConfig(
            rootPath = root.toString(),
            httpEnabled = true,
            ftpEnabled = false,
            username = "tester",
            password = "secret-password",
        )
        Files.write(root.resolve("hello.txt"), "hello".toByteArray(StandardCharsets.UTF_8))
        val job = ShareServerHttpServer(config, ShareServerPathResolver(root.toString()), serverSocket)
            .acceptLoop(scope)
        try {
            val unauthorized = httpRequest(serverSocket.localPort, "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assertTrue(unauthorized.startsWith("HTTP/1.1 401 Unauthorized"))

            val credentials = Base64.getEncoder().encodeToString("tester:secret-password".toByteArray(StandardCharsets.UTF_8))
            val authorized = httpRequest(
                serverSocket.localPort,
                "GET /hello.txt HTTP/1.1\r\nHost: localhost\r\nAuthorization: Basic $credentials\r\n\r\n",
            )
            assertTrue(authorized.startsWith("HTTP/1.1 200 OK"))
            assertTrue(authorized.endsWith("hello"))

            val traversal = httpRequest(
                serverSocket.localPort,
                "GET /../outside HTTP/1.1\r\nHost: localhost\r\nAuthorization: Basic $credentials\r\n\r\n",
            )
            assertTrue(traversal.startsWith("HTTP/1.1 403 Forbidden"))
        } finally {
            serverSocket.close()
            job.cancel()
            scope.cancel()
            deleteTree(root)
        }
    }

    @Test
    fun ftpRequiresLoginAndRejectsTraversal() {
        val root = Files.createTempDirectory("share-server-ftp")
        val serverSocket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val config = ShareServerConfig(
            rootPath = root.toString(),
            httpEnabled = false,
            ftpEnabled = true,
            username = "tester",
            password = "secret-password",
        )
        val job = ShareServerFtpServer(
            config = config,
            resolver = ShareServerPathResolver(root.toString()),
            serverSocket = serverSocket,
            advertisedAddress = InetAddress.getByName("127.0.0.1"),
        ).acceptLoop(scope)
        try {
            Socket("127.0.0.1", serverSocket.localPort).use { socket ->
                socket.soTimeout = 5_000
                val input = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                val output = socket.getOutputStream()
                assertEquals("220 File Explorer share ready", input.readLine())
                sendFtp(output, "PWD")
                assertTrue(input.readLine().startsWith("530 "))
                sendFtp(output, "USER tester")
                assertTrue(input.readLine().startsWith("331 "))
                sendFtp(output, "PASS secret-password")
                assertTrue(input.readLine().startsWith("230 "))
                sendFtp(output, "CWD ../../outside")
                assertTrue(input.readLine().startsWith("550 "))
                sendFtp(output, "QUIT")
                assertTrue(input.readLine().startsWith("221 "))
            }
        } finally {
            serverSocket.close()
            job.cancel()
            scope.cancel()
            deleteTree(root)
        }
    }

    @Test
    fun httpRejectsUploadsOverTheConfiguredLimit() {
        val root = Files.createTempDirectory("share-server-http-limit")
        val serverSocket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val config = ShareServerConfig(
            rootPath = root.toString(),
            httpEnabled = true,
            ftpEnabled = false,
            username = "tester",
            password = "secret-password",
        )
        val credentials = Base64.getEncoder()
            .encodeToString("tester:secret-password".toByteArray(StandardCharsets.UTF_8))
        val job = ShareServerHttpServer(config, ShareServerPathResolver(root.toString()), serverSocket)
            .acceptLoop(scope)
        try {
            val response = httpRequest(
                serverSocket.localPort,
                "PUT /too-large.bin HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Authorization: Basic $credentials\r\n" +
                    "Content-Length: ${ShareServerLimits.MAX_UPLOAD_BYTES + 1}\r\n\r\n",
            )
            assertTrue(response.startsWith("HTTP/1.1 413 Content Too Large"))
        } finally {
            serverSocket.close()
            job.cancel()
            scope.cancel()
            deleteTree(root)
        }
    }

    private fun httpRequest(port: Int, request: String): String =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            val output = socket.getOutputStream()
            output.write(request.toByteArray(StandardCharsets.US_ASCII))
            output.flush()
            String(socket.getInputStream().readBytes(), StandardCharsets.UTF_8)
        }

    private fun sendFtp(output: java.io.OutputStream, command: String) {
        output.write((command + "\r\n").toByteArray(StandardCharsets.US_ASCII))
        output.flush()
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
