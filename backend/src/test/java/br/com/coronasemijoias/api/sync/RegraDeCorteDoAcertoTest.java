package br.com.coronasemijoias.api.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.LinhaDoPedido;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.PagamentoDoPedido;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.PedidoDeVenda;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * A regra de corte do acerto (tarefa 7.4; ADR-0012, item 4): a venda e o estorno feitos sem internet ANTES do início, do
 * computador da maleta, entram na maleta em acerto; os de depois do início, de outro computador ou pela rota online, não. O
 * reenvio igual de venda já gravada continua aceito. Escrito antes da mudança no serviço de vendas.
 */
class RegraDeCorteDoAcertoTest extends ApoioDoEnvioDaFase6 {

    @Autowired
    ServicoDeVendas vendas;

    private UsuarioDeTeste v;
    private UUID cliente;
    private UUID peca;
    private UUID maleta;
    private UUID aparelho;
    private UUID acerto;
    private Instant inicio;

    @BeforeEach
    void umAcertoIniciado() throws Exception {
        v = usuarios.criarVendedor();
        cliente = cliente(v);
        peca = peca("50.00", 20);
        maleta = maleta(v, peca, 10);
        aparelho = usuarios.aparelhoDe(v);
        acerto = UUID.randomUUID();
        postar("/api/dona/acertos", dona(), mapa("id", acerto, "vendedorId", v.vendedorId(),
                "semanaId", jdbc.queryForObject("SELECT semana_id FROM corona.remessas WHERE id = ?", UUID.class, maleta)))
                .andExpect(status().isCreated());
        inicio = jdbc.queryForObject("SELECT iniciado_em FROM corona.acertos WHERE id = ?", java.sql.Timestamp.class, acerto).toInstant();
    }

    private PedidoDeVenda pedido(UUID id, int quantidade) {
        return new PedidoDeVenda(id, maleta, cliente, List.of(new LinhaDoPedido(peca, quantidade, BigDecimal.ZERO)),
                List.of(new PagamentoDoPedido(UUID.randomUUID(), new BigDecimal("50.00").multiply(BigDecimal.valueOf(quantidade)), "DINHEIRO")),
                null);
    }

    private int vendasDaMaleta() {
        return contar("SELECT count(*) FROM corona.vendas WHERE remessa_id = ?", maleta);
    }

    private int saldo() {
        return contar("""
                SELECT i.qtd_enviada + coalesce((SELECT sum(m.quantidade) FROM corona.movimentos_remessa m
                        WHERE m.remessa_id = i.remessa_id AND m.produto_id = i.produto_id), 0)
                  FROM corona.itens_remessa i WHERE i.remessa_id = ? AND i.produto_id = ?""", maleta, peca);
    }

