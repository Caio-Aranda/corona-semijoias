package br.com.coronasemijoias.api.sync;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Catálogo dos formatos de operação da outbox e de até quando cada um é aceito (tarefa 5.13; ADR-0013, item 17; dúvida
 * R5). O formato mais novo de um tipo vale sempre; o anterior vale até {@code publicação do seguinte + prazo} (90 dias,
 * {@code corona.sync.formato-antigo-dias}), e só então o push o recusa com {@code FORMATO_NAO_ACEITO}.
 *
 * <p>"Conhecido" é mais largo que "aceito agora": o servidor continua sabendo ler um formato vencido, para responder
 * {@code JA_RECEBIDO} a quem reenvia o que já entrou e para a quarentena guardar sem aplicar.
 *
 * <p><b>Para publicar um formato novo:</b> acrescente aqui a linha com a data em que o app que o grava sai (nunca
 * mude a data de um formato já publicado) e o leitor dele em {@link OperacoesDoVendedor}. Subir a versão mínima do
 * Corona Vendas nunca tira o suporte a um formato: só o prazo faz isso (ADR-0013, item 17).
 */
@Component
public class FormatosDeOperacao {

    /** Dia em que o formato 1 de todos os tipos foi publicado (tarefa 5.5). */
    static final Instant FORMATO_1 = Instant.parse("2026-11-11T00:00:00Z");

    /** Dia em que o formato 1 dos tipos da Fase 6 foi publicado (tarefa 6.7). A VENDA com crédito não ganha formato. */
    static final Instant FORMATO_1_DA_FASE_6 = Instant.parse("2026-11-13T00:00:00Z");

    /** Dia em que o formato 1 dos tipos da Fase 7 (recebimento do cliente) foi publicado (tarefa 7.3). */
    static final Instant FORMATO_1_DA_FASE_7 = Instant.parse("2026-11-14T00:00:00Z");

    /** Dia em que o formato 1 dos tipos do consignado ao cliente e o formato 2 do cadastro do cliente foram publicados (tarefa 9.1). */
    static final Instant FORMATO_1_DO_CONSIGNADO = Instant.parse("2026-11-19T00:00:00Z");

    /**
     * Dia em que o formato 2 do {@code ACERTO_NOTA} (sem porcentagem: o servidor decide a comissão pela meta, §23.13) foi
     * publicado, com o Corona Vendas 1.3.0. O formato 1 vale até 90 dias depois deste dia. Ajustar para o dia real da publicação
     * do 1.3.0, enquanto ele não saiu.
     */
    static final Instant FORMATO_2_DO_ACERTO_DA_NOTA = Instant.parse("2026-12-01T00:00:00Z");

