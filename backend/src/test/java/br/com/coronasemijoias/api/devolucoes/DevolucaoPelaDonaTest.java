package br.com.coronasemijoias.api.devolucoes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * A dona registra a devolução pelo Corona Gestão (tarefa 6.9; ADR-0014, item 13): a peça vai sempre para a loja (nada
 * entra na maleta do vendedor), o prazo e a maleta em acerto valem como no vendedor, o limite da semana e o valor de
 * aprovação não valem para ela, e a busca da venda pela peça lida vê as vendas de todos os vendedores. Escritos antes do
 * código; cada teste cria os próprios vendedor, cliente, semana, peças e maleta.
 */
class DevolucaoPelaDonaTest extends ApoioDasDevolucoes {

    private int saldo(UUID maleta, UUID produto) {
        return jdbc.queryForObject("""
                SELECT i.qtd_enviada + coalesce((SELECT sum(m.quantidade) FROM corona.movimentos_remessa m
                        WHERE m.remessa_id = i.remessa_id AND m.produto_id = i.produto_id), 0)
                  FROM corona.itens_remessa i WHERE i.remessa_id = ? AND i.produto_id = ? AND i.deleted_at IS NULL""",
                Integer.class, maleta, produto);
    }

    private JsonNode devolverComoDona(UUID id, UUID venda, String motivo, String forma, List<java.util.Map<String, Object>> itens)
            throws Exception {
        return corpoDe(postar(DEVOLUCOES_DA_DONA, dona(), devolucao(id, venda, motivo, forma, itens))
                .andExpect(status().isCreated()));
    }

    @Test
    void donaDevolveEAPecaBoaVaiParaALojaSemMexerNaMaletaDoVendedor() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("50.00", 10);
        UUID maleta = maleta(v, anel, 5);
        UUID venda = vender(v, maleta, cliente(v), List.of(linha(anel, 2)), List.of(pagamento("100.00", "PIX")));
        int saldoAntes = saldo(maleta, anel);
        UUID id = UUID.randomUUID();

        JsonNode d = devolverComoDona(id, venda, "ARREPENDIMENTO", "PIX", List.of(devolvida(anel, 1, "BOA")));

        assertThat(d.get("situacao").asString()).isEqualTo("ACEITA");
        assertThat(d.get("destino").asString()).isEqualTo("REEMBOLSO");
        assertThat(dec(d.get("valorDevolvido"))).isEqualByComparingTo("50.00");
        assertThat(dec(d.get("valorReembolsado"))).isEqualByComparingTo("50.00");
        assertThat(d.get("itens")).hasSize(1);
        assertThat(d.get("itens").get(0).get("destino").asString()).isEqualTo("LOJA");
        assertThat(d.get("vendedorId").asString()).isEqualTo(v.vendedorId().toString());
        // Nada entrou na maleta do vendedor.
        assertThat(saldo(maleta, anel)).isEqualTo(saldoAntes);
        assertThat(contar("SELECT count(*) FROM corona.movimentos_remessa WHERE devolucao_id = ?", id)).isZero();
        // O reembolso e a quem registrou ficam gravados; a venda não muda.
        assertThat(contar("""
                SELECT count(*) FROM corona.pagamentos_cliente
                 WHERE venda_id = ? AND tipo = 'REEMBOLSO' AND valor = -50.00 AND forma = 'PIX' AND devolucao_id = ?""",
                venda, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT registrada_por FROM corona.devolucoes WHERE id = ?", UUID.class, id))
                .isEqualTo(usuarios.dona().id());
        assertThat(jdbc.queryForObject("SELECT status FROM corona.vendas WHERE id = ?", String.class, venda))
                .isEqualTo("CONCLUIDA");
        // A auditoria diz que foi a dona.
        assertThat(contar("""
                SELECT count(*) FROM corona.auditoria
                 WHERE acao = 'DEVOLUCAO_REGISTRADA' AND registro_id = ? AND depois ->> 'registrada_pela_dona' = 'true'""",
                id)).isEqualTo(1);
        // O vendedor vê a devolução na lista dele (é venda dele).
        JsonNode dele = corpoDe(ler(DEVOLUCOES, como(v)).andExpect(status().isOk()));
        assertThat(dele).hasSize(1);
    }

    @Test
    void pecaComDefeitoTambemVaiParaALoja() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("40.00", 10);
        UUID maleta = maleta(v, anel, 3);
        UUID venda = vender(v, maleta, cliente(v), List.of(linha(anel, 1)), List.of(pagamento("40.00", "DINHEIRO")));

        JsonNode d = devolverComoDona(UUID.randomUUID(), venda, "DEFEITO", "DINHEIRO", List.of(devolvida(anel, 1, "DEFEITO")));

        assertThat(d.get("itens").get(0).get("destino").asString()).isEqualTo("LOJA");
        assertThat(d.get("itens").get(0).get("estado").asString()).isEqualTo("DEFEITO");
    }

