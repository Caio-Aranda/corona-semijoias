package br.com.coronasemijoias.api.consignado;

import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.dinheiro.ConversaoDeMoeda;
import br.com.coronasemijoias.api.comum.dinheiro.Dinheiro;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.comum.identificadores.UuidV7;
import br.com.coronasemijoias.api.remessas.ServicoDaMaleta;
import br.com.coronasemijoias.api.remessas.ServicoDaMaleta.LinhaDeDevolucao;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * O Fechamento da maleta (ADR-0016, item 14; ESPECIFICACAO §23.10). Quando a maleta volta, a dona bipa as peças no Corona
 * Gestão. O servidor guarda os bipes, mostra a leitura (enviadas, devolvidas pelos clientes, vendidas, ainda com clientes,
 * esperado na maleta, bipadas e DIFERENÇA) e, ao concluir, devolve ao estoque as peças bipadas (até o que a maleta devia
 * ter) e passa a maleta a {@code FECHADA}. A diferença nunca é corrigida sozinha: fica na leitura para a dona decidir.
 * Comissão, recebido e pendência são da nota, não da maleta: a leitura os divide pelo vendido (VD) de cada maleta dentro da
 * nota, e a última maleta da nota leva a diferença de arredondamento (dúvida D3).
 */
@Service
public class ServicoDoFechamentoDaMaleta {

    public record Leitura(UUID id, UUID produtoId, Integer quantidade) {
    }

    /** Uma peça com cliente: quem, qual nota e quando cobrar. */
    public record PecaComCliente(UUID clienteId, String cliente, UUID notaId, int semana, int sequencia, java.time.LocalDate dataCobranca,
            int quantidade) {
    }

    /**
     * {@code paraLerDepois}: na maleta já fechada, as peças que as clientes devolveram depois e a dona ainda não leu (§23.10,
     * item 3); na maleta em fechamento, zero.
     */
    public record LinhaDaPeca(UUID produtoId, String nome, int enviadas, int devolvidasPelosClientes, int vendidas, int comClientes,
            int esperadoNaMaleta, int bipadas, int diferenca, int devolvidasALoja, List<PecaComCliente> aindaComClientes,
            int paraLerDepois) {
    }

    /** O que a dona decidiu para uma peça que falta na conclusão (§23.10, item 1); o id é do aplicativo. */
    public record DecisaoDaFalta(UUID id, UUID produtoId, String decisao, String motivo) {
    }

    /** Uma falta gravada na conclusão, com a decisão: cobrar do representante ou perda da loja. */
    /**
     * Uma falta gravada na conclusão, com a decisão. Na cobrada: {@code pago} (soma dos pagamentos menos os desfeitos),
     * {@code faltaPagar} e os pagamentos (§23.10, item 1a); na perda da loja, zero e lista vazia.
     */
    public record PendenciaDaMaleta(UUID id, UUID produtoId, String nome, int quantidade, BigDecimal precoUnitario, BigDecimal valor,
            String decisao, String motivo, Instant registradaEm, BigDecimal pago, BigDecimal faltaPagar,
            List<PagamentoDaCobranca> pagamentos) {
    }

    /** Um pagamento da cobrança (nunca o estorno: o pagamento desfeito vem com {@code desfeito}). */
    public record PagamentoDaCobranca(UUID id, BigDecimal valor, String forma, String moeda, BigDecimal valorNaMoeda,
            java.time.LocalDate data, boolean desfeito, String motivoDoEstorno) {
    }

    static final String COBRAR_DO_REPRESENTANTE = "COBRAR_DO_REPRESENTANTE";
    static final String PERDA_DA_LOJA = "PERDA_DA_LOJA";

    /**
     * Um valor em Real e, quando todos os lançamentos que o formam tinham cotação, em Dólar e Guarani: cada lançamento (acerto ou
     * recibo) pela cotação do dia dele, e os resultados somados (ADR-0006, item 10). {@code dolar} e {@code guarani} nulos =
     * "Sem cotação" (só o Real).
     */
    public record Convertido(BigDecimal real, BigDecimal dolar, BigDecimal guarani) {
    }

    /**
     * Os valores da maleta (rateados pelo VD de cada maleta dentro da nota), em Real e, em cada {@code ...Convertido}, também em
     * Dólar e Guarani pela cotação do dia de cada lançamento.
     */
    public record Valores(BigDecimal vd, BigDecimal comissaoDosClientes, BigDecimal recebido, BigDecimal pendenciasAReceber,
            Convertido vdConvertido, Convertido comissaoConvertida, Convertido recebidoConvertido, Convertido pendenciasConvertidas) {
    }

    /** A soma que vai juntando as partes, cada uma pela sua cotação; sem cotação numa parte com valor, o total fica só em Real. */
    private static final class SomaConvertida {
        private BigDecimal real = zero();
        private BigDecimal dolar = BigDecimal.ZERO;
        private BigDecimal guarani = BigDecimal.ZERO;
        private boolean completa = true;
        private boolean comCotacao = false;
        private boolean somouAlgo = false;
        /** Existe alguma cotação no sistema: só então uma soma vazia vale "0,00" em Dólar e Guarani. */
        private final boolean haCotacao;

