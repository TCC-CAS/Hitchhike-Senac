package com.example.hitchhike.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordValidatorTest {

    @Test
    fun aceitaSenhasFortesComConfirmacaoIdentica() {
        val r1 = PasswordValidator.avaliar("Senac@2026", "Senac@2026")
        assertTrue(r1.tamanhoMinimo)
        assertTrue(r1.temMaiuscula)
        assertTrue(r1.temMinuscula)
        assertTrue(r1.temNumero)
        assertTrue(r1.temEspecial)
        assertTrue(r1.confirmacaoCorresponde)
        assertEquals(5, r1.requisitosAtendidos)
        assertTrue(r1.senhaForte)
        assertTrue(r1.validaComConfirmacao)
        assertNull(PasswordValidator.mensagemErro("Senac@2026"))

        assertTrue(PasswordValidator.isValid("Hitchhike#1", "Hitchhike#1"))
        assertTrue(PasswordValidator.isStrong("Abcdef1!"))
    }

    @Test
    fun rejeitaSenhaCurtaComMenosDe8Caracteres() {
        val r = PasswordValidator.avaliar("Abc1!xy", "Abc1!xy")
        assertFalse(r.tamanhoMinimo)
        assertTrue(r.temMaiuscula)
        assertTrue(r.temMinuscula)
        assertTrue(r.temNumero)
        assertTrue(r.temEspecial)
        assertEquals(4, r.requisitosAtendidos)
        assertFalse(r.senhaForte)
        assertFalse(r.validaComConfirmacao)
        assertNotNull(PasswordValidator.mensagemErro("Abc1!xy"))
    }

    @Test
    fun rejeitaSenhaSemLetraMaiuscula() {
        val r = PasswordValidator.avaliar("senac@2026", "senac@2026")
        assertTrue(r.tamanhoMinimo)
        assertFalse(r.temMaiuscula)
        assertTrue(r.temMinuscula)
        assertTrue(r.temNumero)
        assertTrue(r.temEspecial)
        assertFalse(r.senhaForte)
        assertEquals(
            "A senha deve conter pelo menos uma letra maiúscula.",
            PasswordValidator.mensagemErro("senac@2026")
        )
    }

    @Test
    fun rejeitaSenhaSemLetraMinuscula() {
        val r = PasswordValidator.avaliar("SENAC@2026", "SENAC@2026")
        assertTrue(r.tamanhoMinimo)
        assertTrue(r.temMaiuscula)
        assertFalse(r.temMinuscula)
        assertTrue(r.temNumero)
        assertTrue(r.temEspecial)
        assertFalse(r.senhaForte)
        assertEquals(
            "A senha deve conter pelo menos uma letra minúscula.",
            PasswordValidator.mensagemErro("SENAC@2026")
        )
    }

    @Test
    fun rejeitaSenhaSemNumero() {
        val r = PasswordValidator.avaliar("Senac@Tcc", "Senac@Tcc")
        assertTrue(r.tamanhoMinimo)
        assertTrue(r.temMaiuscula)
        assertTrue(r.temMinuscula)
        assertFalse(r.temNumero)
        assertTrue(r.temEspecial)
        assertFalse(r.senhaForte)
        assertEquals(
            "A senha deve conter pelo menos um número.",
            PasswordValidator.mensagemErro("Senac@Tcc")
        )
    }

    @Test
    fun rejeitaSenhaSemCaractereEspecialEIgnoraEspacoEmBrancoComoEspecial() {
        val r = PasswordValidator.avaliar("Senac2026", "Senac2026")
        assertTrue(r.tamanhoMinimo)
        assertTrue(r.temMaiuscula)
        assertTrue(r.temMinuscula)
        assertTrue(r.temNumero)
        assertFalse(r.temEspecial)
        assertFalse(r.senhaForte)
        assertEquals(
            "A senha deve conter pelo menos um caractere especial.",
            PasswordValidator.mensagemErro("Senac2026")
        )

        val comEspaco = PasswordValidator.avaliar("Senac 2026", "Senac 2026")
        assertFalse(comEspaco.temEspecial)
        assertFalse(comEspaco.senhaForte)
    }

    @Test
    fun rejeitaConfirmacaoDivergenteOuVazia() {
        val divergente = PasswordValidator.avaliar("Senac@2026", "Senac@2027")
        assertTrue(divergente.senhaForte)
        assertFalse(divergente.confirmacaoCorresponde)
        assertFalse(divergente.validaComConfirmacao)
        assertFalse(PasswordValidator.isValid("Senac@2026", "Senac@2027"))
        assertFalse(PasswordValidator.isValid("Senac@2026", ""))
        assertFalse(PasswordValidator.avaliar("", "").confirmacaoCorresponde)
    }
}