    private static final Map<String, Map<Integer, Instant>> PRODUCAO = Map.ofEntries(
            Map.entry("VENDA", Map.of(1, FORMATO_1)),
            Map.entry("ESTORNO_VENDA", Map.of(1, FORMATO_1)),
            Map.entry("CLIENTE_NOVO", Map.of(1, FORMATO_1, 2, FORMATO_1_DO_CONSIGNADO)),
            Map.entry("CLIENTE_ALTERADO", Map.of(1, FORMATO_1, 2, FORMATO_1_DO_CONSIGNADO)),
            Map.entry("CONFERENCIA", Map.of(1, FORMATO_1)),
            Map.entry("DEVOLUCAO", Map.of(1, FORMATO_1_DA_FASE_6)),
            Map.entry("PEDIDO_AUTORIZACAO", Map.of(1, FORMATO_1_DA_FASE_6)),
            Map.entry("TROCA", Map.of(1, FORMATO_1_DA_FASE_6)),
            Map.entry("CANCELAR_DEVOLUCAO", Map.of(1, FORMATO_1_DA_FASE_6)),
            Map.entry("CANCELAR_TROCA", Map.of(1, FORMATO_1_DA_FASE_6)),
            Map.entry("RECEBIMENTO_CLIENTE", Map.of(1, FORMATO_1_DA_FASE_7)),
            Map.entry("ESTORNO_RECEBIMENTO_CLIENTE", Map.of(1, FORMATO_1_DA_FASE_7)),
            Map.entry("SEMANA_NOVA", Map.of(1, FORMATO_1_DO_CONSIGNADO)),
            Map.entry("NOTA_CONSIGNADO", Map.of(1, FORMATO_1_DO_CONSIGNADO)),
            Map.entry("REPOSICAO_NOTA", Map.of(1, FORMATO_1_DO_CONSIGNADO)),
            Map.entry("TROCA_NA_NOTA", Map.of(1, FORMATO_1_DO_CONSIGNADO)),
            Map.entry("REMARCAR_NOTA", Map.of(1, FORMATO_1_DO_CONSIGNADO)),
            Map.entry("ACERTO_NOTA", Map.of(1, FORMATO_1_DO_CONSIGNADO, 2, FORMATO_2_DO_ACERTO_DA_NOTA)),
            Map.entry("PAGAMENTO_CONSIGNADO", Map.of(1, FORMATO_1_DO_CONSIGNADO)),
            Map.entry("ESTORNO_ACERTO_NOTA", Map.of(1, FORMATO_1_DO_CONSIGNADO)),
            Map.entry("ESTORNO_RECIBO", Map.of(1, FORMATO_1_DO_CONSIGNADO)));

    private final Clock relogio;
    private final Duration prazo;
    private final Map<String, TreeMap<Integer, Instant>> catalogo = new ConcurrentHashMap<>();

    @Autowired
    public FormatosDeOperacao(Clock relogio, @Value("${corona.sync.formato-antigo-dias:90}") int dias) {
        this(relogio, Duration.ofDays(dias));
    }

    FormatosDeOperacao(Clock relogio, Duration prazo) {
        if (prazo.compareTo(Duration.ofDays(90)) < 0) {
            throw new IllegalStateException("corona.sync.formato-antigo-dias não pode ser menor que 90 (ADR-0013, item 17)");
        }
        this.relogio = relogio;
        this.prazo = prazo;
        reiniciar();
    }

    /** O servidor sabe ler este tipo e formato (mesmo que já vencido). */
    public boolean conhecido(String tipo, int formato) {
        if (tipo == null) {
            return false;
        }
        TreeMap<Integer, Instant> doTipo = catalogo.get(tipo);
        return doTipo != null && doTipo.containsKey(formato);
    }

    /** Conhecido e ainda dentro do prazo. */
    public boolean aceitoAgora(String tipo, int formato) {
        if (!conhecido(tipo, formato)) {
            return false;
        }
        return aceitoAte(tipo, formato).map(limite -> relogio.instant().isBefore(limite)).orElse(true);
    }

    /** Até quando o formato é aceito; vazio se é o mais novo do tipo (vale sempre). */
    public Optional<Instant> aceitoAte(String tipo, int formato) {
        if (!conhecido(tipo, formato)) {
            return Optional.empty();
        }
        Map.Entry<Integer, Instant> seguinte = catalogo.get(tipo).higherEntry(formato);
        return seguinte == null ? Optional.empty() : Optional.of(seguinte.getValue().plus(prazo));
    }

    /** Só para os testes: anuncia um formato (por exemplo, o 2 de teste) publicado no instante dado. */
    void publicar(String tipo, int formato, Instant publicadoEm) {
        catalogo.computeIfAbsent(tipo, t -> new TreeMap<>()).put(formato, publicadoEm);
    }

    /** Só para os testes: volta ao catálogo de produção. */
    synchronized void reiniciar() {
        catalogo.clear();
        PRODUCAO.forEach((tipo, formatos) -> catalogo.put(tipo, new TreeMap<>(formatos)));
    }
}
