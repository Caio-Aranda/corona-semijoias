package br.com.coronasemijoias.api.acertos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * A configuração da comissão, a prévia do vendedor e as pendências depois do fechamento (tarefa 7.5; ADR-0012, itens 5, 6 e 16).
 */
class ComissaoEPendenciasTest extends ApoioDoFechamento {

    private static final String COMISSAO = "/api/dona/configuracoes/comissao";
    private static final String PREVIA = "/api/vendedor/comissao/previa";
    private static final String PENDENCIAS = "/api/dona/pendencias-acerto";

    private UsuarioDeTeste vendedor;
    private UUID cliente;

    @BeforeEach
    void umVendedor() throws Exception {
        vendedor = usuarios.criarVendedor();
        cliente = cliente(vendedor);
    }

    private ResultActions alterar(Object meta, Object abaixo, Object naMeta) throws Exception {
        return mvc.perform(put(COMISSAO).with(dona()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapa("metaGuarani", meta, "percentualAbaixoDaMeta", abaixo, "percentualNaMeta", naMeta))));
    }

    // --- a configuração ---

    @Test
    void lerEMudarComAuditoriaPorChaveMudada() throws Exception {
        ler(COMISSAO, dona()).andExpect(status().isOk()).andExpect(jsonPath("$.metaGuarani").value(700000))
                .andExpect(jsonPath("$.percentualAbaixoDaMeta").value(30)).andExpect(jsonPath("$.percentualNaMeta").value(40));
        int antes = contar("SELECT count(*) FROM corona.auditoria WHERE acao = 'COMISSAO_ALTERADA'");
        alterar(500000, 30, 45).andExpect(status().isOk()).andExpect(jsonPath("$.metaGuarani").value(500000))
                .andExpect(jsonPath("$.percentualNaMeta").value(45));
        // Só a meta e o percentual na meta mudaram: duas linhas de auditoria.
        assertThat(contar("SELECT count(*) FROM corona.auditoria WHERE acao = 'COMISSAO_ALTERADA'")).isEqualTo(antes + 2);
        // Mesmo conteúdo: nada novo.
        alterar(500000, 30, 45).andExpect(status().isOk());
        assertThat(contar("SELECT count(*) FROM corona.auditoria WHERE acao = 'COMISSAO_ALTERADA'")).isEqualTo(antes + 2);
    }

    @Test
    void foraDaFaixaOuAbaixoMaiorQueNaMetaDa400EnaoMudaNada() throws Exception {
        alterar(0, 30, 40).andExpect(status().isBadRequest());
        alterar(1_000_000_001, 30, 40).andExpect(status().isBadRequest());
        alterar(700000, -1, 40).andExpect(status().isBadRequest());
        alterar(700000, 30, 101).andExpect(status().isBadRequest());
        alterar(700000, 41, 40).andExpect(status().isBadRequest());
        ler(COMISSAO, dona()).andExpect(jsonPath("$.metaGuarani").value(700000)).andExpect(jsonPath("$.percentualAbaixoDaMeta").value(30));
    }

    @Test
    void soAdminMudaEVeAConfiguracaoEOVendedorSoVeAPreviaDele() throws Exception {
        ler(COMISSAO, como(vendedor)).andExpect(status().isForbidden());
        mvc.perform(put(COMISSAO).with(como(vendedor)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"metaGuarani\":1,\"percentualAbaixoDaMeta\":1,\"percentualNaMeta\":1}")).andExpect(status().isForbidden());
        ler(PREVIA, dona()).andExpect(status().isForbidden());
    }

    // --- a prévia do vendedor ---

    @Test
    void aPreviaMostraQuantoFaltaEmGuaraniEEmRealEZeraQuandoBateAMeta() throws Exception {
        UUID peca = peca("50.00", 20);
        UUID maleta = maletaConferida(vendedor, semana(), peca, 5);
        vender(vendedor, maleta, cliente, peca, 1, "50.00");
        UsuarioDeTeste outro = usuarios.criarVendedor();

        JsonNode p = corpoDe(ler(PREVIA, como(vendedor)).andExpect(status().isOk()));
        assertThat(p.get("vendidoParaMetaGuarani").asLong()).isEqualTo(55_556L);
        assertThat(p.get("metaGuarani").asInt()).isEqualTo(700_000);
        assertThat(new BigDecimal(p.get("percentualQueValeriaHoje").asString())).isEqualByComparingTo("30");
        assertThat(p.get("faltaParaMetaGuarani").asLong()).isEqualTo(644_444L);
        assertThat(new BigDecimal(p.get("faltaParaMetaReais").asString())).isEqualByComparingTo("580.00");
        assertThat(new BigDecimal(p.get("comissaoPrevista").asString())).isEqualByComparingTo("15.00");

        // Só o que é dele: o outro vendedor não tem nada.
        JsonNode vazio = corpoDe(ler(PREVIA, como(outro)).andExpect(status().isOk()));
        assertThat(vazio.get("vendidoParaMetaGuarani").asLong()).isZero();
        assertThat(vazio.get("faltaParaMetaGuarani").asLong()).isEqualTo(700_000L);

        configurarComissao(50_000, 30, 40);
        JsonNode bateu = corpoDe(ler(PREVIA, como(vendedor)).andExpect(status().isOk()));
        assertThat(bateu.get("faltaParaMetaGuarani").asLong()).isZero();
        assertThat(new BigDecimal(bateu.get("faltaParaMetaReais").asString())).isEqualByComparingTo("0.00");
        assertThat(new BigDecimal(bateu.get("percentualQueValeriaHoje").asString())).isEqualByComparingTo("40");
        assertThat(new BigDecimal(bateu.get("comissaoPrevista").asString())).isEqualByComparingTo("20.00");
    }

    // --- as pendências ---

    /** Fecha um acerto com 1 peça faltando, "cobrar depois": a pendência fica aberta. Devolve o id dela. */
    private UUID faltaAberta(UUID[] pecaDoCenario) throws Exception {
        UUID peca = peca("50.00", 20);
        UUID maleta = maletaConferida(vendedor, semana(), peca, 3);
        UUID acerto = iniciar(vendedor, null);
        bipar(acerto, peca, 2, "BOA");
        fechar(acerto, pedidoDeFechamento(UUID.randomUUID(), List.of(decisaoDeFalta(peca, "COBRAR_DEPOIS", null)), List.of(), "0.00"))
                .andExpect(status().isCreated());
        pecaDoCenario[0] = peca;
        assertThat(maleta).isNotNull();
        return jdbc.queryForObject("SELECT id FROM corona.pendencias_acerto WHERE acerto_id = ? AND tipo = 'FALTA'", UUID.class, acerto);
    }

    private ResultActions resolver(UUID pendencia, UUID comando, String acao, String motivo) throws Exception {
        return postar(PENDENCIAS + "/" + pendencia + "/resolver", dona(), mapa("id", comando, "acao", acao, "motivo", motivo));
    }

    @Test
    void lojaAssumeComMotivoIdempotenteEFinal() throws Exception {
        UUID falta = faltaAberta(new UUID[1]);
        resolver(falta, UUID.randomUUID(), "LOJA_ASSUME", " ").andExpect(status().isBadRequest());
        UUID comando = UUID.randomUUID();
        resolver(falta, comando, "LOJA_ASSUME", "perda da loja").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ASSUMIDA_PELA_LOJA"));
        int auditorias = contar("SELECT count(*) FROM corona.auditoria WHERE acao = 'PENDENCIA_DECIDIDA' AND registro_id = ?", falta);
        resolver(falta, comando, "LOJA_ASSUME", "perda da loja").andExpect(status().isOk());
        assertThat(contar("SELECT count(*) FROM corona.auditoria WHERE acao = 'PENDENCIA_DECIDIDA' AND registro_id = ?", falta))
                .isEqualTo(auditorias);
        resolver(falta, comando, "LOJA_ASSUME", "outro motivo").andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("PENDENCIA_RESOLUCAO_DIFERENTE"));
        resolver(falta, UUID.randomUUID(), "LOJA_ASSUME", "de novo").andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("PENDENCIA_JA_RESOLVIDA"));
    }

    @Test
    void faltaEncontradaVoltaAoEstoqueCentralComOrigemAcerto() throws Exception {
        UUID[] peca = new UUID[1];
        UUID falta = faltaAberta(peca);
        int antes = estoqueAtual(peca[0]);
        resolver(falta, UUID.randomUUID(), "ENCONTRADA", "estava na outra bolsa").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ENCONTRADA"));
        assertThat(estoqueAtual(peca[0])).isEqualTo(antes + 1);
        assertThat(contar("SELECT count(*) FROM corona.movimentacoes_estoque WHERE origem_tipo = 'ACERTO' AND origem_id = ? AND tipo = 'DEVOLUCAO'",
                falta)).isEqualTo(1);
    }

    @Test
    void pendenciaQueNaoExisteDa404EVendedorNaoResolveNemLista() throws Exception {
        resolver(UUID.randomUUID(), UUID.randomUUID(), "LOJA_ASSUME", "x").andExpect(status().isNotFound());
        UUID falta = faltaAberta(new UUID[1]);
        postar(PENDENCIAS + "/" + falta + "/resolver", como(vendedor), mapa("id", UUID.randomUUID(), "acao", "LOJA_ASSUME", "motivo", "x"))
                .andExpect(status().isForbidden());
        ler(PENDENCIAS, como(vendedor)).andExpect(status().isForbidden());
        JsonNode lista = corpoDe(ler(PENDENCIAS + "?vendedorId=" + vendedor.vendedorId() + "&status=ABERTA", dona()).andExpect(status().isOk()));
        assertThat(lista.size()).isEqualTo(1);
    }

    @Test
    void pendenciaAbertaPodeSerCobradaNoProximoFechamento() throws Exception {
        UUID falta = faltaAberta(new UUID[1]);
        UUID peca = peca("50.00", 20);
        UUID maleta = maletaConferida(vendedor, semana(), peca, 1);
        UUID acerto = iniciar(vendedor, null);
        bipar(acerto, peca, 1, "BOA");
        UUID id = UUID.randomUUID();
        var pedido = mapa("id", id, "faltas", List.of(), "defeitos", List.of(), "cobrancas", List.of(),
                "pendenciasAntigas", List.of(mapa("pendenciaId", falta, "decisao", "COBRAR_NO_ACERTO")), "liquidoMostrado", "50.00",
                "pagamento", mapa("restante", mapa("parcelas", 1, "primeiroVencimento", java.time.LocalDate.now().plusDays(30).toString())));
        fechar(acerto, pedido).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT status FROM corona.pendencias_acerto WHERE id = ?", String.class, falta)).isEqualTo("COBRADA");
        assertThat(jdbc.queryForObject("SELECT cobrada_no_acerto_id FROM corona.pendencias_acerto WHERE id = ?", UUID.class, falta))
                .isEqualTo(acerto);
        assertThat(jdbc.queryForObject("SELECT faltas_cobradas FROM corona.acertos WHERE id = ?", BigDecimal.class, acerto))
                .isEqualByComparingTo("50.00");
        assertThat(maleta).isNotNull();
    }
}
