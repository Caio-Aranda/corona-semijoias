package br.com.coronasemijoias.api.contaspagar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.estoque.ServicoDeEstoque;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.OrigemDoMovimento;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.Pedido;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.TipoDeMovimento;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/**
 * Fechar semana (tarefa 7.7; ADR-0012, item 17): fecha uma vez, só semana que já terminou e sem acerto em andamento; depois disso
 * recusa alteração, exclusão, acerto, maleta, lançamento e pagamento com data nela; nunca reabre.
 */
class FecharSemanaTest extends ApoioDasContasAPagar {

    private static final AtomicInteger SEQUENCIA = new AtomicInteger(1);
    private static final String SEMANAS = "/api/dona/semanas";

    @Autowired
    ServicoDeEstoque estoque;

    /** Uma semana de 1990 (já terminou), ou do ano 4500 (ainda não começou). */
    private UUID semana(boolean noPassado) throws Exception {
        int n = SEQUENCIA.getAndIncrement();
        LocalDate inicio = noPassado ? LocalDate.of(1990, 1, 1).plusDays(10L * n) : LocalDate.of(4500, 1, 1).plusDays(10L * n);
        return UUID.fromString(corpoDe(postar(SEMANAS, mapa("numero", 94_000 + n, "inicio", inicio.toString(), "fim", inicio.plusDays(6).toString()))
                .andExpect(status().isCreated())).get("id").asString());
    }

    private LocalDate inicioDa(UUID semana) {
        return jdbc.queryForObject("SELECT inicio FROM corona.semanas WHERE id = ?", LocalDate.class, semana);
    }

    private org.springframework.test.web.servlet.ResultActions fechar(UUID semana, UUID id) throws Exception {
        return postar(SEMANAS + "/" + semana + "/fechar", mapa("id", id));
    }

