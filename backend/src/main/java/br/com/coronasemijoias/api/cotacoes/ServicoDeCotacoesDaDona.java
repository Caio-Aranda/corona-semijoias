package br.com.coronasemijoias.api.cotacoes;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** A cotação do dia e a conversão para a tela da dona (tarefa 1.7). Só leitura; nada é gravado. */
@Service
public class ServicoDeCotacoesDaDona {

    private final CotacaoVigente vigente;
    private final Clock relogio;

    public ServicoDeCotacoesDaDona(CotacaoVigente vigente, Clock relogio) {
        this.vigente = vigente;
        this.relogio = relogio;
    }

    /** O dia de hoje em horário de Brasília (ADR-0006, item 7). */
    public LocalDate hoje() {
        return relogio.instant().atZone(CotacaoVigente.BRASILIA).toLocalDate();
    }

    /** A cotação em vigor para um lançamento de hoje; vazio só antes da primeira busca. */
    public Optional<Cotacao> cotacaoDeHoje() {
        return vigente.vigenteEm(relogio.instant());
    }

    /** A cotação em vigor ao fim do dia pedido, em Brasília (agora, se vier vazia). */
    public Optional<Cotacao> cotacaoDe(LocalDate data) {
        return vigente.vigenteEm(data == null ? relogio.instant() : CotacaoVigente.fimDoDia(data));
    }

    public record Convertido(BigDecimal dolar, BigDecimal guarani) {
    }

    /** Converte só para mostrar: a regra de arredondamento mora na ConversaoDeMoeda (via {@link Cotacao}). */
    public Convertido converter(Cotacao cotacao, BigDecimal reais) {
        return new Convertido(cotacao.emDolar(reais), cotacao.emGuarani(reais));
    }
}
