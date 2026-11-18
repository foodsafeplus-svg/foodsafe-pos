package fr.foodsafe.pos.app

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rapport de plantage : en cas d'erreur non rattrapée, la trace est écrite dans un fichier privé,
 * puis affichée au lancement suivant avec des vues Android simples (sans Compose, au cas où Compose
 * serait lui-même en cause). Aucune donnée n'est envoyée hors de l'appareil.
 */
object CrashReport {
    private fun file(ctx: Context) = File(ctx.filesDir, "dernier-plantage.txt")

    fun install(ctx: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file(ctx).writeText(describe(ctx, error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun describe(ctx: Context, error: Throwable): String {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
        return buildString {
            appendLine("FOODSAFE+ Caisse $version")
            appendLine("Date : " + SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.FRANCE).format(Date()))
            appendLine("Appareil : ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine()
            append(trace)
        }
    }

    fun pending(ctx: Context): String? = file(ctx).takeIf { it.exists() }?.readText()

    fun clear(ctx: Context) {
        file(ctx).delete()
    }

    /** Écran d'erreur lisible, à photographier pour le support. */
    fun show(activity: Activity, report: String, onRetry: () -> Unit) {
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        layout.addView(TextView(activity).apply {
            text = "L'application s'est arrêtée. Prenez cet écran en photo et envoyez-la au support FOODSAFE+."
            textSize = 20f
            setTextColor(0xFFB00020.toInt())
            setTypeface(typeface, Typeface.BOLD)
        })
        layout.addView(Button(activity).apply {
            text = "Réessayer"
            setOnClickListener { onRetry() }
        })
        layout.addView(TextView(activity).apply {
            text = report
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFF000000.toInt())
            setTextIsSelectable(true)
        })
        activity.setContentView(ScrollView(activity).apply { addView(layout) })
    }
}