        SomaConvertida(boolean haCotacao) {
            this.haCotacao = haCotacao;
        }

        void somar(BigDecimal parte, BigDecimal usdVenda, BigDecimal pygVenda) {
            real = real.add(parte);
            somouAlgo = true;
            if (usdVenda != null && pygVenda != null) {
                dolar = dolar.add(ConversaoDeMoeda.paraDolar(parte, usdVenda));
                guarani = guarani.add(ConversaoDeMoeda.paraGuarani(parte, pygVenda));
                comCotacao = true;
            } else if (parte.signum() != 0) {
                completa = false;
            }
        }

        Convertido valor() {
            // Soma vazia sem nenhuma cotação, ou só de partes sem cotação (zeros): não há conversão a mostrar (nunca "US$ 0,00" falso).
            boolean convertido = somouAlgo ? completa && comCotacao : haCotacao;
            return new Convertido(real, convertido ? dolar : null, convertido ? guarani : null);
        }
    }

    /**
     * Quanto cobrar do representante pelas faltas (§23.10, item 1): nesta maleta e somando todas as maletas dele, e quantas peças
     * ficaram como perda da loja nesta maleta.
     */
    public record CobrancaDoRepresentante(UUID vendedorId, String representante, BigDecimal nestaMaleta, BigDecimal todasAsMaletas,
            int pecasPerdidasPelaLoja, BigDecimal pagoNestaMaleta, BigDecimal faltaPagarNestaMaleta, BigDecimal pagoTodasAsMaletas,
            BigDecimal faltaPagarTodasAsMaletas) {
    }

    public record LeituraDoFechamento(UUID remessaId, String codigoDaMaleta, String situacaoDoFechamento, Instant iniciadoEm, Instant concluidoEm,
            List<LinhaDaPeca> pecas, Valores valores, List<PendenciaDaMaleta> pendencias, CobrancaDoRepresentante cobranca) {
    }

    private final JdbcTemplate jdbc;
    private final ServicoDeAuditoria auditoria;
    private final ServicoDaMaleta maleta;
    private final Clock relogio;

    public ServicoDoFechamentoDaMaleta(JdbcTemplate jdbc, ServicoDeAuditoria auditoria, ServicoDaMaleta maleta, Clock relogio) {
        this.jdbc = jdbc;
        this.auditoria = auditoria;
        this.maleta = maleta;
        this.relogio = relogio;
    }

