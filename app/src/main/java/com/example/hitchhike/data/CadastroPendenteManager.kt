
package com.example.hitchhike.data

import android.content.Context

class CadastroPendenteManager(context: Context) {

    private val prefs = context.getSharedPreferences(
        "hitchhike_cadastro_pendente",
        Context.MODE_PRIVATE
    )

    fun salvar(
        uid: String,
        nome: String,
        curso: String,
        instituicao: String,
        email: String,
        papel: PapelUsuario
    ) {
        prefs.edit()
            .putString("uid", uid)
            .putString("nome", nome.trim())
            .putString("curso", curso.trim())
            .putString("instituicao", instituicao.trim())
            .putString("email", email.trim().lowercase())
            .putString("papel", papel.name)
            .apply()
    }

    fun buscar(uid: String): Map<String, String>? {
        if (prefs.getString("uid", null) != uid) {
            return null
        }

        return mapOf(
            "nome" to prefs.getString("nome", "").orEmpty(),
            "curso" to prefs.getString("curso", "").orEmpty(),
            "instituicao" to prefs.getString("instituicao", "").orEmpty(),
            "email" to prefs.getString("email", "").orEmpty(),
            "papel" to prefs.getString("papel", "PASSAGEIRO").orEmpty()
        )
    }

    fun limpar() {
        prefs.edit().clear().apply()
    }
}
