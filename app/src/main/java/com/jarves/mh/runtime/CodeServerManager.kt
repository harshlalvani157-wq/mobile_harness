package com.jarves.mh.runtime

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Manages the lifecycle of the code-server (VS Code web editor) process
 * inside the private Ubuntu PRoot runtime.
 */
class CodeServerManager(
    private val context: Context,
    private val installer: RuntimeInstaller,
) {
    @Volatile private var process: Process? = null
    @Volatile var runningProjectSlug: String? = null; private set
    @Volatile var port: Int = DEFAULT_PORT; private set

    val isRunning: Boolean get() = process?.isAlive == true
    val url: String get() = "http://127.0.0.1:$port"

    private fun resolveCodeServerExecutable(rootfs: File): String {
        return when {
            File(rootfs, "usr/local/bin/code-server").canExecute() -> "/usr/local/bin/code-server"
            File(rootfs, "usr/bin/code-server").canExecute() -> "/usr/bin/code-server"
            File(rootfs, "root/.local/bin/code-server").canExecute() -> "/root/.local/bin/code-server"
            else -> RuntimeInstaller.CODE_SERVER_GUEST_PATH
        }
    }

    /**
     * Starts the code-server instance for the given project workspace.
     * Binds to loopback interface on the specified port without authentication.
     */
    suspend fun start(
        projectSlug: String,
        targetPort: Int = DEFAULT_PORT,
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!installer.isInstalled()) {
            return@withContext Result.failure(IllegalStateException("Linux runtime is not installed"))
        }
        if (!installer.isCodeServerInstalled()) {
            return@withContext Result.failure(IllegalStateException("Code Server is not installed yet"))
        }

        if (isRunning && runningProjectSlug == projectSlug && isServerResponding(targetPort)) {
            return@withContext Result.success(url)
        }

        stop()

        val runtime = runCatching { installer.installedRuntime() }.getOrElse {
            return@withContext Result.failure(it)
        }

        val workspace = File(context.filesDir, "workspaces/$projectSlug").apply { mkdirs() }
        val guestWorkspacePath = "/workspace/$projectSlug"
        val executable = resolveCodeServerExecutable(runtime.rootfs)

        port = targetPort
        runningProjectSlug = projectSlug

        val guestCommand = listOf(
            executable,
            "--bind-addr", "127.0.0.1:$port",
            "--auth", "none",
            "--disable-telemetry",
            "--disable-update-check",
            guestWorkspacePath,
        )

        val environment = mapOf(
            "SHELL" to "/bin/bash",
            "HOME" to "/root",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
        )

        val outputFile = File(context.cacheDir, "code-server-${System.nanoTime()}.log")

        try {
            val spawned = installer.process(
                proot = runtime.proot,
                rootfs = runtime.rootfs,
                workspace = workspace,
                environment = environment,
                guestCommand = guestCommand,
                guestWorkspacePath = guestWorkspacePath,
                outputFile = outputFile,
            )
            process = spawned

            // Check if server binds and starts accepting connections
            var healthy = false
            for (attempt in 1..25) {
                if (!spawned.isAlive) {
                    val log = outputFile.takeIf { it.isFile }?.readText()?.takeLast(500).orEmpty()
                    return@withContext Result.failure(
                        IllegalStateException("Code Server stopped unexpectedly: ${log.ifBlank { "Exit code unknown" }}")
                    )
                }
                if (isServerResponding(port)) {
                    healthy = true
                    break
                }
                delay(600)
            }

            // Return URL even if loopback ping was delayed; WebView will load it
            Result.success(url)
        } catch (e: Exception) {
            Log.e("CodeServerManager", "Failed to start code-server", e)
            stop()
            Result.failure(e)
        }
    }

    /**
     * Gracefully terminates the running code-server process.
     */
    fun stop() {
        process?.destroy()
        process = null
        runningProjectSlug = null
    }

    /**
     * Checks if the HTTP server is currently responding on the loopback port.
     */
    fun isServerResponding(checkPort: Int = port): Boolean {
        return runCatching {
            val connection = URL("http://127.0.0.1:$checkPort").openConnection() as HttpURLConnection
            connection.connectTimeout = 1000
            connection.readTimeout = 1000
            connection.requestMethod = "GET"
            val responseCode = connection.responseCode
            connection.disconnect()
            responseCode in 200..499
        }.getOrDefault(false)
    }

    companion object {
        const val DEFAULT_PORT = 8443
    }
}
