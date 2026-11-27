package br.com.coronasemijoias.api.consignado;

import br.com.coronasemijoias.api.consignado.ServicoDoFechamentoDaMaleta.DecisaoDaFalta;
import br.com.coronasemijoias.api.consignado.ServicoDoFechamentoDaMaleta.Leitura;
import br.com.coronasemijoias.api.consignado.ServicoDoFechamentoDaMaleta.LeituraDoFechamento;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** O Fechamento da maleta no Corona Gestão (ADR-0016, item 14). Só a dona. */
@RestController
@RequestMapping("/api/maletas/{id}/fechamento")
@ExigePerfil("ADMIN")
public class FechamentoDaMaletaController {

    public record PedidoDeLeituras(List<Leitura> leituras) {
    }

    /** As decisões para as peças que faltam (uma por peça); sem falta, pode vir vazio ou sem corpo. */
    public record PedidoDeConclusao(List<DecisaoDaFalta> decisoes) {
    }

    public record RespostaDoInicio(UUID fechamentoId) {
    }

    public record RespostaDasLeituras(int novas) {
    }

    private final ServicoDoFechamentoDaMaleta fechamento;

    FechamentoDaMaletaController(ServicoDoFechamentoDaMaleta fechamento) {
        this.fechamento = fechamento;
    }

    @Operation(operationId = "iniciarFechamentoDaMaleta", summary = "Começar o fechamento da maleta",
            description = "Só maleta enviada ou conferida; um fechamento em andamento por maleta (409 se já há).")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RespostaDoInicio iniciar(@PathVariable UUID id, @AuthenticationPrincipal UsuarioAutenticado quem) {
        return new RespostaDoInicio(fechamento.iniciar(id, quem.id()));
    }

    @Operation(operationId = "registrarLeiturasDoFechamentoDaMaleta", summary = "Guardar as peças bipadas na volta",
            description = "Em lote; cada leitura tem o id do aplicativo (reenviar não duplica).")
    @PostMapping("/leituras")
    public RespostaDasLeituras leituras(@PathVariable UUID id, @RequestBody PedidoDeLeituras pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        return new RespostaDasLeituras(fechamento.registrarLeituras(id, pedido == null ? null : pedido.leituras(), quem.id()));
    }

    @Operation(operationId = "lerFechamentoDaMaleta", summary = "A leitura do fechamento da maleta",
            description = "Por peça: enviadas, devolvidas pelos clientes, vendidas, ainda com clientes, esperado na maleta, bipadas e "
                    + "diferença; e os valores da maleta (VD, comissão dos clientes, recebido e pendências), divididos pelo vendido "
                    + "(VD) de cada maleta dentro da nota. É a mesma consulta do relatório.")
    @GetMapping
    public LeituraDoFechamento ler(@PathVariable UUID id) {
        return fechamento.ler(id);
    }

    @Operation(operationId = "concluirFechamentoDaMaleta", summary = "Concluir o fechamento da maleta",
            description = "Devolve ao estoque as peças bipadas (até o que a maleta devia ter), grava cada peça que falta como perda com "
                    + "a decisão da dona (COBRAR_DO_REPRESENTANTE, que cria a pendência da maleta, ou PERDA_DA_LOJA, com motivo) e passa "
                    + "a maleta a FECHADA. Falta sem decisão: 409 FALTA_SEM_DECISAO, nada gravado. A sobra não volta ao estoque.")
    @PostMapping("/concluir")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void concluir(@PathVariable UUID id, @RequestBody(required = false) PedidoDeConclusao pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        fechamento.concluir(id, pedido == null || pedido.decisoes() == null ? List.of() : pedido.decisoes(), quem.id());
    }

    @Operation(operationId = "registrarVoltasDaMaletaFechada", summary = "Ler as peças que voltaram depois do fechamento",
            description = "Só na maleta FECHADA (continua fechada). Cada leitura devolve a peça ao estoque, no máximo o que as clientes já "
                    + "devolveram à maleta e ainda não voltou à loja (409 PECA_AINDA_COM_A_CLIENTE). Reenviar o mesmo id não duplica.")
    @PostMapping("/voltas")
    public RespostaDasLeituras voltas(@PathVariable UUID id, @RequestBody PedidoDeLeituras pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        return new RespostaDasLeituras(fechamento.registrarVoltas(id, pedido == null ? null : pedido.leituras(), quem.id()));
    }
}
