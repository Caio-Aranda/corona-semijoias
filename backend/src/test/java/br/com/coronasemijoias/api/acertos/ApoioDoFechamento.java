package br.com.coronasemijoias.api.acertos;

import br.com.coronasemijoias.api.testes.CotacoesDaMassa;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Apoio dos testes do fechamento do acerto (tarefa 7.5): a cotação do exemplo do ADR-0012, a configuração da comissão nos
 * padrões e o SQL direto para montar acertos, eventos, comissões e pendências (os testes do banco). Cada classe que usa isto
 * tira as cotações e os acertos fechados que gravou no fim ({@link CotacoesDaMassa#tirar}).
 */
abstract class ApoioDoFechamento extends ApoioDoAcerto {

    @Autowired
    TransactionTemplate transacao;

    UUID cotacaoDoExemplo;

    @BeforeEach
    void cotacaoEConfiguracaoNosPadroes() {
        cotacaoDoExemplo = CotacoesDaMassa.doExemplo(jdbc);
        configurarComissao(700_000, 30, 40);
    }

    @AfterAll
    static void tirarAsCotacoesEOsAcertosDaMassa() throws SQLException {
        try (var con = conectarComoMigracao()) {
            CotacoesDaMassa.tirar(con);
        }
    }

    /** Meta em Guarani, percentual abaixo da meta e percentual na meta (as duas travas do banco valem no COMMIT). */
    void configurarComissao(int meta, int abaixo, int naMeta) {
        transacao.executeWithoutResult(s -> {
            jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = ? WHERE chave = 'comissao_meta_guarani'", meta);
            jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = ? WHERE chave = 'comissao_percentual_abaixo_da_meta'", abaixo);
            jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = ? WHERE chave = 'comissao_percentual_na_meta'", naMeta);
        });
    }

    // --- SQL direto (testes do banco) ---

    /** Liga um evento ao acerto, como o fechamento faz. Devolve o id do evento. */
    UUID eventoNoBanco(UUID acerto, UsuarioDeTeste vendedor, String tipo, UUID origem, UUID venda, String valor) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO corona.acerto_eventos (id, acerto_id, vendedor_id, origem_tipo, origem_id, venda_id, valor)
                VALUES (?, ?, ?, ?, ?, ?, ?::numeric)""", id, acerto, vendedor.vendedorId(), tipo, origem, venda, valor);
        return id;
    }

    UUID comissaoNoBanco(UUID acerto, UsuarioDeTeste vendedor, UUID venda, UUID evento, String tipo, String base,
            String percentual, String valor, UUID estorna) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO corona.comissoes (id, vendedor_id, acerto_id, venda_id, evento_id, tipo, base, percentual, valor, estorna_id)
                VALUES (?, ?, ?, ?, ?, ?, ?::numeric, ?::numeric, ?::numeric, ?)""", id, vendedor.vendedorId(), acerto, venda, evento,
                tipo, base, percentual, valor, estorna);
        return id;
    }

    /** Pendência gravada direto. {@code cobradaEm} só com status COBRADA. */
    UUID pendenciaNoBanco(UUID acerto, UsuarioDeTeste vendedor, String tipo, UUID produto, UUID maleta, int quantidade,
            String valor, String status, UUID cobradaEm, String motivo) {
        UUID id = UUID.randomUUID();
        boolean resolvida = "ASSUMIDA_PELA_LOJA".equals(status);
        jdbc.update("""
                INSERT INTO corona.pendencias_acerto (id, acerto_id, vendedor_id, tipo, produto_id, remessa_id, quantidade, valor,
                    status, cobrada_no_acerto_id, motivo, resolvida_em, resolvida_por)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::numeric, ?, ?, ?, ?, ?)""", id, acerto, vendedor.vendedorId(), tipo, produto, maleta,
                quantidade, valor, status, cobradaEm, motivo, resolvida ? new java.sql.Timestamp(System.currentTimeMillis()) : null,
                resolvida ? usuarios.dona().id() : null);
        return id;
    }

    /** Fecha o acerto por SQL, com as contas do item 8 feitas aqui (percentual 30%, meta 700.000, nada recebido). */
    void fecharNoBanco(UUID acerto, String bruto, String descontos, String devolvido, String comissao, String faltas) {
        BigDecimal b = new BigDecimal(bruto);
        BigDecimal d = new BigDecimal(descontos);
        BigDecimal dev = new BigDecimal(devolvido);
        BigDecimal c = new BigDecimal(comissao);
        BigDecimal f = new BigDecimal(faltas);
        BigDecimal liquidoVendido = b.subtract(d).subtract(dev);
        BigDecimal liquido = liquidoVendido.subtract(c).add(f);
        jdbc.update("""
                UPDATE corona.acertos SET status = 'ACERTADO', fechamento_id = ?, conteudo_do_fechamento = 'teste',
                       fechado_em = now(), fechado_por = ?, cotacao_id = ?, meta_guarani = 700000, percentual_comissao = 30,
                       vendido_para_meta_guarani = 0, vendido_bruto = ?, descontos = ?, devolvido = ?, vendido_liquido = ?,
                       comissao = ?, faltas_cobradas = ?, liquido = ?, recebido_no_acerto = 0, ficou_devendo = ?
                 WHERE id = ?""", UUID.randomUUID(), usuarios.dona().id(), cotacaoDoExemplo, b, d, dev, liquidoVendido, c, f,
                liquido, liquido, acerto);
    }

    // --- pela API ---

    /** Uma leitura da contagem pela dona. */
    void bipar(UUID acerto, UUID peca, int quantidade, String estado) throws Exception {
        postar(ACERTOS_DA_DONA + "/" + acerto + "/leituras", dona(), mapa("id", UUID.randomUUID(), "produtoId", peca,
                "quantidade", quantidade, "estadoPeca", estado)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
    }

    static java.util.Map<String, Object> decisaoDeFalta(UUID produto, String decisao, String motivo) {
        return mapa("produtoId", produto, "decisao", decisao, "motivo", motivo);
    }

    static java.util.Map<String, Object> defeitoAprovado(UUID produto, int aprovadas) {
        return mapa("produtoId", produto, "aprovadas", aprovadas);
    }

    /** O corpo do fechar: faltas, defeitos, cobranças e pendências antigas (listas vazias quando não há) e o líquido mostrado. */
    static java.util.Map<String, Object> pedidoDeFechamento(UUID id, java.util.List<?> faltas, java.util.List<?> defeitos,
            String liquidoMostrado) {
        return mapa("id", id, "faltas", faltas, "defeitos", defeitos, "cobrancas", java.util.List.of(),
                "pendenciasAntigas", java.util.List.of(), "liquidoMostrado", liquidoMostrado);
    }

    org.springframework.test.web.servlet.ResultActions fechar(UUID acerto, java.util.Map<String, Object> pedido) throws Exception {
        return postar(ACERTOS_DA_DONA + "/" + acerto + "/fechar", dona(), pedido);
    }

    tools.jackson.databind.JsonNode previa(UUID acerto) throws Exception {
        return corpoDe(ler(ACERTOS_DA_DONA + "/" + acerto + "/fechamento", dona())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()));
    }

    int estoqueAtual(UUID peca) {
        return contar("SELECT estoque_atual FROM corona.produtos WHERE id = ?", peca);
    }
}
