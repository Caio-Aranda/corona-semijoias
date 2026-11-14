package br.com.coronasemijoias.api.acertos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * As travas das outras operações com a maleta em acerto (tarefa 7.4; ADR-0012, itens 3 e 4; invariante 3 do plano): nenhuma
 * venda nova, estorno pelo vendedor, devolução, devolução à dona, cancelamento nem remessa nova; a dona pode estornar. Escrito
 * antes das mudanças nos serviços. A troca tem o teste dela em TrocaTest (com o acerto de verdade).
 */
class TravasDoAcertoTest extends ApoioDoAcerto {

    private UsuarioDeTeste vendedor;
    private UUID cliente;
    private UUID peca;
    private UUID semana;
    private UUID maleta;
    private UUID venda;

    @BeforeEach
    void umaMaletaComUmaVenda() throws Exception {
        vendedor = usuarios.criarVendedor();
        cliente = cliente(vendedor);
        peca = peca("50.00", 40);
        semana = semana();
        maleta = maletaConferida(vendedor, semana, peca, 8);
        venda = vender(vendedor, maleta, cliente, peca, 2, "100.00");
    }

    private int linhas() {
        return contar("""
                SELECT (SELECT count(*) FROM corona.vendas) + (SELECT count(*) FROM corona.movimentos_remessa)
                     + (SELECT count(*) FROM corona.pagamentos_cliente) + (SELECT count(*) FROM corona.devolucoes)
                     + (SELECT count(*) FROM corona.movimentacoes_estoque)""");
    }

    private String codigo(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return corpoDe(r).get("codigo").asString();
    }

    @Test
    void vendaNovaOnlineERecusada() throws Exception {
        iniciar(vendedor, semana);
        int antes = linhas();
        assertThat(codigo(postar(VENDAS, como(vendedor), mapa("id", UUID.randomUUID(), "remessaId", maleta, "clienteId", cliente,
                "itens", List.of(linha(peca, 1)), "pagamentos", List.of(pagamento("50.00", "DINHEIRO"))))
                .andExpect(status().isConflict()))).isEqualTo("MALETA_EM_ACERTO");
        assertThat(linhas()).isEqualTo(antes);
    }

    @Test
    void estornoPeloVendedorERecusadoEADonaPodeEstornarEAPecaVoltaAMaleta() throws Exception {
        iniciar(vendedor, semana);
        int saldo = saldoDaMaleta(maleta, peca);
        int antes = linhas();
        assertThat(codigo(postar(VENDAS + "/" + venda + "/estorno", como(vendedor), mapa("id", UUID.randomUUID(), "motivo", "Engano."))
                .andExpect(status().isConflict()))).isEqualTo("MALETA_EM_ACERTO");
        assertThat(linhas()).isEqualTo(antes);

        postar("/api/dona/vendas/" + venda + "/estorno", dona(), mapa("id", UUID.randomUUID(), "motivo", "Sobra no acerto."))
                .andExpect(status().isCreated());
        assertThat(saldoDaMaleta(maleta, peca)).isEqualTo(saldo + 2);
        assertThat(statusDaMaleta(maleta)).isEqualTo("EM_ACERTO");
    }

    @Test
    void devolucaoDoClienteERecusada() throws Exception {
        iniciar(vendedor, semana);
        int antes = linhas();
        assertThat(codigo(postar("/api/vendedor/devolucoes", como(vendedor), mapa("id", UUID.randomUUID(), "vendaId", venda,
                "motivo", "ARREPENDIMENTO", "formaReembolso", "PIX", "itens",
                List.of(mapa("produtoId", peca, "quantidade", 1, "estado", "BOA")))).andExpect(status().isConflict())))
                .isEqualTo("MALETA_EM_ACERTO");
        assertThat(linhas()).isEqualTo(antes);
    }

    @Test
    void devolverADonaECancelarMaletaEmAcertoSaoRecusados() throws Exception {
        iniciar(vendedor, semana);
        int antes = linhas();
        assertThat(codigo(postar(REMESSAS + "/" + maleta + "/devolucoes", dona(), mapa("itens",
                List.of(mapa("id", UUID.randomUUID(), "produtoId", peca, "quantidade", 1)))).andExpect(status().isConflict())))
                .isEqualTo("MALETA_EM_ACERTO");
        assertThat(codigo(postar(REMESSAS + "/" + maleta + "/cancelar", dona(), mapa("motivo", "Teste."))
                .andExpect(status().isConflict()))).isEqualTo("MALETA_EM_ACERTO");
        assertThat(linhas()).isEqualTo(antes);
        assertThat(statusDaMaleta(maleta)).isEqualTo("EM_ACERTO");
    }

