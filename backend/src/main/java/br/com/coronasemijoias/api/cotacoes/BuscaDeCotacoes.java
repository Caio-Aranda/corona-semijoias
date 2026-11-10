package br.com.coronasemijoias.api.cotacoes;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A busca diária das cotações no arquivo de fechamento do BCB (ADR-0006, itens 2 e 3). Quem chama
 * (a agenda, ou o teste) dispara {@link #rodar()}; o relógio e o cliente HTTP entram por parâmetro.
 *
 * <p>Regras de cada rodada:
 * <ul>
 *   <li>só de segunda a sexta (em UTC) e só entre a primeira e a última tentativa do dia (16:30 a 21:00 UTC);</li>
 *   <li>tenta o dia de hoje (mesmo que o calendário o marque como feriado: se houver arquivo, ele é gravado)
 *       e, de novo, os dias úteis dos últimos 10 dias que ficaram sem cotação (recuperação);</li>
 *   <li>dia que já tem cotação nunca é relido nem sobrescrito;</li>
 *   <li>qualquer resultado que não seja um arquivo válido (404, erro de rede, tempo esgotado, outro código
 *       HTTP, arquivo fora da regra) só faz tentar de novo na próxima rodada, <strong>sem aviso</strong>: o aviso é
 *       calculado depois, por {@link AvisoDeCotacao}.</li>
 * </ul>
 */
@Component
public class BuscaDeCotacoes {

    private static final Logger LOG = LoggerFactory.getLogger(BuscaDeCotacoes.class);
    private static final DateTimeFormatter DIA_NO_ENDERECO = DateTimeFormatter.ofPattern("uuuuMMdd");

    private final PropriedadesDasCotacoes propriedades;
    private final ClienteDoArquivo cliente;
    private final RepositorioDeCotacoes repositorio;
    private final CalendarioBancario calendario;
    private final Clock relogio;

    public BuscaDeCotacoes(
            PropriedadesDasCotacoes propriedades, ClienteDoArquivo cliente, RepositorioDeCotacoes repositorio,
            CalendarioBancario calendario, Clock relogio) {
        this.propriedades = propriedades;
        this.cliente = cliente;
        this.repositorio = repositorio;
        this.calendario = calendario;
        this.relogio = relogio;
    }

    /** Uma rodada de busca. Devolve os dias que foram gravados agora (vazio se nada mudou). */
    public List<LocalDate> rodar() {
        ZonedDateTime agora = relogio.instant().atZone(ZoneOffset.UTC);
        LocalDate hoje = agora.toLocalDate();
        LocalTime hora = agora.toLocalTime();
        DayOfWeek semana = hoje.getDayOfWeek();
        if (semana == DayOfWeek.SATURDAY || semana == DayOfWeek.SUNDAY) {
            return List.of();
        }
        if (hora.isBefore(propriedades.primeiraTentativa()) || hora.isAfter(propriedades.ultimaTentativa())) {
            return List.of();
        }

        int janela = propriedades.diasDeRecuperacao();
        Set<LocalDate> jaTem = repositorio.diasComCotacao(hoje.minusDays(janela), hoje);
        List<LocalDate> aBuscar = new ArrayList<>();
        if (!jaTem.contains(hoje)) {
            aBuscar.add(hoje);
        }
        for (int atras = 1; atras <= janela; atras++) {
            LocalDate dia = hoje.minusDays(atras);
            if (calendario.ehDiaUtil(dia) && !jaTem.contains(dia)) {
                aBuscar.add(dia);
            }
        }

        List<LocalDate> gravados = new ArrayList<>();
        for (LocalDate dia : aBuscar) {
            if (tentar(dia)) {
                gravados.add(dia);
            }
        }
        return List.copyOf(gravados);
    }

    /** Uma tentativa para um dia. Nunca lança por causa do BCB: o resultado ruim só vira registro no log. */
    private boolean tentar(LocalDate dia) {
        URI endereco = enderecoDo(dia);
        try {
            ClienteDoArquivo.RespostaDoArquivo resposta = cliente.buscar(endereco, propriedades.tempoLimiteDoPedido());
            if (resposta.status() == 404) {
                LOG.info("Cotações: ainda não há arquivo do BCB para {} (404).", dia);
                return false;
            }
            if (resposta.status() != 200) {
                LOG.warn("Cotações: o BCB respondeu {} para {}.", resposta.status(), dia);
                return false;
            }
            TaxasDoDia taxas = LeitorDoArquivoDeFechamento.ler(dia, resposta.corpo());
            boolean gravou = repositorio.gravar(dia, taxas, relogio.instant(), endereco.toString());
            if (gravou) {
                LOG.info("Cotações: gravada a cotação de {}.", dia);
            }
            return gravou;
        } catch (ArquivoForaDaRegraException e) {
            LOG.warn("Cotações: o arquivo de {} está fora da regra e foi recusado: {}", dia, e.getMessage());
            return false;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Cotações: não foi possível buscar {}: {}", dia, e.toString());
            return false;
        }
    }

    URI enderecoDo(LocalDate dia) {
        return URI.create(propriedades.urlDoArquivo().replace(
                PropriedadesDasCotacoes.MARCADOR_DO_DIA, DIA_NO_ENDERECO.format(dia)));
    }
}
