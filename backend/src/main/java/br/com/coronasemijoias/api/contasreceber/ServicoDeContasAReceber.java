package br.com.coronasemijoias.api.contasreceber;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.dinheiro.Dinheiro;
import br.com.coronasemijoias.api.comum.identificadores.UuidV7;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Contas a receber do cliente (tarefa 7.3; ADR-0012, itens 2 e 7). Único lugar que escreve em {@code contas_receber}
 * (inclusive os atalhos {@code valor_pago}, {@code valor_cancelado} e {@code status}), sempre na transação de quem chama
 * e com a linha travada. O app nunca manda valor de parcela: o servidor divide o que falta pagar com
 * {@link Dinheiro#dividirEmParcelas}. Tudo em Real; vencimento é o dia do calendário de Brasília.
 */
@Service
public class ServicoDeContasAReceber {

    public static final int MAXIMO_DE_PARCELAS = 24;
    private static final int MAXIMO_DE_MESES_ATE_O_PRIMEIRO_VENCIMENTO = 12;

    /** O que o app pode pedir: quantas parcelas e quando vence a primeira (as outras são mensais). */
    public record Parcelamento(Integer quantidade, LocalDate primeiroVencimento) {
    }

    /** Uma parcela gravada. */
    public record Parcela(UUID id, int numero, int total, LocalDate vencimento, BigDecimal valor) {
    }

    private final JdbcTemplate jdbc;

    ServicoDeContasAReceber(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Gera as parcelas da venda sobre o que falta pagar ({@code emAberto}: total menos o pago na hora). Sem nada a
     * receber, não grava nada (e recusa o parcelamento pedido). Sem {@code parcelamento}, uma parcela um mês depois da
     * venda. {@code online}: o primeiro vencimento não pode ser antes do dia da venda; pelo envio a data é aceita (a
     * correção da hora pode mudar o dia).
     */
    @Transactional
    public List<Parcela> gerarParcelas(UUID vendaId, UUID clienteId, BigDecimal emAberto, Parcelamento parcelamento,
            LocalDate diaDaVenda, boolean online) {
        if (emAberto.signum() <= 0) {
            if (parcelamento != null) {
                throw Validacoes.recusar("parcelamento", "Esta venda já está paga. Não há parcelas.");
            }
            return List.of();
        }
        int quantidade = 1;
        LocalDate primeiro = diaDaVenda.plusMonths(1);
        if (parcelamento != null) {
            if (parcelamento.quantidade() == null || parcelamento.quantidade() < 1
                    || parcelamento.quantidade() > MAXIMO_DE_PARCELAS) {
                throw Validacoes.recusar("parcelamento", "O número de parcelas vai de 1 a " + MAXIMO_DE_PARCELAS + ".");
            }
            quantidade = parcelamento.quantidade();
            if (parcelamento.primeiroVencimento() != null) {
                primeiro = parcelamento.primeiroVencimento();
            }
            if (online && primeiro.isBefore(diaDaVenda)) {
                throw Validacoes.recusar("parcelamento", "O primeiro vencimento não pode ser antes do dia da venda.");
            }
            if (primeiro.isAfter(diaDaVenda.plusMonths(MAXIMO_DE_MESES_ATE_O_PRIMEIRO_VENCIMENTO))) {
                throw Validacoes.recusar("parcelamento", "O primeiro vencimento está longe demais. Escolha uma data até um ano depois da venda.");
            }
        }
        List<BigDecimal> valores;
        try {
            valores = Dinheiro.dividirEmParcelas(emAberto, quantidade);
        } catch (IllegalArgumentException e) {
            throw Validacoes.recusar("parcelamento", "O valor a prazo (R$ " + emAberto.toPlainString().replace('.', ',')
                    + ") é pequeno demais para " + quantidade + " parcelas. Use menos parcelas.");
        }
        List<Parcela> parcelas = new ArrayList<>();
        for (int k = 1; k <= quantidade; k++) {
            // Sempre a partir da primeira: 31/01, 28/02, 31/03 (nunca 31/01, 28/02, 28/03).
            LocalDate vencimento = primeiro.plusMonths(k - 1L);
            UUID id = UuidV7.gerar();
            jdbc.update("""
                    INSERT INTO corona.contas_receber (id, devedor_tipo, cliente_id, origem_tipo, origem_id, venda_id, parcela,
                        total_parcelas, vencimento, valor)
                    VALUES (?, 'CLIENTE', ?, 'VENDA', ?, ?, ?, ?, ?, ?)""", id, clienteId, vendaId, vendaId, k, quantidade,
                    vencimento, valores.get(k - 1));
            parcelas.add(new Parcela(id, k, quantidade, vencimento, valores.get(k - 1)));
        }
        return parcelas;
    }
}
