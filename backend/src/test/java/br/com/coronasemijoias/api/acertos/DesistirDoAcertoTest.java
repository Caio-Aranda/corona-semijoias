package br.com.coronasemijoias.api.acertos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Desistir do acerto: tudo volta como estava e nada de dinheiro é gravado (tarefa 7.4; ADR-0012, item 3 e F5; invariantes 4
 * e 7 do plano). Escrito antes do serviço.
 */
class DesistirDoAcertoTest extends ApoioDoAcerto {

    private UsuarioDeTeste vendedor;
    private UUID peca;
    private UUID semana;
    private UUID conferida;
    private UUID enviada;

    @BeforeEach
    void umAcertoComDuasMaletas() throws Exception {
        vendedor = usuarios.criarVendedor();
        peca = peca("50.00", 30);
        semana = semana();
        conferida = maletaConferida(vendedor, semana, peca, 6);
        enviada = maletaEnviada(vendedor, semana, "REPOSICAO", peca, 3);
        // Uma venda antes do acerto: o saldo da maleta já não é o enviado.
        vender(vendedor, conferida, cliente(vendedor), peca, 2, "100.00");
    }

    private int linhasDeMovimento() {
        return contar("""
                SELECT (SELECT count(*) FROM corona.movimentos_remessa) + (SELECT count(*) FROM corona.movimentacoes_estoque)
                     + (SELECT count(*) FROM corona.vendas) + (SELECT count(*) FROM corona.pagamentos_cliente)
                     + (SELECT count(*) FROM corona.contas_receber)""");
    }

    private void desistir(UUID acerto, UUID idDoComando, String motivo) throws Exception {
        postar(ACERTOS_DA_DONA + "/" + acerto + "/desistir", dona(), mapa("id", idDoComando, "motivo", motivo))
                .andExpect(status().isCreated());
    }

    @Test
    void asMaletasVoltamComoEstavamSemMovimentoNemDinheiroNovo() throws Exception {
        UUID acerto = iniciar(vendedor, semana);
        int saldoConferida = saldoDaMaleta(conferida, peca);
        int saldoEnviada = saldoDaMaleta(enviada, peca);
        int antes = contar("""
                SELECT (SELECT count(*) FROM corona.movimentos_remessa WHERE remessa_id IN (?, ?))""", conferida, enviada);
        postar(ACERTOS_DA_DONA + "/" + acerto + "/leituras", dona(),
                mapa("id", UUID.randomUUID(), "produtoId", peca, "quantidade", 2, "estadoPeca", "BOA")).andExpect(status().isCreated());
        int linhas = linhasDeMovimento();

        desistir(acerto, UUID.randomUUID(), "A dona desistiu para contar de novo.");

        assertThat(statusDaMaleta(conferida)).isEqualTo("CONFERIDA");
        assertThat(statusDaMaleta(enviada)).isEqualTo("ENVIADA");
        assertThat(contar("SELECT count(*) FROM corona.remessas WHERE id IN (?, ?) AND (acerto_id IS NOT NULL OR status_antes_do_acerto IS NOT NULL)",
                conferida, enviada)).isZero();
        assertThat(saldoDaMaleta(conferida, peca)).isEqualTo(saldoConferida);
        assertThat(saldoDaMaleta(enviada, peca)).isEqualTo(saldoEnviada);
        assertThat(contar("SELECT count(*) FROM corona.movimentos_remessa WHERE remessa_id IN (?, ?)", conferida, enviada)).isEqualTo(antes);
        assertThat(linhasDeMovimento()).isEqualTo(linhas);
        // As leituras ficam como histórico.
        assertThat(contar("SELECT count(*) FROM corona.leituras_acerto WHERE acerto_id = ?", acerto)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM corona.acertos WHERE id = ?", String.class, acerto)).isEqualTo("DESISTIDO");
        assertThat(contar("SELECT count(*) FROM corona.auditoria WHERE acao = 'ACERTO_DESISTIDO' AND registro_id = ?", acerto)).isEqualTo(1);
    }

    @Test
    void amaletaVoltaAVenderEUmNovoAcertoNaMesmaSemanaEAceito() throws Exception {
        UUID acerto = iniciar(vendedor, semana);
        desistir(acerto, UUID.randomUUID(), "Engano.");
        vender(vendedor, conferida, cliente(vendedor), peca, 1, "50.00");
        UUID outro = UUID.randomUUID();
        postar(ACERTOS_DA_DONA, dona(), mapa("id", outro, "vendedorId", vendedor.vendedorId(), "semanaId", semana))
                .andExpect(status().isCreated());
    }

    @Test
    void reenvioIgualDevolve200EOutroIdEmAcertoDesistidoDevolve409() throws Exception {
        UUID acerto = iniciar(vendedor, semana);
        UUID comando = UUID.randomUUID();
        desistir(acerto, comando, "Engano.");
        postar(ACERTOS_DA_DONA + "/" + acerto + "/desistir", dona(), mapa("id", comando, "motivo", "Engano."))
                .andExpect(status().isOk());
        postar(ACERTOS_DA_DONA + "/" + acerto + "/desistir", dona(), mapa("id", UUID.randomUUID(), "motivo", "Engano."))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("DESISTENCIA_JA_REGISTRADA_DIFERENTE"));
        assertThat(contar("SELECT count(*) FROM corona.auditoria WHERE acao = 'ACERTO_DESISTIDO' AND registro_id = ?", acerto)).isEqualTo(1);
    }

    @Test
    void oMotivoEObrigatorio() throws Exception {
        UUID acerto = iniciar(vendedor, semana);
        for (String motivo : new String[] {"", "   "}) {
            postar(ACERTOS_DA_DONA + "/" + acerto + "/desistir", dona(), mapa("id", UUID.randomUUID(), "motivo", motivo))
                    .andExpect(status().isBadRequest());
        }
        postar(ACERTOS_DA_DONA + "/" + acerto + "/desistir", dona(), mapa("id", UUID.randomUUID())).andExpect(status().isBadRequest());
        assertThat(statusDaMaleta(conferida)).isEqualTo("EM_ACERTO");
    }

    @Test
    void acertoInexistenteDevolve404() throws Exception {
        postar(ACERTOS_DA_DONA + "/" + UUID.randomUUID() + "/desistir", dona(), mapa("id", UUID.randomUUID(), "motivo", "x"))
                .andExpect(status().isNotFound());
        assertThat(List.of(conferida, enviada)).hasSize(2);
    }
}
