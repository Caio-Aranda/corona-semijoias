package br.com.coronasemijoias.api.caixa;

import br.com.coronasemijoias.api.caixa.ConsultasDoCaixa.Extrato;
import br.com.coronasemijoias.api.caixa.ConsultasDoCaixa.Lancamento;
import br.com.coronasemijoias.api.caixa.ConsultasDoCaixa.Saldo;
import br.com.coronasemijoias.api.caixa.ServicoDeLancamentosManuais.PedidoDeEstorno;
import br.com.coronasemijoias.api.caixa.ServicoDeLancamentosManuais.PedidoDeLancamento;
import br.com.coronasemijoias.api.caixa.ServicoDeLancamentosManuais.Resultado;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import io.swagger.v3.oas.annotations.Operation;
import java.time.LocalDate;
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
 * O caixa, lado da dona (tarefas 7.6 e 7.7): extrato, saldo e lançamentos manuais. Só o perfil ADMIN; o vendedor nunca vê o caixa.
 * Os ids dos comandos vêm do aplicativo: 201 na primeira gravação, 200 no reenvio igual, 409 com outros dados.
 */
@RestController
@RequestMapping("/api/dona/caixa")
@ExigePerfil("ADMIN")
public class CaixaDaDonaController {

    private final ConsultasDoCaixa consultas;
    private final ServicoDeLancamentosManuais lancamentos;

    CaixaDaDonaController(ConsultasDoCaixa consultas, ServicoDeLancamentosManuais lancamentos) {
        this.consultas = consultas;
        this.lancamentos = lancamentos;
    }

    @Operation(operationId = "verExtratoDoCaixa", summary = "Extrato do caixa",
            description = "Os lançamentos do caixa (recebimentos dos vendedores, pagamentos aos vendedores e a fornecedores e os "
                    + "lançamentos manuais), do mais antigo para o mais novo, com o saldo depois de cada um. Período opcional (de e até, "
                    + "dias de Brasília, inclusive): o saldo anterior é a soma de tudo antes do primeiro dia. Só valores em Real.")
    @GetMapping("/lancamentos")
    public Extrato extrato(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate) {
        return consultas.extrato(de, ate);
    }

    @Operation(operationId = "verSaldoDoCaixa", summary = "Saldo do caixa",
            description = "A soma de todos os lançamentos, em Real, e se está negativo. Saldo negativo só avisa: nada é bloqueado.")
    @GetMapping("/saldo")
    public Saldo saldo() {
        return consultas.saldo();
    }

    @Operation(operationId = "lancarNoCaixa", summary = "Fazer um lançamento manual no caixa",
            description = "Entrada ou saída avulsa, com categoria do mesmo tipo, forma (dinheiro, Transferência (PIX) ou boleto), data "
                    + "(nunca no futuro nem numa semana já fechada) e descrição. O valor vai sempre positivo, em Real: o sinal é do "
                    + "servidor. Mesmo id e mesmo conteúdo devolve 200; mesmo id com dados diferentes, 409.")
    @PostMapping("/lancamentos")
    public ResponseEntity<Lancamento> lancar(@RequestBody PedidoDeLancamento pedido, @AuthenticationPrincipal UsuarioAutenticado quem) {
        Resultado r = lancamentos.lancar(quem.id(), pedido);
        return ResponseEntity.status(r.jaExistia() ? HttpStatus.OK : HttpStatus.CREATED).body(consultas.ver(r.id()));
    }

    @Operation(operationId = "estornarLancamentoDoCaixa", summary = "Estornar um lançamento manual",
            description = "Desfaz o lançamento manual com motivo, na data de hoje. Lançamento que veio de um pagamento ou recebimento "
                    + "não se estorna aqui (desfaça o pagamento). Mesmo id de estorno devolve 200; outro id para o mesmo lançamento, 409.")
    @PostMapping("/lancamentos/{id}/estorno")
    public ResponseEntity<Lancamento> estornar(@PathVariable UUID id, @RequestBody PedidoDeEstorno pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        Resultado r = lancamentos.estornar(quem.id(), id, pedido);
        return ResponseEntity.status(r.jaExistia() ? HttpStatus.OK : HttpStatus.CREATED).body(consultas.ver(r.id()));
    }
}
