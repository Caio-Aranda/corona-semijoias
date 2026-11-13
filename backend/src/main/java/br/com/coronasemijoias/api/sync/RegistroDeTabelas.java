package br.com.coronasemijoias.api.sync;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import br.com.coronasemijoias.api.sync.RegraDoVendedor.Expansao;

/**
 * Lista única das tabelas do schema {@code corona} (ADR-0002, item 3): cada tabela é
 * sincronizável ou só do servidor. O teste de convenções reprova o build se existir no banco uma
 * tabela fora destas listas, ou se uma tabela não tiver as peças do seu grupo.
 *
 * <p>Regra: é sincronizável a tabela cujos registros nascem no Corona Vendas ou chegam a ele.
 * Todas as demais são só do servidor (por exemplo {@code produtos_custos}, que nunca é sincronizada).
 *
 * <p><strong>Ao criar uma tabela</strong>, acrescente-a em {@link #daAplicacao()} na mesma tarefa
 * da migração.
 */
public final class RegistroDeTabelas {

    /** Tabelas de ferramentas, fora das convenções (o teste de convenções as ignora). */
    public static final Set<String> TABELAS_DE_FERRAMENTA = Set.of("flyway_schema_history");

    private final List<TabelaSincronizavel> sincronizaveis;
    private final List<TabelaDoServidor> soDoServidor;

    public RegistroDeTabelas(List<TabelaSincronizavel> sincronizaveis, List<TabelaDoServidor> soDoServidor) {
        this.sincronizaveis = List.copyOf(sincronizaveis);
        this.soDoServidor = List.copyOf(soDoServidor);
        Set<String> nomes = new HashSet<>(TABELAS_DE_FERRAMENTA);
        for (TabelaSincronizavel tabela : this.sincronizaveis) {
            exigirNomeNovo(nomes, tabela.nome());
        }
        for (TabelaDoServidor tabela : this.soDoServidor) {
            exigirNomeNovo(nomes, tabela.nome());
        }
    }

    /** O produto (coluna no {@code %s}) já esteve numa maleta enviada ao vendedor ({@code ?}). Só cresce. */
    private static final String PRODUTO_DE_MALETA = "EXISTS (SELECT 1 FROM corona.itens_remessa ir"
            + " JOIN corona.remessas r ON r.id = ir.remessa_id"
            + " WHERE ir.produto_id = %s AND ir.deleted_at IS NULL AND r.vendedor_id = ? AND r.enviada_em IS NOT NULL)";

    /** A linha é de uma maleta (coluna no {@code %s}) enviada ao vendedor. */
    private static final String DA_MALETA = "EXISTS (SELECT 1 FROM corona.remessas r"
            + " WHERE r.id = %s AND r.vendedor_id = ? AND r.enviada_em IS NOT NULL)";

    /** A linha é de uma venda do vendedor. */
    private static final String DA_VENDA = "EXISTS (SELECT 1 FROM corona.vendas v WHERE v.id = t.venda_id AND v.vendedor_id = ?)";

