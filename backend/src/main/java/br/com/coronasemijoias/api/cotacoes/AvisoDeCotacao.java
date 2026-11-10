package br.com.coronasemijoias.api.cotacoes;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * O aviso "a cotação não foi atualizada" para a dona (ADR-0006, item 12). <strong>Calculado, nunca gravado</strong>:
 * aparece quando existe um dia útil sem cotação entre os últimos 10 dias, e some sozinho quando a
 * recuperação grava o dia. O dia de hoje só conta depois da última tentativa (21:00 UTC). Sábado,
 * domingo e feriado bancário nunca contam.
 *
 * <p>Serviço interno: a rota e a tela vêm na Fase 1, depois do login.
 */
@Component
public class AvisoDeCotacao {

    private final PropriedadesDasCotacoes propriedades;
    private final RepositorioDeCotacoes repositorio;
    private final CalendarioBancario calendario;
    private final Clock relogio;

    public AvisoDeCotacao(
            PropriedadesDasCotacoes propriedades, RepositorioDeCotacoes repositorio, CalendarioBancario calendario,
            Clock relogio) {
        this.propriedades = propriedades;
        this.repositorio = repositorio;
        this.calendario = calendario;
        this.relogio = relogio;
    }

    /**
     * @param diasSemCotacao os dias úteis sem cotação (do mais antigo ao mais novo)
     * @param cotacaoEmUso   a data da cotação que a tela está usando (a mais recente), se já existe alguma
     */
    public record Aviso(List<LocalDate> diasSemCotacao, Optional<LocalDate> cotacaoEmUso) {

        public Aviso {
            diasSemCotacao = List.copyOf(diasSemCotacao);
        }

        public boolean aparece() {
            return !diasSemCotacao.isEmpty();
        }
    }

    public Aviso calcular() {
        ZonedDateTime agora = relogio.instant().atZone(ZoneOffset.UTC);
        LocalDate hoje = agora.toLocalDate();
        LocalTime hora = agora.toLocalTime();
        boolean hojeJaConta = hora.isAfter(propriedades.ultimaTentativa());
        int janela = propriedades.diasDeRecuperacao();

        LocalDate primeiro = hoje.minusDays(janela);
        LocalDate ultimo = hojeJaConta ? hoje : hoje.minusDays(1);
        Set<LocalDate> comCotacao = repositorio.diasComCotacao(primeiro, ultimo);
        List<LocalDate> semCotacao = new ArrayList<>();
        for (LocalDate dia = primeiro; !dia.isAfter(ultimo); dia = dia.plusDays(1)) {
            if (calendario.ehDiaUtil(dia) && !comCotacao.contains(dia)) {
                semCotacao.add(dia);
            }
        }
        return new Aviso(semCotacao, repositorio.maisRecente().map(Cotacao::dataCotacao));
    }
}
