package br.com.coronasemijoias.api.relatorios;

import br.com.coronasemijoias.api.relatorios.ConsultasDaCurvaAbc.Filtros;
import br.com.coronasemijoias.api.relatorios.ConsultasDaCurvaAbc.LinhaDaCurva;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import io.swagger.v3.oas.annotations.Operation;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Curva ABC da dona (tarefa 8.5, §12, grupo Estoque). Só ADMIN. Sai do MESMO vendido líquido dos relatórios de vendas.
 * Sem custo.
 */
@RestController
@RequestMapping("/api/dona/relatorios/estoque")
@ExigePerfil("ADMIN")
public class CurvaAbcController {

    private final ConsultasDaCurvaAbc consultas;

    public CurvaAbcController(ConsultasDaCurvaAbc consultas) {
        this.consultas = consultas;
    }

    @Operation(operationId = "relatorioDeCurvaAbc", summary = "Curva ABC das peças",
            description = "Por peça, pelo vendido líquido do período (venda menos devolução e troca), da maior para a menor; "
                    + "empate pelo código. Classe A até 80% do acumulado, B até 95%, C o resto; a peça que cruza a fronteira fica "
                    + "na classe de cima. Peça sem venda no período fica fora. Datas de Brasília (de e ate inclusive, sobre a data "
                    + "da venda). Filtros: busca, vendedorId, pracaId, clienteId, produtoId, categoriaId, de e ate. recorte = a "
                    + "chave de um total do rodapé (liquido, classe-a, classe-b ou classe-c). Cada valor vem em Real, Dólar e Guarani pela cotação do "
                    + "dia da venda. A página começa em 0 (até 100 por página).")
    @GetMapping("/curva-abc")
    public RelatorioDeCadastro<LinhaDaCurva> curva(
            @RequestParam(required = false) String busca, @RequestParam(required = false) String vendedorId,
            @RequestParam(required = false) String pracaId, @RequestParam(required = false) String clienteId,
            @RequestParam(required = false) String produtoId, @RequestParam(required = false) String categoriaId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.curva(new Filtros(busca, vendedorId, pracaId, clienteId, produtoId, categoriaId, de, ate, recorte,
                pagina, tamanho));
    }
}
