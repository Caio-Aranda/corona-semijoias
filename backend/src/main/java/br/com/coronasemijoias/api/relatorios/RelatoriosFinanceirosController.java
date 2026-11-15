package br.com.coronasemijoias.api.relatorios;

import br.com.coronasemijoias.api.relatorios.ConsultasFinanceiras.Filtros;
import br.com.coronasemijoias.api.relatorios.ConsultasFinanceiras.LinhaDeAcerto;
import br.com.coronasemijoias.api.relatorios.ConsultasFinanceiras.LinhaDeCaixa;
import br.com.coronasemijoias.api.relatorios.ConsultasFinanceiras.LinhaDeComissao;
import br.com.coronasemijoias.api.relatorios.ConsultasFinanceiras.LinhaDeConta;
import br.com.coronasemijoias.api.relatorios.ConsultasFinanceiras.LinhaDeEsperadoEContado;
import br.com.coronasemijoias.api.relatorios.ConsultasFinanceiras.LinhaDePendencia;
import br.com.coronasemijoias.api.relatorios.ConsultasFinanceiras.LinhaDeRecebimento;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import io.swagger.v3.oas.annotations.Operation;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Relatórios financeiros da dona (tarefa 7.12, §12). Só ADMIN e só leitura. Mesma forma dos demais relatórios: linhas
 * paginadas, rodapé e detalhamento de cada total (parâmetro {@code recorte}). Tudo em Real; Dólar e Guarani só para mostrar,
 * pela cotação do dia de cada lançamento (a gravada no fechamento, no que nasce do acerto). A exportação (PDF, Excel, CSV)
 * é feita pelo Corona Gestão com o relatório inteiro, pedido página a página com os MESMOS filtros da tela.
 */
@RestController
@RequestMapping("/api/dona/relatorios/financeiro")
@ExigePerfil("ADMIN")
public class RelatoriosFinanceirosController {

    private static final String PADRAO = " Datas em UTC (de e ate inclusive). A página começa em 0 (até 100 por página). "
            + "recorte = a chave de um total do rodapé: devolve só as linhas que formam esse total. Cada valor vem em Real, "
            + "Dólar e Guarani pela cotação do dia do lançamento (dataDaCotacao).";

    private final ConsultasFinanceiras consultas;

    public RelatoriosFinanceirosController(ConsultasFinanceiras consultas) {
        this.consultas = consultas;
    }

    @Operation(operationId = "relatorioDeAcertos", summary = "Acerto por vendedor e semana",
            description = "Um acerto fechado por linha: vendido, descontos, devolvido, comissão, faltas cobradas, valor do "
                    + "acerto, recebido e o que ficou devendo. Filtros: vendedorId, semanaId, busca, de e ate (do "
                    + "fechamento)." + PADRAO)
    @GetMapping("/acertos")
    public RelatorioDeCadastro<LinhaDeAcerto> acertos(@RequestParam(required = false) String busca,
            @RequestParam(required = false) String vendedorId, @RequestParam(required = false) String semanaId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.acertos(new Filtros(busca, vendedorId, semanaId, null, null, null, null, null, null, null, null,
                null, null, de, ate, recorte, pagina, tamanho));
    }

    @Operation(operationId = "relatorioDePendenciasDoAcerto", summary = "Faltas e pendências",
            description = "Uma pendência por linha (falta, defeito, devolução ou crédito não aceitos), com a situação. "
                    + "Filtros: vendedorId, semanaId, produtoId, tipo, situacao, busca, de e ate." + PADRAO)
    @GetMapping("/pendencias")
    public RelatorioDeCadastro<LinhaDePendencia> pendencias(@RequestParam(required = false) String busca,
            @RequestParam(required = false) String vendedorId, @RequestParam(required = false) String semanaId,
            @RequestParam(required = false) String produtoId, @RequestParam(required = false) String tipo,
            @RequestParam(required = false) String situacao,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.pendencias(new Filtros(busca, vendedorId, semanaId, null, null, null, produtoId, tipo, situacao,
                null, null, null, null, de, ate, recorte, pagina, tamanho));
    }

    @Operation(operationId = "relatorioDeEsperadoEContado", summary = "Esperado x contado",
            description = "Uma peça de cada maleta de um acerto fechado: o que era esperado, o que foi contado (boa e com "
                    + "defeito) e o que faltou, com o valor da falta. Filtros: vendedorId, semanaId, produtoId, busca, de e "
                    + "ate." + PADRAO)
    @GetMapping("/esperado-contado")
    public RelatorioDeCadastro<LinhaDeEsperadoEContado> esperadoEContado(@RequestParam(required = false) String busca,
            @RequestParam(required = false) String vendedorId, @RequestParam(required = false) String semanaId,
            @RequestParam(required = false) String produtoId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.esperadoEContado(new Filtros(busca, vendedorId, semanaId, null, null, null, produtoId, null, null,
                null, null, null, null, de, ate, recorte, pagina, tamanho));
    }

