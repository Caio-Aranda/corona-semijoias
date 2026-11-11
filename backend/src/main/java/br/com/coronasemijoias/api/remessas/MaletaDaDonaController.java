package br.com.coronasemijoias.api.remessas;

import br.com.coronasemijoias.api.remessas.ConsultasDaMaleta.EmAberto;
import br.com.coronasemijoias.api.remessas.ConsultasDaMaleta.Linha;
import br.com.coronasemijoias.api.remessas.ConsultasDaMaleta.Maleta;
import br.com.coronasemijoias.api.remessas.ConsultasDaMaleta.MaletaEmAberto;
import br.com.coronasemijoias.api.remessas.ConsultasDaMaleta.Movimento;
import br.com.coronasemijoias.api.remessas.RemessasDaDonaController.RespostaDaPeca;
import br.com.coronasemijoias.api.remessas.RemessasDaDonaController.RespostaDaRemessa;
import br.com.coronasemijoias.api.remessas.ServicoDaMaleta.LinhaDeDevolucao;
import br.com.coronasemijoias.api.remessas.ServicoDaMaleta.Resultado;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import io.swagger.v3.oas.annotations.Operation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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
 * Maleta enviada (tarefa 3.3; ADR-0010, itens 4 e 5): devolução à dona, cancelamento, desfazer devolução e leitura do
 * saldo. Só ADMIN. Cada operação tem id do aplicativo (idempotência: 201 na primeira vez, 200 no reenvio, 409 com dados
 * diferentes). Nenhuma resposta traz custo.
 */
@RestController
@RequestMapping("/api/dona/remessas")
@ExigePerfil("ADMIN")
public class MaletaDaDonaController {

    public record PedidoDeDevolucao(List<PedidoDaLinha> itens) {
    }

    public record PedidoDaLinha(UUID id, UUID produtoId, Integer quantidade) {
    }

    public record PedidoComMotivo(String motivo) {
    }

    public record PedidoDeEstorno(UUID id, String motivo) {
    }

    public record RespostaDaLinha(RespostaDaPeca produto, int enviada, int devolvida, int saldo, BigDecimal precoUnitario,
            BigDecimal valorEmAberto) {

        static RespostaDaLinha de(Linha l) {
            return new RespostaDaLinha(RespostaDaPeca.de(l.produto()), l.enviada(), l.devolvida(), l.saldo(), l.precoUnitario(),
                    l.valorEmAberto());
        }
    }

    public record RespostaDoMovimento(UUID id, String tipo, UUID produtoId, String produtoNome, int quantidade, UUID estornaId,
            boolean desfeito, Instant em) {

        static RespostaDoMovimento de(Movimento m) {
            return new RespostaDoMovimento(m.id(), m.tipo(), m.produtoId(), m.produtoNome(), m.quantidade(), m.estornaId(),
                    m.desfeito(), m.em());
        }
    }

    public record RespostaDaMaleta(UUID id, String status, String motivoDoCancelamento, UUID vendedorId, int pecasEmAberto,
            BigDecimal valorEmAberto, List<RespostaDaLinha> itens, List<RespostaDoMovimento> movimentos) {

        static RespostaDaMaleta de(Maleta m) {
            return new RespostaDaMaleta(m.id(), m.status(), m.motivoDoCancelamento(), m.vendedorId(), m.pecasEmAberto(),
                    m.valorEmAberto(), m.itens().stream().map(RespostaDaLinha::de).toList(),
                    m.movimentos().stream().map(RespostaDoMovimento::de).toList());
        }
    }

    public record RespostaDaMaletaEmAberto(UUID id, int semanaNumero, String tipo, String status, Instant enviadaEm,
            int totalPecas, BigDecimal totalValor, int pecasEmAberto, BigDecimal valorEmAberto) {

        static RespostaDaMaletaEmAberto de(MaletaEmAberto m) {
            return new RespostaDaMaletaEmAberto(m.id(), m.semanaNumero(), m.tipo(), m.status(), m.enviadaEm(), m.totalPecas(),
                    m.totalValor(), m.pecasEmAberto(), m.valorEmAberto());
        }
    }

    public record RespostaDoEmAberto(UUID vendedorId, String vendedorNome, BigDecimal limite, boolean semLimite,
            boolean limiteDefinido, BigDecimal valorEmAberto, BigDecimal limiteRestante,
            List<RespostaDaMaletaEmAberto> maletas) {

        static RespostaDoEmAberto de(EmAberto e) {
            return new RespostaDoEmAberto(e.vendedorId(), e.vendedorNome(), e.limite(), e.semLimite(),
                    e.limiteDefinido(), e.valorEmAberto(), e.limiteRestante(),
                    e.maletas().stream().map(RespostaDaMaletaEmAberto::de).toList());
        }
    }

