
package com.example.hitchhike.ui

import android.os.Bundle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.example.hitchhike.R

class DocumentoLegalActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val tipo = intent.getStringExtra("tipo")

        val titulo: String
        val arquivo: Int

        if (tipo == "termos") {
            titulo = "Termos de Uso"
            arquivo = R.raw.termos_uso
        } else if (tipo == "privacidade") {
            titulo = "Política de Privacidade"
            arquivo = R.raw.politica_privacidade
        } else {
            finish()
            return
        }

        title = titulo

        val conteudo = resources.openRawResource(arquivo)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

        val padding = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            24f,
            resources.displayMetrics
        ).toInt()

        val coluna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        layoutInflater.inflate(R.layout.include_btn_voltar, coluna, true)
        configurarBotaoVoltar(coluna.findViewById(R.id.btnVoltar))

        val texto = TextView(this).apply {
            text = conteudo
            textSize = 15f
            setTextColor(getColor(R.color.hh_text))
            setLineSpacing(6f, 1f)
        }
        coluna.addView(texto)

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.hh_background))
            isFillViewport = true
            addView(coluna)
        }

        setContentView(scrollView)
        scrollView.applySystemInsets()
    }
}
