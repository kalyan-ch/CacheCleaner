package com.cachecleaner.app

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class AppCache(val packageName: String, val label: String, val cacheBytes: Long)

enum class ShizukuState { NOT_RUNNING, NO_PERMISSION, READY }

object Permissions {

    @Suppress("DEPRECATION")
    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } else {
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun shizukuState(): ShizukuState = try {
        when {
            !Shizuku.pingBinder() -> ShizukuState.NOT_RUNNING
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuState.READY
            else -> ShizukuState.NO_PERMISSION
        }
    } catch (e: Throwable) {
        ShizukuState.NOT_RUNNING
    }
}

object CacheScanner {

    /** Lists every app with a non-empty cache, biggest first. Needs usage access. */
    @Suppress("DEPRECATION")
    suspend fun scan(ctx: Context): List<AppCache> = withContext(Dispatchers.IO) {
        val pm = ctx.packageManager
        val stats = ctx.getSystemService(StorageStatsManager::class.java)
        val user = Process.myUserHandle()
        pm.getInstalledApplications(0)
            .mapNotNull { info ->
                try {
                    val s = stats.queryStatsForPackage(info.storageUuid, info.packageName, user)
                    AppCache(info.packageName, pm.getApplicationLabel(info).toString(), s.cacheBytes)
                } catch (e: Exception) {
                    null
                }
            }
            .filter { it.cacheBytes > 0 }
            .sortedByDescending { it.cacheBytes }
    }
}

object ShizukuRunner {

    private fun args(ctx: Context) =
        Shizuku.UserServiceArgs(ComponentName(ctx.packageName, UserService::class.java.name))
            .daemon(false)
            .processNameSuffix("cleaner")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)

    /** Starts the privileged service, clears all caches, then shuts the service down. */
    suspend fun trimCaches(ctx: Context) {
        val args = args(ctx)
        var connection: ServiceConnection? = null
        try {
            val service = withTimeout(15_000) {
                suspendCancellableCoroutine<IUserService> { cont ->
                    val conn = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                            if (!cont.isActive) return
                            if (binder != null && binder.pingBinder()) {
                                cont.resume(IUserService.Stub.asInterface(binder))
                            } else {
                                cont.resumeWithException(IllegalStateException("Shizuku returned an invalid service"))
                            }
                        }

                        override fun onServiceDisconnected(name: ComponentName?) = Unit
                    }
                    connection = conn
                    Shizuku.bindUserService(args, conn)
                }
            }
            val result = withContext(Dispatchers.IO) { service.trimCaches() }
            val code = result.substringBefore('\n').toIntOrNull()
            if (code != 0) {
                throw IllegalStateException(result.substringAfter('\n').ifBlank { "pm trim-caches failed" })
            }
        } finally {
            connection?.let { runCatching { Shizuku.unbindUserService(args, it, true) } }
        }
    }
}
