package com.example.hitchhike.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ValidadorPdfCnhTest {

    @Test
    fun aceitaBytesDePdfValidoDentroDoLimite() {
        val pdfBytes = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n".toByteArray(Charsets.UTF_8)
        assertNull(ValidadorPdfCnh.validarBytesPdf(pdfBytes, "application/pdf", "cnh.pdf"))
    }

    @Test
    fun rejeitaImagensTiposInvalidosEArquivosRenomeados() {
        val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertEquals(
            ErroValidacaoPdf.TIPO_NAO_PDF,
            ValidadorPdfCnh.validarBytesPdf(pngBytes, "image/png", "foto.png")
        )
        assertEquals(
            ErroValidacaoPdf.TIPO_NAO_PDF,
            ValidadorPdfCnh.validarBytesPdf(pngBytes, "image/jpeg", "foto.jpg")
        )
        // Imagem renomeada para .pdf com MIME application/pdf é barrada pelo cabeçalho %PDF-
        assertEquals(
            ErroValidacaoPdf.CABECALHO_INVALIDO,
            ValidadorPdfCnh.validarBytesPdf(pngBytes, "application/pdf", "cnh.pdf")
        )
    }

    @Test
    fun rejeitaArquivoVazioEAcimaDe5Mb() {
        assertEquals(
            ErroValidacaoPdf.ARQUIVO_VAZIO,
            ValidadorPdfCnh.validarBytesPdf(ByteArray(0), "application/pdf", "vazio.pdf")
        )
        val grande = ByteArray(ValidadorPdfCnh.LIMITE_BYTES + 1)
        grande[0] = 0x25; grande[1] = 0x50; grande[2] = 0x44; grande[3] = 0x46; grande[4] = 0x2D
        assertEquals(
            ErroValidacaoPdf.ACIMA_DO_LIMITE_5MB,
            ValidadorPdfCnh.validarBytesPdf(grande, "application/pdf", "grande.pdf")
        )
    }
}
