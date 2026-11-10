package br.com.coronasemijoias.api.relatorios;

import br.com.coronasemijoias.api.relatorios.ConsultasDeCadastro.Filtros;
import br.com.coronasemijoias.api.relatorios.ConsultasDeCadastro.LinhaDeAlteracao;
import br.com.coronasemijoias.api.relatorios.ConsultasDeCadastro.LinhaDeAniversariante;
import br.com.coronasemijoias.api.relatorios.ConsultasDeCadastro.LinhaDeCliente;
import br.com.coronasemijoias.api.relatorios.ConsultasDeCadastro.LinhaDeFornecedor;
import br.com.coronasemijoias.api.relatorios.ConsultasDeCadastro.LinhaDeProduto;
import br.com.coronasemijoias.api.relatorios.ConsultasDeCadastro.LinhaDeVendedor;
import br.com.coronasemijoias.api.relatorios.ConsultasDeCadastro.LinhaSemCodigoDeBarras;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import io.swagger.v3.oas.annotations.Operation;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Relatórios de cadastro da dona (tarefa 1.12, §12). Só ADMIN: o de produtos traz o preço de custo, que
 * nenhum vendedor pode ver. Todos têm a mesma forma: linhas paginadas, rodapé e detalhamento de cada total
 * (parâmetro {@code recorte} = a chave do total). Os valores em dinheiro vêm em Real; a tela mostra Dólar e
 * Guarani pela cotação de hoje.
 */
@RestController
@RequestMapping("/api/dona/relatorios/cadastros")
@ExigePerfil("ADMIN")
public class RelatoriosDeCadastroController {

    private static final String PADRAO = " Por padrão só aparecem os ativos (situacao=TODOS ou INATIVOS para os outros). "
            + "Datas em UTC (de e ate inclusive). A página começa em 0 (até 100 por página). "
            + "recorte = a chave de um total do rodapé: devolve só as linhas que formam esse total.";

    private final ConsultasDeCadastro consultas;
    private final CodigosEmConflito conflitos;

    public RelatoriosDeCadastroController(ConsultasDeCadastro consultas, CodigosEmConflito conflitos) {
        this.consultas = consultas;
        this.conflitos = conflitos;
    }

    @Operation(summary = "Relatório de vendedores",
            description = "Limite de consignação, restrição, situação, praça e comissão. Filtros: busca, pracaId, restricaoId, "
                    + "situacao, de e ate (data do cadastro)." + PADRAO)
    @GetMapping("/vendedores")
    public RelatorioDeCadastro<LinhaDeVendedor> vendedores(@RequestParam(required = false) String busca,
            @RequestParam(required = false) String pracaId, @RequestParam(required = false) String restricaoId,
            @RequestParam(required = false) String situacao,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.vendedores(new Filtros(busca, null, null, pracaId, null, restricaoId, situacao, de, ate, null,
                recorte, pagina, tamanho));
    }

    @Operation(summary = "Relatório de clientes",
            description = "Filtros: busca, vendedorId, pracaId (a do vendedor responsável), de e ate (data do cadastro). "
                    + "Cliente não tem situação: só os excluídos ficam de fora." + PADRAO)
    @GetMapping("/clientes")
    public RelatorioDeCadastro<LinhaDeCliente> clientes(@RequestParam(required = false) String busca,
            @RequestParam(required = false) String vendedorId, @RequestParam(required = false) String pracaId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.clientes(new Filtros(busca, null, null, pracaId, vendedorId, null, null, de, ate, null, recorte,
                pagina, tamanho));
    }

    @Operation(summary = "Relatório de fornecedores",
            description = "Filtros: busca, de e ate (data do cadastro). Fornecedor não tem situação." + PADRAO)
    @GetMapping("/fornecedores")
    public RelatorioDeCadastro<LinhaDeFornecedor> fornecedores(@RequestParam(required = false) String busca,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.fornecedores(new Filtros(busca, null, null, null, null, null, null, de, ate, null, recorte,
                pagina, tamanho));
    }

