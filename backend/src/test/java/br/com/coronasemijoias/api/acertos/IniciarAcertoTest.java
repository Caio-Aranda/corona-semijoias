package br.com.coronasemijoias.api.acertos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Iniciar o acerto (tarefa 7.4; ADR-0012, itens 2, 3 e 16; invariantes 1, 2 e 7 do plano). Escrito antes do serviço.
 */
class IniciarAcertoTest extends ApoioDoAcerto {

    private UsuarioDeTeste vendedor;
    private UUID peca;

    @BeforeEach
    void umVendedorComPeca() throws Exception {
        vendedor = usuarios.criarVendedor();
        peca = peca("50.00", 20);
    }

    private List<String> idsDasMaletas(JsonNode ficha) {
        List<String> ids = new ArrayList<>();
        ficha.get("maletas").forEach(m -> ids.add(m.get("id").asString()));
        return ids;
    }

    @Test
    void ligaNovaEReposicaoEnviadaEConferidaGuardandoOStatusDeAntes() throws Exception {
        UUID semana = semana();
        UUID nova = maletaConferida(vendedor, semana, peca, 5);
        UUID reposicao = maletaEnviada(vendedor, semana, "REPOSICAO", peca, 2);
        UUID rascunho = rascunho(vendedor, semana(), "REPOSICAO", peca, 1);
        UUID cancelada = rascunho(vendedor, semana(), "REPOSICAO", peca, 1);
        jdbc.update("UPDATE corona.remessas SET status = 'CANCELADA', motivo_cancelamento = 'teste' WHERE id = ?", cancelada);
        UsuarioDeTeste outro = usuarios.criarVendedor();
        UUID doOutro = maletaConferida(outro, semana(), peca, 3);

        UUID id = UUID.randomUUID();
        JsonNode ficha = corpoDe(postar(ACERTOS_DA_DONA, dona(), mapa("id", id, "vendedorId", vendedor.vendedorId(),
                "semanaId", semana)).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("EM_ACERTO"))
                .andExpect(jsonPath("$.pedidoPeloVendedor").value(false)));