    /** Começa o fechamento. Só maleta que está com o representante (enviada ou conferida); um em andamento por maleta. */
    @Transactional
    public UUID iniciar(UUID remessaId, UUID usuarioId) {
        String status = trancar(remessaId);
        if ("FECHADA".equals(status)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.MALETA_JA_FECHADA);
        }
        if (!"ENVIADA".equals(status) && !"CONFERIDA".equals(status)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.MALETA_NAO_PODE_FECHAR);
        }
        if (emAndamento(remessaId) != null) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.FECHAMENTO_JA_EM_ANDAMENTO);
        }
        UUID id = UuidV7.gerar();
        jdbc.update("INSERT INTO corona.fechamentos_maleta (id, remessa_id, iniciado_em, iniciado_por) VALUES (?, ?, ?, ?)", id, remessaId,
                Timestamp.from(relogio.instant()), usuarioId);
        auditoria.registrar(Ator.usuario(usuarioId), "FECHAMENTO_DA_MALETA_INICIADO", "fechamentos_maleta", id, null,
                Map.of("remessa_id", remessaId.toString()));
        return id;
    }

    /** Guarda os bipes (idempotentes pelo id). Peça que não é desta maleta é recusada (e diz de qual é, se achar). */
    @Transactional
    public int registrarLeituras(UUID remessaId, List<Leitura> leituras, UUID usuarioId) {
        if (leituras == null || leituras.isEmpty()) {
            throw Validacoes.recusar("leituras", "Informe pelo menos uma peça lida na volta.");
        }
        trancar(remessaId);
        UUID fechamento = emAndamento(remessaId);
        if (fechamento == null) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.FECHAMENTO_NAO_ENCONTRADO);
        }
        int novas = 0;
        Set<UUID> ids = new HashSet<>();
        for (Leitura l : leituras) {
            if (l == null || l.produtoId() == null || l.quantidade() == null || l.quantidade() < 1 || l.quantidade() > 100_000) {
                throw Validacoes.recusar("leituras", "Confira a peça e a quantidade lidas na volta.");
            }
            UUID id = Validacoes.idDoPedido(l.id());
            if (!ids.add(id)) {
                throw Validacoes.recusar("leituras", "A mesma leitura aparece duas vezes.");
            }
            List<Map<String, Object>> ja = jdbc.queryForList(
                    "SELECT fechamento_id, produto_id, quantidade FROM corona.leituras_fechamento_maleta WHERE id = ?", id);
            if (!ja.isEmpty()) {
                Map<String, Object> g = ja.get(0);
                if (!fechamento.equals(g.get("fechamento_id")) || !l.produtoId().equals(g.get("produto_id"))
                        || l.quantidade() != ((Number) g.get("quantidade")).intValue()) {
                    throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.LANCAMENTO_JA_REGISTRADO_DIFERENTE);
                }
                continue;
            }
            Boolean daMaleta = jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM corona.itens_remessa WHERE remessa_id = ? AND produto_id = ? AND deleted_at IS NULL)",
                    Boolean.class, remessaId, l.produtoId());
            if (!Boolean.TRUE.equals(daMaleta)) {
                throw pecaDeOutraMaleta(remessaId, l.produtoId());
            }
            jdbc.update("INSERT INTO corona.leituras_fechamento_maleta (id, fechamento_id, produto_id, quantidade, lida_em) VALUES (?, ?, ?, ?, ?)",
                    id, fechamento, l.produtoId(), l.quantidade(), Timestamp.from(relogio.instant()));
            novas++;
        }
        return novas;
    }

    /** A peça lida não é desta maleta: diz de qual maleta (aberta) ela é, se achar; senão, só que não é desta. */
    private ErroDaApi pecaDeOutraMaleta(UUID remessaId, UUID produtoId) {
        List<String> outras = jdbc.queryForList(
                """
                SELECT r.codigo FROM corona.itens_remessa i JOIN corona.remessas r ON r.id = i.remessa_id
                WHERE i.produto_id = ? AND i.deleted_at IS NULL AND r.deleted_at IS NULL AND r.id <> ?
                  AND r.status IN ('ENVIADA', 'CONFERIDA', 'EM_ACERTO') AND r.codigo IS NOT NULL
                ORDER BY r.enviada_em DESC NULLS LAST LIMIT 1
                """, String.class, produtoId, remessaId);
        if (outras.isEmpty()) {
            return new ErroDaApi(HttpStatus.UNPROCESSABLE_ENTITY, CodigoErro.PECA_NAO_E_DESTA_MALETA);
        }
        return ErroDaApi.comDetalhe(HttpStatus.UNPROCESSABLE_ENTITY, CodigoErro.PECA_NAO_E_DESTA_MALETA,
                "Esta peça não é desta maleta. Ela está na maleta " + outras.get(0) + ".");
    }

    /** Conclui sem nenhuma falta para decidir (o mesmo que {@link #concluir(UUID, List, UUID)} sem decisões). */
    @Transactional
    public void concluir(UUID remessaId, UUID usuarioId) {
        concluir(remessaId, List.of(), usuarioId);
    }

    /**
     * Conclui: devolve ao estoque as peças bipadas (no máximo o que a maleta devia ter), grava cada falta como PERDA na maleta com
     * a decisão da dona (§23.10, item 1) e fecha a maleta. A quantidade que falta é do servidor; sem decisão para cada peça que
     * falta, recusa ({@code FALTA_SEM_DECISAO}) e nada é gravado. A sobra não volta ao estoque. Concluir de novo recusa.
     */
    @Transactional
    public void concluir(UUID remessaId, List<DecisaoDaFalta> decisoes, UUID usuarioId) {
        String status = trancar(remessaId);
        UUID fechamento = emAndamento(remessaId);
        if (fechamento == null) {
            throw new ErroDaApi(HttpStatus.CONFLICT, "FECHADA".equals(status) ? CodigoErro.MALETA_JA_FECHADA
                    : CodigoErro.FECHAMENTO_NAO_ENCONTRADO);
        }
        Map<UUID, DecisaoDaFalta> decididas = conferirDecisoes(decisoes);
        List<LinhaDeDevolucao> devolver = new ArrayList<>();
        Map<UUID, Integer> faltas = new LinkedHashMap<>();
        for (Map<String, Object> l : jdbc.queryForList("""
                SELECT i.produto_id, coalesce((SELECT sum(b.quantidade) FROM corona.leituras_fechamento_maleta b
                                                WHERE b.fechamento_id = ? AND b.produto_id = i.produto_id), 0) AS bipadas
                  FROM corona.itens_remessa i WHERE i.remessa_id = ? AND i.deleted_at IS NULL ORDER BY i.produto_id""",
                fechamento, remessaId)) {
            UUID produto = (UUID) l.get("produto_id");
            int bipadas = ((Number) l.get("bipadas")).intValue();
            int esperado = esperadoNaMaleta(remessaId, produto);
            int volta = Math.min(bipadas, esperado);
            if (volta > 0) {
                devolver.add(new LinhaDeDevolucao(UuidV7.gerar(), produto, volta));
            }
            if (esperado > bipadas) {
                faltas.put(produto, esperado - bipadas);
            }
        }
        for (UUID produto : decididas.keySet()) {
            if (!faltas.containsKey(produto)) {
                throw Validacoes.recusar("decisoes", "Esta peça não falta na maleta. Atualize a tela e confira.");
            }
        }
        if (!decididas.keySet().containsAll(faltas.keySet())) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.FALTA_SEM_DECISAO);
        }
        if (!devolver.isEmpty()) {
            maleta.devolver(remessaId, devolver, usuarioId);
        }
        UUID vendedor = jdbc.queryForObject("SELECT vendedor_id FROM corona.remessas WHERE id = ?", UUID.class, remessaId);
        for (Map.Entry<UUID, Integer> f : faltas.entrySet()) {
            DecisaoDaFalta d = decididas.get(f.getKey());
            int quantidade = f.getValue();
            BigDecimal preco = jdbc.queryForObject("""
                    SELECT coalesce(i.preco_remessa, p.preco_venda) FROM corona.itens_remessa i JOIN corona.produtos p ON p.id = i.produto_id
                     WHERE i.remessa_id = ? AND i.produto_id = ? AND i.deleted_at IS NULL""", BigDecimal.class, remessaId, f.getKey());
            UUID movimento = UuidV7.gerar();
            jdbc.update("INSERT INTO corona.movimentos_remessa (id, remessa_id, produto_id, tipo, quantidade) VALUES (?, ?, ?, 'PERDA', ?)",
                    movimento, remessaId, f.getKey(), -quantidade);
            jdbc.update("""
                    INSERT INTO corona.pendencias_da_maleta (id, fechamento_id, remessa_id, vendedor_id, produto_id, quantidade, preco_unitario,
                                                             valor, decisao, motivo, movimento_id, registrada_por)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", d.id(), fechamento, remessaId, vendedor, f.getKey(), quantidade,
                    Dinheiro.arredondar(preco), Dinheiro.precoDaLinha(preco, quantidade), d.decisao(), d.motivo(), movimento, usuarioId);
        }
        jdbc.update("UPDATE corona.remessas SET status = 'FECHADA' WHERE id = ?", remessaId);
        jdbc.update("UPDATE corona.fechamentos_maleta SET situacao = 'CONCLUIDO', concluido_em = ?, concluido_por = ? WHERE id = ?",
                Timestamp.from(relogio.instant()), usuarioId, fechamento);
        auditoria.registrar(Ator.usuario(usuarioId), "FECHAMENTO_DA_MALETA_CONCLUIDO", "fechamentos_maleta", fechamento, null,
                Map.of("remessa_id", remessaId.toString(), "pecas_devolvidas", devolver.size(), "faltas", faltas.size()));
    }

    /** Cada decisão completa, uma por peça; perda da loja com motivo. O motivo vai limpo (sem espaços nas pontas). */
    private static Map<UUID, DecisaoDaFalta> conferirDecisoes(List<DecisaoDaFalta> decisoes) {
        Map<UUID, DecisaoDaFalta> porPeca = new LinkedHashMap<>();
        Set<UUID> ids = new HashSet<>();
        for (DecisaoDaFalta d : decisoes == null ? List.<DecisaoDaFalta>of() : decisoes) {
            if (d == null || d.produtoId() == null || d.decisao() == null
                    || !(COBRAR_DO_REPRESENTANTE.equals(d.decisao()) || PERDA_DA_LOJA.equals(d.decisao()))) {
                throw Validacoes.recusar("decisoes", "Para cada peça que falta, escolha Cobrar do representante ou Perda da loja.");
            }
            UUID id = Validacoes.idDoPedido(d.id());
            String motivo = Validacoes.limpar(d.motivo());
            if (PERDA_DA_LOJA.equals(d.decisao()) && motivo == null) {
                throw Validacoes.recusar("motivo", "Diga o motivo da perda da loja. Isso ajuda a entender depois.");
            }
            if (motivo != null && motivo.length() > 500) {
                throw Validacoes.recusar("motivo", "O motivo pode ter no máximo 500 letras.");
            }
            if (!ids.add(id) || porPeca.containsKey(d.produtoId())) {
                throw Validacoes.recusar("decisoes", "A mesma peça aparece duas vezes. Escolha uma decisão só para cada peça.");
            }
            porPeca.put(d.produtoId(), new DecisaoDaFalta(id, d.produtoId(), d.decisao(), motivo));
        }
        return porPeca;
    }

    /**
     * As peças que voltam depois de a maleta ser fechada (§23.10, item 3): cada leitura devolve a peça ao estoque, no máximo o
     * que as clientes já devolveram àquela maleta e ainda não voltou à loja (o saldo da maleta fechada). A maleta continua
     * fechada. Idempotente pelo id da leitura.
     */
    @Transactional
    public int registrarVoltas(UUID remessaId, List<Leitura> leituras, UUID usuarioId) {
        if (leituras == null || leituras.isEmpty()) {
            throw Validacoes.recusar("leituras", "Informe pelo menos uma peça lida.");
        }
        String status = trancar(remessaId);
        List<UUID> concluido = jdbc.queryForList(
                "SELECT id FROM corona.fechamentos_maleta WHERE remessa_id = ? AND situacao = 'CONCLUIDO'", UUID.class, remessaId);
        if (!"FECHADA".equals(status) || concluido.isEmpty()) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.FECHAMENTO_NAO_ENCONTRADO);
        }
        UUID fechamento = concluido.get(0);
        int novas = 0;
        Set<UUID> ids = new HashSet<>();
        for (Leitura l : leituras) {
            if (l == null || l.produtoId() == null || l.quantidade() == null || l.quantidade() < 1 || l.quantidade() > 100_000) {
                throw Validacoes.recusar("leituras", "Confira a peça e a quantidade lidas.");
            }
            UUID id = Validacoes.idDoPedido(l.id());
            if (!ids.add(id)) {
                throw Validacoes.recusar("leituras", "A mesma leitura aparece duas vezes.");
            }
            List<Map<String, Object>> ja = jdbc.queryForList(
                    "SELECT fechamento_id, produto_id, quantidade, depois_de_fechar FROM corona.leituras_fechamento_maleta WHERE id = ?", id);
            if (!ja.isEmpty()) {
                Map<String, Object> g = ja.get(0);
                if (!fechamento.equals(g.get("fechamento_id")) || !l.produtoId().equals(g.get("produto_id"))
                        || l.quantidade() != ((Number) g.get("quantidade")).intValue() || !Boolean.TRUE.equals(g.get("depois_de_fechar"))) {
                    throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.LANCAMENTO_JA_REGISTRADO_DIFERENTE);
                }
                continue;
            }
            Boolean daMaleta = jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM corona.itens_remessa WHERE remessa_id = ? AND produto_id = ? AND deleted_at IS NULL)",
                    Boolean.class, remessaId, l.produtoId());
            if (!Boolean.TRUE.equals(daMaleta)) {
                throw pecaDeOutraMaleta(remessaId, l.produtoId());
            }
            if (l.quantidade() > esperadoNaMaleta(remessaId, l.produtoId())) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.PECA_AINDA_COM_A_CLIENTE);
            }
            jdbc.update("""
                    INSERT INTO corona.leituras_fechamento_maleta (id, fechamento_id, produto_id, quantidade, lida_em, depois_de_fechar)
                    VALUES (?, ?, ?, ?, ?, true)""", id, fechamento, l.produtoId(), l.quantidade(), Timestamp.from(relogio.instant()));
            maleta.devolverDaMaletaFechada(remessaId, List.of(new LinhaDeDevolucao(UuidV7.gerar(), l.produtoId(), l.quantidade())),
                    usuarioId);
            novas++;
        }
        return novas;
    }

    // --- a leitura (a mesma do relatório) ---

    @Transactional(readOnly = true)
    public LeituraDoFechamento ler(UUID remessaId) {
        Map<String, Object> remessa = remessa(remessaId);
        // O fechamento em andamento, ou o último concluído.
        List<Map<String, Object>> f = jdbc.queryForList("""
                SELECT id, situacao, iniciado_em, concluido_em FROM corona.fechamentos_maleta WHERE remessa_id = ?
                 ORDER BY iniciado_em DESC LIMIT 1""", remessaId);
        if (f.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.FECHAMENTO_NAO_ENCONTRADO);
        }
        UUID fechamento = (UUID) f.get(0).get("id");
        boolean fechada = "FECHADA".equals(jdbc.queryForObject("SELECT status FROM corona.remessas WHERE id = ?", String.class, remessaId));
        List<LinhaDaPeca> pecas = new ArrayList<>();
        List<Map<String, Object>> linhas = jdbc.queryForList("""
                SELECT i.produto_id, p.nome, i.qtd_enviada,
                       coalesce((SELECT sum(m.quantidade) FROM corona.movimentos_remessa m
                                  WHERE m.remessa_id = i.remessa_id AND m.produto_id = i.produto_id AND m.tipo = 'CONSIGNADO_RETORNO'), 0) AS retornos,
                       coalesce((SELECT sum(m.quantidade) FROM corona.movimentos_remessa m
                                  WHERE m.remessa_id = i.remessa_id AND m.produto_id = i.produto_id AND m.tipo = 'CONSIGNADO_SAIDA'), 0) AS saidas,
                       coalesce((SELECT sum(m.quantidade) FROM corona.movimentos_remessa m
                                  WHERE m.remessa_id = i.remessa_id AND m.produto_id = i.produto_id AND m.tipo <> 'DEVOLUCAO_DONA'), 0) AS sem_devolucao,
                       coalesce((SELECT -sum(m.quantidade) FROM corona.movimentos_remessa m
                                  WHERE m.remessa_id = i.remessa_id AND m.produto_id = i.produto_id AND m.tipo = 'DEVOLUCAO_DONA'), 0) AS devolvidas,
                       coalesce((SELECT sum(a.qtd_dx - a.qtd_dv) FROM corona.itens_acerto_nota a
                                  JOIN corona.acertos_nota z ON z.id = a.acerto_id AND z.estornado_em IS NULL
                                 WHERE a.remessa_id = i.remessa_id AND a.produto_id = i.produto_id), 0) AS vendidas,
                       coalesce((SELECT sum(l.quantidade) FROM corona.leituras_fechamento_maleta l
                                  WHERE l.fechamento_id = ? AND l.produto_id = i.produto_id), 0) AS bipadas
                  FROM corona.itens_remessa i JOIN corona.produtos p ON p.id = i.produto_id
                 WHERE i.remessa_id = ? AND i.deleted_at IS NULL ORDER BY p.nome, i.produto_id""", fechamento, remessaId);
        for (Map<String, Object> l : linhas) {
            UUID produto = (UUID) l.get("produto_id");
            int enviadas = ((Number) l.get("qtd_enviada")).intValue();
            int retornos = ((Number) l.get("retornos")).intValue();
            int saidas = ((Number) l.get("saidas")).intValue();
            int vendidas = ((Number) l.get("vendidas")).intValue();
            int comClientes = -(saidas + retornos) - vendidas;
            int esperado = enviadas + ((Number) l.get("sem_devolucao")).intValue();
            int bipadas = ((Number) l.get("bipadas")).intValue();
            int devolvidas = ((Number) l.get("devolvidas")).intValue();
            // Na maleta fechada, o saldo positivo é o que as clientes devolveram depois e ainda não foi lido.
            int paraLerDepois = fechada ? Math.max(0, esperado - devolvidas) : 0;
            pecas.add(new LinhaDaPeca(produto, (String) l.get("nome"), enviadas, retornos, vendidas, comClientes, esperado, bipadas,
                    bipadas - esperado, devolvidas, pecasComClientes(remessaId, produto), paraLerDepois));
        }
        return new LeituraDoFechamento(remessaId, codigoDaMaleta(remessaId), (String) f.get(0).get("situacao"), instante(f.get(0).get("iniciado_em")),
                instante(f.get(0).get("concluido_em")), pecas, valores(remessaId), pendencias(remessaId), cobranca(remessaId));
    }

    private CobrancaDoRepresentante cobranca(UUID remessaId) {
        return jdbc.queryForObject("""
                SELECT v.id, v.nome,
                       coalesce((SELECT sum(d.valor) FROM corona.pendencias_da_maleta d
                                  WHERE d.remessa_id = r.id AND d.decisao = 'COBRAR_DO_REPRESENTANTE'), 0) AS nesta,
                       coalesce((SELECT sum(d.valor) FROM corona.pendencias_da_maleta d
                                  WHERE d.vendedor_id = v.id AND d.decisao = 'COBRAR_DO_REPRESENTANTE'), 0) AS todas,
                       coalesce((SELECT sum(d.quantidade) FROM corona.pendencias_da_maleta d
                                  WHERE d.remessa_id = r.id AND d.decisao = 'PERDA_DA_LOJA'), 0) AS perdidas,
                       coalesce((SELECT sum(g.valor) FROM corona.pagamentos_cobranca_maleta g
                                  JOIN corona.pendencias_da_maleta d ON d.id = g.pendencia_id WHERE d.remessa_id = r.id), 0) AS pago_nesta,
                       coalesce((SELECT sum(g.valor) FROM corona.pagamentos_cobranca_maleta g WHERE g.vendedor_id = v.id), 0) AS pago_todas
                  FROM corona.remessas r JOIN corona.vendedores v ON v.id = r.vendedor_id
                 WHERE r.id = ?""", (rs, n) -> {
                    BigDecimal nesta = Dinheiro.arredondar(rs.getBigDecimal(3));
                    BigDecimal todas = Dinheiro.arredondar(rs.getBigDecimal(4));
                    BigDecimal pagoNesta = Dinheiro.arredondar(rs.getBigDecimal(6));
                    BigDecimal pagoTodas = Dinheiro.arredondar(rs.getBigDecimal(7));
                    return new CobrancaDoRepresentante(rs.getObject(1, UUID.class), rs.getString(2), nesta, todas, rs.getInt(5), pagoNesta,
                            nesta.subtract(pagoNesta), pagoTodas, todas.subtract(pagoTodas));
                }, remessaId);
    }

    /** As faltas gravadas na conclusão, com a decisão da dona (§23.10, item 1). */
    private List<PendenciaDaMaleta> pendencias(UUID remessaId) {
        return jdbc.query("""
                SELECT d.id, d.produto_id, p.nome, d.quantidade, d.preco_unitario, d.valor, d.decisao, d.motivo, d.created_at,
                       coalesce((SELECT sum(g.valor) FROM corona.pagamentos_cobranca_maleta g WHERE g.pendencia_id = d.id), 0) AS pago
                  FROM corona.pendencias_da_maleta d JOIN corona.produtos p ON p.id = d.produto_id
                 WHERE d.remessa_id = ? ORDER BY p.nome, d.id""",
                (rs, n) -> {
                    UUID id = rs.getObject(1, UUID.class);
                    BigDecimal valor = rs.getBigDecimal(6);
                    BigDecimal pago = Dinheiro.arredondar(rs.getBigDecimal(10));
                    boolean cobrada = COBRAR_DO_REPRESENTANTE.equals(rs.getString(7));
                    return new PendenciaDaMaleta(id, rs.getObject(2, UUID.class), rs.getString(3), rs.getInt(4), rs.getBigDecimal(5), valor,
                            rs.getString(7), rs.getString(8), rs.getTimestamp(9).toInstant(), pago,
                            cobrada ? valor.subtract(pago) : Dinheiro.arredondar(BigDecimal.ZERO), pagamentos(id));
                }, remessaId);
    }

    private List<PagamentoDaCobranca> pagamentos(UUID pendenciaId) {
        return jdbc.query("""
                SELECT g.id, g.valor, g.forma, g.moeda, g.valor_na_moeda, g.data_pagamento, e.motivo
                  FROM corona.pagamentos_cobranca_maleta g
                  LEFT JOIN corona.pagamentos_cobranca_maleta e ON e.estorna_id = g.id
                 WHERE g.pendencia_id = ? AND g.estorna_id IS NULL ORDER BY g.created_at, g.id""",
                (rs, n) -> new PagamentoDaCobranca(rs.getObject(1, UUID.class), rs.getBigDecimal(2), rs.getString(3), rs.getString(4),
                        rs.getBigDecimal(5), rs.getDate(6).toLocalDate(), rs.getString(7) != null, rs.getString(7)), pendenciaId);
    }

    private String codigoDaMaleta(UUID remessaId) {
        return jdbc.queryForObject("SELECT codigo FROM corona.remessas WHERE id = ?", String.class, remessaId);
    }

    private static Instant instante(Object o) {
        return o == null ? null : ((Timestamp) o).toInstant();
    }

    /** Peças ainda com clientes: itens de notas SEM acerto ativo (DX da linha). */
    private List<PecaComCliente> pecasComClientes(UUID remessaId, UUID produtoId) {
        return jdbc.query("""
                SELECT c.id, c.nome, n.id AS nota_id, s.numero, n.sequencia, n.data_cobranca, sum(i.quantidade) AS qtd
                  FROM corona.itens_nota_consignado i
                  JOIN corona.notas_consignado n ON n.id = i.nota_id
                  JOIN corona.semanas_vendedor s ON s.id = n.semana_id
                  JOIN corona.clientes c ON c.id = n.cliente_id
                 WHERE i.remessa_id = ? AND i.produto_id = ?
                   AND NOT EXISTS (SELECT 1 FROM corona.acertos_nota a WHERE a.nota_id = n.id AND a.estornado_em IS NULL)
                 GROUP BY c.id, c.nome, n.id, s.numero, n.sequencia, n.data_cobranca
                HAVING sum(i.quantidade) > 0 ORDER BY n.data_cobranca, c.nome, n.id""",
                (rs, k) -> new PecaComCliente(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class), rs.getInt(4),
                        rs.getInt(5), rs.getDate(6).toLocalDate(), rs.getInt(7)), remessaId, produtoId);
    }

    /**
     * VD, comissão, recebido e pendência da maleta: cada nota acertada é dividida pelo VD de cada maleta dentro dela, e a
     * última maleta (na ordem do id) leva a diferença de arredondamento.
     */
    private Valores valores(UUID remessaId) {
        boolean haCotacao = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM corona.cotacoes)", Boolean.class));
        SomaConvertida vd = new SomaConvertida(haCotacao);
        SomaConvertida comissao = new SomaConvertida(haCotacao);
        SomaConvertida recebido = new SomaConvertida(haCotacao);
        SomaConvertida pendencia = new SomaConvertida(haCotacao);
        List<Map<String, Object>> acertos = jdbc.queryForList("""
                SELECT DISTINCT a.id, a.valor_vd, a.comissao, a.bruto, ct.usd_venda, ct.pyg_venda,
                       coalesce((SELECT sum(ap.valor) FROM corona.aplicacoes_recibo ap WHERE ap.acerto_id = a.id), 0) AS aplicado
                  FROM corona.acertos_nota a JOIN corona.itens_acerto_nota i ON i.acerto_id = a.id
                  LEFT JOIN corona.cotacoes ct ON ct.id = a.cotacao_id
                 WHERE i.remessa_id = ? AND a.estornado_em IS NULL""", remessaId);
        for (Map<String, Object> a : acertos) {
            UUID acerto = (UUID) a.get("id");
            BigDecimal usd = (BigDecimal) a.get("usd_venda");
            BigDecimal pyg = (BigDecimal) a.get("pyg_venda");
            // O VD de cada maleta dentro do acerto, na ordem do id da maleta.
            Map<UUID, BigDecimal> vdPorMaleta = new LinkedHashMap<>();
            for (Map<String, Object> m : jdbc.queryForList("""
                    SELECT remessa_id, sum((qtd_dx - qtd_dv) * preco_unitario) AS vd FROM corona.itens_acerto_nota
                     WHERE acerto_id = ? GROUP BY remessa_id ORDER BY remessa_id""", acerto)) {
                vdPorMaleta.put((UUID) m.get("remessa_id"), (BigDecimal) m.get("vd"));
            }
            BigDecimal vdDoAcerto = (BigDecimal) a.get("valor_vd");
            BigDecimal aplicado = (BigDecimal) a.get("aplicado");
            BigDecimal saldo = Dinheiro.subtrair((BigDecimal) a.get("bruto"), aplicado);
            vd.somar(vdPorMaleta.getOrDefault(remessaId, zero()), usd, pyg);
            comissao.somar(rateio((BigDecimal) a.get("comissao"), vdPorMaleta, vdDoAcerto, remessaId), usd, pyg);
            pendencia.somar(rateio(saldo, vdPorMaleta, vdDoAcerto, remessaId), usd, pyg);
            somarRecebido(recebido, acerto, vdPorMaleta, vdDoAcerto, remessaId);
        }
        Convertido v = vd.valor();
        Convertido c = comissao.valor();
        Convertido r = recebido.valor();
        Convertido p = pendencia.valor();
        return new Valores(v.real(), c.real(), r.real(), p.real(), v, c, r, p);
    }

    /**
     * O recebido de um acerto, recibo por recibo, cada um pela cotação do dia do recibo. O total em Real é o rateio do que foi
     * aplicado ao acerto (como sempre foi); o último recibo fecha a conta, para a soma das partes não se afastar um centavo.
     */
    private void somarRecebido(SomaConvertida recebido, UUID acerto, Map<UUID, BigDecimal> vdPorMaleta, BigDecimal vdDoAcerto,
            UUID remessaId) {
        List<Map<String, Object>> porRecibo = jdbc.queryForList("""
                SELECT ap.recibo_id, sum(ap.valor) AS valor, ct.usd_venda, ct.pyg_venda
                  FROM corona.aplicacoes_recibo ap
                  JOIN corona.recibos_consignado r ON r.id = ap.recibo_id
                  LEFT JOIN corona.cotacoes ct ON ct.id = r.cotacao_id
                 WHERE ap.acerto_id = ?
                 GROUP BY ap.recibo_id, r.pago_em, ct.usd_venda, ct.pyg_venda
                 ORDER BY r.pago_em, ap.recibo_id""", acerto);
        BigDecimal aplicado = zero();
        for (Map<String, Object> l : porRecibo) {
            aplicado = aplicado.add((BigDecimal) l.get("valor"));
        }
        BigDecimal total = rateio(aplicado, vdPorMaleta, vdDoAcerto, remessaId);
        BigDecimal dado = zero();
        for (int k = 0; k < porRecibo.size(); k++) {
            Map<String, Object> l = porRecibo.get(k);
            BigDecimal parte = k == porRecibo.size() - 1 ? Dinheiro.subtrair(total, dado)
                    : rateio((BigDecimal) l.get("valor"), vdPorMaleta, vdDoAcerto, remessaId);
            dado = dado.add(parte);
            recebido.somar(parte, (BigDecimal) l.get("usd_venda"), (BigDecimal) l.get("pyg_venda"));
        }
    }

    /** A parte da maleta no total da nota, proporcional ao VD dela; a última maleta fecha a conta. */
    private static BigDecimal rateio(BigDecimal total, Map<UUID, BigDecimal> vdPorMaleta, BigDecimal vdTotal, UUID remessaId) {
        if (total.signum() == 0 || !vdPorMaleta.containsKey(remessaId)) {
            return zero();
        }
        long todo = vdTotal.movePointRight(2).longValueExact();
        List<UUID> ordem = new ArrayList<>(vdPorMaleta.keySet());
        if (todo == 0) {
            // Sem VD para dividir: tudo vai para a primeira maleta da nota.
            return remessaId.equals(ordem.get(0)) ? total : zero();
        }
        BigDecimal dado = zero();
        for (UUID m : ordem) {
            boolean ultima = m.equals(ordem.get(ordem.size() - 1));
            BigDecimal parte;
            if (ultima) {
                parte = Dinheiro.subtrair(total, dado);
            } else {
                parte = Dinheiro.parteProporcional(total, vdPorMaleta.get(m).movePointRight(2).longValueExact(), todo);
            }
            if (m.equals(remessaId)) {
                return parte;
            }
            dado = dado.add(parte);
        }
        return zero();
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }

    // --- internos ---

    private int esperadoNaMaleta(UUID remessaId, UUID produtoId) {
        Integer v = jdbc.queryForObject("""
                SELECT coalesce((SELECT i.qtd_enviada FROM corona.itens_remessa i
                                  WHERE i.remessa_id = ? AND i.produto_id = ? AND i.deleted_at IS NULL), 0)
                       + coalesce((SELECT sum(m.quantidade) FROM corona.movimentos_remessa m WHERE m.remessa_id = ? AND m.produto_id = ?), 0)""",
                Integer.class, remessaId, produtoId, remessaId, produtoId);
        return v == null ? 0 : v;
    }

    private UUID emAndamento(UUID remessaId) {
        return jdbc.queryForList("SELECT id FROM corona.fechamentos_maleta WHERE remessa_id = ? AND situacao = 'EM_ANDAMENTO'", UUID.class,
                remessaId).stream().findFirst().orElse(null);
    }

    private Map<String, Object> remessa(UUID remessaId) {
        List<Map<String, Object>> r = jdbc.queryForList("SELECT status FROM corona.remessas WHERE id = ? AND deleted_at IS NULL", remessaId);
        if (r.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.ROTA_NAO_ENCONTRADA);
        }
        return r.get(0);
    }

    /** Trava a remessa e devolve o status. */
    private String trancar(UUID remessaId) {
        List<String> s = jdbc.queryForList("SELECT status FROM corona.remessas WHERE id = ? AND deleted_at IS NULL FOR UPDATE", String.class,
                remessaId);
        if (s.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.ROTA_NAO_ENCONTRADA);
        }
        return s.get(0);
    }
}
