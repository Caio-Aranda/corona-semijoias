package br.com.coronasemijoias.api.relatorios;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** O antes e o depois da auditoria em texto da loja (tarefa 8.9): nome do campo do glossário, sem senha, hash nem token. */
class TextoDaAuditoriaTest {

    @Test
    void camposConhecidosUsamONomeDaLojaEOsDesconhecidosViramFrase() {
        Map<String, Object> campos = new LinkedHashMap<>();
        campos.put("preco_venda", "12.50");
        campos.put("estoque_minimo", 3);
        campos.put("dentro_garantia", false);
        campos.put("campo_novo_qualquer", "x");
        assertThat(TextoDaAuditoria.dosCampos(campos))
                .isEqualTo("Preço de venda: 12.50; Estoque mínimo: 3; Dentro da garantia: Não; Campo novo qualquer: x");
    }

    @Test
    void campoSecretoNuncaSai() {
        Map<String, Object> campos = new LinkedHashMap<>();
        campos.put("login", "ana");
        campos.put("senha_hash", "$2a$10$xxxxxxxx");
        campos.put("senha", "123");
        campos.put("senhaTemporaria", "abc");
        campos.put("refresh_token", "t");
        campos.put("segredo", "s");
        assertThat(TextoDaAuditoria.dosCampos(campos)).isEqualTo("Login: ana");
    }

    @Test
    void vazioENuloENumeroEListaEBooleano() {
        assertThat(TextoDaAuditoria.dosCampos(null)).isNull();
        assertThat(TextoDaAuditoria.dosCampos(Map.of())).isNull();
        Map<String, Object> campos = new LinkedHashMap<>();
        campos.put("restricao", null);
        campos.put("ativo", true);
        campos.put("motivos_revisao", java.util.List.of("A", "B"));
        assertThat(TextoDaAuditoria.dosCampos(campos)).isEqualTo("Restrição: sem valor; Ativo: Sim; Motivos da revisão: A, B");
    }

    @Test
    void acaoETabelaEmPalavrasDaLoja() {
        assertThat(TextoDaAuditoria.acao("PRECO_VENDA_ALTERADO")).isEqualTo("Preço de venda alterado");
        assertThat(TextoDaAuditoria.acao("ACAO_NOVA_QUE_NAO_CONHECEMOS")).isEqualTo("Acao nova que nao conhecemos");
        assertThat(TextoDaAuditoria.area("devolucoes")).isEqualTo("Devolução");
        assertThat(TextoDaAuditoria.area("tabela_nova")).isEqualTo("Tabela nova");
    }
}
