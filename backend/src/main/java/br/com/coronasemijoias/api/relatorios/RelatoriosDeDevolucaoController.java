package br.com.coronasemijoias.api.relatorios;

import br.com.coronasemijoias.api.relatorios.ConsultasDeDevolucaoETroca.Filtros;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import io.swagger.v3.oas.annotations.Operation;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Relatórios de devolução e troca da dona (tarefa 6.10, §12). Só ADMIN. Mesma forma dos demais relatórios: linhas
 * paginadas, rodapé e detalhamento de cada total (parâmetro {@code recorte}). Dinheiro em Real; Dólar e Guarani pela
 * cotação do dia de cada lançamento. A exportação (PDF, Excel, CSV) é feita pelo Corona Gestão, página a página.
 */
@RestController
@RequestMapping("/api/dona/relatorios/devolucoes")
@ExigePerfil("ADMIN")
public class RelatoriosDeDevolucaoController {

    private static final String PADRAO = " Datas em horário de Brasília (de e ate inclusive). A página começa em 0 (até 100 "
            + "por página). recorte = a chave de um total do rodapé: devolve só as linhas desse total. Cada valor vem em Real, "
            + "Dólar e Guarani pela cotação do dia do lançamento (dataDaCotacao; vazio se não havia cotação).";

    private final ConsultasDeDevolucaoETroca consultas;
    private final ConferenciasDeDevolucaoETroca conferencias;

    public RelatoriosDeDevolucaoController(ConsultasDeDevolucaoETroca consultas, ConferenciasDeDevolucaoETroca conferencias) {
        this.consultas = consultas;
        this.conferencias = conferencias;
    }

    @Operation(operationId = "relatorioDeDevolucoes", summary = "Devoluções por vendedor e motivo",
            description = "Uma linha por devolução (as canceladas ficam marcadas e fora do total). situacao: REGISTRADAS (padrão), CANCELADAS, TODAS, ACEITA, EM_REVISAO, MANTIDA ou NAO_ACEITA. tipo: DEVOLUCAO ou TROCA. motivo: ARREPENDIMENTO, DEFEITO, TAMANHO ou OUTRO." + PADRAO)
    @GetMapping("/devolucoes")
    public RelatorioDeCadastro<ConsultasDeDevolucaoETroca.LinhaDeDevolucao> devolucoes(@RequestParam(required = false) String busca,
            @RequestParam(required = false) String vendedorId, @RequestParam(required = false) String produtoId,
            @RequestParam(required = false) String categoriaId, @RequestParam(required = false) String motivo,
            @RequestParam(required = false) String situacao, @RequestParam(required = false) String tipo,
            @RequestParam(required = false) String forma,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.devolucoes(new Filtros(busca, vendedorId, produtoId, categoriaId, motivo, situacao, tipo, forma, de, ate,
                recorte, pagina, tamanho));
    }

    @Operation(operationId = "relatorioDeTaxaDeDevolucaoPorVendedor", summary = "Taxa de devolução por vendedor",
            description = "Por vendedor: vendido, devolvido, trocas, devoluções por motivo e a porcentagem devolvida, com o aviso de acima do limite configurado." + PADRAO)
    @GetMapping("/por-vendedor")
    public RelatorioDeCadastro<ConsultasDeDevolucaoETroca.LinhaPorVendedor> porVendedor(@RequestParam(required = false) String busca,
            @RequestParam(required = false) String vendedorId, @RequestParam(required = false) String produtoId,
            @RequestParam(required = false) String categoriaId, @RequestParam(required = false) String motivo,
            @RequestParam(required = false) String situacao, @RequestParam(required = false) String tipo,
            @RequestParam(required = false) String forma,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.porVendedor(new Filtros(busca, vendedorId, produtoId, categoriaId, motivo, situacao, tipo, forma, de, ate,
                recorte, pagina, tamanho));
    }

    @Operation(operationId = "relatorioDePecasComDefeito", summary = "Peças com defeito",
            description = "Uma linha por peça devolvida com defeito, com foto, código e valor." + PADRAO)
    @GetMapping("/pecas-com-defeito")
    public RelatorioDeCadastro<ConsultasDeDevolucaoETroca.LinhaDePecaComDefeito> pecasComDefeito(@RequestParam(required = false) String busca,
            @RequestParam(required = false) String vendedorId, @RequestParam(required = false) String produtoId,
            @RequestParam(required = false) String categoriaId, @RequestParam(required = false) String motivo,
            @RequestParam(required = false) String situacao, @RequestParam(required = false) String tipo,
            @RequestParam(required = false) String forma,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.pecasComDefeito(new Filtros(busca, vendedorId, produtoId, categoriaId, motivo, situacao, tipo, forma, de, ate,
                recorte, pagina, tamanho));
    }

