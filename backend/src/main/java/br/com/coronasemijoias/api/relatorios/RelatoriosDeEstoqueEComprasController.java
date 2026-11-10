package br.com.coronasemijoias.api.relatorios;

import br.com.coronasemijoias.api.relatorios.ConsultasDeEstoqueECompras.Filtros;
import br.com.coronasemijoias.api.relatorios.ConsultasDeEstoqueECompras.LinhaAbaixoDoMinimo;
import br.com.coronasemijoias.api.relatorios.ConsultasDeEstoqueECompras.LinhaDeCompra;
import br.com.coronasemijoias.api.relatorios.ConsultasDeEstoqueECompras.LinhaDeConciliacao;
import br.com.coronasemijoias.api.relatorios.ConsultasDeEstoqueECompras.LinhaDeFornecedorNasCompras;
import br.com.coronasemijoias.api.relatorios.ConsultasDeEstoqueECompras.LinhaDeHistoricoDeCusto;
import br.com.coronasemijoias.api.relatorios.ConsultasDeEstoqueECompras.LinhaDeInventario;
import br.com.coronasemijoias.api.relatorios.ConsultasDeEstoqueECompras.LinhaDeItemDeCompra;
import br.com.coronasemijoias.api.relatorios.ConsultasDeEstoqueECompras.LinhaDeMovimento;
import br.com.coronasemijoias.api.relatorios.ConsultasDeEstoqueECompras.LinhaDePosicao;
import br.com.coronasemijoias.api.relatorios.ConsultasDeEstoqueECompras.LinhaDeProdutoParado;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import io.swagger.v3.oas.annotations.Operation;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Relatórios de estoque e de compras da dona (tarefa 2.5, §12). Só ADMIN: trazem custo e compras, que nenhum
 * vendedor pode ver. Mesma forma dos relatórios de cadastro: linhas paginadas, rodapé e detalhamento de cada
 * total (parâmetro {@code recorte}). Dinheiro em Real. A exportação (PDF, Excel, CSV) é feita pelo Corona
 * Gestão com o relatório inteiro, pedido página a página com os MESMOS filtros da tela.
 */
@RestController
@RequestMapping("/api/dona/relatorios")
@ExigePerfil("ADMIN")
public class RelatoriosDeEstoqueEComprasController {

    private static final String PADRAO = " Datas em UTC (de e ate inclusive). A página começa em 0 (até 100 por página). "
            + "recorte = a chave de um total do rodapé: devolve só as linhas que formam esse total. "
            + "Filtros de produto: busca (nome, referência ou código), categoriaId, fornecedorId, produtoId.";

    private final ConsultasDeEstoqueECompras consultas;

    public RelatoriosDeEstoqueEComprasController(ConsultasDeEstoqueECompras consultas) {
        this.consultas = consultas;
    }

    @Operation(summary = "Posição atual do estoque (com valor a custo)",
            description = "Saldo de cada produto e valor a custo (saldo x custo); total geral no rodapé. situacao do "
                    + "produto: ATIVOS (padrão), INATIVOS ou TODOS." + PADRAO)
    @GetMapping("/estoque/posicao-atual")
    public RelatorioDeCadastro<LinhaDePosicao> posicaoAtual(@RequestParam(required = false) String busca,
            @RequestParam(required = false) String categoriaId, @RequestParam(required = false) String fornecedorId,
            @RequestParam(required = false) String produtoId, @RequestParam(required = false) String situacao,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.posicaoAtual(new Filtros(busca, categoriaId, fornecedorId, produtoId, situacao, null, null,
                null, null, null, null, null, null, recorte, pagina, tamanho));
    }