    @Test
    void mesmoIdEMesmoPedidoDevolve200EOutroConteudoDa409() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("50.00", 10);
        UUID maleta = maleta(v, anel, 5);
        UUID venda = vender(v, maleta, cliente(v), List.of(linha(anel, 2)), List.of(pagamento("100.00", "PIX")));
        UUID id = UUID.randomUUID();
        devolverComoDona(id, venda, "ARREPENDIMENTO", "PIX", List.of(devolvida(anel, 1, "BOA")));

        postar(DEVOLUCOES_DA_DONA, dona(), devolucao(id, venda, "ARREPENDIMENTO", "PIX", List.of(devolvida(anel, 1, "BOA"))))
                .andExpect(status().isOk());
        assertThat(codigoDe(postar(DEVOLUCOES_DA_DONA, dona(),
                devolucao(id, venda, "ARREPENDIMENTO", "PIX", List.of(devolvida(anel, 2, "BOA"))))
                .andExpect(status().isConflict()))).isEqualTo("DEVOLUCAO_JA_REGISTRADA_DIFERENTE");
        assertThat(contar("SELECT count(*) FROM corona.devolucoes WHERE id = ?", id)).isEqualTo(1);
    }

    @Test
    void foraDoPrazoDaGarantiaDa409ComOMesmoCodigo() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("50.00", 10);
        UUID maleta = maleta(v, anel, 5);
        UUID venda = venderEm(v, maleta, cliente(v), anel, 1, "50.00", diasAtras(10));

        assertThat(codigoDe(postar(DEVOLUCOES_DA_DONA, dona(),
                devolucao(UUID.randomUUID(), venda, "ARREPENDIMENTO", "PIX", List.of(devolvida(anel, 1, "BOA"))))
                .andExpect(status().isConflict()))).isEqualTo("GARANTIA_VENCIDA");
        assertThat(contar("SELECT count(*) FROM corona.devolucoes WHERE venda_id = ?", venda)).isZero();
    }

    @Test
    void limiteDaSemanaEValorDeAprovacaoNaoValemParaADona() throws Exception {
        limiteSemanal("10.00");
        valorDeAprovacao("20.00");
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("80.00", 10);
        UUID maleta = maleta(v, anel, 5);
        UUID venda = vender(v, maleta, cliente(v), List.of(linha(anel, 1)), List.of(pagamento("80.00", "PIX")));

        JsonNode d = devolverComoDona(UUID.randomUUID(), venda, "ARREPENDIMENTO", "PIX", List.of(devolvida(anel, 1, "BOA")));

        assertThat(d.get("situacao").asString()).isEqualTo("ACEITA");
        assertThat(d.get("motivosRevisao")).isEmpty();
    }

    @Test
    void maletaEmAcertoRecusaComOMesmoCodigo() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("50.00", 10);
        UUID maleta = maleta(v, anel, 5);
        UUID venda = vender(v, maleta, cliente(v), List.of(linha(anel, 1)), List.of(pagamento("50.00", "PIX")));
        jdbc.update("UPDATE corona.remessas SET status = 'EM_ACERTO' WHERE id = ?", maleta);

        assertThat(codigoDe(postar(DEVOLUCOES_DA_DONA, dona(),
                devolucao(UUID.randomUUID(), venda, "ARREPENDIMENTO", "PIX", List.of(devolvida(anel, 1, "BOA"))))
                .andExpect(status().isConflict()))).isEqualTo("MALETA_EM_ACERTO");
    }

    @Test
    void acimaDoVendidoEVendaInexistenteERecusado() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("50.00", 10);
        UUID maleta = maleta(v, anel, 5);
        UUID venda = vender(v, maleta, cliente(v), List.of(linha(anel, 1)), List.of(pagamento("50.00", "PIX")));

        postar(DEVOLUCOES_DA_DONA, dona(), devolucao(UUID.randomUUID(), venda, "ARREPENDIMENTO", "PIX",
                List.of(devolvida(anel, 2, "BOA")))).andExpect(status().is4xxClientError());
        assertThat(codigoDe(postar(DEVOLUCOES_DA_DONA, dona(), devolucao(UUID.randomUUID(), UUID.randomUUID(), "ARREPENDIMENTO",
                "PIX", List.of(devolvida(anel, 1, "BOA")))).andExpect(status().isNotFound()))).isEqualTo("VENDA_NAO_ENCONTRADA");
        assertThat(contar("SELECT count(*) FROM corona.devolucoes WHERE venda_id = ?", venda)).isZero();
    }

    @Test
    void vendedorDesativadoNaoImpedeADonaDeDevolverUmaVendaDele() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("50.00", 10);
        UUID maleta = maleta(v, anel, 5);
        UUID venda = vender(v, maleta, cliente(v), List.of(linha(anel, 1)), List.of(pagamento("50.00", "PIX")));
        jdbc.update("UPDATE corona.vendedores SET ativo = FALSE WHERE id = ?", v.vendedorId());
        try {
            JsonNode d = devolverComoDona(UUID.randomUUID(), venda, "ARREPENDIMENTO", "PIX", List.of(devolvida(anel, 1, "BOA")));
            assertThat(d.get("situacao").asString()).isEqualTo("ACEITA");
        } finally {
            jdbc.update("UPDATE corona.vendedores SET ativo = TRUE WHERE id = ?", v.vendedorId());
        }
    }

    @Test
    void donaCancelaADevolucaoQueRegistrou() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("50.00", 10);
        UUID maleta = maleta(v, anel, 5);
        UUID venda = vender(v, maleta, cliente(v), List.of(linha(anel, 2)), List.of(pagamento("100.00", "PIX")));
        UUID id = UUID.randomUUID();
        devolverComoDona(id, venda, "ARREPENDIMENTO", "PIX", List.of(devolvida(anel, 1, "BOA")));

        postar(DEVOLUCOES_DA_DONA + "/" + id + "/cancelar", dona(), mapa("id", UUID.randomUUID(), "motivo", "Foi engano"))
                .andExpect(status().isCreated());

        assertThat(situacaoDaDevolucao(id)).isEqualTo("CANCELADA");
        assertThat(somaDosPagamentos(venda)).isEqualByComparingTo("100.00");
    }

    private BigDecimal somaDosPagamentos(UUID venda) {
        return jdbc.queryForObject("SELECT coalesce(sum(valor), 0) FROM corona.pagamentos_cliente WHERE venda_id = ?",
                BigDecimal.class, venda);
    }

    // --- vendas com a peça (a dona vê as de todos os vendedores) ---

    @Test
    void buscaDaDonaAchaAsVendasDeTodosOsVendedoresPeloCodigo() throws Exception {
        UsuarioDeTeste a = usuarios.criarVendedor();
        UsuarioDeTeste b = usuarios.criarVendedor();
        UUID anel = peca("50.00", 20);
        UUID maletaA = maleta(a, anel, 5);
        UUID maletaB = maleta(b, anel, 5);
        UUID vendaA = vender(a, maletaA, cliente(a), List.of(linha(anel, 1)), List.of(pagamento("50.00", "PIX")));
        UUID vendaB = vender(b, maletaB, cliente(b), List.of(linha(anel, 2)), List.of(pagamento("100.00", "PIX")));
        String codigo = jdbc.queryForObject("SELECT codigo FROM corona.produto_codigos WHERE produto_id = ? LIMIT 1",
                String.class, anel);

        JsonNode r = corpoDe(ler(DEVOLUCOES_DA_DONA + "/vendas-com-a-peca?codigo=" + codigo.toLowerCase(), dona())
                .andExpect(status().isOk()));

        assertThat(r.get("peca").get("id").asString()).isEqualTo(anel.toString());
        List<String> ids = new java.util.ArrayList<>();
        r.get("vendas").forEach(x -> ids.add(x.get("vendaId").asString()));
        assertThat(ids).contains(vendaA.toString(), vendaB.toString());
        // Sem código nem peça: pedido malformado.
        ler(DEVOLUCOES_DA_DONA + "/vendas-com-a-peca", dona()).andExpect(status().isBadRequest());
    }

    // --- permissões ---

    @Test
    void vendedorNaoUsaAsRotasNovasDaDona() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        postar(DEVOLUCOES_DA_DONA, como(v), devolucao(UUID.randomUUID(), UUID.randomUUID(), "ARREPENDIMENTO", "PIX",
                List.of(devolvida(UUID.randomUUID(), 1, "BOA")))).andExpect(status().isForbidden());
        ler(DEVOLUCOES_DA_DONA + "/vendas-com-a-peca?codigo=X", como(v)).andExpect(status().isForbidden());
        mvc.perform(post(DEVOLUCOES_DA_DONA).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
    }

    @Test
    void respostaDaDonaNaoTrazCustoNemDadoSensivel() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("50.00", 10);
        UUID maleta = maleta(v, anel, 5);
        UUID venda = vender(v, maleta, cliente(v), List.of(linha(anel, 1)), List.of(pagamento("50.00", "PIX")));
        String texto = json.writeValueAsString(devolverComoDona(UUID.randomUUID(), venda, "ARREPENDIMENTO", "PIX",
                List.of(devolvida(anel, 1, "BOA"))));
        assertThat(texto).doesNotContain("precoCusto").doesNotContain("custo");
    }
}
