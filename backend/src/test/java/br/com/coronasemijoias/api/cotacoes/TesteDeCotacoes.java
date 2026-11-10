package br.com.coronasemijoias.api.cotacoes;

import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Base dos testes de cotações com banco. Cada teste roda numa transação desfeita no fim (a tabela é
 * imutável: não dá para apagar o que o teste gravou). O relógio e o cliente HTTP são falsos: nenhum
 * teste depende da hora nem da internet.
 */
@Transactional
abstract class TesteDeCotacoes extends TesteDeIntegracao {

    static final String ARQUIVO_REAL = "20261106.csv";

    @Autowired
    JdbcTemplate jdbc;

    RelogioAjustavel relogio;
    ClienteFalso cliente;
    RepositorioDeCotacoes repositorio;
    CalendarioBancario calendario;
    PropriedadesDasCotacoes propriedades;
    BuscaDeCotacoes busca;
    AvisoDeCotacao aviso;
    CotacaoVigente vigente;

    @BeforeEach
    void montar() {
        relogio = new RelogioAjustavel();
        cliente = new ClienteFalso();
        repositorio = new RepositorioDeCotacoes(jdbc);
        calendario = new CalendarioBancario();
        propriedades = new PropriedadesDasCotacoes(null, null, null, null, null);
        busca = new BuscaDeCotacoes(propriedades, cliente, repositorio, calendario, relogio);
        aviso = new AvisoDeCotacao(propriedades, repositorio, calendario, relogio);
        vigente = new CotacaoVigente(repositorio);
    }

    /** Ajusta o relógio e faz uma rodada de busca. */
    List<LocalDate> rodadaAs(String instanteUtc) {
        relogio.em(instanteUtc);
        return busca.rodar();
    }

    /** Ajusta o relógio e calcula o aviso. */
    AvisoDeCotacao.Aviso avisoAs(String instanteUtc) {
        relogio.em(instanteUtc);
        return aviso.calcular();
    }

    /** O arquivo real de 06/11/2026 com todas as datas trocadas para o dia pedido. */
    static String arquivoValidoDe(LocalDate dia) {
        String data = "%02d/%02d/%d".formatted(dia.getDayOfMonth(), dia.getMonthValue(), dia.getYear());
        return LeitorDoArquivoDeFechamentoTest.arquivo(ARQUIVO_REAL).replace("06/11/2026", data);
    }

    /** Grava direto uma cotação (as taxas do arquivo real) para montar o cenário do teste. */
    void gravarCotacaoDe(LocalDate dia) {
        TaxasDoDia taxas = LeitorDoArquivoDeFechamento.ler(
                LocalDate.of(2026, 11, 6), LeitorDoArquivoDeFechamentoTest.arquivo(ARQUIVO_REAL));
        repositorio.gravar(dia, taxas, Instant.parse("2026-01-01T00:00:00Z"), "teste");
    }

    /** Grava a cotação de todo dia útil dos 12 dias até o fim (inclusive): nenhum dia da janela fica sem cotação. */
    void gravarUteisAte(LocalDate fim) {
        for (LocalDate dia = fim.minusDays(12); !dia.isAfter(fim); dia = dia.plusDays(1)) {
            if (calendario.ehDiaUtil(dia)) {
                gravarCotacaoDe(dia);
            }
        }
    }

    int linhasNoBanco() {
        return jdbc.queryForObject("SELECT count(*) FROM corona.cotacoes", Integer.class);
    }

    /** Relógio que o teste ajusta, sempre em UTC. */
    static final class RelogioAjustavel extends Clock {

        private Instant agora = Instant.parse("2026-11-09T12:00:00Z");

        void em(String instanteUtc) {
            agora = Instant.parse(instanteUtc);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zona) {
            return this;
        }

        @Override
        public Instant instant() {
            return agora;
        }
    }

    /**
     * O "BCB" dos testes: cada dia pode ter uma resposta (código e texto) ou uma falha de rede; o que não foi
     * combinado responde 404. Guarda todos os dias pedidos.
     */
    static final class ClienteFalso implements ClienteDoArquivo {

        private final Map<LocalDate, RespostaDoArquivo> respostas = new HashMap<>();
        private final Map<LocalDate, IOException> falhas = new HashMap<>();
        private final List<LocalDate> pedidos = new ArrayList<>();
        private Duration ultimoTempoLimite;

        void arquivo(LocalDate dia, String texto) {
            falhas.remove(dia);
            respostas.put(dia, new RespostaDoArquivo(200, texto));
        }

        void status(LocalDate dia, int status) {
            falhas.remove(dia);
            respostas.put(dia, new RespostaDoArquivo(status, ""));
        }

        void redeCaida(LocalDate dia) {
            respostas.remove(dia);
            falhas.put(dia, new IOException("sem rede (teste)"));
        }

        void limpar(LocalDate dia) {
            respostas.remove(dia);
            falhas.remove(dia);
        }

        List<LocalDate> pedidos() {
            return List.copyOf(pedidos);
        }

        long vezesPedido(LocalDate dia) {
            return pedidos.stream().filter(dia::equals).count();
        }

        void esquecerPedidos() {
            pedidos.clear();
        }

        Duration ultimoTempoLimite() {
            return ultimoTempoLimite;
        }

        @Override
        public RespostaDoArquivo buscar(URI endereco, Duration tempoLimite) throws IOException {
            String nome = endereco.getPath().substring(endereco.getPath().lastIndexOf('/') + 1);
            LocalDate dia = LocalDate.parse(nome.substring(0, 8), java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            pedidos.add(dia);
            ultimoTempoLimite = tempoLimite;
            if (falhas.containsKey(dia)) {
                throw falhas.get(dia);
            }
            return respostas.getOrDefault(dia, new RespostaDoArquivo(404, ""));
        }
    }
}