    @Operation(summary = "Movimentações do estoque por período",
            description = "Cada entrada, saída, ajuste, perda e desfazer, com quem fez. Totais por tipo, entradas e saídas "
                    + "(em peças). Filtros: tipo (ENTRADA, SAIDA, AJUSTE, REMESSA, DEVOLUCAO ou AVARIA), origem (MANUAL, "
                    + "AJUSTE_MANUAL, COMPRA, INVENTARIO, MALETA ou ESTORNO), usuarioId, de, ate, situacao do produto "
                    + "(TODOS por padrão)." + PADRAO)
    @GetMapping("/estoque/movimentacoes")
    public RelatorioDeCadastro<LinhaDeMovimento> movimentacoes(@RequestParam(required = false) String busca,
            @RequestParam(required = false) String categoriaId, @RequestParam(required = false) String fornecedorId,
            @RequestParam(required = false) String produtoId, @RequestParam(required = false) String situacao,
            @RequestParam(required = false) String tipo, @RequestParam(required = false) String origem,
            @RequestParam(required = false) String usuarioId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.movimentacoes(new Filtros(busca, categoriaId, fornecedorId, produtoId, situacao, tipo, origem,
                usuarioId, de, ate, null, null, null, recorte, pagina, tamanho));
    }

    @Operation(summary = "Produtos no estoque mínimo ou abaixo",
            description = "Mesma regra do aviso: mínimo definido e saldo menor ou igual a ele. falta = peças para chegar ao "
                    + "mínimo. situacao do produto: ATIVOS (padrão), INATIVOS ou TODOS." + PADRAO)
    @GetMapping("/estoque/abaixo-do-minimo")
    public RelatorioDeCadastro<LinhaAbaixoDoMinimo> abaixoDoMinimo(@RequestParam(required = false) String busca,
            @RequestParam(required = false) String categoriaId, @RequestParam(required = false) String fornecedorId,
            @RequestParam(required = false) String produtoId, @RequestParam(required = false) String situacao,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.abaixoDoMinimo(new Filtros(busca, categoriaId, fornecedorId, produtoId, situacao, null, null,
                null, null, null, null, null, null, recorte, pagina, tamanho));
    }

    @Operation(summary = "Produtos parados",
            description = "Com saldo e sem saída (SAIDA ou REMESSA, não desfeita) há N dias (dias, de 1 a 3650; padrão 30). "
                    + "Produto cadastrado há menos de N dias não entra. situacao do produto: ATIVOS (padrão), INATIVOS ou "
                    + "TODOS." + PADRAO)
    @GetMapping("/estoque/parados")
    public RelatorioDeCadastro<LinhaDeProdutoParado> parados(@RequestParam(required = false) Integer dias,
            @RequestParam(required = false) String busca, @RequestParam(required = false) String categoriaId,
            @RequestParam(required = false) String fornecedorId, @RequestParam(required = false) String produtoId,
            @RequestParam(required = false) String situacao, @RequestParam(required = false) String recorte,
            @RequestParam(required = false) Integer pagina, @RequestParam(required = false) Integer tamanho) {
        return consultas.parados(new Filtros(busca, categoriaId, fornecedorId, produtoId, situacao, null, null, null, null,
                null, dias, null, null, recorte, pagina, tamanho));
    }

    @Operation(summary = "Inventário e divergências",
            description = "Por inventário e produto: esperado, contado, diferença e o ajuste que o fechamento gerou. "
                    + "Filtros: inventarioId, situacao do inventário (EM_ANDAMENTO, FECHADO, CANCELADO ou TODOS), "
                    + "divergencia (SIM: só quem tem diferença; NAO), de e ate (quando o inventário começou)." + PADRAO)
    @GetMapping("/estoque/inventarios")
    public RelatorioDeCadastro<LinhaDeInventario> inventarios(@RequestParam(required = false) String inventarioId,
            @RequestParam(required = false) String situacao, @RequestParam(required = false) String divergencia,
            @RequestParam(required = false) String busca, @RequestParam(required = false) String categoriaId,
            @RequestParam(required = false) String fornecedorId, @RequestParam(required = false) String produtoId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.inventarios(new Filtros(busca, categoriaId, fornecedorId, produtoId, situacao, null, null, null,
                de, ate, null, inventarioId, divergencia, recorte, pagina, tamanho));
    }

