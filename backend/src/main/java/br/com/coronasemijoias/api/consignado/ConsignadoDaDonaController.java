package br.com.coronasemijoias.api.consignado;

import br.com.coronasemijoias.api.configuracoes.Configuracoes;
import br.com.coronasemijoias.api.consignado.ConsultasDoConsignado.AlertaLido;
import br.com.coronasemijoias.api.consignado.ConsultasDoConsignado.DetalheDaNota;
import br.com.coronasemijoias.api.consignado.ConsultasDoConsignado.NotaResumo;
import br.com.coronasemijoias.api.consignado.ConsultasDoConsignado.SemanaDoRepresentante;
import br.com.coronasemijoias.api.consignado.ConsultasDoConsignado.SituacaoDoCliente;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Leituras da dona sobre o consignado ao cliente, alertas, conciliações e o que o Corona Gestão mostra ou esconde
 * (ADR-0016, itens 11, 15 e 17). Só ADMIN. Nenhuma rota escreve dado do consignado: tudo nasce no Corona Vendas.
 */
@RestController
@RequestMapping("/api")
@ExigePerfil("ADMIN")
public class ConsignadoDaDonaController {

    public record TelasDoGestao(boolean acertoDoVendedorVisivel) {
    }

    private final ConsultasDoConsignado consultas;
    private final ConciliacoesDoConsignado conciliacoes;
    private final Configuracoes configuracoes;

    ConsignadoDaDonaController(ConsultasDoConsignado consultas, ConciliacoesDoConsignado conciliacoes, Configuracoes configuracoes) {
        this.consultas = consultas;
        this.conciliacoes = conciliacoes;
        this.configuracoes = configuracoes;
    }

    @Operation(operationId = "listarSemanasDoRepresentante", summary = "Semanas de um representante, com os totais")
    @GetMapping("/consignado/vendedores/{vendedorId}/semanas")
    public List<SemanaDoRepresentante> semanas(@PathVariable UUID vendedorId) {
        return consultas.semanasDoRepresentante(vendedorId);
    }

    @Operation(operationId = "verSemanaDoRepresentante", summary = "Uma semana do representante (totais da semana)")
    @GetMapping("/consignado/semanas/{semanaId}")
    public SemanaDoRepresentante semana(@PathVariable UUID semanaId) {
        return consultas.semana(semanaId);
    }

    @Operation(operationId = "listarNotasDoConsignado", summary = "Notas do consignado",
            description = "Filtros opcionais: representante, semana, situação (EM_ABERTO, PENDENTE, FECHADO, REMARCADO), cliente e texto "
                    + "(nome do cliente ou número da nota). Até 200, das mais novas.")
    @GetMapping("/consignado/notas")
    public List<NotaResumo> notas(@RequestParam(required = false) UUID vendedorId, @RequestParam(required = false) UUID semanaId,
            @RequestParam(required = false) String situacao, @RequestParam(required = false) UUID clienteId,
            @RequestParam(required = false) String texto) {
        return consultas.notas(vendedorId, semanaId, situacao, clienteId, texto);
    }

    @Operation(operationId = "verNotaDoConsignado", summary = "Detalhe da nota: linhas, acertos e recibos")
    @GetMapping("/consignado/notas/{id}")
    public DetalheDaNota nota(@PathVariable UUID id) {
        return consultas.detalhe(id);
    }

    @Operation(operationId = "verSituacaoDoClienteDoConsignado", summary = "Situação do cliente",
            description = "Quanto deve, as notas dele e as peças que estão com ele agora.")
    @GetMapping("/consignado/clientes/{id}/situacao")
    public SituacaoDoCliente situacaoDoCliente(@PathVariable UUID id) {
        return consultas.situacaoDoCliente(id);
    }

    @Operation(operationId = "listarAlertasDoConsignado", summary = "Alertas do consignado para a dona",
            description = "Divergências aceitas do balcão (peça sem saldo, preço diferente, total recalculado, pagamento acima do "
                    + "devido, documento repetido). soNaoVistos=true mostra só os que ainda não foram vistos.")
    @GetMapping("/dona/consignado/alertas")
    public List<AlertaLido> alertas(@RequestParam(required = false, defaultValue = "false") boolean soNaoVistos) {
        return consultas.alertas(soNaoVistos);
    }

    @Operation(operationId = "marcarAlertaDoConsignadoComoVisto", summary = "Marcar um alerta como visto")
    @PostMapping("/dona/consignado/alertas/{id}/visto")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void visto(@PathVariable UUID id) {
        consultas.marcarVisto(id);
    }

    @Operation(operationId = "conferirConsignado", summary = "Conciliações do consignado (só leitura)",
            description = "Sete conferências (K1 a K7) sobre o banco; cada uma lista o que não bate. Vazio = tudo certo.")
    @GetMapping("/dona/consignado/conciliacoes")
    public ConciliacoesDoConsignado.Resultado conciliar() {
        return conciliacoes.conferir();
    }

    @Operation(operationId = "lerTelasDoGestao", summary = "O que o Corona Gestão mostra",
            description = "acertoDoVendedorVisivel = falso (padrão) esconde Acertos, Comissão por meta, Pendências do acerto e a "
                    + "semana da dona (§7.4). As rotas e os dados continuam; nada é apagado.")
    @GetMapping("/configuracoes/telas")
    public TelasDoGestao telas() {
        return new TelasDoGestao(configuracoes.inteiro(
                br.com.coronasemijoias.api.configuracoes.ChaveDeConfiguracao.ACERTO_DO_VENDEDOR_VISIVEL) == 1);
    }
}
