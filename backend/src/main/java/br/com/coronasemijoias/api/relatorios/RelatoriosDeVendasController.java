package br.com.coronasemijoias.api.relatorios;

import br.com.coronasemijoias.api.relatorios.ConsultasDeVendasDaDona.Filtros;
import br.com.coronasemijoias.api.relatorios.ConsultasDeVendasDaDona.LinhaDeVenda;
import br.com.coronasemijoias.api.relatorios.ConsultasDeVendasDaDona.LinhaDeVendido;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import io.swagger.v3.oas.annotations.Operation;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Relatórios de vendas da dona (tarefa 8.5, §12). Só ADMIN. Mesma forma dos demais relatórios: linhas paginadas, rodapé e
 * detalhamento de cada total (parâmetro {@code recorte}). Dinheiro em Real; cada valor vem também em Dólar e Guarani pela
 * cotação do dia da venda, linha a linha, e o rodapé soma as linhas já convertidas. Todos saem do MESMO vendido líquido da
 * curva ABC (venda menos devolução e troca; a venda estornada fica fora dos totais). Nenhum custo aparece aqui. A
 * exportação (PDF, Excel, CSV) é feita pelo Corona Gestão com o relatório inteiro, pedido página a página com os MESMOS filtros.
 */
@RestController
@RequestMapping("/api/dona/relatorios/vendas")
@ExigePerfil("ADMIN")
public class RelatoriosDeVendasController {

    private static final String PADRAO = " Datas de Brasília (de e ate inclusive, sobre a data da venda). A página começa em 0 "
            + "(até 100 por página). recorte = a chave de um total do rodapé: devolve só as linhas que formam esse total. "
            + "Filtros: busca (vendedor, cliente, peça, referência ou código), vendedorId, pracaId, clienteId, produtoId, "
            + "categoriaId, de e ate. Vendido líquido = vendido - devolvido (devoluções e trocas não canceladas). Cada valor vem "
            + "em Real, Dólar e Guarani pela cotação do dia da venda; a venda estornada fica fora dos totais.";

    private final ConsultasDeVendasDaDona consultas;

    public RelatoriosDeVendasController(ConsultasDeVendasDaDona consultas) {
        this.consultas = consultas;
    }

    @Operation(operationId = "relatorioDeVendasPorVendedor", summary = "Vendas por vendedor",
            description = "Uma linha por vendedor: vendas, peças, vendido, devolvido, vendido líquido e ticket médio (vendido líquido dividido pelo número de vendas)." + PADRAO)
    @GetMapping("/por-vendedor")
    public RelatorioDeCadastro<LinhaDeVendido> porVendedor(
            @RequestParam(required = false) String busca, @RequestParam(required = false) String vendedorId,
            @RequestParam(required = false) String pracaId, @RequestParam(required = false) String clienteId,
            @RequestParam(required = false) String produtoId, @RequestParam(required = false) String categoriaId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.porVendedor(new Filtros(busca, vendedorId, pracaId, clienteId, produtoId, categoriaId, null, de, ate,
                recorte, pagina, tamanho));
    }

    @Operation(operationId = "relatorioDeVendasPorPraca", summary = "Vendas por praça",
            description = "Uma linha por praça do vendedor (vendedor sem praça aparece como Sem praça)." + PADRAO)
    @GetMapping("/por-praca")
    public RelatorioDeCadastro<LinhaDeVendido> porPraca(
            @RequestParam(required = false) String busca, @RequestParam(required = false) String vendedorId,
            @RequestParam(required = false) String pracaId, @RequestParam(required = false) String clienteId,
            @RequestParam(required = false) String produtoId, @RequestParam(required = false) String categoriaId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.porPraca(new Filtros(busca, vendedorId, pracaId, clienteId, produtoId, categoriaId, null, de, ate,
                recorte, pagina, tamanho));
    }

    @Operation(operationId = "relatorioDeVendasPorCliente", summary = "Vendas por cliente",
            description = "Uma linha por cliente, com tudo o que ele comprou no período." + PADRAO)
    @GetMapping("/por-cliente")
    public RelatorioDeCadastro<LinhaDeVendido> porCliente(
            @RequestParam(required = false) String busca, @RequestParam(required = false) String vendedorId,
            @RequestParam(required = false) String pracaId, @RequestParam(required = false) String clienteId,
            @RequestParam(required = false) String produtoId, @RequestParam(required = false) String categoriaId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.porCliente(new Filtros(busca, vendedorId, pracaId, clienteId, produtoId, categoriaId, null, de, ate,
                recorte, pagina, tamanho));
    }

    @Operation(operationId = "relatorioDeVendasPorProduto", summary = "Vendas por produto",
            description = "Uma linha por peça, com código, referência e foto. É o mesmo vendido líquido da curva ABC." + PADRAO)
    @GetMapping("/por-produto")
    public RelatorioDeCadastro<LinhaDeVendido> porProduto(
            @RequestParam(required = false) String busca, @RequestParam(required = false) String vendedorId,
            @RequestParam(required = false) String pracaId, @RequestParam(required = false) String clienteId,
            @RequestParam(required = false) String produtoId, @RequestParam(required = false) String categoriaId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.porProduto(new Filtros(busca, vendedorId, pracaId, clienteId, produtoId, categoriaId, null, de, ate,
                recorte, pagina, tamanho));
    }

