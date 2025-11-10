package com.example.aimesdes.navigation

import java.text.Normalizer
import java.util.Locale

object DireccionMetro {
    private val estaciones = listOf(
        "cerrillos",
        "lo valledor",
        "pac",
        "franklin",
        "biobío",
        "ñuble",
        "estadio nacional",
        "ñuñoa",
        "inés de suárez",
        "los leones"
    )

    private val indiceNormalizado: Map<String, Int> = estaciones
        .mapIndexed { idx, nombre -> normalize(nombre) to idx }
        .toMap()

    fun decidirDireccion(contexto: Map<String, String>): String {
        val estActualRaw = contexto["estacion_actual"] ?: return "Estación no encontrada."
        val estDestinoRaw = contexto["estacion_destino"] ?: return "Estación no encontrada."

        val idxActual = indiceNormalizado[normalize(estActualRaw)] ?: return "Estación no encontrada."
        val idxDestino = indiceNormalizado[normalize(estDestinoRaw)] ?: return "Estación no encontrada."

        if (idxActual == idxDestino) return "Ya estás en la estación de destino."
        return if (idxDestino > idxActual) {
            "Debes ir en dirección a ${titleCase(estaciones.last())}."
        } else {
            "Debes ir en dirección a ${titleCase(estaciones.first())}."
        }
    }

    fun normalizaEstacion(o: String) = normalize(o)
    fun indiceDe(o: String) = indiceNormalizado[normalize(o)]

    private fun normalize(s: String): String {
        val nfd = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        return nfd.replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            .replace("ñ", "n")
    }

    private fun titleCase(s: String): String =
        s.split(Regex("\\s+")).joinToString(" ") { w ->
            w.replaceFirstChar { c ->
                if (c.isLowerCase()) c.titlecase(Locale("es", "CL")) else c.toString()
            }
        }
}
