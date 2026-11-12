package br.com.coronasemijoias.api.configuracoes;

import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.comum.dinheiro.Dinheiro;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A garantia e os limites de devolução (tarefa 6.2; ADR-0014, itens 4 e 10): ler e mudar as cinco chaves, com
 * auditoria do valor antigo. Quem decide a faixa é a API (mensagem clara) e o banco (segunda trava); o serviço da
 * devolução (tarefa 6.3) lê daqui, nunca do app.
 */
@Service
public class ServicoDeGarantia {

    /** O que a dona vê e muda. Dinheiro vazio = sem limite. */
    public record Garantia(int prazoArrependimentoDias, int prazoDefeitoDias, BigDecimal limiteReembolsoSemanal,
            BigDecimal valorAprovacaoReembolso, int percentualAlertaDevolucao) {
    }

    private final Configuracoes configuracoes;
    private final JdbcTemplate jdbc;
    private final ServicoDeAuditoria auditoria;

    public ServicoDeGarantia(Configuracoes configuracoes, JdbcTemplate jdbc, ServicoDeAuditoria auditoria) {
        this.configuracoes = configuracoes;
        this.jdbc = jdbc;
        this.auditoria = auditoria;
    }

    @Transactional(readOnly = true)
    public Garantia ler() {
        return new Garantia(configuracoes.inteiro(ChaveDeConfiguracao.PRAZO_ARREPENDIMENTO_DIAS),
                configuracoes.inteiro(ChaveDeConfiguracao.PRAZO_DEFEITO_DIAS),
                configuracoes.dinheiroOuVazio(ChaveDeConfiguracao.LIMITE_REEMBOLSO_SEMANAL).orElse(null),
                configuracoes.dinheiroOuVazio(ChaveDeConfiguracao.VALOR_APROVACAO_REEMBOLSO).orElse(null),
                configuracoes.inteiro(ChaveDeConfiguracao.PERCENTUAL_ALERTA_DEVOLUCAO));
    }

    /** Grava só o que mudou; cada chave mudada vira um registro de auditoria com o valor de antes e o de depois. */
    @Transactional
    public Garantia alterar(Garantia novo, UUID donaId) {
        Garantia atual = ler();
        Ator ator = Ator.usuario(donaId);
        mudarInteiro(ChaveDeConfiguracao.PRAZO_ARREPENDIMENTO_DIAS, atual.prazoArrependimentoDias(),
                novo.prazoArrependimentoDias(), ator);
        mudarInteiro(ChaveDeConfiguracao.PRAZO_DEFEITO_DIAS, atual.prazoDefeitoDias(), novo.prazoDefeitoDias(), ator);
        mudarDinheiro(ChaveDeConfiguracao.LIMITE_REEMBOLSO_SEMANAL, atual.limiteReembolsoSemanal(),
                novo.limiteReembolsoSemanal(), ator);
        mudarDinheiro(ChaveDeConfiguracao.VALOR_APROVACAO_REEMBOLSO, atual.valorAprovacaoReembolso(),
                novo.valorAprovacaoReembolso(), ator);
        mudarInteiro(ChaveDeConfiguracao.PERCENTUAL_ALERTA_DEVOLUCAO, atual.percentualAlertaDevolucao(),
                novo.percentualAlertaDevolucao(), ator);
        return ler();
    }

    private void mudarInteiro(ChaveDeConfiguracao chave, int antes, int depois, Ator ator) {
        if (antes == depois) {
            return;
        }
        jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = ? WHERE chave = ?", depois, chave.chave());
        auditar(chave, antes, depois, ator);
    }

    private void mudarDinheiro(ChaveDeConfiguracao chave, BigDecimal antes, BigDecimal depois, Ator ator) {
        BigDecimal novo = depois == null ? null : Dinheiro.arredondar(depois);
        if (Objects.equals(antes, novo)) {
            return;
        }
        jdbc.update("UPDATE corona.configuracoes SET valor_dinheiro = ? WHERE chave = ?", novo, chave.chave());
        auditar(chave, antes == null ? null : antes.toPlainString(), novo == null ? null : novo.toPlainString(), ator);
    }

    private void auditar(ChaveDeConfiguracao chave, Object antes, Object depois, Ator ator) {
        UUID id = jdbc.queryForObject("SELECT id FROM corona.configuracoes WHERE chave = ?", UUID.class, chave.chave());
        auditoria.registrar(ator, "GARANTIA_ALTERADA", "configuracoes", id, valor(chave, antes), valor(chave, depois));
    }

    private static Map<String, Object> valor(ChaveDeConfiguracao chave, Object valor) {
        Map<String, Object> campos = new LinkedHashMap<>();
        campos.put("chave", chave.chave());
        campos.put("valor", valor);
        return campos;
    }
}
