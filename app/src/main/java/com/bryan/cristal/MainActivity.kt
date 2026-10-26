package com.bryan.cristal

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

class MainActivity : Activity() {

    private lateinit var state: TextView
    private lateinit var downloadInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun text(s: String, size: Float = 16f, bold: Boolean = false) = TextView(this).apply {
            text = s
            textSize = size
            setTextColor(Color.rgb(30, 30, 40))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
        }

        fun button(s: String, onClick: () -> Unit) = Button(this).apply {
            text = s
            isAllCaps = false
            setOnClickListener { onClick() }
        }

        col.addView(text("Cristal Traductor", 24f, true))
        state = text("", 16f, true)
        col.addView(state)

        col.addView(text("Paso 1 — Permitir ajustes restringidos", 17f, true))
        col.addView(text("Como la app no viene de Play Store, Android bloquea el permiso de accesibilidad la primera vez. Abre la info de la app, toca los tres puntos (⋮) arriba a la derecha y elige “Permitir ajustes restringidos”. Si no aparece ese menú, salta al paso 2."))
        col.addView(button("Abrir info de la app") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })

        col.addView(text("Paso 2 — Encender el cristal", 17f, true))
        col.addView(text("En Accesibilidad busca “Cristal Traductor” (a veces dentro de “Apps instaladas” o “Servicios descargados”) y actívalo. Ahí mismo puedes activar el “acceso directo” para encenderlo y apagarlo rápido."))
        col.addView(button("Abrir Accesibilidad") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        col.addView(text("Paso 3 (opcional) — Descargar idiomas", 17f, true))
        col.addView(text("Cada idioma pesa unos 30 MB y se descarga solo la primera vez que aparece. Si quieres tenerlos listos con Wi-Fi, descárgalos ahora: inglés, japonés, italiano, francés, portugués, coreano y chino → español."))
        downloadInfo = text("", 14f)
        col.addView(button("Descargar idiomas ahora") { downloadModels() })
        col.addView(downloadInfo)

        col.addView(text("Cómo se usa", 17f, true))
        col.addView(text("• Arrastra “⠿ Mover” para subir o bajar el cristal.\n• ↕ cambia el alto (35 %, 50 %, 75 %, pantalla completa).\n• ⏸ pausa y ▶ reanuda.\n• ✕ apaga el servicio.\n• Todo lo que tocas atraviesa el cristal: puedes hacer scroll, pasar página o tocar enlaces normalmente."))

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(250, 249, 245))
            addView(col)
        }
        setContentView(scroll)
        col.gravity = Gravity.START
    }

    override fun onResume() {
        super.onResume()
        state.text = if (CristalService.instance != null) "✅ El cristal está encendido" else "⚪ El cristal está apagado"
    }

    private fun downloadModels() {
        val langs = listOf(
            TranslateLanguage.ENGLISH, TranslateLanguage.JAPANESE, TranslateLanguage.ITALIAN,
            TranslateLanguage.FRENCH, TranslateLanguage.PORTUGUESE, TranslateLanguage.KOREAN,
            TranslateLanguage.CHINESE
        )
        var done = 0
        var failed = 0
        downloadInfo.text = "Descargando 0 de ${langs.size}…"
        for (l in langs) {
            val t = Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(l).setTargetLanguage(TranslateLanguage.SPANISH).build()
            )
            t.downloadModelIfNeeded(DownloadConditions.Builder().build())
                .addOnSuccessListener { done++ }
                .addOnFailureListener { failed++ }
                .addOnCompleteListener {
                    t.close()
                    downloadInfo.text = if (done + failed < langs.size)
                        "Descargando ${done + failed} de ${langs.size}…"
                    else if (failed == 0) "✅ Idiomas listos"
                    else "Listos $done, fallaron $failed. Vuelve a intentarlo con buen internet."
                }
        }
    }
}