    @Test
    void remessaNovaNaoEEnviadaMasORascunhoPodeSerMontado() throws Exception {
        iniciar(vendedor, semana);
        UUID rascunho = rascunho(vendedor, semana(), "REPOSICAO", peca, 3);
        int estoqueCentral = contar("SELECT estoque_atual FROM corona.produtos WHERE id = ?", peca);
        assertThat(codigo(postar(REMESSAS + "/" + rascunho + "/enviar", dona(), null).andExpect(status().isConflict())))
                .isEqualTo("VENDEDOR_EM_ACERTO");
        assertThat(statusDaMaleta(rascunho)).isEqualTo("RASCUNHO");
        assertThat(contar("SELECT estoque_atual FROM corona.produtos WHERE id = ?", peca)).isEqualTo(estoqueCentral);
        // Montar mais um rascunho continua livre.
        rascunho(vendedor, semana(), "NOVA", peca, 1);
    }

    @Test
    void maletaEnviadaEmAcertoNaoPodeSerConferida() throws Exception {
        UUID semana2 = semana();
        UUID enviada = maletaEnviada(vendedor, semana2, "REPOSICAO", peca, 2);
        iniciar(vendedor, semana);
        assertThat(statusDaMaleta(enviada)).isEqualTo("EM_ACERTO");
        assertThat(codigo(postar("/api/vendedor/maletas/" + enviada + "/conferencia", como(vendedor), mapa("id", UUID.randomUUID(),
                "itens", List.of(mapa("produtoId", peca, "contada", 2)))).andExpect(status().isConflict())))
                .isEqualTo("MALETA_EM_ACERTO");
    }

    @Test
    void oRecebimentoPosteriorDoClienteContinuaAceito() throws Exception {
        UUID aprazo = UUID.randomUUID();
        postar(VENDAS, como(vendedor), mapa("id", aprazo, "remessaId", maleta, "clienteId", cliente, "itens", List.of(linha(peca, 1)),
                "pagamentos", List.of())).andExpect(status().isCreated());
        iniciar(vendedor, semana);
        postar(VENDAS + "/" + aprazo + "/recebimentos", como(vendedor),
                mapa("id", UUID.randomUUID(), "valor", "10.00", "forma", "PIX")).andExpect(status().isCreated());
    }

    @Test
    void aTrocaDeComputadorLevaTambemAMaletaEmAcerto() throws Exception {
        UUID antigo = usuarios.aparelhoDe(vendedor);
        como(vendedor);
        iniciar(vendedor, semana);
        UUID novo = usuarios.registrarAparelho(vendedor, UUID.randomUUID(), "AGUARDANDO_LIBERACAO");
        assertThat(jdbc.queryForObject("SELECT dispositivo_id FROM corona.remessas WHERE id = ?", UUID.class, maleta)).isEqualTo(antigo);
        postar("/api/dona/aparelhos/" + novo + "/liberar", dona(), null).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT dispositivo_id FROM corona.remessas WHERE id = ?", UUID.class, maleta)).isEqualTo(novo);
        assertThat(statusDaMaleta(maleta)).isEqualTo("EM_ACERTO");
    }

    @Test
    void aListaDeMaletasDoVendedorMostraAMaletaEmAcerto() throws Exception {
        iniciar(vendedor, semana);
        JsonNode lista = corpoDe(ler("/api/vendedor/maletas", como(vendedor)).andExpect(status().isOk()));
        assertThat(lista).hasSize(1);
        assertThat(lista.get(0).get("status").asString()).isEqualTo("EM_ACERTO");
        ler("/api/vendedor/maletas/" + maleta, como(vendedor)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EM_ACERTO"));
    }

    @Test
    void depoisDeDesistirTudoVoltaAFuncionar() throws Exception {
        UUID acerto = iniciar(vendedor, semana);
        postar(ACERTOS_DA_DONA + "/" + acerto + "/desistir", dona(), mapa("id", UUID.randomUUID(), "motivo", "Engano."))
                .andExpect(status().isCreated());
        vender(vendedor, maleta, cliente, peca, 1, "50.00");
        postar(VENDAS + "/" + venda + "/estorno", como(vendedor), mapa("id", UUID.randomUUID(), "motivo", "Engano."))
                .andExpect(status().isCreated());
        UUID rascunho = rascunho(vendedor, semana(), "REPOSICAO", peca, 1);
        postar(REMESSAS + "/" + rascunho + "/enviar", dona(), null).andExpect(status().isCreated());
    }
}
