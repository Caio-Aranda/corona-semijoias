package br.com.coronasemijoias.api.relatorios;

import static br.com.coronasemijoias.api.relatorios.MotorDeRelatorio.DefinicaoDeTotal.contagem;
import static br.com.coronasemijoias.api.relatorios.MotorDeRelatorio.DefinicaoDeTotal.soma;
import static br.com.coronasemijoias.api.relatorios.MotorDeRelatorio.TODOS;
import static br.com.coronasemijoias.api.relatorios.MotorDeRelatorio.idOpcional;
import static br.com.coronasemijoias.api.relatorios.MotorDeRelatorio.reais;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.relatorios.MotorDeRelatorio.Consulta;
import br.com.coronasemijoias.api.relatorios.MotorDeRelatorio.DefinicaoDeTotal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Relatórios de cadastro da dona (tarefa 1.12, §12): vendedores, clientes, fornecedores, produtos (com
 * custo), produtos sem código de barras, aniversariantes e as alterações de produtos. Só leitura. Quem
 * foi excluído (exclusão lógica) nunca aparece; quem está inativo só aparece quando a pessoa pede.
 */
@Service
public class ConsultasDeCadastro {

    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");

    /** Filtros comuns; cada relatório usa só os que fazem sentido para ele. */
    public record Filtros(String busca, String categoriaId, String fornecedorId, String pracaId, String vendedorId,
            String restricaoId, String situacao, LocalDate de, LocalDate ate, Integer mes, String recorte,
            Integer pagina, Integer tamanho, Mais mais) {

        /** Os filtros antigos, sem os da tarefa 1.13. */
        public Filtros(String busca, String categoriaId, String fornecedorId, String pracaId, String vendedorId,
                String restricaoId, String situacao, LocalDate de, LocalDate ate, Integer mes, String recorte,
                Integer pagina, Integer tamanho) {
            this(busca, categoriaId, fornecedorId, pracaId, vendedorId, restricaoId, situacao, de, ate, mes, recorte,
                    pagina, tamanho, Mais.NENHUM);
        }

        public Mais mais() {
            return mais == null ? Mais.NENHUM : mais;
        }
    }

    /**
     * Filtros de um relatório só (tarefa 1.13), como texto da URL e validados aqui: SIM/NAO, faixas de valor
     * em reais, estoque (ABAIXO_DO_MINIMO, ZERADO, COM_ESTOQUE) e foto (COM, SEM).
     */
    public record Mais(String comRestricao, String limiteDe, String limiteAte, String estoque, String precoDe,
            String precoAte, String foto, String comProdutos, String comVendedor) {
        static final Mais NENHUM = new Mais(null, null, null, null, null, null, null, null, null);
    }

    public record LinhaDeVendedor(UUID id, int codigo, String nome, String praca, String restricao,
            boolean bloqueiaRemessa, BigDecimal limiteConsignacao, BigDecimal comissaoPercentual, boolean ativo) {
    }

    public record LinhaDeCliente(UUID id, String nome, String telefone, String whatsapp, String cidade, String uf,
            String vendedor, String praca, Instant cadastradoEm) {
    }

    public record LinhaDeFornecedor(UUID id, String razaoSocial, String cnpj, String contato, String telefone,
            String cidade, String uf, long produtosAtivos) {
    }

    public record LinhaDeProduto(UUID id, String nome, String referencia, String codigoPrincipal, String categoria,
            String fornecedor, BigDecimal precoVenda, BigDecimal precoCusto, int estoqueMinimo, int estoqueAtual,
            boolean ativo) {
    }

    public record LinhaSemCodigoDeBarras(UUID id, String nome, String referencia, String codigos, String categoria,
            String fornecedor, boolean temCodigoDoFornecedor, boolean ativo) {
    }

    public record LinhaDeAniversariante(UUID id, String nome, int dia, int mes, LocalDate dataNascimento,
            String telefone, String whatsapp, String praca, boolean ativo) {
    }

