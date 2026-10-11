package io.nekohasekai.sagernet.nobody

import android.util.Log
import io.nekohasekai.sagernet.ktx.app
import java.io.File
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

object NobodyLog {

    private const val TAG = "NobodyLog"
    const val PORT = 24023

    @Volatile
    private var enabled = false

    private var logFile: File? = null
    // external mirror so crash location is diagnosable on a logcat-less device
    private val mirrorFile: File by lazy {
        val base = app.getExternalFilesDir(null) ?: app.cacheDir
        File(base, "nobody.log")
    }
    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<PrintWriter>()

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun isEnabled(): Boolean = enabled

    fun init(dir: File) {
        if (logFile == null) {
            logFile = File(dir, "nobody.log")
        }
    }

    @Synchronized
    fun start(port: Int = PORT) {
        if (enabled) return
        enabled = true
        write("LOGGER", "===== NobodyLog started, port=$port =====")
        thread(name = "NobodyLogServer", isDaemon = true) {
            try {
                val ss = ServerSocket(port, 8, InetAddress.getByName("0.0.0.0"))
                serverSocket = ss
                while (enabled) {
                    val socket: Socket = ss.accept()
                    handleClient(socket)
                }
            } catch (e: Exception) {
                if (enabled) write("LOGGER", "server error: ${e.message}")
            }
        }
    }

    @Synchronized
    fun stop() {
        if (!enabled) return
        write("LOGGER", "===== NobodyLog stopped =====")
        enabled = false
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun handleClient(socket: Socket) {
        thread(name = "NobodyLogClient", isDaemon = true) {
            try {
                val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true)
                writeSync(writer, "===== connected to NobodyLog =====")
                clients.add(writer)
                val reader = socket.getInputStream().bufferedReader()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.trim() == "clear") {
                        runCatching { logFile?.writeText("") }
                        writeSync(writer, "===== log cleared =====")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "client error: ${e.message}")
            } finally {
                runCatching { socket.close() }
            }
        }
    }

    fun i(tag: String, msg: String) = write("I/$tag", msg)

    fun e(tag: String, msg: String) = write("E/$tag", msg)

    private fun write(tag: String, msg: String) {
        if (!enabled) return
        val line = "${timeFormat.format(Date())} [$tag] $msg"
        Log.i(TAG, line)
        runCatching {
            logFile?.appendText(line + "\n")
        }
        runCatching {
            mirrorFile.appendText(line + "\n")
        }
        clients.forEach { writer ->
            runCatching {
                if (writer.checkError()) {
                    clients.remove(writer)
                } else {
                    writeSync(writer, line)
                }
            }
        }
    }

    // unconditional checkpoint, survives crashes before log is enabled
    fun checkpoint(msg: String) {
        val line = "${timeFormat.format(Date())} [CHECKPOINT] $msg"
        runCatching {
            mirrorFile.appendText(line + "\n")
        }
    }

    private fun writeSync(writer: PrintWriter, line: String) {
        writer.println(line)
        writer.flush()
    }
}
