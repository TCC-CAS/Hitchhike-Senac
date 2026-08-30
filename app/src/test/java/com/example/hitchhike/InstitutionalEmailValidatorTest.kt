package com.example.hitchhike

import com.example.hitchhike.util.InstitutionalEmailValidator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstitutionalEmailValidatorTest {
    @Test
    fun aceitaDominiosSenac() {
        assertTrue(InstitutionalEmailValidator.isValid("isabella@senac.br"))
        assertTrue(InstitutionalEmailValidator.isValid("aluno@sp.senac.br"))
        assertTrue(InstitutionalEmailValidator.isValid("  Docente@Senac.BR  "))
    }

    @Test
    fun rejeitaDominiosExternos() {
        assertFalse(InstitutionalEmailValidator.isValid("aluno@gmail.com"))
        assertFalse(InstitutionalEmailValidator.isValid("aluno@senac.com"))
        assertFalse(InstitutionalEmailValidator.isValid("senac.br"))
        assertFalse(InstitutionalEmailValidator.isValid(""))
    }
}