    /** As tabelas da aplicação, cada sincronizável com a regra do vendedor (tarefa 5.4). */
    public static RegistroDeTabelas daAplicacao() {
        return new RegistroDeTabelas(
                List.of(
                        // Cotações do Banco Central (ADR-0006): públicas, iguais para todos os vendedores.
                        new TabelaSincronizavel("cotacoes", List.of(
                                "data_cotacao",
                                "usd_compra", "usd_venda", "usd_paridade_compra", "usd_paridade_venda",
                                "pyg_compra", "pyg_venda", "pyg_paridade_compra", "pyg_paridade_venda",
                                "buscada_em", "fonte"), true, RegraDoVendedor.daLoja()),
                        // Cadastros básicos (tarefa 1.2). Categorias e semanas são da loja.
                        new TabelaSincronizavel("categorias", List.of("nome", "ativo"), false, RegraDoVendedor.daLoja()),
                        new TabelaSincronizavel("semanas", List.of("numero", "inicio", "fim", "situacao"), false,
                                RegraDoVendedor.daLoja()),
                        // Clientes são da LOJA (11/11/2026): todo vendedor recebe o diretório (nome, CPF, telefone, cidade e
                        // se está ativo); o resto do cadastro (e quem atende) só vai para o vendedor responsável.
                        new TabelaSincronizavel("clientes", List.of(
                                "nome", "cpf", "data_nascimento", "telefone", "whatsapp", "ativo",
                                "endereco_logradouro", "endereco_numero", "endereco_complemento", "endereco_bairro",
                                "endereco_cidade", "endereco_uf", "endereco_cep",
                                "observacoes", "vendedor_responsavel_id"), false,
                                RegraDoVendedor.diretorioDaLoja(Set.of("nome", "cpf", "telefone", "endereco_cidade", "ativo"),
                                        "vendedor_responsavel_id")),
                        // Produtos e códigos (tarefa 1.5). Sem custo e sem fornecedor: o vendedor nunca os recebe. Sem
                        // estoque_atual nem estoque_minimo (ADR-0008, item 11). Vão os produtos que já estiveram numa maleta
                        // enviada ao vendedor (o conjunto só cresce; ADR-0013, item 5).
                        new TabelaSincronizavel("produtos", List.of(
                                "nome", "referencia", "categoria_id", "preco_venda",
                                "ativo", "foto_principal_hash"), false, RegraDoVendedor.doVendedor(PRODUTO_DE_MALETA.formatted("t.id"))),
                        new TabelaSincronizavel("produto_codigos", List.of(
                                "produto_id", "codigo", "tipo", "principal", "ativo"), false,
                                RegraDoVendedor.doVendedor(PRODUTO_DE_MALETA.formatted("t.produto_id"))),
                        // Fotos (tarefa 1.6): só o que o vendedor precisa para baixar a imagem. As chaves dos
                        // arquivos e as medidas ficam no servidor.
                        new TabelaSincronizavel("produtos_fotos", List.of(
                                "produto_id", "arquivo_hash", "principal", "ordem"), false,
                                RegraDoVendedor.doVendedor(PRODUTO_DE_MALETA.formatted("t.produto_id"))),
                        // Remessas (tarefa 3.2, ADR-0010): só as maletas do vendedor já enviadas (rascunho nunca vai). Sem
                        // custo. Ao entregar a maleta, vão junto os itens dela (que foram gravados no rascunho).
                        new TabelaSincronizavel("remessas", List.of(
                                "vendedor_id", "semana_id", "praca_id", "tipo", "status", "dispositivo_id",
                                "total_pecas", "total_valor", "enviada_em", "motivo_cancelamento"), false,
                                RegraDoVendedor.doVendedor("t.vendedor_id = ? AND t.enviada_em IS NOT NULL",
                                        new Expansao("itens_remessa", "remessa_id", "id"))),
                        // Ao entregar o item, vão junto o produto, os códigos e as fotos dele (gravados antes, talvez meses).
                        new TabelaSincronizavel("itens_remessa", List.of(
                                "remessa_id", "produto_id", "qtd_enviada", "preco_remessa"), false,
                                RegraDoVendedor.doVendedor(DA_MALETA.formatted("t.remessa_id"),
                                        new Expansao("produtos", "id", "produto_id"),
                                        new Expansao("produto_codigos", "produto_id", "produto_id"),
                                        new Expansao("produtos_fotos", "produto_id", "produto_id"))),
                        new TabelaSincronizavel("movimentos_remessa", List.of(
                                "remessa_id", "produto_id", "tipo", "quantidade", "venda_id", "dispositivo_id",
                                "estorna_id"), true, RegraDoVendedor.doVendedor(DA_MALETA.formatted("t.remessa_id"))),
                        // Vendas (tarefa 4.2, ADR-0011): só as do próprio vendedor (privadas, mesmo do cliente da loja). Sem custo.
                        new TabelaSincronizavel("vendas", List.of(
                                "vendedor_id", "cliente_id", "remessa_id", "status", "subtotal", "desconto", "total",
                                "cotacao_id", "vendida_em", "estorno_id", "estornada_em", "motivo_estorno"), false,
                                RegraDoVendedor.doVendedor("t.vendedor_id = ?")),
                        new TabelaSincronizavel("itens_venda", List.of(
                                "venda_id", "produto_id", "quantidade", "preco_unitario", "desconto"), true,
                                RegraDoVendedor.doVendedor(DA_VENDA)),
                        new TabelaSincronizavel("pagamentos_cliente", List.of(
                                "venda_id", "tipo", "forma", "valor", "pago_em"), true, RegraDoVendedor.doVendedor(DA_VENDA)),
                        // Conferência da maleta (tarefa 4.3): do próprio vendedor.
                        new TabelaSincronizavel("conferencias", List.of(
                                "remessa_id", "vendedor_id", "dispositivo_id", "divergencias", "conferida_em"), true,
                                RegraDoVendedor.doVendedor("t.vendedor_id = ?")),
                        new TabelaSincronizavel("itens_conferencia", List.of(
                                "conferencia_id", "produto_id", "esperado", "contado"), true,
                                RegraDoVendedor.doVendedor("EXISTS (SELECT 1 FROM corona.conferencias c"
                                        + " WHERE c.id = t.conferencia_id AND c.vendedor_id = ?)")),
                        // Devolução do cliente (tarefa 6.3, ADR-0014): sincronizáveis, mas NADA vai ao vendedor ainda (dúvida Q4):
                        // o Corona Vendas de hoje recusa o lote inteiro se chegar uma tabela que ele não conhece. A tarefa 6.7
                        // troca o FALSE pelo filtro do vendedor, a expansão e o oráculo, e leva também as colunas novas de
                        // pagamentos_cliente (devolucao_id, estorna_id) e movimentos_remessa (devolucao_id), que ficam fora até lá.
                        new TabelaSincronizavel("devolucoes", List.of(
                                "venda_id", "cliente_id", "vendedor_id", "remessa_id", "motivo", "observacao", "destino",
                                "dentro_garantia", "situacao", "autorizacao_id", "valor_devolvido", "valor_reembolsado",
                                "forma_reembolso", "cotacao_id", "devolvida_em", "hora_ajustada", "cancelada_em",
                                "motivo_cancelamento", "revisao_fora_do_prazo", "revisao_acima_do_limite_semanal",
                                "revisao_acima_do_valor_de_aprovacao", "revisao_autorizacao_invalida",
                                "revisao_reembolso_maior_que_pago", "revisada_em", "observacao_revisao"), false,
                                RegraDoVendedor.doVendedor("FALSE")),
                        new TabelaSincronizavel("itens_devolucao", List.of(
                                "devolucao_id", "item_venda_id", "quantidade", "valor", "estado_peca", "destino_peca"), true,
                                RegraDoVendedor.doVendedor("FALSE")),
                        // Autorização da dona (tarefa 6.4, ADR-0014, item 6): como as devoluções, nada vai ao vendedor até a
                        // tarefa 6.7 (que liga o filtro do vendedor e o tipo PEDIDO_AUTORIZACAO no envio).
                        new TabelaSincronizavel("autorizacoes_excecao", List.of(
                                "venda_id", "vendedor_id", "cliente_id", "motivo", "observacao", "tipo", "situacao", "valor_pedido",
                                "cotacao_id", "pedida_em", "hora_ajustada", "decidida_em", "motivo_decisao", "valida_ate"), false,
                                RegraDoVendedor.doVendedor("FALSE")),
                        new TabelaSincronizavel("itens_autorizacao", List.of(
                                "autorizacao_id", "item_venda_id", "quantidade"), true, RegraDoVendedor.doVendedor("FALSE")),
                        // Troca e crédito do cliente (tarefa 6.5, ADR-0014, itens 8 e 9): como as devoluções, nada vai ao
                        // vendedor até a tarefa 6.7 (filtro do vendedor, os tipos TROCA e CANCELAR_TROCA e o formato da venda
                        // com crédito). As colunas novas de tabelas antigas (vendas.troca_id, pagamentos_cliente.credito_id,
                        // devolucoes.valor_em_credito) ficam fora até lá: o Corona Vendas recusa o lote com coluna desconhecida.
                        new TabelaSincronizavel("trocas", List.of(
                                "venda_origem_id", "devolucao_id", "venda_nova_id", "cliente_id", "vendedor_id", "diferenca",
                                "cotacao_id", "trocada_em", "hora_ajustada", "situacao", "cancelada_em", "motivo_cancelamento"),
                                false, RegraDoVendedor.doVendedor("FALSE")),
                        new TabelaSincronizavel("creditos_cliente", List.of(
                                "cliente_id", "vendedor_id", "origem", "troca_id", "devolucao_id", "credito_origem_id", "valor",
                                "valor_utilizado", "situacao", "cotacao_id", "gerado_em", "hora_ajustada", "cancelado_em"),
                                false, RegraDoVendedor.doVendedor("FALSE")),
                        new TabelaSincronizavel("usos_credito", List.of(
                                "credito_id", "tipo", "venda_id", "pagamento_id", "credito_destino_id", "valor", "estorna_id",
                                "usado_em"), true, RegraDoVendedor.doVendedor("FALSE"))
                        // Tabela sincronizável nova: declare a regra do vendedor (filtro, expansão, diretório).
                ),
                List.of(
                        new TabelaDoServidor("sync_estado", false),
                        new TabelaDoServidor("perfis", true),
                        new TabelaDoServidor("usuarios", false),
                        new TabelaDoServidor("configuracoes", false),
                        new TabelaDoServidor("sessoes", false),
                        new TabelaDoServidor("tokens_de_renovacao", false),
                        new TabelaDoServidor("tentativas_login", true),
                        // Auditoria (tarefa 1.4): histórico de quem fez o quê, só inserção.
                        new TabelaDoServidor("auditoria", true),
                        // Resultado das importações em massa (tarefa 1.13): histórico, só inserção.
                        new TabelaDoServidor("importacoes", true),
                        new TabelaDoServidor("importacoes_linhas", true),
                        // Movimentações do estoque central (tarefa 2.2): histórico imutável, nunca vai ao vendedor.
                        new TabelaDoServidor("movimentacoes_estoque", true),
                        // Compras (tarefa 2.3): custo e fornecedor são só da dona, nunca vão ao vendedor.
                        new TabelaDoServidor("compras", false),
                        new TabelaDoServidor("itens_compra", true),
                        new TabelaDoServidor("historico_custo", true),
                        // Inventário (tarefa 2.4): contagem do estoque central, só da dona.
                        new TabelaDoServidor("inventarios", false),
                        new TabelaDoServidor("itens_inventario", false),
                        new TabelaDoServidor("leituras_inventario", true),
                        // Cadastros que o vendedor nunca recebe (ESPECIFICACAO §10): fornecedores, praças, restrições.
                        new TabelaDoServidor("fornecedores", false),
                        new TabelaDoServidor("pracas", false),
                        new TabelaDoServidor("restricoes", false),
                        // Preço de custo (tarefa 1.5): só do servidor, NUNCA sincronizada.
                        new TabelaDoServidor("produtos_custos", false),
                        // Cadastro do vendedor e abas (tarefa 1.3): dados de pessoas, nunca vão ao Corona Vendas.
                        new TabelaDoServidor("vendedores", false),
                        new TabelaDoServidor("vendedores_enderecos", false),
                        new TabelaDoServidor("vendedores_contatos", false),
                        new TabelaDoServidor("vendedores_trabalhos", false),
                        new TabelaDoServidor("vendedores_conjuges", false),
                        new TabelaDoServidor("vendedores_mei", false),
                        new TabelaDoServidor("vendedores_observacoes", false),
                        new TabelaDoServidor("vendedores_referencias", false),
                        new TabelaDoServidor("vendedores_documentos", false),
                        // Computadores do Corona Vendas (tarefa 5.3, ADR-0013 item 13): só do servidor, nunca vão ao app.
                        new TabelaDoServidor("dispositivos", false),
                        // Registro dos envios do Corona Vendas (tarefa 5.5, ADR-0013 item 2): só do servidor.
                        new TabelaDoServidor("sync_log", false),
                        // Envios de um computador bloqueado, guardados sem aplicar (tarefa 5.8, ADR-0013 item 14): só do servidor.
                        new TabelaDoServidor("envios_em_quarentena", false)));
    }

    /** Este registro somado a outro (usado pelos testes para acrescentar as tabelas de teste). */
    public RegistroDeTabelas com(RegistroDeTabelas outro) {
        List<TabelaSincronizavel> todasSincronizaveis = new ArrayList<>(sincronizaveis);
        todasSincronizaveis.addAll(outro.sincronizaveis);
        List<TabelaDoServidor> todasDoServidor = new ArrayList<>(soDoServidor);
        todasDoServidor.addAll(outro.soDoServidor);
        return new RegistroDeTabelas(todasSincronizaveis, todasDoServidor);
    }

    public List<TabelaSincronizavel> sincronizaveis() {
        return sincronizaveis;
    }

    public List<TabelaDoServidor> soDoServidor() {
        return soDoServidor;
    }

    /** A sincronizável pelo nome, se existir. */
    public java.util.Optional<TabelaSincronizavel> sincronizavel(String nome) {
        return sincronizaveis.stream().filter(t -> t.nome().equals(nome)).findFirst();
    }

    private static void exigirNomeNovo(Set<String> nomes, String nome) {
        if (!nomes.add(nome)) {
            throw new IllegalArgumentException("A tabela " + nome + " aparece mais de uma vez no registro.");
        }
    }
}