    @Operation(operationId = "relatorioDeVendasPorCategoria", summary = "Vendas por categoria",
            description = "Uma linha por categoria de peça. Uma venda com peças de duas categorias conta nas duas." + PADRAO)
    @GetMapping("/por-categoria")
    public RelatorioDeCadastro<LinhaDeVendido> porCategoria(
            @RequestParam(required = false) String busca, @RequestParam(required = false) String vendedorId,
            @RequestParam(required = false) String pracaId, @RequestParam(required = false) String clienteId,
            @RequestParam(required = false) String produtoId, @RequestParam(required = false) String categoriaId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.porCategoria(new Filtros(busca, vendedorId, pracaId, clienteId, produtoId, categoriaId, null, de, ate,
                recorte, pagina, tamanho));
    }

    @Operation(operationId = "relatorioDeVendasPorPeriodo", summary = "Vendas por dia",
            description = "Uma linha por dia (calendário de Brasília), pela data da venda. A chave é a data AAAA-MM-DD." + PADRAO)
    @GetMapping("/por-periodo")
    public RelatorioDeCadastro<LinhaDeVendido> porPeriodo(
            @RequestParam(required = false) String busca, @RequestParam(required = false) String vendedorId,
            @RequestParam(required = false) String pracaId, @RequestParam(required = false) String clienteId,
            @RequestParam(required = false) String produtoId, @RequestParam(required = false) String categoriaId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.porPeriodo(new Filtros(busca, vendedorId, pracaId, clienteId, produtoId, categoriaId, null, de, ate,
                recorte, pagina, tamanho));
    }

    @Operation(operationId = "relatorioDeRankingDeVendedores", summary = "Ranking de vendedores",
            description = "Os vendedores do que mais vendeu para o que menos vendeu (pelo vendido líquido), com a posição." + PADRAO)
    @GetMapping("/ranking-de-vendedores")
    public RelatorioDeCadastro<LinhaDeVendido> rankingDeVendedores(
            @RequestParam(required = false) String busca, @RequestParam(required = false) String vendedorId,
            @RequestParam(required = false) String pracaId, @RequestParam(required = false) String clienteId,
            @RequestParam(required = false) String produtoId, @RequestParam(required = false) String categoriaId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.rankingDeVendedores(new Filtros(busca, vendedorId, pracaId, clienteId, produtoId, categoriaId, null, de, ate,
                recorte, pagina, tamanho));
    }

    @Operation(operationId = "relatorioDeRankingDePecas", summary = "Ranking de peças",
            description = "As peças da que mais vendeu para a que menos vendeu (pelo vendido líquido), com a posição e a foto." + PADRAO)
    @GetMapping("/ranking-de-pecas")
    public RelatorioDeCadastro<LinhaDeVendido> rankingDePecas(
            @RequestParam(required = false) String busca, @RequestParam(required = false) String vendedorId,
            @RequestParam(required = false) String pracaId, @RequestParam(required = false) String clienteId,
            @RequestParam(required = false) String produtoId, @RequestParam(required = false) String categoriaId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.rankingDePecas(new Filtros(busca, vendedorId, pracaId, clienteId, produtoId, categoriaId, null, de, ate,
                recorte, pagina, tamanho));
    }

    @Operation(operationId = "relatorioDeTicketMedio", summary = "Ticket médio por vendedor",
            description = "Os vendedores do maior para o menor ticket médio (vendido líquido dividido pelo número de vendas)." + PADRAO)
    @GetMapping("/ticket-medio")
    public RelatorioDeCadastro<LinhaDeVendido> ticketMedio(
            @RequestParam(required = false) String busca, @RequestParam(required = false) String vendedorId,
            @RequestParam(required = false) String pracaId, @RequestParam(required = false) String clienteId,
            @RequestParam(required = false) String produtoId, @RequestParam(required = false) String categoriaId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.ticketMedio(new Filtros(busca, vendedorId, pracaId, clienteId, produtoId, categoriaId, null, de, ate,
                recorte, pagina, tamanho));
    }

    @Operation(operationId = "relatorioDeVendas", summary = "Vendas, uma linha por venda",
            description = "Cada venda com peças, vendido, devolvido, vendido líquido, cotação e, se foi estornada, quem estornou e "
                    + "o motivo. situacao: REGISTRADAS (padrão), CANCELADAS ou TODAS (as canceladas aparecem marcadas e ficam FORA "
                    + "do total)." + PADRAO)
    @GetMapping("/vendas")
    public RelatorioDeCadastro<LinhaDeVenda> vendas(
            @RequestParam(required = false) String busca, @RequestParam(required = false) String vendedorId,
            @RequestParam(required = false) String pracaId, @RequestParam(required = false) String clienteId,
            @RequestParam(required = false) String produtoId, @RequestParam(required = false) String categoriaId,
            @RequestParam(required = false) String situacao,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.vendas(new Filtros(busca, vendedorId, pracaId, clienteId, produtoId, categoriaId, situacao, de, ate,
                recorte, pagina, tamanho));
    }

    @Operation(operationId = "relatorioDeVendasCanceladas", summary = "Vendas canceladas",
            description = "As vendas estornadas, com quem estornou, quando e por quê. Ficam fora dos totais dos outros relatórios."
                    + PADRAO)
    @GetMapping("/canceladas")
    public RelatorioDeCadastro<LinhaDeVenda> canceladas(
            @RequestParam(required = false) String busca, @RequestParam(required = false) String vendedorId,
            @RequestParam(required = false) String pracaId, @RequestParam(required = false) String clienteId,
            @RequestParam(required = false) String produtoId, @RequestParam(required = false) String categoriaId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.canceladas(new Filtros(busca, vendedorId, pracaId, clienteId, produtoId, categoriaId, null, de, ate,
                recorte, pagina, tamanho));
    }
}
