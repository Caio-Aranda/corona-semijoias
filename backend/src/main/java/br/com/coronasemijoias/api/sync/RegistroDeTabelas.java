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
                        // Cadastros básicos (tarefa 1.2). O filtro por vendedor (clientes dele) é da Fase 5.
                        new TabelaSincronizavel("categorias", List.of("nome"), false),
                        new TabelaSincronizavel("semanas", List.of("numero", "inicio", "fim", "situacao"), false),
                        new TabelaSincronizavel("clientes", List.of(
                                "nome", "cpf", "telefone", "whatsapp",
                                "endereco_logradouro", "endereco_numero", "endereco_complemento", "endereco_bairro",
                                "endereco_cidade", "endereco_uf", "endereco_cep",
                                "observacoes", "vendedor_responsavel_id"), false),
                        // Produtos e códigos (tarefa 1.5). Sem custo e sem fornecedor: o vendedor nunca os recebe.
                        // O filtro por maleta (só os produtos dele) é da Fase 5.
                        new TabelaSincronizavel("produtos", List.of(
                                "nome", "referencia", "categoria_id", "preco_venda", "estoque_minimo", "estoque_atual",
                                "ativo", "foto_principal_hash"), false),
                        new TabelaSincronizavel("produto_codigos", List.of(
                                "produto_id", "codigo", "tipo", "principal", "ativo"), false),
                        // Fotos (tarefa 1.6): só o que o vendedor precisa para baixar a imagem. As chaves dos
                        // arquivos e as medidas ficam no servidor.
                        new TabelaSincronizavel("produtos_fotos", List.of(
                                "produto_id", "arquivo_hash", "principal", "ordem"), false)
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
                        new TabelaDoServidor("vendedores_documentos", false)));
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
