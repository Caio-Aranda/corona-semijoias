package br.com.coronasemijoias.api.cotacoes;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Qual cotação vale (ADR-0006, item 7). Só leitura; a conversão em si é da
 * {@link br.com.coronasemijoias.api.comum.dinheiro.ConversaoDeMoeda}.
 *
 * <ul>
 *   <li><strong>Lançamento</strong> (venda, pagamento, devolução...): a cotação de maior {@code data_cotacao}
 *       menor ou igual à data do lançamento <em>em horário de Brasília</em>. Sábado, domingo e feriado caem
 *       no último dia útil; hoje, antes da busca, cai no último dia útil.</li>
 *   <li><strong>O que não é lançamento</strong> (preço da peça, limite, saldo da maleta) e o <strong>acerto no
 *       fechamento</strong> (que grava o {@code cotacao_id}, Fase 7): a cotação mais recente.</li>
 * </ul>
 * Vazio só no sistema novo, antes da primeira busca.
 */
@Component
public class CotacaoVigente {

    /** Horário de Brasília (UTC-3, sem horário de verão hoje). */
    public static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");

    private final RepositorioDeCotacoes repositorio;

    public CotacaoVigente(RepositorioDeCotacoes repositorio) {
        this.repositorio = repositorio;
    }

    /** A cotação de um lançamento feito neste instante (UTC), pela data em Brasília. */
    public Optional<Cotacao> paraLancamento(Instant momentoDoLancamento) {
        return paraLancamentoEm(momentoDoLancamento.atZone(BRASILIA).toLocalDate());
    }

    /** A cotação de um lançamento cuja data em Brasília já é conhecida. */
    public Optional<Cotacao> paraLancamentoEm(LocalDate dataEmBrasilia) {
        return repositorio.ateODia(dataEmBrasilia);
    }

    /** A mais recente: para o que não é lançamento e para o acerto, no momento do fechamento. */
    public Optional<Cotacao> maisRecente() {
        return repositorio.maisRecente();
    }
}