    public record LinhaDeAlteracao(UUID id, String acao, String descricao, UUID produtoId, String produto, String quem,
            Instant quando) {
    }

    public record LinhaDeImportacao(UUID id, UUID loteId, String tipo, String tipoNaTela, String arquivo, String referencia,
            String resultado, String resultadoNaTela, String motivo, String quem, Instant quando) {
    }

    private final MotorDeRelatorio motor;
    private final Clock relogio;

    public ConsultasDeCadastro(MotorDeRelatorio motor, Clock relogio) {
        this.motor = motor;
        this.relogio = relogio;
    }

    /** ATIVOS (padrão), INATIVOS ou TODOS. */
    private static String situacao(String pedida) {
        String s = pedida == null || pedida.isBlank() ? "ATIVOS" : pedida.strip().toUpperCase();
        if (!List.of("ATIVOS", "INATIVOS", "TODOS").contains(s)) {
            throw Validacoes.recusar("situacao", "Escolha ativos, inativos ou todos.");
        }
        return s;
    }

    private static void porSituacao(Consulta c, String situacao, String coluna) {
        switch (situacao(situacao)) {
            case "ATIVOS" -> c.onde(coluna + " = true");
            case "INATIVOS" -> c.onde(coluna + " = false");
            default -> {
            }
        }
    }

    private static void periodo(Consulta c, String coluna, Filtros f) {
        MotorDeRelatorio.conferirPeriodo(f.de(), f.ate());
        c.periodo(coluna, f.de(), f.ate());
    }

    private static boolean preenchido(String v) {
        return v != null && !v.isBlank();
    }

    /** SIM ou NAO (ou vazio): devolve true, false ou null. */
    private static Boolean simNao(String valor, String campo) {
        if (!preenchido(valor)) {
            return null;
        }
        return switch (valor.strip().toUpperCase()) {
            case "SIM" -> true;
            case "NAO" -> false;
            default -> throw Validacoes.recusar(campo, "Escolha sim ou não.");
        };
    }

    private static BigDecimal valorOpcional(String valor, String campo) {
        if (!preenchido(valor)) {
            return null;
        }
        try {
            BigDecimal v = new BigDecimal(valor.strip());
            if (v.signum() < 0 || v.scale() > 2 || v.precision() - v.scale() > 12) {
                throw new NumberFormatException();
            }
            return v;
        } catch (NumberFormatException e) {
            throw Validacoes.recusar(campo, "Digite um valor em reais, como 150 ou 150.50.");
        }
    }

    /** Faixa de valores sobre uma expressão; "de" e "até" valem inclusive. */
    private static void faixa(Consulta c, String expressao, String deTexto, String ateTexto, String campoDe,
            String campoAte) {
        BigDecimal de = valorOpcional(deTexto, campoDe);
        BigDecimal ate = valorOpcional(ateTexto, campoAte);
        if (de != null && ate != null && ate.compareTo(de) < 0) {
            throw Validacoes.recusar(campoAte, "O valor final não pode ser menor que o inicial.");
        }
        if (de != null) {
            c.onde(expressao + " >= :" + campoDe, campoDe, de);
        }
        if (ate != null) {
            c.onde(expressao + " <= :" + campoAte, campoAte, ate);
        }
    }

    private static void existencia(Consulta c, Boolean quer, String existe) {
        if (quer != null) {
            c.onde(quer ? existe : "NOT (" + existe + ")");
        }
    }

    private static void filtroDeUuid(Consulta c, String valor, String campo, String condicao) {
        UUID id = idOpcional(valor, campo);
        if (id != null) {
            c.onde(condicao, campo, id);
        }
    }