    private final ServicoDaMaleta servico;
    private final ConsultasDaMaleta consultas;
    private final ConsultasDeRemessas remessas;

    public MaletaDaDonaController(ServicoDaMaleta servico, ConsultasDaMaleta consultas, ConsultasDeRemessas remessas) {
        this.servico = servico;
        this.consultas = consultas;
        this.remessas = remessas;
    }

    @Operation(operationId = "devolverPecasDaMaleta", summary = "Devolver peças da maleta à dona",
            description = "Para cada peça (id do aplicativo, produto e quantidade positiva), tira da maleta enviada e devolve ao "
                    + "estoque da loja, na mesma transação e tudo ou nada. Nunca mais que o saldo da maleta naquela peça (409). "
                    + "Várias peças por chamada, sem repetir peça. Só maleta enviada. Mesmo id com os mesmos dados devolve 200 sem "
                    + "mexer de novo; primeira vez 201; mesmo id com outros dados, 409.")
    @PostMapping("/{id}/devolucoes")
    public ResponseEntity<RespostaDaMaleta> devolver(@PathVariable UUID id, @RequestBody PedidoDeDevolucao pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        List<LinhaDeDevolucao> linhas = pedido.itens() == null ? null
                : pedido.itens().stream()
                        .map(l -> l == null ? null : new LinhaDeDevolucao(l.id(), l.produtoId(), l.quantidade())).toList();
        return resposta(servico.devolver(id, linhas, quem.id()));
    }

    @Operation(operationId = "desfazerDevolucaoDaMaleta", summary = "Desfazer uma devolução",
            description = "Estorno: a peça volta para a maleta e o estoque da loja baixa de novo (409 se a loja não tiver as peças). "
                    + "Exige o id do estorno (do aplicativo) e o motivo. Mesmo id e mesmo motivo devolve 200; desfazer duas vezes ou "
                    + "desfazer um estorno, 409.")
    @PostMapping("/{id}/devolucoes/{devolucaoId}/estorno")
    public ResponseEntity<RespostaDaMaleta> desfazer(@PathVariable UUID id, @PathVariable UUID devolucaoId,
            @RequestBody PedidoDeEstorno pedido, @AuthenticationPrincipal UsuarioAutenticado quem) {
        return resposta(servico.desfazerDevolucao(id, devolucaoId, pedido.id(), pedido.motivo(), quem.id()));
    }

    @Operation(operationId = "cancelarRemessa", summary = "Cancelar a maleta",
            description = "Rascunho vira cancelada sem mexer no estoque. Enviada só cancela se nada foi devolvido ou vendido: o "
                    + "estoque da loja volta ao que era (estorno). Motivo obrigatório. Cancelar de novo devolve 200 sem mexer.")
    @PostMapping("/{id}/cancelar")
    public RespostaDaRemessa cancelar(@PathVariable UUID id, @RequestBody PedidoComMotivo pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        servico.cancelar(id, pedido.motivo(), quem.id());
        return RespostaDaRemessa.de(remessas.ver(id));
    }

    @Operation(operationId = "verMaletaDaRemessa", summary = "Ver o que há na maleta",
            description = "Por peça: enviada, devolvida e saldo (enviada mais os movimentos), a preço congelado; valor em aberto, "
                    + "peças em aberto e os movimentos da maleta.")
    @GetMapping("/{id}/maleta")
    public RespostaDaMaleta maleta(@PathVariable UUID id) {
        return RespostaDaMaleta.de(consultas.ver(id));
    }

    @Operation(operationId = "listarMaletasEmAbertoDoVendedor", summary = "Maletas em aberto de um vendedor",
            description = "Maletas enviadas com o valor em aberto de cada uma, o total, o limite de consignação (vazio se o vendedor "
                    + "está sem limite ou ainda não tem limite definido; veja semLimite e limiteDefinido) e quanto ainda sobra do limite.")
    @GetMapping("/em-aberto")
    public RespostaDoEmAberto emAberto(@RequestParam UUID vendedorId) {
        return RespostaDoEmAberto.de(consultas.emAberto(vendedorId));
    }

    private ResponseEntity<RespostaDaMaleta> resposta(Resultado r) {
        return ResponseEntity.status(r.jaExistia() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(RespostaDaMaleta.de(consultas.ver(r.remessaId())));
    }
}
