package com.cachecleaner.app

import kotlin.system.exitProcess

/**
 * Runs inside a separate process that Shizuku starts with shell (ADB) privileges.
 * From there, `pm trim-caches` is allowed to clear every app's cache.
 */
class UserService : IUserService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun trimCaches(): String {
        return try {
            // Ask the system to free an absurd amount of space, which makes it
            // delete all app caches it is able to.
            val process = ProcessBuilder("pm", "trim-caches", "999999999999999")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val code = process.waitFor()
            "$code\n$output"
        } catch (e: Exception) {
            "-1\n${e.message}"
        }
    }
}
