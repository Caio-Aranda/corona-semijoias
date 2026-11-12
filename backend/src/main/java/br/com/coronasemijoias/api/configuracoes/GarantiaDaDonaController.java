package br.com.coronasemijoias.api.configuracoes;

import br.com.coronasemijoias.api.cadastros.Mensagens;
import br.com.coronasemijoias.api.configuracoes.ServicoDeGarantia.Garantia;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A garantia e os limites de devolução, na mão da dona (tarefa 6.2; ADR-0014, itens 4 e 10). A tela é da tarefa 6.9. */
@RestController
@RequestMapping("/api/dona/configuracoes/garantia")
@ExigePerfil("ADMIN")
public class GarantiaDaDonaController {

    private static final String PRAZO = "Use um número de dias entre 1 e 365.";
    private static final String PORCENTAGEM = "Use uma porcentagem entre 1 e 100.";
    private static final String VALOR = "Informe um valor maior que zero, ou deixe vazio para não ter limite.";

    /** Os cinco valores de uma vez. Valor em dinheiro vazio = sem limite. */
    public record PedidoDeGarantia(
            @NotNull(message = Mensagens.OBRIGATORIO) @Min(value = 1, message = PRAZO) @Max(value = 365, message = PRAZO)
            Integer prazoArrependimentoDias,
            @NotNull(message = Mensagens.OBRIGATORIO) @Min(value = 1, message = PRAZO) @Max(value = 365, message = PRAZO)
            Integer prazoDefeitoDias,
            @DecimalMin(value = "0.01", message = VALOR) BigDecimal limiteReembolsoSemanal,
            @DecimalMin(value = "0.01", message = VALOR) BigDecimal valorAprovacaoReembolso,
            @NotNull(message = Mensagens.OBRIGATORIO) @Min(value = 1, message = PORCENTAGEM)
            @Max(value = 100, message = PORCENTAGEM) Integer percentualAlertaDevolucao) {
    }

    private final ServicoDeGarantia garantia;

    GarantiaDaDonaController(ServicoDeGarantia garantia) {
        this.garantia = garantia;
    }

    @Operation(operationId = "lerGarantia", summary = "Ver a garantia e os limites de devolução",
            description = "Dias para desistir e para devolver por defeito, limite de reembolso por semana, valor a partir do "
                    + "qual a dona aprova antes e porcentagem que avisa sobre um vendedor. Dinheiro vazio = sem limite.")
    @GetMapping
    public Garantia ler() {
        return garantia.ler();
    }

    @Operation(operationId = "alterarGarantia", summary = "Mudar a garantia e os limites de devolução",
            description = "Grava os cinco valores. Cada valor que mudou fica na auditoria com o valor antigo. Fora da faixa, 400.")
    @PutMapping
    public Garantia alterar(@Valid @RequestBody PedidoDeGarantia pedido, @AuthenticationPrincipal UsuarioAutenticado quem) {
        return garantia.alterar(new Garantia(pedido.prazoArrependimentoDias(), pedido.prazoDefeitoDias(),
                pedido.limiteReembolsoSemanal(), pedido.valorAprovacaoReembolso(), pedido.percentualAlertaDevolucao()),
                quem.id());
    }
}
