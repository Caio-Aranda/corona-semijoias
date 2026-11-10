package br.com.coronasemijoias.api.vendedores;

import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import io.swagger.v3.oas.annotations.Operation;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Aba "Documentos" do vendedor: fotos e PDFs (RG, comprovantes). Dados pessoais: só a dona acessa e a
 * resposta do download nunca fica em cache.
 */
@RestController
@RequestMapping("/api/dona/vendedores/{id}/documentos")
@ExigePerfil("ADMIN")
public class DocumentosDoVendedorController {

    private final ServicoDeDocumentos servico;

    public DocumentosDoVendedorController(ServicoDeDocumentos servico) {
        this.servico = servico;
    }

    @Operation(summary = "Listar os documentos do vendedor")
    @GetMapping
    public List<DocumentoDoVendedor.Resposta> listar(@PathVariable UUID id) {
        return servico.listar(id);
    }

    @Operation(summary = "Enviar um documento",
            description = "Formulário com 'descricao' e 'arquivo'. Aceita PDF, JPG, PNG e WebP de até 10 MB; "
                    + "o tipo vale pelo conteúdo do arquivo.")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentoDoVendedor.Resposta> enviar(@PathVariable UUID id,
            @RequestParam(name = "descricao", required = false) String descricao,
            @RequestPart(name = "arquivo", required = false) MultipartFile arquivo,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        return ResponseEntity.status(HttpStatus.CREATED).body(servico.enviar(id, descricao, arquivo, quem.id()));
    }

    @Operation(summary = "Baixar um documento")
    @GetMapping("/{documentoId}/arquivo")
    public ResponseEntity<byte[]> baixar(@PathVariable UUID id, @PathVariable UUID documentoId) {
        ServicoDeDocumentos.Baixado baixado = servico.baixar(id, documentoId);
        DocumentoDoVendedor documento = baixado.documento();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(documento.getTipoConteudo()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(documento.getNomeOriginal(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body(baixado.conteudo());
    }

    @Operation(summary = "Excluir um documento", description = "Exclusão lógica: o documento some da lista e do download.")
    @DeleteMapping("/{documentoId}")
    public ResponseEntity<Void> excluir(@PathVariable UUID id, @PathVariable UUID documentoId) {
        servico.excluir(id, documentoId);
        return ResponseEntity.noContent().build();
    }
}