    @Operation(operationId = "relatorioDeTrocas", summary = "Trocas",
            description = "Uma linha por troca: o que voltou, o que saiu e a diferença." + PADRAO)
    @GetMapping("/trocas")
    public RelatorioDeCadastro<ConsultasDeDevolucaoETroca.LinhaDeTroca> trocas(@RequestParam(required = false) String busca,
            @RequestParam(required = false) String vendedorId, @RequestParam(required = false) String produtoId,
            @RequestParam(required = false) String categoriaId, @RequestParam(required = false) String motivo,
            @RequestParam(required = false) String situacao, @RequestParam(required = false) String tipo,
            @RequestParam(required = false) String forma,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String pracaId,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.trocas(new Filtros(busca, vendedorId, produtoId, categoriaId, motivo, situacao, tipo, forma, de, ate,
                recorte, pagina, tamanho, pracaId));
    }

    @Operation(operationId = "relatorioDeCreditosDosClientes", summary = "Créditos dos clientes, em aberto e usados",
            description = "Uma linha por crédito: valor, usado e saldo. situacao: REGISTRADOS (padrão), ABERTOS, UTILIZADOS, CANCELADOS ou TODOS. tipo: TROCA, DEVOLUCAO ou TRANSFERENCIA." + PADRAO)
    @GetMapping("/creditos")
    public RelatorioDeCadastro<ConsultasDeDevolucaoETroca.LinhaDeCredito> creditos(@RequestParam(required = false) String busca,
            @RequestParam(required = false) String vendedorId, @RequestParam(required = false) String produtoId,
            @RequestParam(required = false) String categoriaId, @RequestParam(required = false) String motivo,
            @RequestParam(required = false) String situacao, @RequestParam(required = false) String tipo,
            @RequestParam(required = false) String forma,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String pracaId,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.creditos(new Filtros(busca, vendedorId, produtoId, categoriaId, motivo, situacao, tipo, forma, de, ate,
                recorte, pagina, tamanho, pracaId));
    }

    @Operation(operationId = "relatorioDeDinheiroDevolvido", summary = "Dinheiro devolvido aos clientes",
            description = "Uma linha por devolução de dinheiro (e por cancelamento dela, em negativo). forma: DINHEIRO, PIX, CARTAO ou CHEQUE." + PADRAO)
    @GetMapping("/reembolsos")
    public RelatorioDeCadastro<ConsultasDeDevolucaoETroca.LinhaDeReembolso> reembolsos(@RequestParam(required = false) String busca,
            @RequestParam(required = false) String vendedorId, @RequestParam(required = false) String produtoId,
            @RequestParam(required = false) String categoriaId, @RequestParam(required = false) String motivo,
            @RequestParam(required = false) String situacao, @RequestParam(required = false) String tipo,
            @RequestParam(required = false) String forma,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.reembolsos(new Filtros(busca, vendedorId, produtoId, categoriaId, motivo, situacao, tipo, forma, de, ate,
                recorte, pagina, tamanho));
    }

    @Operation(operationId = "relatorioDePedidosDeAutorizacao", summary = "Pedidos de autorização",
            description = "Uma linha por pedido de autorização de devolução. situacao: TODAS (padrão), PEDIDA, APROVADA, USADA, NEGADA ou VENCIDA. tipo: FORA_DO_PRAZO ou ACIMA_DO_VALOR." + PADRAO)
    @GetMapping("/autorizacoes")
    public RelatorioDeCadastro<ConsultasDeDevolucaoETroca.LinhaDeAutorizacao> autorizacoes(@RequestParam(required = false) String busca,
            @RequestParam(required = false) String vendedorId, @RequestParam(required = false) String produtoId,
            @RequestParam(required = false) String categoriaId, @RequestParam(required = false) String motivo,
            @RequestParam(required = false) String situacao, @RequestParam(required = false) String tipo,
            @RequestParam(required = false) String forma,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.autorizacoes(new Filtros(busca, vendedorId, produtoId, categoriaId, motivo, situacao, tipo, forma, de, ate,
                recorte, pagina, tamanho));
    }

    @Operation(operationId = "conferirDevolucoesETrocas", summary = "Conferências de devolução e troca",
            description = "Confere o banco inteiro: saldo da maleta com devolução e troca, devolvido por peça, créditos menos "
                    + "usos, dinheiro devolvido e contas da venda. Cada conferência traz quantos itens olhou e as divergências "
                    + "(o que não bate); tudoCerto = nenhuma divergência. Só leitura.")
    @GetMapping("/conferencia")
    public ConferenciasDeDevolucaoETroca.Resultado conferencia() {
        return conferencias.conferir();
    }
}
