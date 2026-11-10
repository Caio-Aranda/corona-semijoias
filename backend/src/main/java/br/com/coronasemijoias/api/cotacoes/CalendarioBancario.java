package br.com.coronasemijoias.api.cotacoes;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Feriados bancários nacionais, sem arquivo do BCB (ADR-0006, item 3). Ficam no código: se surgir
 * feriado nacional novo, acrescente aqui.
 *
 * <p>Fixos: 1/1, 21/4, 1/5, 7/9, 12/10, 2/11, 15/11, 20/11 e 25/12. Móveis, pela Páscoa: segunda e
 * terça de Carnaval (48 e 47 dias antes), Sexta-feira Santa (2 dias antes) e Corpus Christi (60 depois).
 * A Quarta-feira de Cinzas é dia útil.
 *
 * <p>Errar um feriado não grava número errado: dia útil que não tem arquivo vira um aviso falso; feriado
 * que tem arquivo é lido e gravado normalmente.
 */
@Component
public class CalendarioBancario {

    private static final Set<MonthDay> FIXOS = Set.of(
            MonthDay.of(1, 1), MonthDay.of(4, 21), MonthDay.of(5, 1), MonthDay.of(9, 7), MonthDay.of(10, 12),
            MonthDay.of(11, 2), MonthDay.of(11, 15), MonthDay.of(11, 20), MonthDay.of(12, 25));

    public boolean ehFeriado(LocalDate dia) {
        if (FIXOS.contains(MonthDay.from(dia))) {
            return true;
        }
        LocalDate pascoa = pascoa(dia.getYear());
        return dia.equals(pascoa.minusDays(48))
                || dia.equals(pascoa.minusDays(47))
                || dia.equals(pascoa.minusDays(2))
                || dia.equals(pascoa.plusDays(60));
    }

    /** Segunda a sexta que não é feriado bancário: o dia em que o BCB publica o arquivo. */
    public boolean ehDiaUtil(LocalDate dia) {
        DayOfWeek semana = dia.getDayOfWeek();
        return semana != DayOfWeek.SATURDAY && semana != DayOfWeek.SUNDAY && !ehFeriado(dia);
    }

    /** Domingo de Páscoa no calendário gregoriano (algoritmo de Meeus, Jones e Butcher). */
    static LocalDate pascoa(int ano) {
        int a = ano % 19;
        int b = ano / 100;
        int c = ano % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int mes = (h + l - 7 * m + 114) / 31;
        int dia = (h + l - 7 * m + 114) % 31 + 1;
        return LocalDate.of(ano, mes, dia);
    }
}
