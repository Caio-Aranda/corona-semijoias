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

    /** A linha é de uma nota do consignado do vendedor. */
    private static final String DA_NOTA = "EXISTS (SELECT 1 FROM corona.notas_consignado n"
            + " WHERE n.id = %s AND n.vendedor_id = ?)";

    /** A linha é de uma devolução do vendedor. */
    private static final String DA_DEVOLUCAO = "EXISTS (SELECT 1 FROM corona.devolucoes d"
            + " WHERE d.id = t.devolucao_id AND d.vendedor_id = ?)";

    /** As tabelas da aplicação, cada sincronizável com a regra do vendedor (tarefa 5.4). */
    public static RegistroDeTabelas daAplicacao() {
        return new RegistroDeTabelas(
                List.of(
                        // Cotações do Banco Central e da dona (ADR-0006, itens 4 e 15): iguais para todos os vendedores. "lancada_por" nunca vai.
                        new TabelaSincronizavel("cotacoes", List.of(
                                "data_cotacao",
                                "usd_compra", "usd_venda", "usd_paridade_compra", "usd_paridade_venda",
                                "pyg_compra", "pyg_venda", "pyg_paridade_compra", "pyg_paridade_venda",
                                "buscada_em", "fonte", "origem", "vigente_desde", "guaranis_por_real"), true,
                                RegraDoVendedor.daLoja()),
                        // Cadastros básicos (tarefa 1.2). Categorias e semanas são da loja.
                        new TabelaSincronizavel("categorias", List.of("nome", "ativo"), false, RegraDoVendedor.daLoja()),
                        new TabelaSincronizavel("semanas", List.of("numero", "inicio", "fim", "situacao"), false,
                                RegraDoVendedor.daLoja()),
                        // Clientes (tarefa 9.1; ADR-0016, item 4): cada representante recebe SÓ a sua lista, com o cadastro todo
                        // (isto reverte, para o Corona Vendas, o diretório da loja de 11/11/2026). Nunca vão clientes de outro.
                        new TabelaSincronizavel("clientes", List.of(
                                "nome", "cpf", "data_nascimento", "telefone", "whatsapp", "ativo",
                                "endereco_logradouro", "endereco_numero", "endereco_complemento", "endereco_bairro",
                                "endereco_cidade", "endereco_uf", "endereco_cep",
                                "observacoes", "vendedor_responsavel_id",
                                // Consignado ao cliente (tarefa 9.1, V049): documento, indicação, comissão e hashes das fotos.
                                "documento", "documento_tipo", "indicado_por_cliente_id", "indicado_por_nome", "como_conheceu",
                                "comissao_percentual", "foto_hash", "foto_documento_frente_hash", "foto_documento_verso_hash"), false,
                                RegraDoVendedor.doVendedor("t.vendedor_responsavel_id = ?")),
                        // Produtos e códigos (tarefa 1.5). Sem custo e sem fornecedor: o vendedor nunca os recebe. Sem
                        // estoque_atual nem estoque_minimo (ADR-0008, item 11). Vão os produtos que já estiveram numa maleta
                        // enviada ao vendedor (o conjunto só cresce; ADR-0013, item 5).
                        new TabelaSincronizavel("produtos", List.of(
                                "nome", "referencia", "categoria_id", "preco_venda",
                                "ativo", "foto_principal_hash", "tam_ton"), false, RegraDoVendedor.doVendedor(PRODUTO_DE_MALETA.formatted("t.id"))),
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
                                "total_pecas", "total_valor", "enviada_em", "motivo_cancelamento",
                                // Consignado ao cliente (tarefa 9.3, V053): código da maleta e nome da praça (só no formato 2).
                                "codigo", "praca_nome"), false,
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
                                "estorna_id", "devolucao_id", "nota_id", "acerto_nota_id"), true,
                                RegraDoVendedor.doVendedor(DA_MALETA.formatted("t.remessa_id"))),
                        // Vendas (tarefa 4.2, ADR-0011): só as do próprio vendedor (privadas, mesmo do cliente da loja). Sem custo.
                        new TabelaSincronizavel("vendas", List.of(
                                "vendedor_id", "cliente_id", "remessa_id", "status", "subtotal", "desconto", "total",
                                "cotacao_id", "vendida_em", "estorno_id", "estornada_em", "motivo_estorno", "troca_id"), false,
                                RegraDoVendedor.doVendedor("t.vendedor_id = ?")),
                        new TabelaSincronizavel("itens_venda", List.of(
                                "venda_id", "produto_id", "quantidade", "preco_unitario", "desconto"), true,
                                RegraDoVendedor.doVendedor(DA_VENDA)),
                        new TabelaSincronizavel("pagamentos_cliente", List.of(
                                "venda_id", "tipo", "forma", "valor", "pago_em", "devolucao_id", "estorna_id", "credito_id", "posterior"), true,
                                RegraDoVendedor.doVendedor(DA_VENDA)),
                        // Conferência da maleta (tarefa 4.3): do próprio vendedor.
                        new TabelaSincronizavel("conferencias", List.of(
                                "remessa_id", "vendedor_id", "dispositivo_id", "divergencias", "conferida_em"), true,
                                RegraDoVendedor.doVendedor("t.vendedor_id = ?")),
                        new TabelaSincronizavel("itens_conferencia", List.of(
                                "conferencia_id", "produto_id", "esperado", "contado"), true,
                                RegraDoVendedor.doVendedor("EXISTS (SELECT 1 FROM corona.conferencias c"
                                        + " WHERE c.id = t.conferencia_id AND c.vendedor_id = ?)")),
                        // Fase 6 (tarefa 6.7; ADR-0014, item 14): devolução, autorização, troca e crédito do próprio vendedor. A
                        // pertença vem de uma coluna que nunca muda e já existe quando a linha nasce (nenhuma expansão nova): o
                        // vendedor_id copiado da venda, ou a linha mãe gravada na mesma transação. A devolução que a dona registrar
                        // de uma venda do vendedor também vai a ele. Nunca vão os "*_por" (quem registrou, revisou ou decidiu).
                        new TabelaSincronizavel("devolucoes", List.of(
                                "venda_id", "cliente_id", "vendedor_id", "remessa_id", "motivo", "observacao", "destino",
                                "dentro_garantia", "situacao", "autorizacao_id", "valor_devolvido", "valor_reembolsado",
                                "valor_em_credito", "valor_abatido", "forma_reembolso", "cotacao_id", "devolvida_em", "hora_ajustada",
                                "cancelamento_id", "cancelada_em", "motivo_cancelamento", "revisao_fora_do_prazo",
                                "revisao_acima_do_limite_semanal", "revisao_acima_do_valor_de_aprovacao",
                                "revisao_autorizacao_invalida", "revisao_reembolso_maior_que_pago", "revisada_em",
                                "observacao_revisao"), false, RegraDoVendedor.doVendedor("t.vendedor_id = ?")),
                        new TabelaSincronizavel("itens_devolucao", List.of(
                                "devolucao_id", "item_venda_id", "quantidade", "valor", "estado_peca", "destino_peca"), true,
                                RegraDoVendedor.doVendedor(DA_DEVOLUCAO)),
                        new TabelaSincronizavel("autorizacoes_excecao", List.of(
                                "venda_id", "vendedor_id", "cliente_id", "motivo", "observacao", "tipo", "situacao", "valor_pedido",
                                "cotacao_id", "pedida_em", "hora_ajustada", "decidida_em", "motivo_decisao", "valida_ate"), false,
                                RegraDoVendedor.doVendedor("t.vendedor_id = ?")),
                        new TabelaSincronizavel("itens_autorizacao", List.of(
                                "autorizacao_id", "item_venda_id", "quantidade"), true,
                                RegraDoVendedor.doVendedor("EXISTS (SELECT 1 FROM corona.autorizacoes_excecao a"
                                        + " WHERE a.id = t.autorizacao_id AND a.vendedor_id = ?)")),
                        new TabelaSincronizavel("trocas", List.of(
                                "venda_origem_id", "devolucao_id", "venda_nova_id", "cliente_id", "vendedor_id", "diferenca",
                                "cotacao_id", "trocada_em", "hora_ajustada", "situacao", "cancelamento_id", "cancelada_em",
                                "motivo_cancelamento"), false, RegraDoVendedor.doVendedor("t.vendedor_id = ?")),
                        // Só quem pode usar o crédito o recebe. Sem credito_origem_id: no crédito transferido, ele aponta para o
                        // crédito do vendedor antigo (dúvida S4).
                        new TabelaSincronizavel("creditos_cliente", List.of(
                                "cliente_id", "vendedor_id", "origem", "troca_id", "devolucao_id", "valor", "valor_utilizado",
                                "situacao", "cotacao_id", "gerado_em", "hora_ajustada", "cancelado_em"), false,
                                RegraDoVendedor.doVendedor("t.vendedor_id = ?")),
                        // Sem credito_destino_id: no uso de transferência, ele aponta para o crédito do vendedor novo (dúvida S4).
                        new TabelaSincronizavel("usos_credito", List.of(
                                "credito_id", "tipo", "venda_id", "pagamento_id", "valor", "estorna_id", "usado_em"), true,
                                RegraDoVendedor.doVendedor("EXISTS (SELECT 1 FROM corona.creditos_cliente c"
                                        + " WHERE c.id = t.credito_id AND c.vendedor_id = ?)")),
                        // Venda paga pelo envio com crédito acima do saldo, para a dona revisar (tarefa 6.7, V039): pela venda.
                        new TabelaSincronizavel("creditos_sem_saldo", List.of(
                                "venda_id", "pagamento_id", "credito_id", "valor_pedido", "valor_usado", "situacao", "registrada_em",
                                "revisada_em", "observacao_revisao"), false, RegraDoVendedor.doVendedor(DA_VENDA)),
                        // Contas a receber (tarefa 7.3, V041; nível 2 na 7.6, V045): as parcelas do cliente (pela venda do vendedor) e as
                        // contas que o próprio vendedor deve à loja (vendedor_id), com as baixas dos dois níveis.
                        new TabelaSincronizavel("contas_receber", List.of(
                                "devedor_tipo", "cliente_id", "vendedor_id", "origem_tipo", "origem_id", "venda_id", "parcela",
                                "total_parcelas", "vencimento", "valor", "valor_pago", "valor_cancelado", "status", "forma_prevista"), false,
                                RegraDoVendedor.doVendedor("t.vendedor_id = ? OR EXISTS (SELECT 1 FROM corona.vendas v"
                                        + " WHERE v.id = t.venda_id AND v.vendedor_id = ?)")),
                        new TabelaSincronizavel("baixas_receber", List.of(
                                "conta_id", "tipo", "valor", "baixada_em", "pagamento_cliente_id", "recebimento_vendedor_id",
                                "motivo_cancelamento", "devolucao_id", "estorna_id"), true,
                                RegraDoVendedor.doVendedor("EXISTS (SELECT 1 FROM corona.contas_receber c"
                                        + " LEFT JOIN corona.vendas v ON v.id = c.venda_id"
                                        + " WHERE c.id = t.conta_id AND (c.vendedor_id = ? OR v.vendedor_id = ?))")),
                        // O que o vendedor pagou à dona (tarefa 7.6, V045): só o dele. Nunca vão quem registrou, o motivo do estorno nem
                        // o resumo do pedido. O caixa e as categorias são só do servidor (nunca vão ao vendedor).
                        new TabelaSincronizavel("recebimentos_vendedor", List.of(
                                "vendedor_id", "momento", "acerto_id", "aplicado_em", "forma", "valor", "recebido_em", "estorna_id"), true,
                                RegraDoVendedor.doVendedor("t.vendedor_id = ?")),
                        // Acerto, parte 1 (tarefa 7.4, V043): o acerto do vendedor e a contagem dele. Nunca vão os "*_por" (quem
                        // iniciou, desistiu ou leu) nem remessas.acerto_id (as maletas dele chegam com status EM_ACERTO).
                        // Acerto, parte 2 (tarefa 7.5, V044): o fechamento e os totais chegam; nunca fechamento_id nem o resumo das
                        // decisões (conteudo_do_fechamento) nem fechado_por.
                        new TabelaSincronizavel("acertos", List.of(
                                "vendedor_id", "semana_id", "status", "pedido_pelo_vendedor", "iniciado_em", "desistido_em",
                                "motivo_desistencia", "fechado_em", "cotacao_id", "meta_guarani", "percentual_comissao",
                                "vendido_para_meta_guarani", "vendido_bruto", "descontos", "devolvido", "vendido_liquido", "comissao",
                                "faltas_cobradas", "liquido", "recebido_no_acerto", "ficou_devendo", "pago_ao_vendedor", "credito_usado",
                                "credito_do_acerto_id"), false,
                                RegraDoVendedor.doVendedor("t.vendedor_id = ?")),
                        new TabelaSincronizavel("leituras_acerto", List.of(
                                "acerto_id", "produto_id", "quantidade", "estado_peca", "lida_em", "pelo_vendedor", "dispositivo_id"), true,
                                RegraDoVendedor.doVendedor("EXISTS (SELECT 1 FROM corona.acertos a"
                                        + " WHERE a.id = t.acerto_id AND a.vendedor_id = ?)")),
                        // O que o fechamento grava (tarefa 7.5): esperado x contado, eventos, comissões e pendências. Só do vendedor
                        // dono. O preco_remessa é preço de venda; nunca vai custo. Sem "*_por" nem o motivo da loja.
                        new TabelaSincronizavel("itens_acerto", List.of(
                                "acerto_id", "produto_id", "remessa_id", "preco_remessa", "esperado", "contado_boa", "contado_defeito",
                                "falta", "defeito_aprovado"), true,
                                RegraDoVendedor.doVendedor("EXISTS (SELECT 1 FROM corona.acertos a"
                                        + " WHERE a.id = t.acerto_id AND a.vendedor_id = ?)")),
                        new TabelaSincronizavel("acerto_eventos", List.of(
                                "acerto_id", "vendedor_id", "origem_tipo", "origem_id", "venda_id", "valor"), true,
                                RegraDoVendedor.doVendedor("t.vendedor_id = ?")),
                        new TabelaSincronizavel("comissoes", List.of(
                                "vendedor_id", "acerto_id", "venda_id", "evento_id", "tipo", "base", "percentual", "valor", "estorna_id"),
                                true, RegraDoVendedor.doVendedor("t.vendedor_id = ?")),
                        new TabelaSincronizavel("pendencias_acerto", List.of(
                                "acerto_id", "vendedor_id", "tipo", "produto_id", "remessa_id", "quantidade", "valor", "origem_tipo",
                                "origem_id", "status", "cobrada_no_acerto_id", "resolvida_em"), false,
                                RegraDoVendedor.doVendedor("t.vendedor_id = ?")),
                        // Consignado ao cliente (tarefa 9.1, V050 e V051; ADR-0016, item 13): só do representante dono, pela coluna
                        // vendedor_id da linha ou da mãe, que nunca muda. Nunca vão alertas, numeros_da_semana nem fechamento.
                        new TabelaSincronizavel("semanas_vendedor", List.of(
                                "vendedor_id", "numero", "aberta_em", "fechada_em", "situacao", "dispositivo_id"), false,
                                RegraDoVendedor.doVendedor("t.vendedor_id = ?")),
                        new TabelaSincronizavel("notas_consignado", List.of(
                                "vendedor_id", "semana_id", "sequencia", "cliente_id", "saida_em", "data_cobranca", "nota_origem_id",
                                "situacao", "cotacao_id", "dispositivo_id", "hora_ajustada"), false,
                                RegraDoVendedor.doVendedor("t.vendedor_id = ?")),
                        new TabelaSincronizavel("reposicoes_nota", List.of("nota_id", "feita_em", "dispositivo_id", "hora_ajustada"),
                                true, RegraDoVendedor.doVendedor(DA_NOTA.formatted("t.nota_id"))),
                        new TabelaSincronizavel("trocas_nota", List.of("nota_id", "trocada_em", "dispositivo_id", "hora_ajustada"),
                                true, RegraDoVendedor.doVendedor(DA_NOTA.formatted("t.nota_id"))),
                        new TabelaSincronizavel("itens_nota_consignado", List.of(
                                "nota_id", "produto_id", "remessa_id", "origem", "quantidade", "preco_unitario", "reposicao_id",
                                "troca_id"), true, RegraDoVendedor.doVendedor(DA_NOTA.formatted("t.nota_id"))),
                        new TabelaSincronizavel("remarcacoes_nota", List.of(
                                "nota_id", "data_cobranca_anterior", "data_cobranca_nova", "remarcada_em", "dispositivo_id",
                                "hora_ajustada"), true, RegraDoVendedor.doVendedor(DA_NOTA.formatted("t.nota_id"))),
                        new TabelaSincronizavel("acertos_nota", List.of(
                                "nota_id", "vendedor_id", "semana_id", "acertado_em", "registrado_em", "comissao_percentual", "itens_dx", "itens_dv",
                                "itens_vd", "valor_dx", "valor_dv", "valor_vd", "comissao", "bruto", "pendencias_anteriores",
                                "total_a_receber", "total_pago", "ficou_devendo", "recibo_id", "cotacao_id", "dispositivo_id",
                                "hora_ajustada", "estornado_em", "estorno_id", "motivo_estorno"), false,
                                RegraDoVendedor.doVendedor("t.vendedor_id = ?")),
                        new TabelaSincronizavel("itens_acerto_nota", List.of(
                                "acerto_id", "produto_id", "remessa_id", "qtd_dx", "qtd_dv", "preco_unitario"), true,
                                RegraDoVendedor.doVendedor("EXISTS (SELECT 1 FROM corona.acertos_nota a"
                                        + " WHERE a.id = t.acerto_id AND a.vendedor_id = ?)")),
                        new TabelaSincronizavel("recibos_consignado", List.of(
                                "vendedor_id", "cliente_id", "semana_id", "sequencia", "acerto_id", "pago_em", "total", "cotacao_id",
                                "dispositivo_id", "hora_ajustada", "estornado_em", "estorno_id", "motivo_estorno"), false,
                                RegraDoVendedor.doVendedor("t.vendedor_id = ?")),
                        new TabelaSincronizavel("formas_recibo_consignado", List.of("recibo_id", "forma", "valor"), true,
                                RegraDoVendedor.doVendedor("EXISTS (SELECT 1 FROM corona.recibos_consignado r"
                                        + " WHERE r.id = t.recibo_id AND r.vendedor_id = ?)")),
                        new TabelaSincronizavel("aplicacoes_recibo", List.of("recibo_id", "acerto_id", "valor", "estorna_id"), true,
                                RegraDoVendedor.doVendedor("EXISTS (SELECT 1 FROM corona.recibos_consignado r"
                                        + " WHERE r.id = t.recibo_id AND r.vendedor_id = ?)"))
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
                        new TabelaDoServidor("envios_em_quarentena", false),
                        // Caixa automático (tarefa 7.6, V045): nunca vai ao aplicativo do vendedor.
                        new TabelaDoServidor("categorias_caixa", false),
                        new TabelaDoServidor("lancamentos_caixa", true),
                        // Contas a pagar, caixa manual e fechar semana (tarefa 7.7, V046): nunca vão ao aplicativo do vendedor.
                        new TabelaDoServidor("contas_pagar", false),
                        new TabelaDoServidor("baixas_pagar", true),
                        new TabelaDoServidor("fechamentos_semana", true),
                        // Rodadas da conferência diária (tarefa 8.4, V047): só do servidor, imutáveis, nunca vão ao aplicativo.
                        new TabelaDoServidor("rodadas_conferencia", true),
                        new TabelaDoServidor("diferencas_conferencia", true),
                        // Consignado ao cliente (tarefa 9.1, V050 a V052): só do servidor, nunca vão ao aplicativo do vendedor.
                        new TabelaDoServidor("numeros_da_semana", true),
                        new TabelaDoServidor("alertas_consignado", false),
                        new TabelaDoServidor("fechamentos_maleta", false),
                        new TabelaDoServidor("leituras_fechamento_maleta", true),
                        new TabelaDoServidor("fotos_de_cliente", true),
                        // Falta do Fechamento da maleta (V062; §23.10, item 1): só do servidor, imutável.
                        new TabelaDoServidor("pendencias_da_maleta", true),
                        // O que o representante pagou da falta cobrada (V063; §23.10, item 1a): só do servidor, imutável.
                        new TabelaDoServidor("pagamentos_cobranca_maleta", true)));
    }

    /** As tabelas novas do consignado ao cliente (tarefa 9.1): o Corona Vendas que não conhece o formato 2 nunca as recebe. */
    public static final Set<String> TABELAS_DO_CONSIGNADO = Set.of("semanas_vendedor", "notas_consignado", "reposicoes_nota",
            "trocas_nota", "itens_nota_consignado", "remarcacoes_nota", "acertos_nota", "itens_acerto_nota", "recibos_consignado",
            "formas_recibo_consignado", "aplicacoes_recibo");

    /**
     * A visão do formato 1 do pull, para o Corona Vendas que ainda não conhece o consignado (ADR-0016, item 13): sem as tabelas
     * novas e com as colunas e a regra dos clientes e dos movimentos da maleta como eram antes. O app antigo recusa o lote todo
     * se vier tabela ou coluna que ele não conhece, então o servidor NUNCA manda a ele o que é novo. O app que conhece pede
     * {@code formato=2} (a regra completa, que é a deste registro).
     */
    public RegistroDeTabelas paraOFormato1() {
        List<TabelaSincronizavel> antigas = new ArrayList<>();
        for (TabelaSincronizavel t : sincronizaveis) {
            if (TABELAS_DO_CONSIGNADO.contains(t.nome())) {
                continue;
            }
            antigas.add(switch (t.nome()) {
                case "clientes" -> new TabelaSincronizavel("clientes", List.of(
                        "nome", "cpf", "data_nascimento", "telefone", "whatsapp", "ativo",
                        "endereco_logradouro", "endereco_numero", "endereco_complemento", "endereco_bairro",
                        "endereco_cidade", "endereco_uf", "endereco_cep",
                        "observacoes", "vendedor_responsavel_id"), false,
                        RegraDoVendedor.diretorioDaLoja(Set.of("nome", "cpf", "telefone", "endereco_cidade", "ativo"),
                                "vendedor_responsavel_id"));
                // O Tam./Ton. (9.8) só vai no formato 2: o app antigo recusaria o lote por causa da coluna que não conhece.
                case "produtos" -> new TabelaSincronizavel("produtos", List.of(
                        "nome", "referencia", "categoria_id", "preco_venda", "ativo", "foto_principal_hash"), false, t.regra());
                case "remessas" -> new TabelaSincronizavel("remessas", List.of(
                        "vendedor_id", "semana_id", "praca_id", "tipo", "status", "dispositivo_id",
                        "total_pecas", "total_valor", "enviada_em", "motivo_cancelamento"), false, t.regra());
                case "movimentos_remessa" -> new TabelaSincronizavel("movimentos_remessa", List.of(
                        "remessa_id", "produto_id", "tipo", "quantidade", "venda_id", "dispositivo_id",
                        "estorna_id", "devolucao_id"), true, t.regra());
                default -> t;
            });
        }
        return new RegistroDeTabelas(antigas, soDoServidor);
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