    @Test
    void vendaDeAntesDoInicioDoComputadorDaMaletaEAceitaEContadaNaFicha() throws Exception {
        UUID id = UUID.randomUUID();
        assertThat(vendas.venderPeloEnvio(v.vendedorId(), v.id(), aparelho, pedido(id, 2), inicio.minusSeconds(60), false)
                .jaExistia()).isFalse();
        assertThat(saldo()).isEqualTo(8);
        assertThat(statusDaMaleta()).isEqualTo("EM_ACERTO");
        postar("/api/dona/acertos/" + acerto + "/leituras", dona(), mapa("id", UUID.randomUUID(), "produtoId", peca, "quantidade", 8,
                "estadoPeca", "BOA")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.vendasQueChegaramDepoisDoInicio").value(1));
    }

    @Test
    void reenvioIgualDeVendaJaGravadaContinuaAceitoSemEfeitoNovo() throws Exception {
        UUID id = UUID.randomUUID();
        PedidoDeVenda p = pedido(id, 1);
        vendas.venderPeloEnvio(v.vendedorId(), v.id(), aparelho, p, inicio.minusSeconds(60), false);
        int antes = vendasDaMaleta();
        assertThat(vendas.venderPeloEnvio(v.vendedorId(), v.id(), aparelho, p, inicio.minusSeconds(60), false).jaExistia()).isTrue();
        assertThat(vendasDaMaleta()).isEqualTo(antes);
    }

    @Test
    void vendaComHoraDepoisDoInicioOuDeOutroComputadorOuOnlineERecusada() throws Exception {
        int antes = vendasDaMaleta();
        assertThatThrownBy(() -> vendas.venderPeloEnvio(v.vendedorId(), v.id(), aparelho, pedido(UUID.randomUUID(), 1),
                inicio.plusSeconds(1), false)).satisfies(t -> assertThat(((ErroDaApi) t).codigo().name()).isEqualTo("MALETA_EM_ACERTO"));
        assertThatThrownBy(() -> vendas.venderPeloEnvio(v.vendedorId(), v.id(), UUID.randomUUID(), pedido(UUID.randomUUID(), 1),
                inicio.minusSeconds(60), false)).isInstanceOf(ErroDaApi.class);
        // A venda online nunca passa, nem com hora anterior: o corte vale só para o que veio do envio.
        assertThatThrownBy(() -> vendas.vender(v.vendedorId(), v.id(), aparelho, pedido(UUID.randomUUID(), 1), inicio.minusSeconds(60),
                false)).satisfies(t -> assertThat(((ErroDaApi) t).codigo().name()).isEqualTo("MALETA_EM_ACERTO"));
        assertThat(vendasDaMaleta()).isEqualTo(antes);
    }

    @Test
    void estornoFeitoAntesDoInicioEAceitoEOutroNao() throws Exception {
        // Uma venda anterior ao acerto: desfaz o acerto, vende, inicia de novo.
        postar("/api/dona/acertos/" + acerto + "/desistir", dona(), mapa("id", UUID.randomUUID(), "motivo", "Para testar."))
                .andExpect(status().isCreated());
        UUID venda = vender(v, maleta, cliente, List.of(linha(peca, 2)), List.of(pagamento("100.00", "DINHEIRO")));
        UUID outro = UUID.randomUUID();
        postar("/api/dona/acertos", dona(), mapa("id", outro, "vendedorId", v.vendedorId())).andExpect(status().isCreated());
        Instant novoInicio = jdbc.queryForObject("SELECT iniciado_em FROM corona.acertos WHERE id = ?", java.sql.Timestamp.class, outro)
                .toInstant();
        int saldo = saldo();

        // Depois do início, ou pela rota online: recusado.
        assertThatThrownBy(() -> vendas.estornar(venda, v.vendedorId(), v.id(), aparelho, UUID.randomUUID(), "Engano.",
                novoInicio.plusSeconds(1))).satisfies(t -> assertThat(((ErroDaApi) t).codigo().name()).isEqualTo("MALETA_EM_ACERTO"));
        assertThatThrownBy(() -> vendas.estornar(venda, v.vendedorId(), v.id(), aparelho, UUID.randomUUID(), "Engano."))
                .satisfies(t -> assertThat(((ErroDaApi) t).codigo().name()).isEqualTo("MALETA_EM_ACERTO"));
        assertThat(saldo()).isEqualTo(saldo);
        // Antes do início, do computador da maleta: aceito, e a peça volta à maleta.
        assertThat(vendas.estornar(venda, v.vendedorId(), v.id(), aparelho, UUID.randomUUID(), "Engano.", novoInicio.minusSeconds(5))
                .jaExistia()).isFalse();
        assertThat(saldo()).isEqualTo(saldo + 2);
    }

    // --- pelo envio de verdade (PostgreSQL, rota do envio e relógio corrigido) ---

    @Test
    void pelaRotaDoEnvioAVendaDeAntesEAceitaEADeDepoisERecusada() throws Exception {
        Thread.sleep(50);
        UUID antes = UUID.randomUUID();
        UUID depois = UUID.randomUUID();
        JsonNode r = enviar(v, List.of(
                vendaPeloEnvio(antes, maleta, cliente, List.of(linha(peca, 1)), List.of(pagamento("50.00", "DINHEIRO")),
                        inicio.minusSeconds(300)),
                vendaPeloEnvio(depois, maleta, cliente, List.of(linha(peca, 1)), List.of(pagamento("50.00", "DINHEIRO")),
                        Instant.now())));
        assertThat(resultado(r, 0)).isEqualTo("ACEITO");
        assertThat(resultado(r, 1)).isEqualTo("RECUSADO");
        assertThat(codigo(r, 1)).isEqualTo("MALETA_EM_ACERTO");
        assertThat(contar("SELECT count(*) FROM corona.vendas WHERE id IN (?, ?)", antes, depois)).isEqualTo(1);
    }

    private String statusDaMaleta() {
        return jdbc.queryForObject("SELECT status FROM corona.remessas WHERE id = ?", String.class, maleta);
    }
}
