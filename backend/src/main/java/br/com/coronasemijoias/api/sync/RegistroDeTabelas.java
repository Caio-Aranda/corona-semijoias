package br.com.coronasemijoias.api.sync;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    /** As tabelas da aplicação. Fase 0: só as cotações (tarefa 19). */
    public static RegistroDeTabelas daAplicacao() {
        return new RegistroDeTabelas(
                List.of(
                        // Cotações do Banco Central (ADR-0006): só de leitura para o app, sem filtro por vendedor.
                        new TabelaSincronizavel("cotacoes", List.of(
                                "data_cotacao",
                                "usd_compra", "usd_venda", "usd_paridade_compra", "usd_paridade_venda",
                                "pyg_compra", "pyg_venda", "pyg_paridade_compra", "pyg_paridade_venda",
                                "buscada_em", "fonte"), true),
                        // Cadastros básicos (tarefa 1.2). O filtro por vendedor (clientes dele) é da Fase 5. A tarefa 1.14 pôs "ativo" em
                        // categorias e clientes e a data de nascimento em clientes: o vendedor cadastra o cliente, então ela vai (só dos dele).
                        new TabelaSincronizavel("categorias", List.of("nome", "ativo"), false),
                        new TabelaSincronizavel("semanas", List.of("numero", "inicio", "fim", "situacao"), false),
                        new TabelaSincronizavel("clientes", List.of(
                                "nome", "cpf", "data_nascimento", "telefone", "whatsapp", "ativo",
                                "endereco_logradouro", "endereco_numero", "endereco_complemento", "endereco_bairro",
                                "endereco_cidade", "endereco_uf", "endereco_cep",
                                "observacoes", "vendedor_responsavel_id"), false),
                        // Produtos e códigos (tarefa 1.5). Sem custo e sem fornecedor: o vendedor nunca os recebe.
                        // O filtro por maleta (só os produtos dele) é da Fase 5. Sem estoque_atual nem estoque_minimo (ADR-0008, item 11).
                        new TabelaSincronizavel("produtos", List.of(
                                "nome", "referencia", "categoria_id", "preco_venda",
                                "ativo", "foto_principal_hash"), false),
                        new TabelaSincronizavel("produto_codigos", List.of(
                                "produto_id", "codigo", "tipo", "principal", "ativo"), false),
                        // Fotos (tarefa 1.6): só o que o vendedor precisa para baixar a imagem. As chaves dos
                        // arquivos e as medidas ficam no servidor.
                        new TabelaSincronizavel("produtos_fotos", List.of(
                                "produto_id", "arquivo_hash", "principal", "ordem"), false),
                        // Remessas (tarefa 3.2, ADR-0010): o vendedor lerá só a própria maleta (Fases 4 e 5, com filtro por
                        // vendedor). Sem custo em nenhuma coluna; dispositivo_id só é preenchido na conferência (Fase 4).
                        new TabelaSincronizavel("remessas", List.of(
                                "vendedor_id", "semana_id", "praca_id", "tipo", "status", "dispositivo_id",
                                "total_pecas", "total_valor", "enviada_em", "motivo_cancelamento"), false),
                        new TabelaSincronizavel("itens_remessa", List.of(
                                "remessa_id", "produto_id", "qtd_enviada", "preco_remessa"), false),
                        new TabelaSincronizavel("movimentos_remessa", List.of(
                                "remessa_id", "produto_id", "tipo", "quantidade", "venda_id", "dispositivo_id",
                                "estorna_id"), true),
                        // Vendas (tarefa 4.2, ADR-0011): do próprio vendedor (filtro na Fase 5). Sem custo.
                        new TabelaSincronizavel("vendas", List.of(
                                "vendedor_id", "cliente_id", "remessa_id", "status", "subtotal", "desconto", "total",
                                "cotacao_id", "vendida_em", "estorno_id", "estornada_em", "motivo_estorno"), false),
                        new TabelaSincronizavel("itens_venda", List.of(
                                "venda_id", "produto_id", "quantidade", "preco_unitario", "desconto"), true),
                        new TabelaSincronizavel("pagamentos_cliente", List.of(
                                "venda_id", "tipo", "forma", "valor", "pago_em"), true),
                        // Conferência da maleta (tarefa 4.3): do próprio vendedor.
                        new TabelaSincronizavel("conferencias", List.of(
                                "remessa_id", "vendedor_id", "dispositivo_id", "divergencias", "conferida_em"), true),
                        new TabelaSincronizavel("itens_conferencia", List.of(
                                "conferencia_id", "produto_id", "esperado", "contado"), true)
                        // As demais sincronizáveis entram aqui (produtos, vendas...).
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
                        new TabelaDoServidor("dispositivos", false)));
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

    private static void exigirNomeNovo(Set<String> nomes, String nome) {
        if (!nomes.add(nome)) {
            throw new IllegalArgumentException("A tabela " + nome + " aparece mais de uma vez no registro.");
        }
    }
}
