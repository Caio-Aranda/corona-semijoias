package br.com.coronasemijoias.api.cadastros;

import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Semanas de controle: cadastro da dona (tarefa 1.2). */
@RestController
@RequestMapping("/api/dona/semanas")
@ExigePerfil("ADMIN")
public class SemanasDaDonaController {

    /**
     * {@code id} (opcional, UUID do aplicativo) só vale ao criar; {@code version} só ao editar. As datas
     * são dias ({@code 2026-11-09}); {@code situacao} começa {@code ABERTA}.
     */
    public record Pedido(
            UUID id,
            @NotNull(message = Mensagens.OBRIGATORIO) @Min(value = 1, message = "Use um número a partir de 1.")
            @Max(value = 100000, message = "Use um número até 100000.") Integer numero,
            @NotNull(message = Mensagens.OBRIGATORIO) LocalDate inicio,
            @NotNull(message = Mensagens.OBRIGATORIO) LocalDate fim,
            SituacaoDaSemana situacao,
            Integer version) {
    }

    public record Resposta(UUID id, int version, int numero, LocalDate inicio, LocalDate fim, SituacaoDaSemana situacao) {

        static Resposta de(Semana s) {
            return new Resposta(s.getId(), s.getVersion(), s.getNumero(), s.getInicio(), s.getFim(), s.getSituacao());
        }
    }

    private final ServicoDeSemanas servico;

    public SemanasDaDonaController(ServicoDeSemanas servico) {
        this.servico = servico;
    }

    @Operation(summary = "Listar semanas", description = "Busca pelo número; a mais nova primeiro. A página começa em 0 (até 100 por página).")
    @GetMapping
    public Pagina<Resposta> listar(@RequestParam(required = false) String busca,
            @RequestParam(required = false) Integer pagina, @RequestParam(required = false) Integer tamanho) {
        return Pagina.de(servico.listar(busca, pagina, tamanho), Resposta::de);
    }

    @GetMapping("/{id}")
    public Resposta ver(@PathVariable UUID id) {
        return Resposta.de(servico.obter(id));
    }

    @Operation(summary = "Criar semana",
            description = "O número não repete e as datas (as duas pontas contam) não podem estar em outra semana.")
    @PostMapping
    public ResponseEntity<Resposta> criar(@Valid @RequestBody Pedido pedido) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Resposta.de(
                servico.criar(pedido.id(), pedido.numero(), pedido.inicio(), pedido.fim(), pedido.situacao())));
    }

    @Operation(summary = "Editar semana", description = "Mande a version que a tela leu; se outra pessoa alterou antes, volta 409.")
    @PutMapping("/{id}")
    public Resposta editar(@PathVariable UUID id, @Valid @RequestBody Pedido pedido) {
        return Resposta.de(servico.editar(id, pedido.version(), pedido.numero(), pedido.inicio(), pedido.fim(),
                pedido.situacao()));
    }

    @Operation(summary = "Excluir semana", description = "Exclusão lógica: o número e as datas ficam livres.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> excluir(@PathVariable UUID id) {
        servico.excluir(id);
        return ResponseEntity.noContent().build();
    }
}
