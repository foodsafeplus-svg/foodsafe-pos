package fr.foodsafe.pos.app.hardware

import android.app.Presentation
import android.content.Context
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import fr.foodsafe.pos.app.data.OrderSnapshot
import fr.foodsafe.pos.core.kitchen.KitchenTicketRenderer

/** Ce que voit le client : rien en cours, une note en cours, ou une note réglée. */
sealed interface CustomerView {
    data class Idle(val establishment: String) : CustomerView
    data class Order(val lines: List<Pair<String, String>>, val total: String, val remaining: String?, val paid: Boolean) : CustomerView

    companion object {
        fun of(s: OrderSnapshot?, establishment: String): CustomerView {
            if (s == null || s.order.activeLines.isEmpty()) return Idle(establishment)
            val p = s.priced
            val lines = p.lines.map { "${KitchenTicketRenderer.formatQty(it.line.quantityMilli)} x ${it.line.label}" to it.net.format() }
            val rem = s.remaining
            val paid = s.ticket != null
            return Order(lines, p.totalTtc.format(), if (!paid && s.payments.isNotEmpty()) rem.format() else null, paid)
        }
    }
}

/**
 * Écran client sur le second écran Android du terminal (API Presentation, sans SDK fabricant).
 * Sans second écran, ne fait rien.
 */
class CustomerDisplay(private val context: Context) {
    private var presentation: Screen? = null

    fun show(view: CustomerView) {
        val p = presentation ?: open() ?: return
        p.render(view)
    }

    fun close() {
        runCatching { presentation?.dismiss() }
        presentation = null
    }

    private fun open(): Screen? {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val d = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull() ?: return null
        return runCatching { Screen(context, d).also { it.show(); presentation = it } }.getOrNull()
    }

    private class Screen(ctx: Context, display: Display) : Presentation(ctx, display) {
        private lateinit var header: TextView
        private lateinit var lines: TextView
        private lateinit var footer: TextView

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val pad = (12 * context.resources.displayMetrics.density).toInt()
            header = TextView(context).apply {
                textSize = 26f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
                setTextColor(WHITE); setBackgroundColor(BLUE); setPadding(pad, pad, pad, pad)
            }
            lines = TextView(context).apply {
                textSize = 20f; typeface = Typeface.MONOSPACE; setTextColor(NAVY); setPadding(pad, pad, pad, pad)
            }
            footer = TextView(context).apply {
                textSize = 34f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
                setTextColor(WHITE); setBackgroundColor(NAVY); setPadding(pad, pad, pad, pad)
            }
            setContentView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xFFFFFFFF.toInt())
                addView(header, LinearLayout.LayoutParams(-1, -2))
                addView(lines, LinearLayout.LayoutParams(-1, 0, 1f))
                addView(footer, LinearLayout.LayoutParams(-1, -2))
            })
        }

        fun render(v: CustomerView) {
            if (!::header.isInitialized) return
            when (v) {
                is CustomerView.Idle -> {
                    header.text = "FOODSAFE+"
                    lines.gravity = Gravity.CENTER
                    lines.textSize = 28f
                    lines.text = "Bienvenue\n${v.establishment}"
                    footer.text = ""
                }
                is CustomerView.Order -> {
                    header.text = if (v.paid) "Merci de votre visite" else "Votre commande"
                    lines.gravity = Gravity.TOP or Gravity.START
                    lines.textSize = 20f
                    // Dernières lignes en bas : on garde celles qui tiennent.
                    lines.text = v.lines.takeLast(8).joinToString("\n") { (l, a) -> l.take(24).padEnd(24) + " " + a.padStart(10) }
                    footer.text = when {
                        v.paid -> "Réglé : ${v.total}"
                        v.remaining != null -> "Reste à payer : ${v.remaining}"
                        else -> "Total : ${v.total}"
                    }
                }
            }
        }

        companion object {
            const val WHITE = 0xFFFFFFFF.toInt()
            const val BLUE = 0xFF1E4FC8.toInt()
            const val NAVY = 0xFF1C2230.toInt()
        }
    }
}
