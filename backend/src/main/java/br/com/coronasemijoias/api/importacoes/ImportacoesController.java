package br.com.coronasemijoias.api.importacoes;

import br.com.coronasemijoias.api.importacoes.RegistroDeImportacoes.Linha;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Registro do resultado da importação de planilha (a de fotos é registrada pelo próprio servidor). Só ADMIN. */
@RestController
@RequestMapping("/api/dona/importacoes")
@ExigePerfil("ADMIN")
public class ImportacoesController {

    public record PedidoDeLinha(String referencia, String resultado, String motivo) {
    }

    public record PedidoDeLote(String tipo, String arquivoNome, List<PedidoDeLinha> linhas) {
    }

    private final RegistroDeImportacoes registro;

    public ImportacoesController(RegistroDeImportacoes registro) {
        this.registro = registro;
    }

    @Operation(summary = "Registrar o resultado de uma importação",
            description = "O aplicativo gera o id do lote (UUID) e manda as linhas em partes de até 500; repetir o mesmo "
                    + "id junta as partes ao mesmo lote. Cada linha diz a referência (por exemplo \"Linha 12\"), o "
                    + "resultado (CRIADO, ATUALIZADO, JA_EXISTIA ou RECUSADO) e o motivo. Este registro é o que o "
                    + "aplicativo informa: o que mudou nos produtos já foi gravado e conferido pelas rotas de produto.")
    @PostMapping("/{id}/linhas")
    public ResponseEntity<Void> registrar(@PathVariable UUID id, @RequestBody PedidoDeLote pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        List<Linha> linhas = pedido.linhas() == null ? null
                : pedido.linhas().stream().map(l -> l == null ? null : new Linha(l.referencia(), l.resultado(), l.motivo())).toList();
        registro.registrar(id, pedido.tipo(), pedido.arquivoNome(), quem.id(), linhas);
        return ResponseEntity.noContent().build();
    }
}
