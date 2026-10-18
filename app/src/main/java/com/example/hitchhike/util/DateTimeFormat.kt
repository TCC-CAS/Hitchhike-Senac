package com.example.hitchhike.util

import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DateTimeFormat {
    private val locale = Locale("pt", "BR")
    private val formato = SimpleDateFormat("dd/MM/yyyy HH:mm", locale)
    private val moeda = NumberFormat.getCurrencyInstance(locale)

    fun dataHora(millis: Long): String = formato.format(Date(millis))

    fun valor(valor: Double): String = if (valor <= 0) "Cortesia" else moeda.format(valor)
}
