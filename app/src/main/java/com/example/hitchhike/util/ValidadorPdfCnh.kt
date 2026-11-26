package com.example.hitchhike.util

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

data class PdfCnhSelecionado(
    val nomeArquivo: String,
    val tamanhoBytes: Int,
    val dataUrlBase64: String
)

enum class ErroValidacaoPdf {
    ARQUIVO_NAO_ENCONTRADO,
    TIPO_NAO_PDF,
    ARQUIVO_VAZIO,
    ACIMA_DO_LIMITE_5MB,
    CABECALHO_INVALIDO,
    PROTEGIDO_OU_CORROMPIDO,
    MULTIPLAS_PAGINAS
}

class PdfCnhInvalidoException(
    val codigo: ErroValidacaoPdf,
    override val message: String
) : Exception(message)

object ValidadorPdfCnh {

    const val LIMITE_BYTES = 5 * 1024 * 1024 // 5 MB
    private val CABECALHO_PDF = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D) // "%PDF-"

    /**
     * Valida os bytes brutos de um PDF (tamanho <= 5 MB e cabeçalho %PDF-).
     * Separado do ContentResolver/PdfRenderer para permitir testes unitários puros na JVM.
     */
    fun validarBytesPdf(bytes: ByteArray, mimeType: String?, nomeArquivo: String?): ErroValidacaoPdf? {
        val mimeNormalizado = mimeType?.trim()?.lowercase()
        val nomeNormalizado = nomeArquivo?.trim()?.lowercase()
        if (mimeNormalizado != null && mimeNormalizado != "application/pdf" && mimeNormalizado != "application/octet-stream") {
            return ErroValidacaoPdf.TIPO_NAO_PDF
        }
        if (nomeNormalizado != null && nomeNormalizado.isNotEmpty() && !nomeNormalizado.endsWith(".pdf") && mimeNormalizado != "application/pdf") {
            return ErroValidacaoPdf.TIPO_NAO_PDF
        }
        if (bytes.isEmpty()) {
            return ErroValidacaoPdf.ARQUIVO_VAZIO
        }
        if (bytes.size > LIMITE_BYTES) {
            return ErroValidacaoPdf.ACIMA_DO_LIMITE_5MB
        }
        if (bytes.size < CABECALHO_PDF.size) {
            return ErroValidacaoPdf.CABECALHO_INVALIDO
        }
        for (i in CABECALHO_PDF.indices) {
            if (bytes[i] != CABECALHO_PDF[i]) {
                return ErroValidacaoPdf.CABECALHO_INVALIDO
            }
        }
        return null
    }

    fun mensagemErro(codigo: ErroValidacaoPdf): String = when (codigo) {
        ErroValidacaoPdf.ARQUIVO_NAO_ENCONTRADO -> "Não foi possível abrir o arquivo selecionado."
        ErroValidacaoPdf.TIPO_NAO_PDF -> "Aceitamos somente arquivo PDF original da CNH Digital. Imagens não são aceitas."
        ErroValidacaoPdf.ARQUIVO_VAZIO -> "O arquivo PDF selecionado está vazio."
        ErroValidacaoPdf.ACIMA_DO_LIMITE_5MB -> "O arquivo ultrapassa o limite máximo de 5 MB."
        ErroValidacaoPdf.CABECALHO_INVALIDO -> "Arquivo PDF inválido. Renomear uma imagem não a transforma em PDF."
        ErroValidacaoPdf.PROTEGIDO_OU_CORROMPIDO -> "Não foi possível ler o PDF. Verifique se o arquivo está protegido por senha ou corrompido."
        ErroValidacaoPdf.MULTIPLAS_PAGINAS -> "Envie o PDF original da CNH Digital com exatamente 1 página (frente, verso e QR Code na mesma página)."
    }

    /**
     * Lê o Uri selecionado via OpenDocument, verifica MIME, tamanho <= 5 MB, cabeçalho %PDF-
     * e utiliza o PdfRenderer nativo do Android para confirmar que o PDF abre sem senha e possui exatamente 1 página.
     */
    suspend fun lerEValidar(context: Context, uri: Uri): Result<PdfCnhSelecionado> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val mimeType = resolver.getType(uri)
            val (nomeOriginal, tamanhoDeclarado) = consultarMetadados(context, uri)

            if (tamanhoDeclarado != null && tamanhoDeclarado > LIMITE_BYTES) {
                throw PdfCnhInvalidoException(
                    ErroValidacaoPdf.ACIMA_DO_LIMITE_5MB,
                    mensagemErro(ErroValidacaoPdf.ACIMA_DO_LIMITE_5MB)
                )
            }

            val bytes = resolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArrayOutputStream()
                val bloco = ByteArray(8192)
                var total = 0
                while (true) {
                    val lidos = input.read(bloco)
                    if (lidos < 0) break
                    total += lidos
                    if (total > LIMITE_BYTES) {
                        throw PdfCnhInvalidoException(
                            ErroValidacaoPdf.ACIMA_DO_LIMITE_5MB,
                            mensagemErro(ErroValidacaoPdf.ACIMA_DO_LIMITE_5MB)
                        )
                    }
                    buffer.write(bloco, 0, lidos)
                }
                buffer.toByteArray()
            } ?: throw PdfCnhInvalidoException(
                ErroValidacaoPdf.ARQUIVO_NAO_ENCONTRADO,
                mensagemErro(ErroValidacaoPdf.ARQUIVO_NAO_ENCONTRADO)
            )

            val erroBasico = validarBytesPdf(bytes, mimeType, nomeOriginal)
            if (erroBasico != null) {
                throw PdfCnhInvalidoException(erroBasico, mensagemErro(erroBasico))
            }

            val numPaginas = contarPaginasComPdfRenderer(context, bytes)
            if (numPaginas != 1) {
                throw PdfCnhInvalidoException(
                    ErroValidacaoPdf.MULTIPLAS_PAGINAS,
                    mensagemErro(ErroValidacaoPdf.MULTIPLAS_PAGINAS)
                )
            }

            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val nomeExibicao = nomeOriginal?.takeIf { it.isNotBlank() } ?: "cnh.pdf"

            PdfCnhSelecionado(
                nomeArquivo = nomeExibicao,
                tamanhoBytes = bytes.size,
                dataUrlBase64 = "data:application/pdf;base64,$base64"
            )
        }
    }

    private fun consultarMetadados(context: Context, uri: Uri): Pair<String?, Long?> {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return null to null
                val idxNome = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val idxTam = cursor.getColumnIndex(OpenableColumns.SIZE)
                val nome = if (idxNome >= 0 && !cursor.isNull(idxNome)) cursor.getString(idxNome) else null
                val tam = if (idxTam >= 0 && !cursor.isNull(idxTam)) cursor.getLong(idxTam) else null
                nome to tam
            } ?: (null to null)
        } catch (e: Exception) {
            null to null
        }
    }

    private fun contarPaginasComPdfRenderer(context: Context, bytes: ByteArray): Int {
        val temp = File.createTempFile("cnh_validacao_", ".pdf", context.cacheDir)
        try {
            FileOutputStream(temp).use { it.write(bytes) }
            return ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    renderer.pageCount
                }
            }
        } catch (e: SecurityException) {
            throw PdfCnhInvalidoException(
                ErroValidacaoPdf.PROTEGIDO_OU_CORROMPIDO,
                "O PDF está protegido por senha. Envie uma cópia sem senha."
            )
        } catch (e: Exception) {
            throw PdfCnhInvalidoException(
                ErroValidacaoPdf.PROTEGIDO_OU_CORROMPIDO,
                mensagemErro(ErroValidacaoPdf.PROTEGIDO_OU_CORROMPIDO)
            )
        } finally {
            temp.delete()
        }
    }
}