    @Operation(summary = "Conciliação do estoque",
            description = "Produtos em que o saldo guardado não é a soma dos movimentos. Deve vir vazio; se não vier, "
                    + "mostra o que diverge. Filtros: busca (nome), produtoId." + PADRAO)
    @GetMapping("/estoque/conciliacao")
    public RelatorioDeCadastro<LinhaDeConciliacao> conciliacao(@RequestParam(required = false) String busca,
            @RequestParam(required = false) String produtoId, @RequestParam(required = false) String recorte,
            @RequestParam(required = false) Integer pagina, @RequestParam(required = false) Integer tamanho) {
        return consultas.conciliacao(new Filtros(busca, null, null, produtoId, null, null, null, null, null, null, null,
                null, null, recorte, pagina, tamanho));
    }

    @Operation(summary = "Compras por período (uma linha por compra)",
            description = "situacao: REGISTRADAS (padrão), ESTORNADAS ou TODAS (as estornadas aparecem marcadas e ficam FORA "
                    + "do total geral). Filtros: fornecedorId, usuarioId, categoriaId e produtoId (compras que têm esse "
                    + "produto), busca (número, fornecedor ou produto), de e ate (data da compra, data de calendário)." + PADRAO)
    @GetMapping("/compras/por-compra")
    public RelatorioDeCadastro<LinhaDeCompra> compras(@RequestParam(required = false) String busca,
            @RequestParam(required = false) String categoriaId, @RequestParam(required = false) String fornecedorId,
            @RequestParam(required = false) String produtoId, @RequestParam(required = false) String situacao,
            @RequestParam(required = false) String usuarioId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.compras(new Filtros(busca, categoriaId, fornecedorId, produtoId, situacao, null, null, usuarioId,
                de, ate, null, null, null, recorte, pagina, tamanho));
    }

    @Operation(summary = "Compras por fornecedor e período",
            description = "Total por fornecedor (compras, peças e valor) e total geral no rodapé, somados dos itens. As "
                    + "compras estornadas ficam em colunas próprias e FORA do total. Mesmos filtros das compras; categoriaId "
                    + "e produtoId somam só os itens desse produto ou categoria." + PADRAO)
    @GetMapping("/compras/por-fornecedor")
    public RelatorioDeCadastro<LinhaDeFornecedorNasCompras> porFornecedor(@RequestParam(required = false) String busca,
            @RequestParam(required = false) String categoriaId, @RequestParam(required = false) String fornecedorId,
            @RequestParam(required = false) String produtoId, @RequestParam(required = false) String situacao,
            @RequestParam(required = false) String usuarioId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.comprasPorFornecedor(new Filtros(busca, categoriaId, fornecedorId, produtoId, situacao, null,
                null, usuarioId, de, ate, null, null, null, recorte, pagina, tamanho));
    }

    @Operation(summary = "Itens de uma compra",
            description = "Detalhamento da compra: produto (com foto), quantidade, custo unitário e total; o rodapé soma peças "
                    + "e total (igual ao total da compra)." + PADRAO)
    @GetMapping("/compras/por-compra/{id}/itens")
    public RelatorioDeCadastro<LinhaDeItemDeCompra> itensDaCompra(@PathVariable UUID id,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.itensDaCompra(id, new Filtros(null, null, null, null, null, null, null, null, null, null, null,
                null, null, recorte, pagina, tamanho));
    }

    @Operation(summary = "Histórico de custo por produto",
            description = "Cada custo que uma compra deu ao produto: data, compra, fornecedor, custo, custo anterior e variação "
                    + "(em reais e em %). Compras estornadas aparecem marcadas. Filtros: usuarioId, de e ate (data da compra), "
                    + "situacao do produto (TODOS por padrão)." + PADRAO)
    @GetMapping("/compras/historico-de-custo")
    public RelatorioDeCadastro<LinhaDeHistoricoDeCusto> historicoDeCusto(@RequestParam(required = false) String busca,
            @RequestParam(required = false) String categoriaId, @RequestParam(required = false) String fornecedorId,
            @RequestParam(required = false) String produtoId, @RequestParam(required = false) String situacao,
            @RequestParam(required = false) String usuarioId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.historicoDeCusto(new Filtros(busca, categoriaId, fornecedorId, produtoId, situacao, null, null,
                usuarioId, de, ate, null, null, null, recorte, pagina, tamanho));
    }
}
