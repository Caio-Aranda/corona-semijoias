package br.com.coronasemijoias.api.contasreceber;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.contasreceber.ConsultasDeRecebimentosDoVendedor.RecebimentoDaDona;
import br.com.coronasemijoias.api.contasreceber.ServicoDeRecebimentosDoVendedor.PedidoDeEstorno;
import br.com.coronasemijoias.api.contasreceber.ServicoDeRecebimentosDoVendedor.PedidoDeRecebimento;
import br.com.coronasemijoias.api.contasreceber.ServicoDeRecebimentosDoVendedor.Resultado;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import io.swagger.v3.oas.annotations.Operation;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
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
 * Recebimentos do vendedor, lado da dona (tarefa 7.6; ADR-0012, itens 7, 8, 9 e 16). Só o perfil ADMIN: tudo que é dinheiro
 * é da dona. Os ids dos comandos vêm do aplicativo: 201 na primeira gravação, 200 no reenvio igual, 409 com outros dados.
 */
@RestController
@RequestMapping("/api/dona/recebimentos-vendedor")
@ExigePerfil("ADMIN")
public class RecebimentosDoVendedorDaDonaController {

    private final ServicoDeRecebimentosDoVendedor servico;
    private final ConsultasDeRecebimentosDoVendedor consultas;

    RecebimentosDoVendedorDaDonaController(ServicoDeRecebimentosDoVendedor servico, ConsultasDeRecebimentosDoVendedor consultas) {
        this.servico = servico;
        this.consultas = consultas;
    }

    @Operation(operationId = "listarRecebimentosDoVendedor", summary = "Recebimentos dos vendedores",
            description = "O que os vendedores pagaram à loja, do mais novo para o mais antigo, com as contas que cada um baixou. "
                    + "Filtros opcionais: vendedor e período (de e até, dias de Brasília). Só valores em Real.")
    @GetMapping
    public List<RecebimentoDaDona> listar(@RequestParam(required = false) UUID vendedorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate) {
        return consultas.daDona(vendedorId, de, ate);
    }

    @Operation(operationId = "receberDoVendedor", summary = "Registrar um recebimento do vendedor",
            description = "O vendedor paga à loja, fora do acerto, em dinheiro, Transferência (PIX) ou boleto pago. Com a conta "
                    + "indicada, baixa só essa conta; sem conta, o servidor distribui pelas contas em aberto, da que vence primeiro "
                    + "para a que vence depois. Não aceita mais do que o vendedor deve. O boleto pago é o valor inteiro da conta do "
                    + "boleto. Entra no caixa na mesma hora. Mesmo id e mesmo conteúdo devolve 200; mesmo id com dados diferentes, 409.")
    @PostMapping
    public ResponseEntity<RecebimentoDaDona> receber(@RequestBody PedidoDeRecebimento pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        Resultado r = servico.receberAvulso(quem.id(), pedido);
        return ResponseEntity.status(r.jaExistia() ? HttpStatus.OK : HttpStatus.CREATED).body(consultas.verDaDona(r.id()));
    }

    @Operation(operationId = "estornarRecebimentoDoVendedorPelaDona", summary = "Estornar um recebimento do vendedor",
            description = "Desfaz o recebimento, sem limite de dia, com motivo. O dinheiro sai do caixa e as contas que ele baixou voltam "
                    + "a ficar em aberto; se ele pagou um acerto já fechado, o valor vira uma conta nova do vendedor (vencimento hoje ou "
                    + "a data informada). Mesmo id de estorno devolve 200.")
    @PostMapping("/{recebimentoId}/estorno")
    public ResponseEntity<RecebimentoDaDona> estornar(@PathVariable UUID recebimentoId, @RequestBody PedidoDeEstorno pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        if (pedido == null) {
            throw Validacoes.recusar("motivo", "Diga o motivo de estornar o recebimento.");
        }
        Resultado r = servico.estornar(quem.id(), recebimentoId, pedido);
        return ResponseEntity.status(r.jaExistia() ? HttpStatus.OK : HttpStatus.CREATED).body(consultas.verDaDona(r.id()));
    }
}
