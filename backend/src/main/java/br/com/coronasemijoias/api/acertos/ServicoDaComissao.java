package br.com.coronasemijoias.api.acertos;

import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.dinheiro.ConversaoDeMoeda;
import br.com.coronasemijoias.api.configuracoes.ChaveDeConfiguracao;
import br.com.coronasemijoias.api.configuracoes.Configuracoes;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A meta e os percentuais da comissão (tarefa 7.5; ADR-0012, item 5): ler e mudar as três chaves, com auditoria do valor antigo, e
 * a prévia da comissão do vendedor (o mesmo cálculo do fechamento, nada é gravado). A faixa e "abaixo da meta não passa de na
 * meta" são conferidas aqui (mensagem clara) e no banco (segunda trava).
 */
@Service
public class ServicoDaComissao {

    public static final int META_MAXIMA = 1_000_000_000;

    /** O que a dona vê e muda: a meta em Guarani e os dois percentuais (inteiros de 0 a 100). */
    public record Comissao(int metaGuarani, int percentualAbaixoDaMeta, int percentualNaMeta) {
    }

    /** A prévia do vendedor: o vendido da semana, o percentual de hoje e quanto falta para a meta, em Guarani e em Real. */
    public record Previa(long vendidoParaMetaGuarani, int metaGuarani, BigDecimal percentualQueValeriaHoje, long faltaParaMetaGuarani,
            BigDecimal faltaParaMetaReais, BigDecimal comissaoPrevista, LocalDate cotacaoData) {
    }

    private final Configuracoes configuracoes;
    private final JdbcTemplate jdbc;
    private final ServicoDeAuditoria auditoria;
    private final MontagemDoFechamento montagem;

    ServicoDaComissao(Configuracoes configuracoes, JdbcTemplate jdbc, ServicoDeAuditoria auditoria, MontagemDoFechamento montagem) {
        this.configuracoes = configuracoes;
        this.jdbc = jdbc;
        this.auditoria = auditoria;
        this.montagem = montagem;
    }

    @Transactional(readOnly = true)
    public Comissao ler() {
        return new Comissao(configuracoes.inteiro(ChaveDeConfiguracao.COMISSAO_META_GUARANI),
                configuracoes.inteiro(ChaveDeConfiguracao.COMISSAO_PERCENTUAL_ABAIXO_DA_META),
                configuracoes.inteiro(ChaveDeConfiguracao.COMISSAO_PERCENTUAL_NA_META));
    }

    @Transactional
    public Comissao alterar(Comissao novo, UUID donaId) {
        if (novo.metaGuarani() < 1 || novo.metaGuarani() > META_MAXIMA) {
            throw Validacoes.recusar("metaGuarani", "Use uma meta entre 1 e 1.000.000.000 de Guaranis.");
        }
        for (int p : new int[] {novo.percentualAbaixoDaMeta(), novo.percentualNaMeta()}) {
            if (p < 0 || p > 100) {
                throw Validacoes.recusar("percentual", "Use uma porcentagem entre 0 e 100.");
            }
        }
        if (novo.percentualAbaixoDaMeta() > novo.percentualNaMeta()) {
            throw Validacoes.recusar("percentualAbaixoDaMeta", "A porcentagem abaixo da meta não pode ser maior que a porcentagem na meta.");
        }
        Comissao atual = ler();
        Ator ator = Ator.usuario(donaId);
        mudar(ChaveDeConfiguracao.COMISSAO_META_GUARANI, atual.metaGuarani(), novo.metaGuarani(), ator);
        mudar(ChaveDeConfiguracao.COMISSAO_PERCENTUAL_ABAIXO_DA_META, atual.percentualAbaixoDaMeta(), novo.percentualAbaixoDaMeta(), ator);
        mudar(ChaveDeConfiguracao.COMISSAO_PERCENTUAL_NA_META, atual.percentualNaMeta(), novo.percentualNaMeta(), ator);
        return ler();
    }

    private void mudar(ChaveDeConfiguracao chave, int antes, int depois, Ator ator) {
        if (antes == depois) {
            return;
        }
        jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = ? WHERE chave = ?", depois, chave.chave());
        UUID id = jdbc.queryForObject("SELECT id FROM corona.configuracoes WHERE chave = ?", UUID.class, chave.chave());
        auditoria.registrar(ator, "COMISSAO_ALTERADA", "configuracoes", id, campos(chave, antes), campos(chave, depois));
    }

    private static Map<String, Object> campos(ChaveDeConfiguracao chave, int valor) {
        Map<String, Object> campos = new LinkedHashMap<>();
        campos.put("chave", chave.chave());
        campos.put("valor", valor);
        return campos;
    }

    /** O mesmo cálculo do fechamento sobre os eventos do vendedor ainda sem acerto, com a meta e os percentuais de agora. */
    @Transactional(readOnly = true)
    public Previa previa(UUID vendedorId) {
        var m = montagem.montar(UUID.randomUUID(), vendedorId);
        long soma = m.comissao().vendidoParaMetaGuarani();
        FaltaParaMeta falta = faltaParaMeta(m.metaGuarani(), soma, m.cotacao().taxas().pygVenda());
        return new Previa(soma, (int) m.metaGuarani(), m.comissao().percentual(), falta.guarani(), falta.reais(),
                m.comissao().comissao(), m.cotacao().dataCotacao());
    }

    /** Quanto falta para a meta, em Guarani e em Real (só para mostrar; nunca é gravado). */
    public record FaltaParaMeta(long guarani, BigDecimal reais) {
    }

    /**
     * A conta do que falta para a meta, uma só para a prévia do vendedor e a da dona (§13, item 2; ADR-0012, item 5).
     * Guarani que falta = máximo entre 0 e (meta menos a soma já vendida, cada venda convertida pela cotação da própria venda).
     * Em Real, o ADR-0012 manda mostrar pela cotação mais recente: {@code pygVenda} é a taxa de venda do Guarani da cotação da
     * prévia (a de hoje), e não a de cada venda; é só para mostrar. O arredondamento fica em {@link ConversaoDeMoeda}.
     */
    public static FaltaParaMeta faltaParaMeta(long metaGuarani, long vendidoParaMetaGuarani, BigDecimal pygVenda) {
        long falta = Math.max(0, metaGuarani - vendidoParaMetaGuarani);
        return new FaltaParaMeta(falta, ConversaoDeMoeda.guaraniParaReais(BigDecimal.valueOf(falta), pygVenda));
    }
}
