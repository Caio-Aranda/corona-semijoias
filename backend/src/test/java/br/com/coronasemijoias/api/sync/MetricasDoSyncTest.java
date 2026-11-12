package br.com.coronasemijoias.api.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.avisos.AvisoDaDona;
import br.com.coronasemijoias.api.avisos.AvisosDaDonaController;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Métricas e alertas do sync (tarefa 5.11; ADR-0013, item 21). Escritos antes do código: transação aberta faz a
 * métrica subir e o alerta sair; computador parado gera o aviso da dona; duração do pull e do push e defeito da
 * conferência viram métrica.
 */
@ExtendWith(OutputCaptureExtension.class)
class MetricasDoSyncTest extends TesteDeIntegracao {

    @Autowired
    MockMvc mvc;

    @Autowired
    UsuariosDeTeste usuarios;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    MeterRegistry metricas;

    @Autowired
    MetricasDoSync metricasDoSync;

    @Autowired
    AlertaDeAtrasoDoSync alerta;

    @Autowired
    AvisosDaDonaController avisosDaDona;

    @Autowired
    SaudeDoSync saude;

    private final List<UsuarioDeTeste> criados = new ArrayList<>();

    /** Quem o teste criou sai dos avisos (o banco é compartilhado com os outros testes). */
    @AfterEach
    void limpar() {
        criados.forEach(usuarios::desativar);
        criados.clear();
    }

    private UsuarioDeTeste vendedor() {
        UsuarioDeTeste v = usuarios.criarVendedor();
        criados.add(v);
        return v;
    }

    private UUID computador(UsuarioDeTeste v, String situacao, Instant ultimoContato) {
        UUID id = usuarios.registrarAparelho(v, UUID.randomUUID(), situacao);
        jdbc.update("UPDATE corona.dispositivos SET ultimo_contato_em = ? WHERE id = ?", Timestamp.from(ultimoContato), id);
        return id;
    }

    private List<AvisoDaDona> avisosDoSync() {
        return avisosDaDona.listar().avisos().stream()
                .filter(a -> a.tipo().startsWith("COMPUTADOR_")).toList();
    }

    private static Instant ha(Duration d) {
        return Instant.now().minus(d);
    }

    // --- idade da transação mais antiga (limite seguro) ---

    @Test
    void transacaoAbertaFazAMetricaSubirEOAlertaSair(CapturedOutput saida) throws Exception {
        assertThat(metricasDoSync.idadeDaTransacaoMaisAntigaSegundos()).isLessThan(1.0);
        assertThat(alerta.verificar(Duration.ofSeconds(1))).isFalse();

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (var st = c.createStatement()) {
                st.execute("SELECT txid_current()"); // só a transação que ganhou um identificador atrasa o limite
            }
            Thread.sleep(1500);

            assertThat(metricasDoSync.idadeDaTransacaoMaisAntigaSegundos()).isGreaterThanOrEqualTo(1.0);
            assertThat(metricas.get("corona.sync.xmin.idade.segundos").gauge().value()).isGreaterThanOrEqualTo(1.0);
            assertThat(alerta.verificar(Duration.ofSeconds(1))).isTrue();
            assertThat(saida.getAll()).contains("ATRASO DO LIMITE SEGURO DO SYNC");
            assertThat(saude.health().getStatus().getCode()).isEqualTo("UP");
            assertThat(saude.health().getDetails()).containsKey("idadeDaTransacaoMaisAntigaSegundos");

            // O mesmo episódio não repete o alerta.
            int antes = contar(saida.getAll(), "ATRASO DO LIMITE SEGURO DO SYNC");
            alerta.verificar(Duration.ofSeconds(1));
            assertThat(contar(saida.getAll(), "ATRASO DO LIMITE SEGURO DO SYNC")).isEqualTo(antes);
            c.rollback();
        }