    @Operation(operationId = "relatorioDeContasAReceber", summary = "Contas a receber",
            description = "Uma parcela por linha, do cliente (deve ao vendedor) e do vendedor (deve à loja): valor, recebido, "
                    + "cancelado e o que falta. situacao: ABERTA, PARCIAL, PAGA, CANCELADA ou VENCIDA (calculada pela data de "
                    + "hoje em Brasília). Filtros: vendedorId, clienteId, devedor (CLIENTE ou VENDEDOR), situacao, busca, "
                    + "de e ate (do vencimento)." + PADRAO)
    @GetMapping("/contas-a-receber")
    public RelatorioDeCadastro<LinhaDeConta> contasAReceber(@RequestParam(required = false) String busca,
            @RequestParam(required = false) String vendedorId, @RequestParam(required = false) String clienteId,
            @RequestParam(required = false) String devedor, @RequestParam(required = false) String situacao,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.contasAReceber(new Filtros(busca, vendedorId, null, clienteId, null, null, null, null, situacao,
                devedor, null, null, null, de, ate, recorte, pagina, tamanho));
    }

    @Operation(operationId = "relatorioDeContasAPagar", summary = "Contas a pagar",
            description = "Uma parcela por linha, de cada compra: valor, pago, cancelado e o que falta. Filtros: "
                    + "fornecedorId, compraId, situacao, busca, de e ate (do vencimento)." + PADRAO)
    @GetMapping("/contas-a-pagar")
    public RelatorioDeCadastro<LinhaDeConta> contasAPagar(@RequestParam(required = false) String busca,
            @RequestParam(required = false) String fornecedorId, @RequestParam(required = false) String compraId,
            @RequestParam(required = false) String situacao,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.contasAPagar(new Filtros(busca, null, null, null, fornecedorId, compraId, null, null, situacao,
                null, null, null, null, de, ate, recorte, pagina, tamanho));
    }

    @Operation(operationId = "relatorioDeInadimplencia", summary = "Quem está devendo (inadimplência)",
            description = "Só parcelas vencidas e ainda em aberto, do cliente e do vendedor, com os dias de atraso e o "
                    + "rodapé por faixa de atraso. Filtros: vendedorId, clienteId, devedor, busca, de e ate (do "
                    + "vencimento)." + PADRAO)
    @GetMapping("/inadimplencia")
    public RelatorioDeCadastro<LinhaDeConta> inadimplencia(@RequestParam(required = false) String busca,
            @RequestParam(required = false) String vendedorId, @RequestParam(required = false) String clienteId,
            @RequestParam(required = false) String devedor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.inadimplencia(new Filtros(busca, vendedorId, null, clienteId, null, null, null, null, null, devedor,
                null, null, null, de, ate, recorte, pagina, tamanho));
    }

    @Operation(operationId = "relatorioDeFluxoDeCaixa", summary = "Fluxo de caixa",
            description = "Os lançamentos do caixa (entradas positivas, saídas negativas; o estorno vem marcado). Filtros: "
                    + "vendedorId (os que vieram dele), tipo, forma, origem, busca, de e ate (do dia do lançamento)." + PADRAO)
    @GetMapping("/fluxo-de-caixa")
    public RelatorioDeCadastro<LinhaDeCaixa> fluxoDeCaixa(@RequestParam(required = false) String busca,
            @RequestParam(required = false) String vendedorId, @RequestParam(required = false) String tipo,
            @RequestParam(required = false) String forma, @RequestParam(required = false) String origem,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.fluxoDeCaixa(new Filtros(busca, vendedorId, null, null, null, null, null, tipo, null, null, forma,
                origem, null, de, ate, recorte, pagina, tamanho));
    }

    @Operation(operationId = "relatorioDeRecebimentosPorForma", summary = "Recebimentos por forma de pagamento",
            description = "O que os vendedores repassaram à loja: dinheiro, transferência e boleto (o estorno vem marcado, "
                    + "negativo). Filtros: vendedorId, forma, momento (ACERTO ou AVULSO), busca, de e ate." + PADRAO)
    @GetMapping("/recebimentos-por-forma")
    public RelatorioDeCadastro<LinhaDeRecebimento> recebimentosPorForma(@RequestParam(required = false) String busca,
            @RequestParam(required = false) String vendedorId, @RequestParam(required = false) String forma,
            @RequestParam(required = false) String momento,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.recebimentosPorForma(new Filtros(busca, vendedorId, null, null, null, null, null, null, null, null,
                forma, null, momento, de, ate, recorte, pagina, tamanho));
    }

    @Operation(operationId = "relatorioDeComissoes", summary = "Comissões por vendedor e semana",
            description = "A comissão de cada acerto fechado (paga por abatimento no acerto), com o vendido em Guarani, a "
                    + "meta e o percentual que ela decidiu. Filtros: vendedorId, semanaId, busca, de e ate." + PADRAO)
    @GetMapping("/comissoes")
    public RelatorioDeCadastro<LinhaDeComissao> comissoes(@RequestParam(required = false) String busca,
            @RequestParam(required = false) String vendedorId, @RequestParam(required = false) String semanaId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.comissoes(new Filtros(busca, vendedorId, semanaId, null, null, null, null, null, null, null, null,
                null, null, de, ate, recorte, pagina, tamanho));
    }
}