    // --- vendedores ---

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeVendedor> vendedores(Filtros f) {
        Consulta c = new Consulta("""
                v.id, v.codigo, v.nome, pr.nome AS praca, r.codigo AS restricao_codigo, r.descricao AS restricao_descricao,
                coalesce(r.bloqueia_remessa, false) AS bloqueia_remessa, v.limite_consignacao, v.comissao_percentual,
                v.ativo""", """
                corona.vendedores v
                LEFT JOIN corona.pracas pr ON pr.id = v.praca_id
                LEFT JOIN corona.restricoes r ON r.id = v.restricao_id""", "lower(v.nome), v.id", List.of(
                contagem(TODOS, "Vendedores", null),
                contagem("ativos", "Ativos", "v.ativo"),
                contagem("inativos", "Inativos", "NOT v.ativo"),
                contagem("com-restricao", "Com restrição", "v.restricao_id IS NOT NULL"),
                contagem("bloqueados", "Com maleta bloqueada", "r.bloqueia_remessa"),
                soma("limite", "Limite de consignação somado", "v.limite_consignacao")));
        c.onde("v.deleted_at IS NULL");
        porSituacao(c, f.situacao(), "v.ativo");
        c.busca(f.busca(), "v.nome", "cast(v.codigo as text)", "v.cpf_cnpj", "v.telefone1", "v.telefone2", "v.whatsapp");
        filtroDeUuid(c, f.pracaId(), "praca", "v.praca_id = :praca");
        filtroDeUuid(c, f.restricaoId(), "restricao", "v.restricao_id = :restricao");
        periodo(c, "v.created_at", f);
        faixa(c, "v.limite_consignacao", f.mais().limiteDe(), f.mais().limiteAte(), "limiteDe", "limiteAte");
        existencia(c, simNao(f.mais().comRestricao(), "comRestricao"), "v.restricao_id IS NOT NULL");
        return motor.executar(c, f.recorte(), f.pagina(), f.tamanho(), (rs, n) -> {
            String codigo = rs.getString("restricao_codigo");
            return new LinhaDeVendedor(rs.getObject("id", UUID.class), rs.getInt("codigo"), rs.getString("nome"),
                    rs.getString("praca"), codigo == null ? null : codigo + " - " + rs.getString("restricao_descricao"),
                    rs.getBoolean("bloqueia_remessa"), reais(rs, "limite_consignacao"), reais(rs, "comissao_percentual"),
                    rs.getBoolean("ativo"));
        });
    }