        assertThat(metricasDoSync.idadeDaTransacaoMaisAntigaSegundos()).isLessThan(1.0);
        assertThat(alerta.verificar(Duration.ofSeconds(1))).isFalse();
    }

    private static int contar(String texto, String trecho) {
        return texto.split(java.util.regex.Pattern.quote(trecho), -1).length - 1;
    }

    // --- computador parado ---

    @Test
    void computadorHaTresDiasSemContatoGeraOAvisoComONomeEOsDias() {
        UsuarioDeTeste maria = vendedor();
        computador(maria, "ATIVO", ha(Duration.ofDays(3).plusHours(2)));

        List<AvisoDaDona> avisos = avisosDoSync().stream().filter(a -> a.titulo().contains(maria.nome())).toList();
        assertThat(avisos).hasSize(1);
        assertThat(avisos.get(0).tipo()).isEqualTo("COMPUTADOR_SEM_ENVIAR");
        assertThat(avisos.get(0).titulo()).isEqualTo("O computador de " + maria.nome() + " está há 3 dias sem enviar.");
        assertThat(avisos.get(0).texto()).contains("conectar o Corona Vendas à internet");
        assertThat(metricas.get("corona.sync.computadores.sem.enviar").gauge().value()).isGreaterThanOrEqualTo(1.0);
        assertThat(saude.health().getDetails()).containsKey("computadoresSemEnviar");
    }

    @Test
    void computadorEmDiaOuForaDeUsoNaoGeraAviso() {
        UsuarioDeTeste emDia = vendedor();
        computador(emDia, "ATIVO", ha(Duration.ofDays(2)));
        UsuarioDeTeste trocado = vendedor();
        computador(trocado, "SUBSTITUIDO", ha(Duration.ofDays(10)));
        UsuarioDeTeste bloqueado = vendedor();
        computador(bloqueado, "BLOQUEADO", ha(Duration.ofDays(10)));
        UsuarioDeTeste desligado = vendedor();
        computador(desligado, "ATIVO", ha(Duration.ofDays(10)));
        usuarios.desativar(desligado);

        String todos = avisosDoSync().toString();
        for (UsuarioDeTeste v : List.of(emDia, trocado, bloqueado, desligado)) {
            assertThat(todos).doesNotContain(v.nome());
        }
    }

    @Test
    void envioAguardandoHaMuitosDiasGeraOAvisoMesmoComContatoRecente() {
        UsuarioDeTeste ana = vendedor();
        UUID c = computador(ana, "ATIVO", ha(Duration.ofHours(1)));
        jdbc.update("""
                UPDATE corona.dispositivos SET aguardando_envio = 4, aguardando_informado_em = ?, ultimo_push_em = ?
                 WHERE id = ?""", Timestamp.from(ha(Duration.ofDays(5))), Timestamp.from(ha(Duration.ofDays(5))), c);

        assertThat(avisosDoSync()).anySatisfy(a -> {
            assertThat(a.tipo()).isEqualTo("COMPUTADOR_SEM_ENVIAR");
            assertThat(a.titulo()).isEqualTo("O computador de " + ana.nome() + " está há 5 dias sem enviar.");
        });
    }

    @Test
    void segundoComputadorAguardandoLiberacaoGeraOAvisoDeComputadorNovo() {
        UsuarioDeTeste joana = vendedor();
        computador(joana, "ATIVO", ha(Duration.ofMinutes(5)));
        computador(joana, "AGUARDANDO_LIBERACAO", ha(Duration.ofMinutes(1)));

        assertThat(avisosDoSync()).anySatisfy(a -> {
            assertThat(a.tipo()).isEqualTo("COMPUTADOR_NOVO");
            assertThat(a.titulo()).isEqualTo(joana.nome() + " entrou num computador novo.");
            assertThat(a.texto()).contains("Trocar computador").contains("Bloquear computador");
        });
    }

    // --- duração do pull e do push, defeito da conferência ---

    @Test
    void pullPushEDefeitoDaConferenciaViramMetricas() throws Exception {
        UsuarioDeTeste v = vendedor();
        UUID aparelho = usuarios.aparelhoDe(v);
        String token = usuarios.tokenNoAparelho(v, aparelho);
        var quem = UsuariosDeTeste.comToken(token);

        long pulls = metricas.timer("corona.sync.pull.duracao").count();
        long pushes = metricas.timer("corona.sync.push.duracao").count();
        double defeitos = metricas.counter("corona.sync.conferencia.defeitos").count();

        mvc.perform(get("/api/vendedor/sync/pull").with(quem)).andExpect(status().isOk());
        mvc.perform(post("/api/vendedor/sync/push").with(quem).contentType(MediaType.APPLICATION_JSON)
                .content("{\"operacoes\":[],\"horaLocal\":\"" + Instant.now() + "\",\"aguardandoDepoisDeste\":0}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/vendedor/sync/conferencia/diferencas").with(quem).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tabelas\":[\"vendas\",\"clientes\"]}")).andExpect(status().isNoContent());

        assertThat(metricas.timer("corona.sync.pull.duracao").count()).isEqualTo(pulls + 1);
        assertThat(metricas.timer("corona.sync.push.duracao").count()).isEqualTo(pushes + 1);
        assertThat(metricas.counter("corona.sync.conferencia.defeitos").count()).isEqualTo(defeitos + 2);
    }

    @Test
    void metricasNaoTemEtiquetaComDadoDeVendedor() {
        metricas.getMeters().stream().filter(m -> m.getId().getName().startsWith("corona.sync."))
                .forEach(m -> assertThat(m.getId().getTags()).isEmpty());
    }
}
