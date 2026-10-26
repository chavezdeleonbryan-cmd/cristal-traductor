package com.bryan.cristal

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * Traduce textos en el propio teléfono (ML Kit, sin internet después de
 * descargar el idioma) y guarda lo ya traducido para que al volver a pasar
 * por el mismo párrafo la traducción salga al instante.
 */
class TranslateEngine(private val onUpdate: () -> Unit) {

    var target: String = TranslateLanguage.SPANISH

    private val cache = object : LinkedHashMap<String, String>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > 4000
    }
    private val pending = HashSet<String>()
    private val skip = HashSet<String>()
    private val translators = HashMap<String, Translator>()
    private val ready = HashMap<String, Task<Void>>()
    private var lastLang: String? = null

    var status: String = ""
        private set

    private val langId = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.35f).build()
    )

    fun get(text: String): String? = cache[text]

    fun isSkipped(text: String): Boolean = skip.contains(text)

    fun request(text: String) {
        if (cache.containsKey(text) || pending.contains(text) || skip.contains(text)) return
        if (pending.size > 60) return
        pending.add(text)

        langId.identifyLanguage(text)
            .addOnSuccessListener { code ->
                var src: String? = null
                if (code != "und") {
                    src = TranslateLanguage.fromLanguageTag(code)
                    if (src != null && text.length >= 20) lastLang = src
                }
                if (src == null) src = lastLang
                if (src == null || src == target) {
                    skip.add(text)
                    pending.remove(text)
                    return@addOnSuccessListener
                }
                translate(text, src)
            }
            .addOnFailureListener { pending.remove(text) }
    }

    private fun translate(text: String, src: String) {
        val key = "$src>$target"
        val translator = translators.getOrPut(key) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(src)
                    .setTargetLanguage(target)
                    .build()
            )
        }
        val readyTask = ready.getOrPut(key) {
            status = "Descargando idioma (solo la primera vez)…"
            onUpdate()
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                .addOnCompleteListener {
                    status = ""
                    onUpdate()
                }
        }
        readyTask
            .addOnSuccessListener {
                translator.translate(text)
                    .addOnSuccessListener { result ->
                        cache[text] = result
                        pending.remove(text)
                        onUpdate()
                    }
                    .addOnFailureListener { pending.remove(text) }
            }
            .addOnFailureListener {
                ready.remove(key)
                pending.remove(text)
                status = "No se pudo descargar el idioma. Revisa tu internet."
                onUpdate()
            }
    }

    fun close() {
        translators.values.forEach { it.close() }
        langId.close()
    }
}
