package br.com.coronasemijoias.api.relatorios;

import br.com.coronasemijoias.api.relatorios.ConsultasDeMaleta.Filtros;
import br.com.coronasemijoias.api.relatorios.ConsultasDeMaleta.LinhaDeMaletaEmAberto;
import br.com.coronasemijoias.api.relatorios.ConsultasDeMaleta.LinhaDeRemessa;
import br.com.coronasemijoias.api.relatorios.ConsultasDeMaleta.LinhaDeSaldo;
import br.com.coronasemijoias.api.relatorios.ConsultasDeMaleta.LinhaDeVendedor;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import io.swagger.v3.oas.annotations.Operation;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Relatórios da maleta da dona (tarefa 3.4, §12). Só ADMIN. Mesma forma dos relatórios de cadastro, estoque e compras:
 * linhas paginadas, rodapé e detalhamento de cada total (parâmetro {@code recorte}). Dinheiro em Real; cada linha traz
 * também o Dólar e o Guarani pela cotação do dia do envio da maleta, e o rodapé soma as linhas já convertidas. A
 * exportação (PDF, Excel, CSV) é feita pelo Corona Gestão com o relatório inteiro, pedido página a página com os
 * MESMOS filtros da tela.
 */
@RestController
@RequestMapping("/api/dona/relatorios/maleta")
@ExigePerfil("ADMIN")
public class RelatoriosDeMaletaController {

    private static final String PADRAO = " Rascunho nunca entra. Datas em UTC (de e ate inclusive, sobre a data do envio). "
            + "A página começa em 0 (até 100 por página). recorte = a chave de um total do rodapé: devolve só as linhas "
            + "que formam esse total. Cada valor vem em Real, Dólar e Guarani pela cotação do dia do envio "
            + "(dataDaCotacao; vazio se ainda não havia cotação).";

    private final ConsultasDeMaleta consultas;
    private final RelatorioDoFechamentoDaMaleta fechamento;

    public RelatoriosDeMaletaController(ConsultasDeMaleta consultas, RelatorioDoFechamentoDaMaleta fechamento) {
        this.consultas = consultas;
        this.fechamento = fechamento;
    }

    @Operation(operationId = "relatorioDoFechamentoDaMaleta", summary = "Fechamento da maleta",
            description = "Uma linha por peça da maleta (enviadas, devolvidas pelos clientes, vendidas, ainda com clientes e com quem, "
                    + "esperado na maleta, bipadas na volta e diferença). O rodapé traz as peças e os valores da maleta (vendido, "
                    + "comissão dos clientes, recebido e pendências a receber, divididos pelo vendido de cada maleta dentro da nota). "
                    + "Sai da mesma leitura da tela do Fechamento (GET /api/maletas/{id}/fechamento); 404 se o fechamento ainda não "
                    + "foi começado. Parâmetros: remessaId (obrigatório), pagina (começa em 0) e tamanho (até 100).")
    @GetMapping("/fechamento")
    public RelatorioDeCadastro<RelatorioDoFechamentoDaMaleta.LinhaDoFechamento> fechamentoDaMaleta(
            @RequestParam java.util.UUID remessaId, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return fechamento.montar(remessaId, pagina, tamanho);
    }

    @Operation(operationId = "relatorioDeRemessasPorVendedorESemana", summary = "Maletas por vendedor e semana",
            description = "Uma linha por maleta enviada (valor congelado no envio). situacao: REGISTRADAS (padrão: enviadas "
                    + "e adiante), CANCELADAS, TODAS (as canceladas aparecem marcadas e ficam FORA do total geral) ou um "
                    + "estado (ENVIADA, CONFERIDA, EM_ACERTO, ACERTADA). Filtros: vendedorId, pracaId, semanaId, tipo "
                    + "(NOVA ou REPOSICAO), produtoId e categoriaId (maletas que têm a peça), busca (vendedor ou praça), "
                    + "de e ate. Para ver as peças de uma maleta use o relatório de saldo com remessaId." + PADRAO)
    @GetMapping("/remessas")
    public RelatorioDeCadastro<LinhaDeRemessa> remessas(@RequestParam(required = false) String busca,
            @RequestParam(required = false) String vendedorId, @RequestParam(required = false) String pracaId,
            @RequestParam(required = false) String semanaId, @RequestParam(required = false) String produtoId,
            @RequestParam(required = false) String categoriaId, @RequestParam(required = false) String tipo,
            @RequestParam(required = false) String situacao,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.remessas(new Filtros(busca, vendedorId, pracaId, semanaId, produtoId, categoriaId, null, tipo,
                situacao, de, ate, null, recorte, pagina, tamanho));
    }