    // --- clientes ---

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeCliente> clientes(Filtros f) {
        Consulta c = new Consulta("""
                c.id, c.nome, c.telefone, c.whatsapp, c.endereco_cidade, c.endereco_uf, v.nome AS vendedor,
                pr.nome AS praca, c.created_at""", """
                corona.clientes c
                LEFT JOIN corona.vendedores v ON v.id = c.vendedor_responsavel_id
                LEFT JOIN corona.pracas pr ON pr.id = v.praca_id""", "lower(c.nome), c.id", List.of(
                contagem(TODOS, "Clientes", null),
                contagem("com-vendedor", "Com vendedor responsável", "c.vendedor_responsavel_id IS NOT NULL"),
                contagem("sem-vendedor", "Sem vendedor responsável", "c.vendedor_responsavel_id IS NULL")));
        c.onde("c.deleted_at IS NULL");
        c.busca(f.busca(), "c.nome", "c.cpf", "c.telefone", "c.whatsapp");
        filtroDeUuid(c, f.vendedorId(), "vendedor", "c.vendedor_responsavel_id = :vendedor");
        filtroDeUuid(c, f.pracaId(), "praca", "v.praca_id = :praca");
        periodo(c, "c.created_at", f);
        existencia(c, simNao(f.mais().comVendedor(), "comVendedor"), "c.vendedor_responsavel_id IS NOT NULL");
        return motor.executar(c, f.recorte(), f.pagina(), f.tamanho(),
                (rs, n) -> new LinhaDeCliente(rs.getObject("id", UUID.class), rs.getString("nome"),
                        rs.getString("telefone"), rs.getString("whatsapp"), rs.getString("endereco_cidade"),
                        rs.getString("endereco_uf"), rs.getString("vendedor"), rs.getString("praca"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()));
    }

    // --- fornecedores ---

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeFornecedor> fornecedores(Filtros f) {
        String comProdutos = "EXISTS (SELECT 1 FROM corona.produtos p WHERE p.fornecedor_id = f.id "
                + "AND p.deleted_at IS NULL AND p.ativo)";
        Consulta c = new Consulta("""
                f.id, f.razao_social, f.cnpj, f.contato_nome, f.telefone, f.endereco_cidade, f.endereco_uf,
                (SELECT count(*) FROM corona.produtos p WHERE p.fornecedor_id = f.id AND p.deleted_at IS NULL
                   AND p.ativo) AS produtos_ativos""", "corona.fornecedores f", "lower(f.razao_social), f.id", List.of(
                contagem(TODOS, "Fornecedores", null),
                contagem("com-produtos", "Com produtos ativos", comProdutos),
                contagem("sem-produtos", "Sem produtos ativos", "NOT " + comProdutos)));
        c.onde("f.deleted_at IS NULL");
        c.busca(f.busca(), "f.razao_social", "f.cnpj", "f.contato_nome", "f.telefone");
        periodo(c, "f.created_at", f);
        existencia(c, simNao(f.mais().comProdutos(), "comProdutos"), comProdutos);
        return motor.executar(c, f.recorte(), f.pagina(), f.tamanho(),
                (rs, n) -> new LinhaDeFornecedor(rs.getObject("id", UUID.class), rs.getString("razao_social"),
                        rs.getString("cnpj"), rs.getString("contato_nome"), rs.getString("telefone"),
                        rs.getString("endereco_cidade"), rs.getString("endereco_uf"), rs.getLong("produtos_ativos")));
    }

    // --- produtos (com custo: só a dona) ---

    private static final String CODIGO_PRINCIPAL = """
            (SELECT c.codigo FROM corona.produto_codigos c WHERE c.produto_id = p.id AND c.deleted_at IS NULL
             ORDER BY c.principal DESC, c.created_at LIMIT 1)""";

    private static final String BUSCA_DE_PRODUTO = """
            (lower(p.nome) LIKE :texto OR lower(coalesce(p.referencia, '')) LIKE :texto
             OR EXISTS (SELECT 1 FROM corona.produto_codigos cb WHERE cb.produto_id = p.id AND cb.deleted_at IS NULL
                        AND lower(cb.codigo) LIKE :texto))""";

    private static void buscaDeProduto(Consulta c, String busca) {
        String limpo = MotorDeRelatorio.limparBusca(busca);
        if (!limpo.isEmpty()) {
            c.onde(BUSCA_DE_PRODUTO, "texto", "%" + limpo + "%");
        }
    }

    private static void porEstoque(Consulta c, String estoque) {
        if (!preenchido(estoque)) {
            return;
        }
        switch (estoque.strip().toUpperCase()) {
            case "ZERADO" -> c.onde("p.estoque_atual <= 0");
            case "ABAIXO_DO_MINIMO" -> c.onde("p.estoque_atual < p.estoque_minimo");
            case "COM_ESTOQUE" -> c.onde("p.estoque_atual > 0");
            default -> throw Validacoes.recusar("estoque", "Escolha: sem estoque, abaixo do mínimo ou com estoque.");
        }
    }

    private static void porFoto(Consulta c, String foto) {
        if (!preenchido(foto)) {
            return;
        }
        String temFoto = "EXISTS (SELECT 1 FROM corona.produtos_fotos pf WHERE pf.produto_id = p.id "
                + "AND pf.deleted_at IS NULL)";
        switch (foto.strip().toUpperCase()) {
            case "COM" -> c.onde(temFoto);
            case "SEM" -> c.onde("NOT " + temFoto);
            default -> throw Validacoes.recusar("foto", "Escolha: com foto ou sem foto.");
        }
    }

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeProduto> produtos(Filtros f) {
        Consulta c = new Consulta("p.id, p.nome, p.referencia, " + CODIGO_PRINCIPAL + """
                 AS codigo_principal, cat.nome AS categoria, forn.razao_social AS fornecedor, p.preco_venda,
                pc.preco_custo, p.estoque_minimo, p.estoque_atual, p.ativo""", """
                corona.produtos p
                JOIN corona.categorias cat ON cat.id = p.categoria_id
                LEFT JOIN corona.fornecedores forn ON forn.id = p.fornecedor_id
                LEFT JOIN corona.produtos_custos pc ON pc.produto_id = p.id""",
                "lower(p.nome), p.id", List.of(
                        contagem(TODOS, "Produtos", null),
                        contagem("ativos", "Ativos", "p.ativo"),
                        contagem("inativos", "Inativos", "NOT p.ativo"),
                        soma("preco-venda", "Soma dos preços de venda", "p.preco_venda"),
                        soma("custo", "Soma dos custos", "pc.preco_custo")));
        c.onde("p.deleted_at IS NULL");
        porSituacao(c, f.situacao(), "p.ativo");
        buscaDeProduto(c, f.busca());
        filtroDeUuid(c, f.categoriaId(), "categoria", "p.categoria_id = :categoria");
        filtroDeUuid(c, f.fornecedorId(), "fornecedor", "p.fornecedor_id = :fornecedor");
        periodo(c, "p.created_at", f);
        faixa(c, "p.preco_venda", f.mais().precoDe(), f.mais().precoAte(), "precoDe", "precoAte");
        porEstoque(c, f.mais().estoque());
        porFoto(c, f.mais().foto());
        return motor.executar(c, f.recorte(), f.pagina(), f.tamanho(),
                (rs, n) -> new LinhaDeProduto(rs.getObject("id", UUID.class), rs.getString("nome"),
                        rs.getString("referencia"), rs.getString("codigo_principal"), rs.getString("categoria"),
                        rs.getString("fornecedor"), reais(rs, "preco_venda"), reais(rs, "preco_custo"),
                        rs.getInt("estoque_minimo"), rs.getInt("estoque_atual"), rs.getBoolean("ativo")));
    }

    // --- produtos sem código de barras (informativo: EAN-13 não é obrigatório, ADR-0007) ---

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaSemCodigoDeBarras> semCodigoDeBarras(Filtros f) {
        String doFornecedor = "EXISTS (SELECT 1 FROM corona.produto_codigos cf WHERE cf.produto_id = p.id "
                + "AND cf.deleted_at IS NULL AND cf.ativo AND cf.tipo = 'FORNECEDOR')";
        Consulta c = new Consulta("""
                p.id, p.nome, p.referencia, cat.nome AS categoria, forn.razao_social AS fornecedor, p.ativo,
                (SELECT string_agg(k.codigo, ', ' ORDER BY k.principal DESC, k.created_at) FROM corona.produto_codigos k
                  WHERE k.produto_id = p.id AND k.deleted_at IS NULL AND k.ativo) AS codigos,
                """ + doFornecedor + " AS tem_codigo_do_fornecedor", """
                corona.produtos p
                JOIN corona.categorias cat ON cat.id = p.categoria_id
                LEFT JOIN corona.fornecedores forn ON forn.id = p.fornecedor_id""", "lower(p.nome), p.id", List.of(
                contagem(TODOS, "Produtos sem código de barras", null),
                contagem("com-codigo-do-fornecedor", "Com código do fornecedor", doFornecedor),
                contagem("so-codigo-da-loja", "Só com código da loja", "NOT " + doFornecedor)));
        c.onde("p.deleted_at IS NULL");
        c.onde("NOT EXISTS (SELECT 1 FROM corona.produto_codigos e WHERE e.produto_id = p.id AND e.deleted_at IS NULL "
                + "AND e.ativo AND e.tipo = 'EAN13')");
        porSituacao(c, f.situacao(), "p.ativo");
        buscaDeProduto(c, f.busca());
        filtroDeUuid(c, f.categoriaId(), "categoria", "p.categoria_id = :categoria");
        filtroDeUuid(c, f.fornecedorId(), "fornecedor", "p.fornecedor_id = :fornecedor");
        return motor.executar(c, f.recorte(), f.pagina(), f.tamanho(),
                (rs, n) -> new LinhaSemCodigoDeBarras(rs.getObject("id", UUID.class), rs.getString("nome"),
                        rs.getString("referencia"), rs.getString("codigos"), rs.getString("categoria"),
                        rs.getString("fornecedor"), rs.getBoolean("tem_codigo_do_fornecedor"), rs.getBoolean("ativo")));
    }

    // --- aniversariantes (vendedores) ---

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeAniversariante> aniversariantes(Filtros f) {
        int mes = f.mes() == null ? LocalDate.now(relogio.withZone(BRASILIA)).getMonthValue() : f.mes();
        if (mes < 1 || mes > 12) {
            throw Validacoes.recusar("mes", "Escolha um mês de 1 a 12.");
        }
        Consulta c = new Consulta("""
                v.id, v.nome, v.data_nascimento, extract(day FROM v.data_nascimento)::int AS dia,
                extract(month FROM v.data_nascimento)::int AS mes, coalesce(v.whatsapp, v.telefone1) AS contato,
                v.telefone1, v.whatsapp, pr.nome AS praca, v.ativo""", """
                corona.vendedores v
                LEFT JOIN corona.pracas pr ON pr.id = v.praca_id""",
                "extract(day FROM v.data_nascimento), lower(v.nome), v.id", List.of(contagem(TODOS, "Aniversariantes", null)));
        c.onde("v.deleted_at IS NULL");
        c.onde("v.data_nascimento IS NOT NULL");
        // A data de nascimento é uma data de calendário (sem hora): nunca passa por fuso.
        c.onde("extract(month FROM v.data_nascimento) = :mes", "mes", mes);
        porSituacao(c, f.situacao(), "v.ativo");
        c.busca(f.busca(), "v.nome", "v.telefone1", "v.whatsapp");
        filtroDeUuid(c, f.pracaId(), "praca", "v.praca_id = :praca");
        return motor.executar(c, f.recorte(), f.pagina(), f.tamanho(),
                (rs, n) -> new LinhaDeAniversariante(rs.getObject("id", UUID.class), rs.getString("nome"),
                        rs.getInt("dia"), rs.getInt("mes"), rs.getObject("data_nascimento", LocalDate.class),
                        rs.getString("telefone1"), rs.getString("whatsapp"), rs.getString("praca"),
                        rs.getBoolean("ativo")));
    }

    // --- alterações de produtos, pela auditoria ---

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeAlteracao> alteracoesDeProdutos(Filtros f) {
        DefinicaoDeTotal[] totais = {
                contagem(TODOS, "Cadastros e alterações", null),
                contagem("criados", "Produtos cadastrados", "a.acao = 'PRODUTO_CRIADO'"),
                contagem("preco-venda-alterado", "Preços de venda alterados", "a.acao = 'PRECO_VENDA_ALTERADO'"),
                contagem("preco-custo-alterado", "Custos alterados", "a.acao = 'PRECO_CUSTO_ALTERADO'")};
        Consulta c = new Consulta("""
                a.id, a.acao, a.registro_id AS produto_id, p.nome AS produto, u.nome AS quem, a.ocorrida_em""", """
                corona.auditoria a
                LEFT JOIN corona.produtos p ON p.id = a.registro_id
                LEFT JOIN corona.usuarios u ON u.id = a.ator_usuario_id""", "a.ocorrida_em DESC, a.id", List.of(totais));
        c.onde("a.tabela = 'produtos'");
        c.onde("a.acao IN ('PRODUTO_CRIADO', 'PRECO_VENDA_ALTERADO', 'PRECO_CUSTO_ALTERADO')");
        c.busca(f.busca(), "p.nome", "u.nome");
        periodo(c, "a.ocorrida_em", f);
        return motor.executar(c, f.recorte(), f.pagina(), f.tamanho(), (rs, n) -> {
            String acao = rs.getString("acao");
            return new LinhaDeAlteracao(rs.getObject("id", UUID.class), acao, descricaoDaAcao(acao),
                    rs.getObject("produto_id", UUID.class), rs.getString("produto"), rs.getString("quem"),
                    rs.getObject("ocorrida_em", OffsetDateTime.class).toInstant());
        });
    }

    private static String descricaoDaAcao(String acao) {
        return switch (acao) {
            case "PRODUTO_CRIADO" -> "Produto cadastrado";
            case "PRECO_VENDA_ALTERADO" -> "Preço de venda alterado";
            case "PRECO_CUSTO_ALTERADO" -> "Custo alterado";
            default -> acao;
        };
    }

    // --- resultado das importações (tarefa 1.13) ---

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeImportacao> resultadoDasImportacoes(Filtros f, String tipo, String resultado) {
        Consulta c = new Consulta("""
                l.id, i.id AS lote_id, i.tipo, i.arquivo_nome, l.referencia, l.resultado, l.motivo, u.nome AS quem,
                l.created_at""", """
                corona.importacoes_linhas l
                JOIN corona.importacoes i ON i.id = l.importacao_id
                LEFT JOIN corona.usuarios u ON u.id = i.usuario_id""", "l.created_at DESC, i.id, l.ordem", List.of(
                contagem(TODOS, "Linhas e fotos importadas", null),
                contagem("criados", "Criados", "l.resultado = 'CRIADO'"),
                contagem("atualizados", "Atualizados", "l.resultado = 'ATUALIZADO'"),
                contagem("ja-existiam", "Já existiam", "l.resultado = 'JA_EXISTIA'"),
                contagem("recusados", "Recusados", "l.resultado = 'RECUSADO'")));
        c.busca(f.busca(), "l.referencia", "l.motivo", "i.arquivo_nome", "u.nome");
        if (tipo != null && !tipo.isBlank()) {
            String t = tipo.strip().toUpperCase();
            if (!List.of("PLANILHA", "FOTOS").contains(t)) {
                throw Validacoes.recusar("tipo", "Escolha planilha ou fotos.");
            }
            c.onde("i.tipo = :tipoDoLote", "tipoDoLote", t);
        }
        if (resultado != null && !resultado.isBlank()) {
            String r = resultado.strip().toUpperCase();
            if (!List.of("CRIADO", "ATUALIZADO", "JA_EXISTIA", "RECUSADO").contains(r)) {
                throw Validacoes.recusar("resultado", "Escolha criado, atualizado, já existia ou recusado.");
            }
            c.onde("l.resultado = :resultadoDaLinha", "resultadoDaLinha", r);
        }
        periodo(c, "l.created_at", f);
        return motor.executar(c, f.recorte(), f.pagina(), f.tamanho(), (rs, n) -> {
            String t = rs.getString("tipo");
            String r = rs.getString("resultado");
            return new LinhaDeImportacao(rs.getObject("id", UUID.class), rs.getObject("lote_id", UUID.class), t,
                    "FOTOS".equals(t) ? "Fotos em massa" : "Planilha de produtos", rs.getString("arquivo_nome"),
                    rs.getString("referencia"), r, switch (r) {
                        case "CRIADO" -> "Criado";
                        case "ATUALIZADO" -> "Atualizado";
                        case "JA_EXISTIA" -> "Já existia";
                        default -> "Recusado";
                    }, rs.getString("motivo"), rs.getString("quem"),
                    rs.getObject("created_at", OffsetDateTime.class).toInstant());
        });
    }
}
