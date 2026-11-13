package br.com.coronasemijoias.api.devolucoes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.ModoDaDevolucao;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.PecaDoPedido;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;

/**
 * Pedido de autorização à dona, aprovação, negação, uso numa devolução e validade (tarefa 6.4; ADR-0014, itens 5 e 6;
 * plano aprovado no PR #126, itens 1 a 5, 13 a 15 dos testes). Escritos antes do código.
 */
class AutorizacaoDeDevolucaoTest extends ApoioDasDevolucoes {

    // --- 1. pedido ---

    @Test
    void pedidoForaDoPrazoGravaPedidaComValorDoServidorECotacaoDoDia() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("10.00", 10);
        UUID maleta = maleta(v, anel, 5);
        UUID cliente = cliente(v);
        limiteDeDesconto(10);
        // Linha de 3 peças com 0,01 de desconto: 29,99 não divide exato por 3.
        Instant vendidaEm = Instant.parse("2026-04-01T15:00:00Z");
        UUID venda = venderEm(v, maleta, cliente, anel, 3, "0.01", "29.99", vendidaEm);
        // Uma devolução parcial antes, ainda no prazo.
        ServicoDeDevolucoes.Resultado primeira = devolverPeloServico(v, venda, "ARREPENDIMENTO",
                List.of(new PecaDoPedido(anel, 1, "BOA")), null, Instant.parse("2026-04-02T15:00:00Z"), ModoDaDevolucao.ONLINE);
        BigDecimal jaDevolvido = jdbc.queryForObject("SELECT valor_devolvido FROM corona.devolucoes WHERE id = ?",
                BigDecimal.class, primeira.devolucaoId());