    private UUID acertoEmAndamento(UsuarioDeTeste vendedor, UUID semana) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO corona.acertos (id, vendedor_id, semana_id, status, pedido_pelo_vendedor, iniciado_em, iniciado_por)
                VALUES (?, ?, ?, 'EM_ACERTO', false, now(), ?)""", id, vendedor.vendedorId(), semana, usuarios.dona().id());
        return id;
    }

    private UUID peca(int saldo) throws Exception {
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        UUID categoria = UUID.fromString(corpoDe(postar("/api/dona/categorias", mapa("nome", "Cat " + sufixo)).andExpect(status().isCreated()))
                .get("id").asString());
        UUID id = UUID.fromString(corpoDe(postar("/api/dona/produtos", mapa("nome", "Anel " + sufixo, "categoriaId", categoria, "precoVenda", "50.00",
                "precoCusto", "1.00", "estoqueMinimo", 1, "codigos", List.of(mapa("codigo", "CR-" + sufixo, "tipo", "LOJA", "principal", true))))
                .andExpect(status().isCreated())).get("id").asString());
        estoque.registrar(new Pedido(null, id, TipoDeMovimento.ENTRADA, saldo, null, OrigemDoMovimento.MANUAL, null), usuarios.dona().id());
        return id;
    }

    @Test
    void fechaRegistraAuditaEMudaAVersao() throws Exception {
        UUID semana = semana(true);
        int versaoAntes = jdbc.queryForObject("SELECT version FROM corona.semanas WHERE id = ?", Integer.class, semana);
        UUID id = UUID.randomUUID();
        fechar(semana, id).andExpect(status().isCreated()).andExpect(jsonPath("$.situacao").value("FECHADA"));
        assertThat(jdbc.queryForObject("SELECT situacao FROM corona.semanas WHERE id = ?", String.class, semana)).isEqualTo("FECHADA");
        assertThat(jdbc.queryForObject("SELECT version FROM corona.semanas WHERE id = ?", Integer.class, semana)).isGreaterThan(versaoAntes);
        assertThat(contar("SELECT count(*) FROM corona.fechamentos_semana WHERE id = ? AND semana_id = ?", id, semana)).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM corona.auditoria WHERE acao = 'SEMANA_FECHADA' AND registro_id = ?", semana)).isEqualTo(1);
    }

    @Test
    void reenvioIgualDa200OMesmoIdParaOutraSemanaOuOutroIdDa409() throws Exception {
        UUID semana = semana(true);
        UUID outra = semana(true);
        UUID id = UUID.randomUUID();
        fechar(semana, id).andExpect(status().isCreated());
        fechar(semana, id).andExpect(status().isOk());
        fechar(outra, id).andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("FECHAMENTO_DA_SEMANA_JA_REGISTRADO_DIFERENTE"));
        fechar(semana, UUID.randomUUID()).andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("SEMANA_FECHADA_NAO_MUDA"));
        assertThat(jdbc.queryForObject("SELECT situacao FROM corona.semanas WHERE id = ?", String.class, outra)).isEqualTo("ABERTA");
        fechar(UUID.randomUUID(), UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void semanaQueNaoTerminouNaoFecha() throws Exception {
        UUID futura = semana(false);
        fechar(futura, UUID.randomUUID()).andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("SEMANA_NAO_TERMINOU"));
        assertThat(jdbc.queryForObject("SELECT situacao FROM corona.semanas WHERE id = ?", String.class, futura)).isEqualTo("ABERTA");
    }

    @Test
    void acertoEmAndamentoImpedeEDesistidoNao() throws Exception {
        UUID semana = semana(true);
        UsuarioDeTeste vendedor = usuarios.criarVendedor();
        UUID acerto = acertoEmAndamento(vendedor, semana);
        fechar(semana, UUID.randomUUID()).andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("SEMANA_COM_ACERTO_EM_ANDAMENTO"));
        jdbc.update("""
                UPDATE corona.acertos SET status = 'DESISTIDO', desistencia_id = ?, desistido_em = now(), desistido_por = ?,
                       motivo_desistencia = 'teste' WHERE id = ?""", UUID.randomUUID(), usuarios.dona().id(), acerto);
        fechar(semana, UUID.randomUUID()).andExpect(status().isCreated());
    }

    @Test
    void depoisDeFechadaNaoMudaNaoSeExcluiNemReabre() throws Exception {
        UUID semana = semana(true);
        JsonNode antes = corpoDe(ler(SEMANAS + "/" + semana).andExpect(status().isOk()));
        fechar(semana, UUID.randomUUID()).andExpect(status().isCreated());
        JsonNode depois = corpoDe(ler(SEMANAS + "/" + semana).andExpect(status().isOk()));
        for (String situacao : new String[] {"ABERTA", null}) {
            java.util.Map<String, Object> corpo = mapa("numero", antes.get("numero").asInt() + 1000, "inicio", antes.get("inicio").asString(),
                    "fim", antes.get("fim").asString(), "version", depois.get("version").asInt());
            corpo.put("situacao", situacao);
            mvc.perform(put(SEMANAS + "/" + semana).with(dona()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(corpo)))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("SEMANA_FECHADA_NAO_MUDA"));
        }
        mvc.perform(delete(SEMANAS + "/" + semana).with(dona())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("SEMANA_FECHADA_NAO_MUDA"));
        assertThat(jdbc.queryForObject("SELECT situacao FROM corona.semanas WHERE id = ?", String.class, semana)).isEqualTo("FECHADA");
    }

    @Test
    void formularioNaoFechaNemCriaSemanaFechada() throws Exception {
        UUID semana = semana(true);
        JsonNode atual = corpoDe(ler(SEMANAS + "/" + semana).andExpect(status().isOk()));
        mvc.perform(put(SEMANAS + "/" + semana).with(dona()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                mapa("numero", atual.get("numero").asInt(), "inicio", atual.get("inicio").asString(), "fim", atual.get("fim").asString(),
                        "situacao", "FECHADA", "version", atual.get("version").asInt())))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("situacao"));
        postar(SEMANAS, mapa("numero", 94_999, "inicio", "1985-01-01", "fim", "1985-01-07", "situacao", "FECHADA")).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT situacao FROM corona.semanas WHERE id = ?", String.class, semana)).isEqualTo("ABERTA");
    }

    @Test
    void depoisDeFechadaRecusaAcertoMaletaLancamentoEPagamentoComDataNela() throws Exception {
        UsuarioDeTeste vendedor = usuarios.criarVendedor();
        UUID semana = semana(true);
        // Um rascunho de maleta já montado, antes de fechar: o envio depois de fechada é recusado.
        UUID peca = peca(10);
        UUID rascunho = UUID.randomUUID();
        JsonNode r = corpoDe(postar("/api/dona/remessas", mapa("id", rascunho, "vendedorId", vendedor.vendedorId(), "semanaId", semana, "tipo", "NOVA"))
                .andExpect(status().isCreated()));
        mvc.perform(put("/api/dona/remessas/" + rascunho + "/itens/" + peca).with(dona()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapa("quantidade", 2, "version", r.get("version").asInt())))).andExpect(status().isOk());
        // Uma conta a pagar para tentar pagar com data na semana.
        UUID conta = contasDe(compra(fornecedor(), "30.00"), 1, LocalDate.of(2027, 6, 10)).get(0);
        UUID categoria = UUID.randomUUID();
        postar("/api/dona/caixa/categorias", mapa("id", categoria, "nome", "Cat " + categoria, "tipo", "SAIDA")).andExpect(status().isCreated());

        fechar(semana, UUID.randomUUID()).andExpect(status().isCreated());
        LocalDate dentro = inicioDa(semana).plusDays(2);

        postar("/api/dona/acertos", mapa("id", UUID.randomUUID(), "vendedorId", vendedor.vendedorId(), "semanaId", semana))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("SEMANA_FECHADA"));
        postar("/api/dona/remessas", mapa("id", UUID.randomUUID(), "vendedorId", vendedor.vendedorId(), "semanaId", semana, "tipo", "NOVA"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("SEMANA_FECHADA"));
        postar("/api/dona/remessas/" + rascunho + "/enviar", null).andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("SEMANA_FECHADA"));
        assertThat(jdbc.queryForObject("SELECT status FROM corona.remessas WHERE id = ?", String.class, rascunho)).isEqualTo("RASCUNHO");

        postar("/api/dona/caixa/lancamentos", mapa("id", UUID.randomUUID(), "tipo", "SAIDA", "valor", "5.00", "forma", "DINHEIRO", "categoriaId", categoria,
                "data", dentro, "descricao", "Teste")).andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("DATA_EM_SEMANA_FECHADA"));
        postar(CONTAS + "/" + conta + "/pagamentos", mapa("id", UUID.randomUUID(), "valor", "10.00", "forma", "PIX", "data", dentro))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("DATA_EM_SEMANA_FECHADA"));
        assertThat(contar("SELECT count(*) FROM corona.baixas_pagar WHERE conta_id = ?", conta)).isZero();
        // Com a data de hoje (que nunca cai em semana fechada) segue normal.
        pagar(conta, UUID.randomUUID(), "10.00", "PIX").andExpect(status().isCreated());
    }
}