        assertThat(idsDasMaletas(ficha)).containsExactlyInAnyOrder(nova.toString(), reposicao.toString());
        assertThat(statusDaMaleta(nova)).isEqualTo("EM_ACERTO");
        assertThat(statusDaMaleta(reposicao)).isEqualTo("EM_ACERTO");
        assertThat(statusDaMaleta(rascunho)).isEqualTo("RASCUNHO");
        assertThat(statusDaMaleta(cancelada)).isEqualTo("CANCELADA");
        assertThat(statusDaMaleta(doOutro)).isEqualTo("CONFERIDA");
        assertThat(jdbc.queryForObject("SELECT status_antes_do_acerto FROM corona.remessas WHERE id = ?", String.class, nova))
                .isEqualTo("CONFERIDA");
        assertThat(jdbc.queryForObject("SELECT status_antes_do_acerto FROM corona.remessas WHERE id = ?", String.class, reposicao))
                .isEqualTo("ENVIADA");
        assertThat(contar("SELECT count(*) FROM corona.remessas WHERE acerto_id = ?", id)).isEqualTo(2);
        assertThat(contar("SELECT count(*) FROM corona.auditoria WHERE acao = 'ACERTO_INICIADO' AND registro_id = ?", id)).isEqualTo(1);
    }

    @Test
    void asemanaSaiDaMaisRecenteDasMaletasOuDaEscolhidaPelaDona() throws Exception {
        UUID antiga = semana();
        maletaConferida(vendedor, antiga, peca, 2);
        UUID recente = semana();
        maletaConferida(vendedor, recente, peca, 2);
        JsonNode ficha = corpoDe(postar(ACERTOS_DA_DONA, dona(), mapa("id", UUID.randomUUID(), "vendedorId", vendedor.vendedorId()))
                .andExpect(status().isCreated()));
        assertThat(ficha.get("semanaId").asString()).isEqualTo(recente.toString());

        UsuarioDeTeste outro = usuarios.criarVendedor();
        UUID s1 = semana();
        maletaConferida(outro, s1, peca, 2);
        maletaConferida(outro, semana(), peca, 2);
        JsonNode escolhida = corpoDe(postar(ACERTOS_DA_DONA, dona(), mapa("id", UUID.randomUUID(), "vendedorId", outro.vendedorId(),
                "semanaId", s1)).andExpect(status().isCreated()));
        assertThat(escolhida.get("semanaId").asString()).isEqualTo(s1.toString());
    }

    @Test
    void aDonaIniciaAcertoSemMaletaNaSemanaDeHoje() throws Exception {
        LocalDate hoje = LocalDate.ofInstant(relogio.instant(), ZoneId.of("America/Sao_Paulo"));
        UUID daSemana = jdbc.query("SELECT id FROM corona.semanas WHERE deleted_at IS NULL AND ? BETWEEN inicio AND fim",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, hoje);
        if (daSemana == null) {
            daSemana = UUID.fromString(corpoDe(postar("/api/dona/semanas", dona(), mapa("numero", 99_500, "inicio", hoje.minusDays(1).toString(),
                    "fim", hoje.plusDays(5).toString())).andExpect(status().isCreated())).get("id").asString());
        }
        JsonNode ficha = corpoDe(postar(ACERTOS_DA_DONA, dona(), mapa("id", UUID.randomUUID(), "vendedorId", vendedor.vendedorId()))
                .andExpect(status().isCreated()));
        assertThat(ficha.get("semanaId").asString()).isEqualTo(daSemana.toString());
        assertThat(ficha.get("maletas")).isEmpty();
    }

    @Test
    void reenvioIgualDevolve200EOutroConteudoDevolve409() throws Exception {
        UUID semana = semana();
        maletaConferida(vendedor, semana, peca, 5);
        UUID id = UUID.randomUUID();
        var corpo = mapa("id", id, "vendedorId", vendedor.vendedorId(), "semanaId", semana);
        postar(ACERTOS_DA_DONA, dona(), corpo).andExpect(status().isCreated());
        postar(ACERTOS_DA_DONA, dona(), corpo).andExpect(status().isOk());
        assertThat(contar("SELECT count(*) FROM corona.acertos WHERE vendedor_id = ?", vendedor.vendedorId())).isEqualTo(1);
        // O mesmo id com outro vendedor ou outra semana é outro pedido.
        postar(ACERTOS_DA_DONA, dona(), mapa("id", id, "vendedorId", usuarios.criarVendedor().vendedorId(), "semanaId", semana))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("ACERTO_JA_REGISTRADO_DIFERENTE"));
        postar(ACERTOS_DA_DONA, dona(), mapa("id", id, "vendedorId", vendedor.vendedorId(), "semanaId", semana()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("ACERTO_JA_REGISTRADO_DIFERENTE"));
    }

    @Test
    void outroIdComAcertoEmAndamentoDevolve409ComOIdDoQueEstaEmAndamento() throws Exception {
        UUID semana = semana();
        maletaConferida(vendedor, semana, peca, 5);
        UUID primeiro = iniciar(vendedor, semana);
        postar(ACERTOS_DA_DONA, dona(), mapa("id", UUID.randomUUID(), "vendedorId", vendedor.vendedorId(), "semanaId", semana))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("ACERTO_EM_ANDAMENTO"))
                .andExpect(jsonPath("$.acertoId").value(primeiro.toString()));
    }

    @Test
    void semanaFechadaOuInexistenteEUmaSemanaJaAcertadaRecusam() throws Exception {
        UUID semana = semana();
        maletaConferida(vendedor, semana, peca, 5);
        br.com.coronasemijoias.api.testes.SemanasDeTeste.fecharNoBanco(jdbc, semana, usuarios.dona().id());
        postar(ACERTOS_DA_DONA, dona(), mapa("id", UUID.randomUUID(), "vendedorId", vendedor.vendedorId(), "semanaId", semana))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("SEMANA_FECHADA"));
        postar(ACERTOS_DA_DONA, dona(), mapa("id", UUID.randomUUID(), "vendedorId", vendedor.vendedorId(),
                "semanaId", UUID.randomUUID())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("SEMANA_NAO_ENCONTRADA"));
        assertThat(contar("SELECT count(*) FROM corona.acertos WHERE vendedor_id = ?", vendedor.vendedorId())).isZero();
    }

    @Test
    void umAcertoDaMesmaSemanaJaFeitoNaoSeRepete() throws Exception {
        UUID semana = semana();
        UUID id = UUID.randomUUID();
        // Um acerto na semana, de outro modo terminado (aqui desistido e depois outro em andamento).
        jdbc.update("""
                INSERT INTO corona.acertos (id, vendedor_id, semana_id, status, pedido_pelo_vendedor, iniciado_em, iniciado_por)
                VALUES (?, ?, ?, 'EM_ACERTO', false, now(), ?)""", id, vendedor.vendedorId(), semana, usuarios.dona().id());
        jdbc.update("""
                UPDATE corona.acertos SET status = 'DESISTIDO', desistencia_id = ?, desistido_em = now(), desistido_por = ?,
                       motivo_desistencia = 'x' WHERE id = ?""", UUID.randomUUID(), usuarios.dona().id(), id);
        UUID novo = iniciar(vendedor, semana);
        desistirPelaDona(novo);
        // Outro vendedor, mesma semana, sem problema.
        UsuarioDeTeste outro = usuarios.criarVendedor();
        postar(ACERTOS_DA_DONA, dona(), mapa("id", UUID.randomUUID(), "vendedorId", outro.vendedorId(), "semanaId", semana))
                .andExpect(status().isCreated());
    }

    private void desistirPelaDona(UUID acerto) throws Exception {
        postar(ACERTOS_DA_DONA + "/" + acerto + "/desistir", dona(), mapa("id", UUID.randomUUID(), "motivo", "teste"))
                .andExpect(status().isCreated());
    }

    // --- pedido pelo vendedor ---

    @Test
    void ovendedorPedeOProprioAcertoComOsPreRequisitos() throws Exception {
        UUID semana = semana();
        UUID maleta = maletaConferida(vendedor, semana, peca, 5);
        UUID id = UUID.randomUUID();
        postar(ACERTOS_DO_VENDEDOR, como(vendedor), mapa("id", id)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("EM_ACERTO")).andExpect(jsonPath("$.pedidoPeloVendedor").value(true));
        assertThat(statusDaMaleta(maleta)).isEqualTo("EM_ACERTO");
        assertThat(jdbc.queryForObject("SELECT dispositivo_id FROM corona.acertos WHERE id = ?", UUID.class, id))
                .isEqualTo(usuarios.aparelhoDe(vendedor));
        postar(ACERTOS_DO_VENDEDOR, como(vendedor), mapa("id", id)).andExpect(status().isOk());
    }

    @Test
    void vendedorSemMaletaNaoPedeAcerto() throws Exception {
        postar(ACERTOS_DO_VENDEDOR, como(vendedor), mapa("id", UUID.randomUUID())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("SEM_MALETA_PARA_ACERTO"));
    }

    @Test
    void vendedorComEnviosAguardandoNaoPedeAcertoMasADonaInicia() throws Exception {
        UUID semana = semana();
        maletaConferida(vendedor, semana, peca, 5);
        var token = como(vendedor);
        UUID aparelho = usuarios.aparelhoDe(vendedor);
        jdbc.update("UPDATE corona.dispositivos SET aguardando_envio = 3, aguardando_informado_em = now() WHERE id = ?", aparelho);
        postar(ACERTOS_DO_VENDEDOR, token, mapa("id", UUID.randomUUID())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("ENVIOS_AGUARDANDO"));
        UUID id = UUID.randomUUID();
        JsonNode ficha = corpoDe(postar(ACERTOS_DA_DONA, dona(), mapa("id", id, "vendedorId", vendedor.vendedorId(), "semanaId", semana))
                .andExpect(status().isCreated()));
        assertThat(ficha.get("enviosAguardando").asInt()).isEqualTo(3);
        // Fila vazia informada: aceita.
        jdbc.update("UPDATE corona.dispositivos SET aguardando_envio = 0 WHERE id = ?", aparelho);
    }

    @Test
    void maletaConferidaEmOutroComputadorBarraOPedidoDoVendedor() throws Exception {
        UUID maleta = maletaConferida(vendedor, semana(), peca, 5);
        var token = como(vendedor);
        UUID outroComputador = usuarios.registrarAparelho(vendedor, UUID.randomUUID(), "SUBSTITUIDO");
        jdbc.update("UPDATE corona.remessas SET dispositivo_id = ? WHERE id = ?", outroComputador, maleta);
        postar(ACERTOS_DO_VENDEDOR, token, mapa("id", UUID.randomUUID())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("MALETA_EM_OUTRO_APARELHO"));
    }

    @Test
    void vendedorInativoNaoPedeMasADonaIniciaPorEle() throws Exception {
        UUID semana = semana();
        maletaConferida(vendedor, semana, peca, 5);
        var token = como(vendedor);
        jdbc.update("UPDATE corona.vendedores SET ativo = false WHERE id = ?", vendedor.vendedorId());
        try {
            postar(ACERTOS_DO_VENDEDOR, token, mapa("id", UUID.randomUUID())).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.codigo").value("VENDEDOR_DESATIVADO"));
            postar(ACERTOS_DA_DONA, dona(), mapa("id", UUID.randomUUID(), "vendedorId", vendedor.vendedorId(), "semanaId", semana))
                    .andExpect(status().isCreated());
        } finally {
            jdbc.update("UPDATE corona.vendedores SET ativo = true WHERE id = ?", vendedor.vendedorId());
        }
    }

    @Test
    void vendedorInexistenteDevolve404() throws Exception {
        postar(ACERTOS_DA_DONA, dona(), mapa("id", UUID.randomUUID(), "vendedorId", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }
}
