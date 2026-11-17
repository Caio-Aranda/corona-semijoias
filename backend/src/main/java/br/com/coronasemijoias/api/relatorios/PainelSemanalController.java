package br.com.coronasemijoias.api.relatorios;

import br.com.coronasemijoias.api.relatorios.ConsultasDoPainelSemanal.Filtros;
import br.com.coronasemijoias.api.relatorios.ConsultasDoPainelSemanal.PainelDaSemana;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** O painel semanal da dona (tarefa 8.7). Só ADMIN, só leitura. Nada vai ao Corona Vendas e nenhum custo aparece. */
@RestController
@RequestMapping("/api/dona/painel-semanal")
@ExigePerfil("ADMIN")
public class PainelSemanalController {

    private final ConsultasDoPainelSemanal consultas;

    public PainelSemanalController(ConsultasDoPainelSemanal consultas) {
        this.consultas = consultas;
    }

    @Operation(operationId = "painelSemanal", summary = "Painel da semana",
            description = "Pedidos: uma linha por vendedor na semana (acerto fechado, acerto em andamento ou maletas em aberto). "
                    + "A linha sem acerto fechado é prévia (a mesma conta do fechamento, nada é gravado) e a prévia inclui o que o "
                    + "vendedor ainda não acertou de semanas anteriores (previaIncluiSemanasAnteriores). Totais: novas maletas, "
                    + "reposições, acerto, prévia, vendas, trocas e créditos, total e total recebido no acerto; cada bloco traz a "
                    + "rota e os filtros do relatório de onde saiu. Filtros: semana (padrão: a de hoje, em Brasília), vendedor, "
                    + "praca e situacao (EM_ABERTO, EM_ACERTO ou ACERTADO; só muda as linhas e o bloco da prévia). Cada valor vem "
                    + "em Real, Dólar e Guarani pela cotação do dia de cada lançamento.")
    @GetMapping
    public PainelDaSemana painel(@RequestParam(required = false) String semana, @RequestParam(required = false) String vendedor,
            @RequestParam(required = false) String praca, @RequestParam(required = false) String situacao) {
        return consultas.painel(new Filtros(semana, vendedor, praca, situacao));
    }
}
