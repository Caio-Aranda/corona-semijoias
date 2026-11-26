package br.com.coronasemijoias.api.configuracoes;

import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A meta, os percentuais e os dias do retorno do consignado (plano acerto-45-dias, D5; especificação §23.13): ler e mudar as
 * quatro chaves, com auditoria do valor antigo. A faixa é conferida aqui (mensagem clara) e no banco (segunda trava).
 */
@Service
public class ServicoDoConsignado {

    public static final int META_MAXIMA = 1_000_000_000;
    public static final int PERCENTUAL_MAXIMO = 40;
    public static final int DIAS_MAXIMOS = 365;

    public record Consignado(int metaGuarani, int percentualAbaixoDaMeta, int percentualNaMeta, int diasDoRetorno) {
    }

    private final Configuracoes configuracoes;
    private final JdbcTemplate jdbc;
    private final ServicoDeAuditoria auditoria;

    ServicoDoConsignado(Configuracoes configuracoes, JdbcTemplate jdbc, ServicoDeAuditoria auditoria) {
        this.configuracoes = configuracoes;
        this.jdbc = jdbc;
        this.auditoria = auditoria;
    }

    @Transactional(readOnly = true)
    public Consignado ler() {
        return new Consignado(configuracoes.inteiro(ChaveDeConfiguracao.CONSIGNADO_META_GUARANI),
                configuracoes.inteiro(ChaveDeConfiguracao.CONSIGNADO_COMISSAO_ABAIXO_DA_META),
                configuracoes.inteiro(ChaveDeConfiguracao.CONSIGNADO_COMISSAO_NA_META),
                configuracoes.inteiro(ChaveDeConfiguracao.CONSIGNADO_DIAS_DO_RETORNO));
    }

    @Transactional
    public Consignado alterar(Consignado novo, UUID donaId) {
        if (novo.metaGuarani() < 1 || novo.metaGuarani() > META_MAXIMA) {
            throw Validacoes.recusar("metaGuarani", "Use uma meta entre 1 e 1.000.000.000 de Guaranis.");
        }
        for (int p : new int[] {novo.percentualAbaixoDaMeta(), novo.percentualNaMeta()}) {
            if (p < 0 || p > PERCENTUAL_MAXIMO) {
                throw Validacoes.recusar("percentual", "Use uma porcentagem entre 0 e 40.");
            }
        }
        if (novo.percentualAbaixoDaMeta() > novo.percentualNaMeta()) {
            throw Validacoes.recusar("percentualAbaixoDaMeta", "A porcentagem abaixo da meta não pode ser maior que a porcentagem na meta.");
        }
        if (novo.diasDoRetorno() < 1 || novo.diasDoRetorno() > DIAS_MAXIMOS) {
            throw Validacoes.recusar("diasDoRetorno", "Use de 1 a 365 dias para o retorno.");
        }
        Consignado atual = ler();
        Ator ator = Ator.usuario(donaId);
        mudar(ChaveDeConfiguracao.CONSIGNADO_META_GUARANI, atual.metaGuarani(), novo.metaGuarani(), ator);
        mudar(ChaveDeConfiguracao.CONSIGNADO_COMISSAO_ABAIXO_DA_META, atual.percentualAbaixoDaMeta(), novo.percentualAbaixoDaMeta(), ator);
        mudar(ChaveDeConfiguracao.CONSIGNADO_COMISSAO_NA_META, atual.percentualNaMeta(), novo.percentualNaMeta(), ator);
        mudar(ChaveDeConfiguracao.CONSIGNADO_DIAS_DO_RETORNO, atual.diasDoRetorno(), novo.diasDoRetorno(), ator);
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
}
