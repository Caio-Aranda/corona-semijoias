package br.com.coronasemijoias.api.conciliacoes;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.conciliacoes.ConsultasDaConferenciaDiaria.Pagina;
import br.com.coronasemijoias.api.conciliacoes.ConsultasDaConferenciaDiaria.RodadaComDiferencas;
import br.com.coronasemijoias.api.conciliacoes.ServicoDaConferenciaDiaria.Origem;
import br.com.coronasemijoias.api.conciliacoes.ServicoDaConferenciaDiaria.Resultado;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A conferência diária da dona (tarefa 8.4; ADR-0015, item 6): "Conferir agora", o histórico e o detalhe de cada rodada. Só o perfil
 * ADMIN; nada disto vai ao Corona Vendas. Nada é corrigido: a rodada só confere e guarda o resultado. O id do comando vem do
 * aplicativo: 201 na primeira gravação, 200 no reenvio do mesmo id, 409 se outra rodada está rodando.
 */
@RestController
@RequestMapping("/api/dona/conferencias")
@ExigePerfil("ADMIN")
public class ConferenciaDiariaDaDonaController {

    /** O corpo de "Conferir agora": só o id do comando, gerado pelo aplicativo. */
    public record PedidoDeConferencia(UUID id) {
    }

    private final ServicoDaConferenciaDiaria servico;
    private final ConsultasDaConferenciaDiaria consultas;

    ConferenciaDiariaDaDonaController(ServicoDaConferenciaDiaria servico, ConsultasDaConferenciaDiaria consultas) {
        this.servico = servico;
        this.consultas = consultas;
    }

    @Operation(operationId = "conferirAgora", summary = "Conferir agora",
            description = "Roda todas as conferências (estoque, devolução e troca, C1 a C5 e C7 a C12) num retrato só do banco e guarda o "
                    + "resultado. Nada é corrigido. Devolve a rodada com as diferenças: 201 na primeira vez, 200 se o mesmo id já "
                    + "foi gravado, 409 se outra conferência está rodando. Sem id, 400.")
    @PostMapping
    public ResponseEntity<RodadaComDiferencas> conferirAgora(@RequestBody PedidoDeConferencia pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        if (pedido == null || pedido.id() == null) {
            throw Validacoes.recusar("id", "Atualize o aplicativo e tente de novo: faltou o número do pedido de conferência.");
        }
        UUID id = Validacoes.idDoPedido(pedido.id());
        Resultado r = servico.rodar(id, Origem.PEDIDA, quem.id());
        return ResponseEntity.status(r.jaExistia() ? HttpStatus.OK : HttpStatus.CREATED).body(consultas.ver(r.id()));
    }

    @Operation(operationId = "listarConferencias", summary = "Histórico das conferências",
            description = "As rodadas da conferência, da mais nova para a mais velha: quando, se foi automática ou pedida (e por quem), a "
                    + "situação, quantas diferenças e as conferências que deram erro. pagina começa em zero; tamanho vai de 1 a 100 "
                    + "(padrão 30).")
    @GetMapping
    public Pagina listar(@RequestParam(required = false) Integer pagina, @RequestParam(required = false) Integer tamanho) {
        return consultas.listar(pagina, tamanho);
    }

    @Operation(operationId = "verConferencia", summary = "Ver uma conferência",
            description = "A rodada com as diferenças agrupadas por conferência, na ordem em que foram guardadas. Valores só em Real "
                    + "(a conferência não usa Dólar nem Guarani). 404 se a rodada não existe.")
    @GetMapping("/{id}")
    public RodadaComDiferencas ver(@PathVariable UUID id) {
        return consultas.ver(id);
    }
}