    @Operation(summary = "Relatório de produtos (com preço de custo)",
            description = "Só a dona vê o custo. Filtros: busca (nome, referência ou código), categoriaId, fornecedorId, "
                    + "situacao, de e ate (data do cadastro)." + PADRAO)
    @GetMapping("/produtos")
    public RelatorioDeCadastro<LinhaDeProduto> produtos(@RequestParam(required = false) String busca,
            @RequestParam(required = false) String categoriaId, @RequestParam(required = false) String fornecedorId,
            @RequestParam(required = false) String situacao,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.produtos(new Filtros(busca, categoriaId, fornecedorId, null, null, null, situacao, de, ate, null,
                recorte, pagina, tamanho));
    }

    @Operation(summary = "Relatório de produtos sem código de barras",
            description = "Informativo: o código de barras (EAN-13) não é obrigatório, a loja pode usar só o código dela. "
                    + "Filtros: busca, categoriaId, fornecedorId, situacao." + PADRAO)
    @GetMapping("/produtos-sem-codigo-de-barras")
    public RelatorioDeCadastro<LinhaSemCodigoDeBarras> semCodigoDeBarras(@RequestParam(required = false) String busca,
            @RequestParam(required = false) String categoriaId, @RequestParam(required = false) String fornecedorId,
            @RequestParam(required = false) String situacao, @RequestParam(required = false) String recorte,
            @RequestParam(required = false) Integer pagina, @RequestParam(required = false) Integer tamanho) {
        return consultas.semCodigoDeBarras(new Filtros(busca, categoriaId, fornecedorId, null, null, null, situacao, null,
                null, null, recorte, pagina, tamanho));
    }

    @Operation(summary = "Relatório de códigos duplicados ou em conflito",
            description = "Códigos quase iguais (muda só espaço, traço ou maiúscula), do mesmo código em fornecedores "
                    + "diferentes, e códigos de barras com número errado. O servidor já recusa o código repetido de verdade. "
                    + "Filtros: busca, fornecedorId, situacao (a do produto)." + PADRAO)
    @GetMapping("/codigos-em-conflito")
    public RelatorioDeCadastro<CodigosEmConflito.Linha> codigosEmConflito(@RequestParam(required = false) String busca,
            @RequestParam(required = false) String fornecedorId, @RequestParam(required = false) String situacao,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return conflitos.relatorio(busca, fornecedorId, situacao, recorte, pagina, tamanho);
    }

    @Operation(summary = "Relatório de aniversariantes (vendedores)",
            description = "Vendedores que fazem aniversário no mês (mes de 1 a 12; sem mes, o mês de hoje em Brasília). "
                    + "O cadastro de cliente não tem data de nascimento, por isso só vendedores. A data é de calendário, "
                    + "sem fuso. Filtros: busca, pracaId, situacao." + PADRAO)
    @GetMapping("/aniversariantes")
    public RelatorioDeCadastro<LinhaDeAniversariante> aniversariantes(@RequestParam(required = false) Integer mes,
            @RequestParam(required = false) String busca, @RequestParam(required = false) String pracaId,
            @RequestParam(required = false) String situacao, @RequestParam(required = false) String recorte,
            @RequestParam(required = false) Integer pagina, @RequestParam(required = false) Integer tamanho) {
        return consultas.aniversariantes(new Filtros(busca, null, null, pracaId, null, null, situacao, null, null, mes,
                recorte, pagina, tamanho));
    }

    @Operation(summary = "Relatório de cadastros e alterações de produtos",
            description = "Produtos cadastrados e preços alterados no período, com quem fez (pela auditoria). Não é o "
                    + "resultado da importação em massa: o servidor não guarda esse resultado (pendência da Fase 2). "
                    + "Filtros: busca (produto ou pessoa), de e ate." + PADRAO)
    @GetMapping("/alteracoes-de-produtos")
    public RelatorioDeCadastro<LinhaDeAlteracao> alteracoesDeProdutos(@RequestParam(required = false) String busca,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.alteracoesDeProdutos(new Filtros(busca, null, null, null, null, null, null, de, ate, null,
                recorte, pagina, tamanho));
    }
}