    @Operation(operationId = "relatorioDeReposicoes", summary = "Reposições",
            description = "As maletas do tipo REPOSICAO, no mesmo formato de maletas por vendedor e semana. Filtros: "
                    + "vendedorId, pracaId, semanaId, produtoId, categoriaId, busca, situacao, de e ate." + PADRAO)
    @GetMapping("/reposicoes")
    public RelatorioDeCadastro<LinhaDeRemessa> reposicoes(@RequestParam(required = false) String busca,
            @RequestParam(required = false) String vendedorId, @RequestParam(required = false) String pracaId,
            @RequestParam(required = false) String semanaId, @RequestParam(required = false) String produtoId,
            @RequestParam(required = false) String categoriaId, @RequestParam(required = false) String situacao,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.reposicoes(new Filtros(busca, vendedorId, pracaId, semanaId, produtoId, categoriaId, null, null,
                situacao, de, ate, null, recorte, pagina, tamanho));
    }

    @Operation(operationId = "relatorioDeSaldoPorMaleta", summary = "Saldo atual por maleta (peça a peça)",
            description = "Por peça de cada maleta com o vendedor: enviada, devolvida à dona, saldo (enviada mais os "
                    + "movimentos) e valor a preço congelado. situacao: COM_SALDO (padrão), ZERADAS ou TODAS. Só maletas "
                    + "enviadas (rascunho e cancelada ficam de fora). Filtros: remessaId (detalha uma maleta), vendedorId, "
                    + "pracaId, semanaId, tipo, produtoId, categoriaId, busca (nome, referência ou código da peça), de e ate."
                    + PADRAO)
    @GetMapping("/saldo")
    public RelatorioDeCadastro<LinhaDeSaldo> saldo(@RequestParam(required = false) String busca,
            @RequestParam(required = false) String remessaId, @RequestParam(required = false) String vendedorId,
            @RequestParam(required = false) String pracaId, @RequestParam(required = false) String semanaId,
            @RequestParam(required = false) String produtoId, @RequestParam(required = false) String categoriaId,
            @RequestParam(required = false) String tipo, @RequestParam(required = false) String situacao,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.saldoPorMaleta(new Filtros(busca, vendedorId, pracaId, semanaId, produtoId, categoriaId,
                remessaId, tipo, situacao, de, ate, null, recorte, pagina, tamanho));
    }

    @Operation(operationId = "relatorioDeMercadoriaPorVendedor", summary = "Mercadoria em maletas por vendedor",
            description = "Por vendedor com peça ainda em maleta: maletas em aberto, atrasadas, peças e valor a preço "
                    + "congelado, limite de consignação e quanto sobra dele (o limite 0 do cadastro vale como sem limite; "
                    + "a sobra conta tudo o que o vendedor tem em aberto, mesmo com filtros). Filtros: vendedorId, "
                    + "pracaId, semanaId, tipo, produtoId, categoriaId, busca, de e ate (das maletas), diasDeAtraso "
                    + "(padrão 7)." + PADRAO)
    @GetMapping("/mercadoria-por-vendedor")
    public RelatorioDeCadastro<LinhaDeVendedor> mercadoriaPorVendedor(@RequestParam(required = false) String busca,
            @RequestParam(required = false) String vendedorId, @RequestParam(required = false) String pracaId,
            @RequestParam(required = false) String semanaId, @RequestParam(required = false) String produtoId,
            @RequestParam(required = false) String categoriaId, @RequestParam(required = false) String tipo,
            @RequestParam(required = false) Integer diasDeAtraso,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.mercadoriaPorVendedor(new Filtros(busca, vendedorId, pracaId, semanaId, produtoId, categoriaId,
                null, tipo, null, de, ate, diasDeAtraso, recorte, pagina, tamanho));
    }

    @Operation(operationId = "relatorioDeMaletasEmAbertoEAtrasadas", summary = "Maletas em aberto e atrasadas",
            description = "Uma linha por maleta enviada que ainda tem peça com o vendedor: dias desde o envio (calendário de "
                    + "Brasília), se está atrasada, peças e valor em aberto. Atrasada = mais de diasDeAtraso dias desde o "
                    + "envio (padrão 7; de 0 a 365). situacao: TODAS (padrão), ATRASADAS ou NO_PRAZO. Filtros: vendedorId, "
                    + "pracaId, semanaId, tipo, produtoId, categoriaId, busca, de e ate." + PADRAO)
    @GetMapping("/em-aberto")
    public RelatorioDeCadastro<LinhaDeMaletaEmAberto> emAberto(@RequestParam(required = false) String busca,
            @RequestParam(required = false) String vendedorId, @RequestParam(required = false) String pracaId,
            @RequestParam(required = false) String semanaId, @RequestParam(required = false) String produtoId,
            @RequestParam(required = false) String categoriaId, @RequestParam(required = false) String tipo,
            @RequestParam(required = false) String situacao, @RequestParam(required = false) Integer diasDeAtraso,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.maletasEmAberto(new Filtros(busca, vendedorId, pracaId, semanaId, produtoId, categoriaId, null,
                tipo, situacao, de, ate, diasDeAtraso, recorte, pagina, tamanho));
    }
}
