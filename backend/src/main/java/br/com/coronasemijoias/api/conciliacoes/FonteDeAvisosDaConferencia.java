package br.com.coronasemijoias.api.conciliacoes;

import br.com.coronasemijoias.api.avisos.AvisoDaDona;
import br.com.coronasemijoias.api.avisos.FonteDeAvisos;
import br.com.coronasemijoias.api.conciliacoes.ConsultasDaConferenciaDiaria.Rodada;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * "O que precisa da sua atenção": o resultado da conferência diária (tarefa 8.4; ADR-0015, item 6). Olha só a ÚLTIMA rodada, calculado
 * na hora e nunca gravado: o aviso some sozinho quando uma rodada nova dá tudo certo.
 *
 * <ul>
 *   <li>última rodada com diferença: {@link #COM_DIFERENCA}, com quantas diferenças;</li>
 *   <li>última rodada que não terminou, ou nenhuma rodada, ou a última começou há mais de 26 horas: {@link #NAO_RODOU};</li>
 *   <li>última rodada sem diferença e de menos de 26 horas: nenhum aviso.</li>
 * </ul>
 */
@Component
public class FonteDeAvisosDaConferencia implements FonteDeAvisos {

    public static final String COM_DIFERENCA = "CONFERENCIA_COM_DIFERENCA";
    public static final String NAO_RODOU = "CONFERENCIA_NAO_RODOU";
    /** Padrão R2 do ADR-0015: a conferência automática é diária; com folga de duas horas, 26 horas sem rodar avisa. */
    public static final Duration LIMITE_SEM_RODAR = Duration.ofHours(26);

    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");

    private final ConsultasDaConferenciaDiaria consultas;
    private final Clock relogio;

    FonteDeAvisosDaConferencia(ConsultasDaConferenciaDiaria consultas, Clock relogio) {
        this.consultas = consultas;
        this.relogio = relogio;
    }

    @Override
    public List<AvisoDaDona> avisos() {
        return avisosDe(consultas.ultima(), Instant.now(relogio));
    }

    /** A regra, sem banco: a última rodada (se houver) e a hora de agora. */
    public static List<AvisoDaDona> avisosDe(Optional<Rodada> ultima, Instant agora) {
        List<AvisoDaDona> avisos = new ArrayList<>();
        if (ultima.isEmpty()) {
            avisos.add(naoRodou("A conferência automática ainda não rodou. Toque em Conferir agora.", List.of()));
            return avisos;
        }
        Rodada r = ultima.get();
        List<LocalDate> dia = List.of(r.iniciadaEm().atZone(BRASILIA).toLocalDate());
        boolean velha = Duration.between(r.iniciadaEm(), agora).compareTo(LIMITE_SEM_RODAR) > 0;
        if ("COM_DIFERENCA".equals(r.situacao())) {
            String titulo = "A conferência de hoje achou " + (r.totalDiferencas() == 1 ? "1 diferença" : r.totalDiferencas() + " diferenças");
            avisos.add(new AvisoDaDona(COM_DIFERENCA, titulo, "Abra a conferência para ver o que não bate. Nada foi mudado sozinho.", dia));
        }
        if ("FALHOU".equals(r.situacao())) {
            avisos.add(naoRodou("Uma parte da conferência não terminou. Toque em Conferir agora; se continuar, avise o suporte.", dia));
        } else if (velha) {
            avisos.add(naoRodou("A conferência automática não rodou nas últimas 26 horas. Toque em Conferir agora.", dia));
        }
        return avisos;
    }

    private static AvisoDaDona naoRodou(String texto, List<LocalDate> dias) {
        return new AvisoDaDona(NAO_RODOU, "A conferência de hoje não rodou", texto, dias);
    }
}