        Instant pedidaEm = Instant.parse("2026-04-15T15:00:00Z");
        ServicoDeAutorizacoes.Resultado r = pedirPeloServico(v, venda, "ARREPENDIMENTO", List.of(new PecaDoPedido(anel, 2, "BOA")),
                pedidaEm);
        assertThat(r.jaExistia()).isFalse();
        Map<String, Object> gravada = jdbc.queryForMap("""
                SELECT situacao, tipo, valor_pedido, cotacao_id, vendedor_id, cliente_id, decidida_em, valida_ate
                  FROM corona.autorizacoes_excecao WHERE id = ?""", r.autorizacaoId());
        assertThat(gravada.get("situacao")).isEqualTo("PEDIDA");
        assertThat(gravada.get("tipo")).isEqualTo("FORA_DO_PRAZO");
        assertThat((BigDecimal) gravada.get("valor_pedido")).isEqualByComparingTo(new BigDecimal("29.99").subtract(jaDevolvido));
        assertThat(gravada.get("vendedor_id")).isEqualTo(v.vendedorId());
        assertThat(gravada.get("cliente_id")).isEqualTo(cliente);
        assertThat(gravada.get("decidida_em")).isNull();
        assertThat(gravada.get("valida_ate")).isNull();
        UUID cotacaoDoDia = jdbc.query("SELECT id FROM corona.cotacoes WHERE data_cotacao <= ? ORDER BY data_cotacao DESC LIMIT 1",
                (rs, n) -> rs.getObject(1, UUID.class), LocalDate.of(2026, 4, 15)).stream().findFirst().orElse(null);
        assertThat(gravada.get("cotacao_id")).isEqualTo(cotacaoDoDia);
        assertThat(contar("SELECT count(*) FROM corona.itens_autorizacao WHERE autorizacao_id = ? AND quantidade = 2",
                r.autorizacaoId())).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM corona.auditoria WHERE tabela = 'autorizacoes_excecao' AND registro_id = ? "
                + "AND acao = 'AUTORIZACAO_PEDIDA'", r.autorizacaoId())).isEqualTo(1);
    }

    @Test
    void pedidoPelaRotaReenvioIgual200DiferenteE409() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("40.00", 10);
        UUID maleta = maleta(v, anel, 5);
        UUID venda = venderEm(v, maleta, cliente(v), anel, 3, "120.00", diasAtras(10));
        UUID id = UUID.randomUUID();
        JsonNode criada = corpoDe(postar(AUTORIZACOES, como(v), pedidoDeAutorizacao(id, venda, "ARREPENDIMENTO",
                List.of(devolvida(anel, 2, "BOA")))).andExpect(status().isCreated()));
        assertThat(criada.get("situacao").asString()).isEqualTo("PEDIDA");
        assertThat(criada.get("tipo").asString()).isEqualTo("FORA_DO_PRAZO");
        assertThat(dec(criada.get("valorPedido"))).isEqualByComparingTo("80.00");
        assertThat(criada.get("itens")).hasSize(1);
        assertThat(criada.get("itens").get(0).get("quantidade").asInt()).isEqualTo(2);
        assertThat(criada.get("itens").get(0).get("produto").get("nome").asString()).startsWith("Anel ");
        assertThat(criada.get("diasDesdeAVenda").asInt()).isEqualTo(10);

        postar(AUTORIZACOES, como(v), pedidoDeAutorizacao(id, venda, "ARREPENDIMENTO", List.of(devolvida(anel, 2, "BOA"))))
                .andExpect(status().isOk());
        assertThat(codigoDe(postar(AUTORIZACOES, como(v), pedidoDeAutorizacao(id, venda, "ARREPENDIMENTO",
                List.of(devolvida(anel, 1, "BOA")))).andExpect(status().isConflict()))).isEqualTo("AUTORIZACAO_JA_REGISTRADA_DIFERENTE");
        assertThat(contar("SELECT count(*) FROM corona.autorizacoes_excecao WHERE venda_id = ?", venda)).isEqualTo(1);

        // Segundo pedido da mesma venda com um aberto: 409.
        assertThat(codigoDe(postar(AUTORIZACOES, como(v), pedidoDeAutorizacao(UUID.randomUUID(), venda, "ARREPENDIMENTO",
                List.of(devolvida(anel, 1, "BOA")))).andExpect(status().isConflict()))).isEqualTo("AUTORIZACAO_JA_PEDIDA");
        // A lista do vendedor tem o pedido.
        JsonNode minhas = corpoDe(ler(AUTORIZACOES, como(v)).andExpect(status().isOk()));
        assertThat(minhas).hasSize(1);
        assertThat(corpoDe(ler(AUTORIZACOES + "/" + id, como(v)).andExpect(status().isOk())).get("id").asString())
                .isEqualTo(id.toString());
    }

    @Test
    void noPrazoAbaixoDoValorEDesnecessarioEAcimaDoValorEAcimaDoValor() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("100.00", 10);
        UUID maleta = maleta(v, anel, 5);
        UUID cliente = cliente(v);
        UUID venda = vender(v, maleta, cliente, List.of(linha(anel, 2)), List.of(pagamento("200.00", "PIX")));

        assertThat(codigoDe(postar(AUTORIZACOES, como(v), pedidoDeAutorizacao(UUID.randomUUID(), venda, "ARREPENDIMENTO",
                List.of(devolvida(anel, 1, "BOA")))).andExpect(status().isConflict()))).isEqualTo("AUTORIZACAO_DESNECESSARIA");
        valorDeAprovacao("150.00");
        // 100,00 não passa de 150,00: ainda desnecessária.
        postar(AUTORIZACOES, como(v), pedidoDeAutorizacao(UUID.randomUUID(), venda, "ARREPENDIMENTO",
                List.of(devolvida(anel, 1, "BOA")))).andExpect(status().isConflict());
        JsonNode acima = pedir(v, venda, "ARREPENDIMENTO", List.of(devolvida(anel, 2, "BOA")));
        assertThat(acima.get("tipo").asString()).isEqualTo("ACIMA_DO_VALOR");
        assertThat(dec(acima.get("valorPedido"))).isEqualByComparingTo("200.00");
    }

    @Test
    void pedidoRecusadoAcimaDoQuePodeVoltarAPrazoEstornadaEMalformado() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("50.00", 20);
        UUID outra = peca("50.00", 20);
        UUID maleta = maleta(v, anel, 10, outra, 5);
        UUID cliente = cliente(v);
        UUID venda = venderEm(v, maleta, cliente, anel, 2, "100.00", diasAtras(10));
        assertThat(codigoDe(postar(AUTORIZACOES, como(v), pedidoDeAutorizacao(UUID.randomUUID(), venda, "ARREPENDIMENTO",
                List.of(devolvida(anel, 3, "BOA")))).andExpect(status().isConflict()))).isEqualTo("DEVOLUCAO_ACIMA_DO_VENDIDO");

        UUID aPrazo = vender(v, maleta, cliente, List.of(linha(anel, 1)), List.of());
        assertThat(codigoDe(postar(AUTORIZACOES, como(v), pedidoDeAutorizacao(UUID.randomUUID(), aPrazo, "ARREPENDIMENTO",
                List.of(devolvida(anel, 1, "BOA")))).andExpect(status().isConflict()))).isEqualTo("VENDA_A_PRAZO_SEM_DEVOLUCAO");
        UUID estornada = vender(v, maleta, cliente, List.of(linha(anel, 1)), List.of(pagamento("50.00", "PIX")));
        postar(VENDAS + "/" + estornada + "/estorno", como(v), mapa("id", UUID.randomUUID(), "motivo", "engano"))
                .andExpect(status().isCreated());
        assertThat(codigoDe(postar(AUTORIZACOES, como(v), pedidoDeAutorizacao(UUID.randomUUID(), estornada, "ARREPENDIMENTO",
                List.of(devolvida(anel, 1, "BOA")))).andExpect(status().isConflict()))).isEqualTo("VENDA_JA_ESTORNADA");

        List<Map<String, Object>> uma = List.of(devolvida(anel, 1, "BOA"));
        List<Map<String, Object>> ruins = new ArrayList<>();
        ruins.add(pedidoDeAutorizacao(UUID.randomUUID(), venda, "ARREPENDIMENTO", List.of()));
        ruins.add(pedidoDeAutorizacao(UUID.randomUUID(), venda, "NAO_GOSTOU", uma));
        ruins.add(pedidoDeAutorizacao(UUID.randomUUID(), venda, "OUTRO", uma));
        ruins.add(pedidoDeAutorizacao(UUID.randomUUID(), venda, "DEFEITO", uma));
        ruins.add(pedidoDeAutorizacao(UUID.randomUUID(), venda, "ARREPENDIMENTO", List.of(devolvida(outra, 1, "BOA"))));
        ruins.add(pedidoDeAutorizacao(UUID.randomUUID(), null, "ARREPENDIMENTO", uma));
        for (Map<String, Object> ruim : ruins) {
            JsonNode erro = corpoDe(postar(AUTORIZACOES, como(v), ruim).andExpect(status().isBadRequest()));
            assertThat(erro.get("detail").asString()).as("%s", ruim).isNotBlank();
        }
        assertThat(contar("SELECT count(*) FROM corona.autorizacoes_excecao WHERE vendedor_id = ?", v.vendedorId())).isZero();
    }

    // --- 2. aprovar e negar ---

    @Test
    void aprovarGravaQuemQuandoEValidadePeloDiaDeBrasilia() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("30.00", 10);
        UUID maleta = maleta(v, anel, 5);
        UUID venda = venderEm(v, maleta, cliente(v), anel, 2, "60.00", Instant.parse("2026-03-01T15:00:00Z"));
        UUID id = pedirPeloServico(v, venda, "ARREPENDIMENTO", List.of(new PecaDoPedido(anel, 1, "BOA")),
                Instant.parse("2026-03-19T15:00:00Z")).autorizacaoId();

        // 19/03 às 23:30 em Brasília = 20/03 às 02:30 UTC: vale até 26/03 (dia de Brasília + 7).
        relogio.fixar(Instant.parse("2026-03-20T02:30:00Z"));
        UUID dona = usuarios.dona().id();
        assertThat(autorizacoes.aprovar(id, dona).jaExistia()).isFalse();
        Map<String, Object> gravada = jdbc.queryForMap(
                "SELECT situacao, decidida_por, decidida_em, valida_ate FROM corona.autorizacoes_excecao WHERE id = ?", id);
        assertThat(gravada.get("situacao")).isEqualTo("APROVADA");
        assertThat(gravada.get("decidida_por")).isEqualTo(dona);
        assertThat(gravada.get("decidida_em")).isNotNull();
        assertThat(gravada.get("valida_ate").toString()).isEqualTo("2026-03-26");
        // Repetir aprovar: 200 (já existia); negar depois: 409.
        assertThat(autorizacoes.aprovar(id, dona).jaExistia()).isTrue();
        assertThatThrownBy(() -> autorizacoes.negar(id, dona, "não"))
                .satisfies(t -> assertThat(codigoDoErro(t)).isEqualTo("AUTORIZACAO_JA_DECIDIDA"));
        assertThat(contar("SELECT count(*) FROM corona.auditoria WHERE registro_id = ? AND acao = 'AUTORIZACAO_APROVADA'", id))
                .isEqualTo(1);
    }

    @Test
    void negarExigeMotivoEGravaPelaRota() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("30.00", 10);
        UUID maleta = maleta(v, anel, 5);
        UUID venda = venderEm(v, maleta, cliente(v), anel, 2, "60.00", diasAtras(9));
        UUID id = id(pedir(v, venda, "ARREPENDIMENTO", List.of(devolvida(anel, 1, "BOA"))));

        // A lista da dona (padrão: esperando a dona) tem o pedido, com o nome do vendedor.
        JsonNode lista = corpoDe(ler(AUTORIZACOES_DA_DONA, dona()).andExpect(status().isOk()));
        assertThat(lista).anyMatch(a -> a.get("id").asString().equals(id.toString()) && !a.get("vendedorNome").asString().isBlank());
        assertThat(corpoDe(ler(AUTORIZACOES_DA_DONA + "/" + id, dona()).andExpect(status().isOk())).get("situacao").asString())
                .isEqualTo("PEDIDA");

        postar(AUTORIZACOES_DA_DONA + "/" + id + "/negar", dona(), mapa("motivo", "  ")).andExpect(status().isBadRequest());
        JsonNode negada = corpoDe(postar(AUTORIZACOES_DA_DONA + "/" + id + "/negar", dona(), mapa("motivo", "Peça usada"))
                .andExpect(status().isOk()));
        assertThat(negada.get("situacao").asString()).isEqualTo("NEGADA");
        assertThat(negada.get("motivoDecisao").asString()).isEqualTo("Peça usada");
        postar(AUTORIZACOES_DA_DONA + "/" + id + "/negar", dona(), mapa("motivo", "Peça usada")).andExpect(status().isOk());
        assertThat(codigoDe(postar(AUTORIZACOES_DA_DONA + "/" + id + "/aprovar", dona(), null)
                .andExpect(status().isConflict()))).isEqualTo("AUTORIZACAO_JA_DECIDIDA");
        assertThat(contar("SELECT count(*) FROM corona.auditoria WHERE registro_id = ? AND acao = 'AUTORIZACAO_NEGADA'", id))
                .isEqualTo(1);
        // Negado: pode pedir de novo.
        pedir(v, venda, "ARREPENDIMENTO", List.of(devolvida(anel, 1, "BOA")));
    }

    // --- 3. uso ---

    @Test
    void devolucaoComAutorizacaoAprovadaPassaForaDoPrazoEConsomeAAutorizacao() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("25.00", 20);
        UUID brinco = peca("15.00", 20);
        UUID maleta = maleta(v, anel, 10, brinco, 10);
        UUID cliente = cliente(v);
        UUID venda = venderEm(v, maleta, cliente, anel, 3, "75.00", diasAtras(12));
        UUID outraVenda = venderEm(v, maleta, cliente, anel, 3, "75.00", diasAtras(12));
        venderEm(v, maleta, cliente, brinco, 1, "15.00", diasAtras(12));

        // Sem autorização: passou do prazo.
        assertThat(codigoDe(postar(DEVOLUCOES, como(v), devolucao(UUID.randomUUID(), venda, "ARREPENDIMENTO", "PIX",
                List.of(devolvida(anel, 1, "BOA")))).andExpect(status().isConflict()))).isEqualTo("GARANTIA_VENCIDA");

        UUID autorizacao = id(pedir(v, venda, "ARREPENDIMENTO", List.of(devolvida(anel, 2, "BOA"))));
        // Ainda esperando a dona: não vale.
        assertThat(codigoDe(postar(DEVOLUCOES, como(v), devolucaoAutorizada(venda, "ARREPENDIMENTO", autorizacao,
                List.of(devolvida(anel, 1, "BOA")))).andExpect(status().isConflict()))).isEqualTo("AUTORIZACAO_NAO_APROVADA");
        JsonNode aprovada = aprovar(autorizacao);
        assertThat(aprovada.get("situacao").asString()).isEqualTo("APROVADA");
        assertThat(aprovada.get("validaAte").asString()).isNotBlank();

        // Não confere: mais peças, outra peça, outro motivo, outra venda.
        List<Map<String, Object>> naoConferem = List.of(
                devolucaoAutorizada(venda, "ARREPENDIMENTO", autorizacao, List.of(devolvida(anel, 3, "BOA"))),
                devolucaoAutorizada(venda, "ARREPENDIMENTO", autorizacao, List.of(devolvida(brinco, 1, "BOA"))),
                devolucaoAutorizada(venda, "TAMANHO", autorizacao, List.of(devolvida(anel, 1, "BOA"))),
                devolucaoAutorizada(outraVenda, "ARREPENDIMENTO", autorizacao, List.of(devolvida(anel, 1, "BOA"))));
        for (Map<String, Object> corpo : naoConferem) {
            ResultActions r = postar(DEVOLUCOES, como(v), corpo);
            int s = r.andReturn().getResponse().getStatus();
            assertThat(s).as("%s", corpo).isIn(400, 409);
            if (s == 409) {
                assertThat(codigoDe(r)).as("%s", corpo).isEqualTo("AUTORIZACAO_NAO_CONFERE");
            }
        }
        assertThat(situacaoDaAutorizacao(autorizacao)).isEqualTo("APROVADA");

        // Menos peças que o autorizado: passa, aceita, fora da garantia, com a autorização ligada e usada.
        JsonNode d = corpoDe(postar(DEVOLUCOES, como(v), devolucaoAutorizada(venda, "ARREPENDIMENTO", autorizacao,
                List.of(devolvida(anel, 1, "BOA")))).andExpect(status().isCreated()));
        assertThat(d.get("situacao").asString()).isEqualTo("ACEITA");
        assertThat(d.get("dentroGarantia").asBoolean()).isFalse();
        assertThat(d.get("autorizacaoId").asString()).isEqualTo(autorizacao.toString());
        assertThat(d.get("motivosRevisao")).isEmpty();
        assertThat(situacaoDaAutorizacao(autorizacao)).isEqualTo("USADA");

        // Usada: segunda devolução com ela = 409.
        assertThat(codigoDe(postar(DEVOLUCOES, como(v), devolucaoAutorizada(venda, "ARREPENDIMENTO", autorizacao,
                List.of(devolvida(anel, 1, "BOA")))).andExpect(status().isConflict()))).isEqualTo("AUTORIZACAO_JA_USADA");

        // Cancelar a devolução não devolve a autorização para uso.
        postar(DEVOLUCOES + "/" + d.get("id").asString() + "/cancelar", como(v), mapa("id", UUID.randomUUID(), "motivo", "engano"))
                .andExpect(status().isCreated());
        assertThat(situacaoDaAutorizacao(autorizacao)).isEqualTo("USADA");
        // Depois de usada, um pedido novo para a mesma venda pode ser feito.
        pedir(v, venda, "ARREPENDIMENTO", List.of(devolvida(anel, 1, "BOA")));
    }

    @Test
    void autorizacaoNegadaNaoVale() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("25.00", 20);
        UUID maleta = maleta(v, anel, 10);
        UUID venda = venderEm(v, maleta, cliente(v), anel, 3, "75.00", diasAtras(12));
        UUID autorizacao = id(pedir(v, venda, "ARREPENDIMENTO", List.of(devolvida(anel, 1, "BOA"))));
        postar(AUTORIZACOES_DA_DONA + "/" + autorizacao + "/negar", dona(), mapa("motivo", "não")).andExpect(status().isOk());
        assertThat(codigoDe(postar(DEVOLUCOES, como(v), devolucaoAutorizada(venda, "ARREPENDIMENTO", autorizacao,
                List.of(devolvida(anel, 1, "BOA")))).andExpect(status().isConflict()))).isEqualTo("AUTORIZACAO_NAO_APROVADA");
        assertThat(contar("SELECT count(*) FROM corona.devolucoes WHERE venda_id = ?", venda)).isZero();
    }

    // --- 4. validade ---

    @Test
    void validadeDeSeteDiasDeBrasiliaMesmoSemAAgendaEOEnvioNaoConsomeAVencida() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("20.00", 20);
        UUID maleta = maleta(v, anel, 10);
        UUID cliente = cliente(v);
        UUID venda = venderEm(v, maleta, cliente, anel, 4, "80.00", Instant.parse("2026-03-01T15:00:00Z"));
        UUID dona = usuarios.dona().id();
        UUID autorizacao = pedirPeloServico(v, venda, "ARREPENDIMENTO", List.of(new PecaDoPedido(anel, 2, "BOA")),
                Instant.parse("2026-03-19T15:00:00Z")).autorizacaoId();
        relogio.fixar(Instant.parse("2026-03-20T02:30:00Z"));
        autorizacoes.aprovar(autorizacao, dona);
        relogio.reiniciar();
        List<PecaDoPedido> uma = List.of(new PecaDoPedido(anel, 1, "BOA"));

        // 8º dia (27/03 às 00:30 em Brasília = 03:30 UTC): venceu, mesmo com a situação ainda aprovada.
        assertThatThrownBy(() -> devolverPeloServico(v, venda, "ARREPENDIMENTO", uma, autorizacao,
                Instant.parse("2026-03-27T03:30:00Z"), ModoDaDevolucao.ONLINE))
                .satisfies(t -> assertThat(codigoDoErro(t)).isEqualTo("AUTORIZACAO_VENCIDA"));
        assertThat(situacaoDaAutorizacao(autorizacao)).isEqualTo("APROVADA");

        // Pelo envio, a vencida não é consumida: a devolução entra para revisão.
        ServicoDeDevolucoes.Resultado revisao = devolverPeloServico(v, venda, "ARREPENDIMENTO", uma, autorizacao,
                Instant.parse("2026-03-27T03:30:00Z"), ModoDaDevolucao.ENVIO);
        Map<String, Object> gravada = devolucaoGravada(revisao.devolucaoId());
        assertThat(gravada.get("situacao")).isEqualTo("EM_REVISAO");
        assertThat(gravada.get("autorizacao_id")).isNull();
        assertThat(motivosGravados(revisao.devolucaoId())).containsExactly("FORA_DO_PRAZO", "AUTORIZACAO_INVALIDA");
        assertThat(situacaoDaAutorizacao(autorizacao)).isEqualTo("APROVADA");
        JsonNode lida = corpoDe(ler(DEVOLUCOES_DA_DONA + "/" + revisao.devolucaoId(), dona()).andExpect(status().isOk()));
        assertThat(lida.get("motivosRevisao").toString()).isEqualTo("[\"FORA_DO_PRAZO\",\"AUTORIZACAO_INVALIDA\"]");

        // 7º dia (26/03 às 23:00 em Brasília = 27/03 às 02:00 UTC): passa.
        ServicoDeDevolucoes.Resultado ok = devolverPeloServico(v, venda, "ARREPENDIMENTO", uma, autorizacao,
                Instant.parse("2026-03-27T02:00:00Z"), ModoDaDevolucao.ONLINE);
        assertThat(devolucaoGravada(ok.devolucaoId()).get("situacao")).isEqualTo("ACEITA");
        assertThat(situacaoDaAutorizacao(autorizacao)).isEqualTo("USADA");
    }

    @Test
    void agendaMarcaVencidasSoAsAprovadasComDataPassada() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("20.00", 40);
        UUID maleta = maleta(v, anel, 20);
        UUID cliente = cliente(v);
        UUID dona = usuarios.dona().id();
        List<PecaDoPedido> uma = List.of(new PecaDoPedido(anel, 1, "BOA"));
        Instant vendidaEm = Instant.parse("2026-05-01T15:00:00Z");
        Instant pedidaEm = Instant.parse("2026-05-20T15:00:00Z");
        UUID aprovadaAntiga = pedirPeloServico(v, venderEm(v, maleta, cliente, anel, 1, "20.00", vendidaEm), "ARREPENDIMENTO", uma,
                pedidaEm).autorizacaoId();
        UUID aprovadaDoDia = pedirPeloServico(v, venderEm(v, maleta, cliente, anel, 1, "20.00", vendidaEm), "ARREPENDIMENTO", uma,
                pedidaEm).autorizacaoId();
        UUID pedida = pedirPeloServico(v, venderEm(v, maleta, cliente, anel, 1, "20.00", vendidaEm), "ARREPENDIMENTO", uma,
                pedidaEm).autorizacaoId();
        UUID vendaDaUsada = venderEm(v, maleta, cliente, anel, 1, "20.00", vendidaEm);
        UUID usada = pedirPeloServico(v, vendaDaUsada, "ARREPENDIMENTO", uma, pedidaEm).autorizacaoId();

        relogio.fixar(Instant.parse("2026-05-20T15:00:00Z"));
        autorizacoes.aprovar(aprovadaAntiga, dona); // vale até 27/05
        autorizacoes.aprovar(usada, dona);
        relogio.fixar(Instant.parse("2026-05-22T15:00:00Z"));
        autorizacoes.aprovar(aprovadaDoDia, dona); // vale até 29/05
        devolverPeloServico(v, vendaDaUsada, "ARREPENDIMENTO", uma, usada, Instant.parse("2026-05-22T16:00:00Z"),
                ModoDaDevolucao.ONLINE);

        // 29/05: a de 27/05 venceu; a que vale até hoje, a pedida e a usada ficam.
        relogio.fixar(Instant.parse("2026-05-29T15:00:00Z"));
        autorizacoes.marcarVencidas();
        assertThat(situacaoDaAutorizacao(aprovadaAntiga)).isEqualTo("VENCIDA");
        assertThat(situacaoDaAutorizacao(aprovadaDoDia)).isEqualTo("APROVADA");
        assertThat(situacaoDaAutorizacao(pedida)).isEqualTo("PEDIDA");
        assertThat(situacaoDaAutorizacao(usada)).isEqualTo("USADA");
        // Rodar de novo não muda nada.
        autorizacoes.marcarVencidas();
        assertThat(situacaoDaAutorizacao(aprovadaAntiga)).isEqualTo("VENCIDA");
        // Vencida: na rota online, 409.
        UUID vendaDaVencida = jdbc.queryForObject("SELECT venda_id FROM corona.autorizacoes_excecao WHERE id = ?", UUID.class,
                aprovadaAntiga);
        assertThatThrownBy(() -> devolverPeloServico(v, vendaDaVencida, "ARREPENDIMENTO", uma, aprovadaAntiga,
                Instant.parse("2026-05-29T16:00:00Z"), ModoDaDevolucao.ONLINE))
                .satisfies(t -> assertThat(codigoDoErro(t)).isEqualTo("AUTORIZACAO_VENCIDA"));
    }

    // --- 5. usada duas vezes ---

    @Test
    void duasDevolucoesAoMesmoTempoComAMesmaAutorizacaoSoUmaPassa() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("25.00", 20);
        UUID maleta = maleta(v, anel, 10);
        UUID venda = venderEm(v, maleta, cliente(v), anel, 4, "100.00", diasAtras(12));
        UUID autorizacao = id(pedir(v, venda, "ARREPENDIMENTO", List.of(devolvida(anel, 2, "BOA"))));
        aprovar(autorizacao);
        RequestPostProcessor quem = como(v);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<Integer>> resultados = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                resultados.add(pool.submit(() -> {
                    largada.await();
                    return postar(DEVOLUCOES, quem, devolucaoAutorizada(venda, "ARREPENDIMENTO", autorizacao,
                            List.of(devolvida(anel, 1, "BOA")))).andReturn().getResponse().getStatus();
                }));
            }
            largada.countDown();
            List<Integer> s = new ArrayList<>();
            for (Future<Integer> f : resultados) {
                s.add(f.get(60, TimeUnit.SECONDS));
            }
            assertThat(s).containsExactlyInAnyOrder(201, 409);
        } finally {
            pool.shutdown();
        }
        assertThat(contar("SELECT count(*) FROM corona.devolucoes WHERE autorizacao_id = ?", autorizacao)).isEqualTo(1);
        assertThat(situacaoDaAutorizacao(autorizacao)).isEqualTo("USADA");

        // Segunda trava no banco: outra devolução com a mesma autorização é recusada pelo índice único.
        UUID item = jdbc.queryForObject("SELECT id FROM corona.itens_venda WHERE venda_id = ?", UUID.class, venda);
        assertThatThrownBy(() -> gravarDevolucaoDireto(v, venda, autorizacao, item)).isInstanceOf(SQLException.class);
    }

    @Test
    void bancoRecusaUsadaSemDevolucaoLigada() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("25.00", 20);
        UUID maleta = maleta(v, anel, 10);
        UUID venda = venderEm(v, maleta, cliente(v), anel, 2, "50.00", diasAtras(12));
        UUID autorizacao = id(pedir(v, venda, "ARREPENDIMENTO", List.of(devolvida(anel, 1, "BOA"))));
        aprovar(autorizacao);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.autorizacoes_excecao SET situacao = 'USADA' WHERE id = ?", autorizacao))
                .isInstanceOf(Exception.class);
        assertThat(situacaoDaAutorizacao(autorizacao)).isEqualTo("APROVADA");
    }

    /** Uma devolução gravada direto pelo usuário da aplicação, com a autorização, numa transação desfeita no fim. */
    private void gravarDevolucaoDireto(UsuarioDeTeste v, UUID venda, UUID autorizacao, UUID itemDaVenda) throws SQLException {
        Map<String, Object> daVenda = jdbc.queryForMap("SELECT cliente_id, remessa_id FROM corona.vendas WHERE id = ?", venda);
        try (Connection c = conectarComoAplicacao()) {
            try {
                UUID dev = UUID.randomUUID();
                try (PreparedStatement s = c.prepareStatement("""
                        INSERT INTO corona.devolucoes (id, venda_id, cliente_id, vendedor_id, remessa_id, motivo, destino,
                            dentro_garantia, situacao, autorizacao_id, valor_devolvido, valor_reembolsado, forma_reembolso,
                            devolvida_em, registrada_por)
                        VALUES (?, ?, ?, ?, ?, 'ARREPENDIMENTO', 'REEMBOLSO', false, 'ACEITA', ?, 0, 0, NULL, now(), ?)""")) {
                    s.setObject(1, dev);
                    s.setObject(2, venda);
                    s.setObject(3, daVenda.get("cliente_id"));
                    s.setObject(4, v.vendedorId());
                    s.setObject(5, daVenda.get("remessa_id"));
                    s.setObject(6, autorizacao);
                    s.setObject(7, v.id());
                    s.executeUpdate();
                }
            } finally {
                c.rollback();
            }
        }
    }

    // --- 13. permissões ---

    @Test
    void vendedorNaoUsaRotasDaDonaENaoVeNemUsaAutorizacaoDeOutro() throws Exception {
        UsuarioDeTeste a = usuarios.criarVendedor();
        UsuarioDeTeste b = usuarios.criarVendedor();
        UUID anel = peca("25.00", 20);
        UUID maletaDeA = maleta(a, anel, 5);
        UUID maletaDeB = maleta(b, anel, 5);
        UUID vendaDeA = venderEm(a, maletaDeA, cliente(a), anel, 2, "50.00", diasAtras(12));
        UUID vendaDeB = venderEm(b, maletaDeB, cliente(b), anel, 2, "50.00", diasAtras(12));
        UUID autorizacao = id(pedir(a, vendaDeA, "ARREPENDIMENTO", List.of(devolvida(anel, 1, "BOA"))));
        aprovar(autorizacao);

        assertThat(codigoDe(ler(AUTORIZACOES + "/" + autorizacao, como(b)).andExpect(status().isNotFound())))
                .isEqualTo("AUTORIZACAO_NAO_ENCONTRADA");
        assertThat(corpoDe(ler(AUTORIZACOES, como(b)).andExpect(status().isOk()))).isEmpty();
        assertThat(codigoDe(postar(AUTORIZACOES, como(b), pedidoDeAutorizacao(UUID.randomUUID(), vendaDeA, "ARREPENDIMENTO",
                List.of(devolvida(anel, 1, "BOA")))).andExpect(status().isNotFound()))).isEqualTo("VENDA_NAO_ENCONTRADA");
        assertThat(codigoDe(postar(DEVOLUCOES, como(b), devolucaoAutorizada(vendaDeB, "ARREPENDIMENTO", autorizacao,
                List.of(devolvida(anel, 1, "BOA")))).andExpect(status().isNotFound()))).isEqualTo("AUTORIZACAO_NAO_ENCONTRADA");
        // Pelo envio também: autorização de outro vendedor nunca é aceita.
        assertThatThrownBy(() -> devolverPeloServico(b, vendaDeB, "ARREPENDIMENTO", List.of(new PecaDoPedido(anel, 1, "BOA")),
                autorizacao, null, ModoDaDevolucao.ENVIO))
                .satisfies(t -> assertThat(codigoDoErro(t)).isEqualTo("AUTORIZACAO_NAO_ENCONTRADA"));

        ler(AUTORIZACOES_DA_DONA, como(a)).andExpect(status().isForbidden());
        ler(AUTORIZACOES_DA_DONA + "/" + autorizacao, como(a)).andExpect(status().isForbidden());
        postar(AUTORIZACOES_DA_DONA + "/" + autorizacao + "/aprovar", como(a), null).andExpect(status().isForbidden());
        postar(AUTORIZACOES_DA_DONA + "/" + autorizacao + "/negar", como(a), mapa("motivo", "x")).andExpect(status().isForbidden());
        ler(DEVOLUCOES_DA_DONA, como(a)).andExpect(status().isForbidden());
        postar(DEVOLUCOES_DA_DONA + "/" + UUID.randomUUID() + "/manter", como(a), mapa()).andExpect(status().isForbidden());
        postar(DEVOLUCOES_DA_DONA + "/" + UUID.randomUUID() + "/nao-aceitar", como(a), mapa("observacao", "x"))
                .andExpect(status().isForbidden());
        postar(AUTORIZACOES, dona(), pedidoDeAutorizacao(UUID.randomUUID(), vendaDeA, "ARREPENDIMENTO",
                List.of(devolvida(anel, 1, "BOA")))).andExpect(status().isForbidden());
        ler(AUTORIZACOES, UsuariosDeTeste.comToken("invalido")).andExpect(status().isUnauthorized());
        ler(AUTORIZACOES_DA_DONA, UsuariosDeTeste.comToken("invalido")).andExpect(status().isUnauthorized());
        assertThat(situacaoDaAutorizacao(autorizacao)).isEqualTo("APROVADA");
    }

    // --- 14. nada de custo ---

    @Test
    void nenhumaRespostaDasRotasDaAutorizacaoTemCustoNemFornecedor() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("25.00", 20);
        UUID maleta = maleta(v, anel, 5);
        UUID venda = venderEm(v, maleta, cliente(v), anel, 2, "50.00", diasAtras(12));
        JsonNode pedido = pedir(v, venda, "ARREPENDIMENTO", List.of(devolvida(anel, 1, "BOA")));
        UUID autorizacao = id(pedido);
        List<String> respostas = List.of(pedido.toString(),
                corpoDe(ler(AUTORIZACOES, como(v))).toString(),
                corpoDe(ler(AUTORIZACOES + "/" + autorizacao, como(v))).toString(),
                corpoDe(ler(AUTORIZACOES_DA_DONA, dona())).toString(),
                corpoDe(ler(AUTORIZACOES_DA_DONA + "/" + autorizacao, dona())).toString(),
                aprovar(autorizacao).toString(),
                corpoDe(postar(DEVOLUCOES, como(v), devolucaoAutorizada(venda, "ARREPENDIMENTO", autorizacao,
                        List.of(devolvida(anel, 1, "BOA"))))).toString(),
                corpoDe(ler(DEVOLUCOES_DA_DONA + "?situacao=ACEITA&vendedorId=" + v.vendedorId(), dona())).toString());
        for (String r : respostas) {
            assertThat(r.toLowerCase()).doesNotContain("custo").doesNotContain("fornecedor");
        }
    }

    // --- 15. imutabilidade ---

    @Test
    void autorizacaoEItensNaoSaoAlteradosNemApagados() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("20.00", 40);
        UUID maleta = maleta(v, anel, 20);
        UUID cliente = cliente(v);
        UUID dona = usuarios.dona().id();
        List<Map<String, Object>> uma = List.of(devolvida(anel, 1, "BOA"));
        UUID venda = venderEm(v, maleta, cliente, anel, 2, "40.00", diasAtras(12));
        UUID outraVenda = venderEm(v, maleta, cliente, anel, 2, "40.00", diasAtras(12));
        UUID pedida = id(pedir(v, venda, "ARREPENDIMENTO", uma));
        UUID negada = id(pedir(v, outraVenda, "ARREPENDIMENTO", uma));
        autorizacoes.negar(negada, dona, "não");
        UUID vendaDaUsada = venderEm(v, maleta, cliente, anel, 2, "40.00", diasAtras(12));
        UUID usada = id(pedir(v, vendaDaUsada, "ARREPENDIMENTO", uma));
        aprovar(usada);
        postar(DEVOLUCOES, como(v), devolucaoAutorizada(vendaDaUsada, "ARREPENDIMENTO", usada, uma)).andExpect(status().isCreated());
        UUID vendaDaVencida = venderEm(v, maleta, cliente, anel, 2, "40.00", Instant.parse("2026-02-01T15:00:00Z"));
        UUID vencida = pedirPeloServico(v, vendaDaVencida, "ARREPENDIMENTO", List.of(new PecaDoPedido(anel, 1, "BOA")),
                Instant.parse("2026-02-20T15:00:00Z")).autorizacaoId();
        relogio.fixar(Instant.parse("2026-02-20T15:00:00Z"));
        autorizacoes.aprovar(vencida, dona);
        relogio.reiniciar();
        autorizacoes.marcarVencidas();
        assertThat(situacaoDaAutorizacao(vencida)).isEqualTo("VENCIDA");

        assertThatThrownBy(() -> jdbc.update("UPDATE corona.autorizacoes_excecao SET valor_pedido = 1.00 WHERE id = ?", pedida))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.autorizacoes_excecao SET motivo = 'TAMANHO' WHERE id = ?", pedida))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.autorizacoes_excecao SET deleted_at = now() WHERE id = ?", pedida))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.autorizacoes_excecao SET vendedor_id = ? WHERE id = ?",
                usuarios.criarVendedor().vendedorId(), pedida)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.autorizacoes_excecao SET venda_id = ? WHERE id = ?", outraVenda, pedida))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM corona.autorizacoes_excecao WHERE id = ?", pedida))
                .isInstanceOf(Exception.class);
        // Transições proibidas.
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE corona.autorizacoes_excecao SET situacao = 'APROVADA', valida_ate = current_date, motivo_decisao = NULL
                 WHERE id = ?""", negada)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE corona.autorizacoes_excecao SET situacao = 'USADA', decidida_em = now(), decidida_por = ?,
                       valida_ate = current_date WHERE id = ?""", dona, pedida)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.autorizacoes_excecao SET situacao = 'APROVADA' WHERE id = ?", usada))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.autorizacoes_excecao SET situacao = 'USADA' WHERE id = ?", vencida))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.autorizacoes_excecao SET valida_ate = valida_ate + 30 WHERE id = ?",
                usada)).isInstanceOf(Exception.class);
        // Itens: nem alterados nem apagados; item de outra venda recusado.
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.itens_autorizacao SET quantidade = 2 WHERE autorizacao_id = ?", pedida))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM corona.itens_autorizacao WHERE autorizacao_id = ?", pedida))
                .isInstanceOf(Exception.class);
        UUID itemDeOutraVenda = jdbc.queryForObject("SELECT id FROM corona.itens_venda WHERE venda_id = ?", UUID.class, outraVenda);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO corona.itens_autorizacao (id, autorizacao_id, item_venda_id, quantidade) VALUES (?, ?, ?, 1)""",
                UUID.randomUUID(), pedida, itemDeOutraVenda)).isInstanceOf(Exception.class);
        assertThat(situacaoDaAutorizacao(pedida)).isEqualTo("PEDIDA");
        assertThat(situacaoDaAutorizacao(negada)).isEqualTo("NEGADA");
        assertThat(situacaoDaAutorizacao(usada)).isEqualTo("USADA");
    }
}
