package com.shivam.tally.bridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import com.shivam.tally.core.TrackedApp
import com.shivam.tally.data.TallyApp
import com.shivam.tally.rules.RulesRepository
import com.shivam.tally.service.TallyAccessibilityService
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.util.concurrent.Executors

/** MethodChannel "tally/api" for commands, EventChannel "tally/counts" for live count pushes. */
class TallyBridge(private val context: Context, engine: FlutterEngine) {
    private val app = TallyApp.get(context)
    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private var listener: (() -> Unit)? = null

    init {
        MethodChannel(engine.dartExecutor.binaryMessenger, "tally/api").setMethodCallHandler { call, result ->
            try { handle(call.method, call.arguments as? Map<*, *> ?: emptyMap<Any, Any>(), result) }
            catch (t: Throwable) { result.error("bridge", t.message, null) }
        }
        EventChannel(engine.dartExecutor.binaryMessenger, "tally/counts").setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(args: Any?, sink: EventChannel.EventSink) {
                val l = { sink.success(counts()) }
                listener = l
                app.store.addListener(l)
                l()
            }
            override fun onCancel(args: Any?) { listener?.let { app.store.removeListener(it) }; listener = null }
        })
    }

    private fun counts(): Map<String, Int> = TrackedApp.entries.associate { it.id to app.store.todayCount(it) }

    private fun app(args: Map<*, *>) = TrackedApp.fromId(args["app"] as? String) ?: error("bad app")

    private fun handle(method: String, args: Map<*, *>, r: MethodChannel.Result) {
        when (method) {
            "getState" -> bg.execute { val s = state(); main.post { r.success(s) } }
            "getDailyTotals" -> bg.execute {
                val t = app.store.dailyTotals((args["days"] as? Int) ?: 30)
                main.post { r.success(t) }
            }
            "getToday" -> bg.execute { val t = app.store.todaySummary(); main.post { r.success(t) } }
            "getPatterns" -> bg.execute { val t = app.store.patterns((args["days"] as? Int) ?: 7); main.post { r.success(t) } }
            "adjust" -> { app.store.adjust(app(args), (args["delta"] as? Int) ?: 1); r.success(null) }
            "getSettings" -> r.success(app.settings.toMap())
            "setSettings" -> { app.settings.apply(args); TallyAccessibilityService.instance?.onSettingsChanged(); r.success(null) }
            "resetToday" -> { app.store.resetToday(); r.success(null) }
            "shareCsv" -> bg.execute {
                val f = File(context.getExternalFilesDir(null), "tally_export.csv").apply { writeText(app.store.exportCsv()) }
                main.post {
                    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", f)
                    val send = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    launch(Intent.createChooser(send, "Export Tally CSV").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    r.success(f.absolutePath)
                }
            }
            "requestNotificationPermission" -> {
                (context as? android.app.Activity)?.let {
                    if (Build.VERSION.SDK_INT >= 33) it.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
                }
                r.success(null)
            }
            "clearAll" -> { app.store.clearAll(); r.success(null) }
            "exportCsv" -> bg.execute {
                val f = File(context.getExternalFilesDir(null), "tally_export.csv").apply { writeText(app.store.exportCsv()) }
                main.post { r.success(f.absolutePath) }
            }
            "openAccessibilitySettings" -> { launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); r.success(null) }
            "openAppInfo" -> { launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))); r.success(null) }
            "requestBatteryExemption" -> {
                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                if (!launch(i)) launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                r.success(null)
            }
            "setCalibration" -> {
                val on = args["on"] as? Boolean ?: false
                app.settings.calibrationEnabled = on
                TallyAccessibilityService.instance?.setCalibration(on)
                r.success(null)
            }
            else -> r.notImplemented()
        }
    }

    private fun launch(i: Intent): Boolean = try {
        context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (_: Exception) { false }

    private fun state(): Map<String, Any?> {
        val svc = TallyAccessibilityService.instance
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val rules = RulesRepository.info(context)
        val enabledList = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        val me = ComponentName(context, TallyAccessibilityService::class.java).flattenToString()
        return mapOf(
            "counts" to counts(),
            "accessibilityEnabled" to enabledList.split(':').any { it.equals(me, true) || it.equals(me.replace("/.", "/${context.packageName}."), true) },
            "serviceAlive" to (svc != null),
            "batteryExempt" to pm.isIgnoringBatteryOptimizations(context.packageName),
            "rulesVersion" to (svc?.activeRulesVersion?.takeIf { it != "none" } ?: rules.version),
            "notificationsAllowed" to (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED),
            "rulesSource" to rules.source,
            "ruleTargets" to rules.perApp,
            "brand" to Build.MANUFACTURER.lowercase(),
            "model" to Build.MODEL,
            "sdk" to Build.VERSION.SDK_INT,
            "calibrationRunning" to (svc?.calibrationRunning ?: false),
            "calibrationFile" to svc?.calibrationFile?.absolutePath,
            "dumpDir" to File(context.getExternalFilesDir(null), "dumps").absolutePath,
        )
    }
}
