package br.com.coronasemijoias.api.conciliacoes;

import br.com.coronasemijoias.api.conciliacoes.ConciliacoesDaFase7.Resultado;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * As conferências do dinheiro da dona (tarefa 7.8, §13). Só ADMIN. Só leitura: a rota nunca corrige nada. O vendedor não tem rota
 * de conciliação (nada disto vai ao Corona Vendas).
 */
@RestController
@RequestMapping("/api/dona/conciliacoes")
@ExigePerfil("ADMIN")
public class ConciliacoesDaDonaController {

    private final ConciliacoesDaFase7 conciliacoes;

    public ConciliacoesDaDonaController(ConciliacoesDaFase7 conciliacoes) {
        this.conciliacoes = conciliacoes;
    }

    @Operation(operationId = "conferirODinheiroDaFase7", summary = "Conferências do dinheiro (vendas, acertos, comissão, caixa, contas, estoque)",
            description = "Confere o banco inteiro num retrato só: C1 vendas e o que falta receber, C2 acerto do vendedor, C3 comissão, "
                    + "C4 caixa, C5 contas a receber e a pagar, C7 estoque e maletas, C8 maletas acertadas e faltas, C9 cada venda, estorno "
                    + "e devolução em um acerto só, C10 compras e parcelas (a C6 está vaga). Cada conferência traz quantos registros olhou "
                    + "e as diferenças (o que não bate, com o esperado e o encontrado); tudoCerto = nenhuma diferença. Com chave (por "
                    + "exemplo C4) devolve só aquela conferência; chave desconhecida dá 400. Só leitura: nada é corrigido.")
    @GetMapping
    public Resultado conferir(@RequestParam(required = false) String chave) {
        if (chave == null || chave.isBlank()) {
            return conciliacoes.conferir();
        }
        return conciliacoes.conferir(chave);
    }
}
