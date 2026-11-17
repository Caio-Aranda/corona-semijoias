package br.com.coronasemijoias.api.relatorios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Relatório de Auditoria da dona (tarefa 8.9, §12 Controle). Massa própria desta classe: duas pessoas e um registro do servidor,
 * cada um com registros de ids conhecidos; todo filtro usa essas pessoas ou esses ids, então nada colide com as outras classes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ControleDeAuditoriaTest extends ApoioDoControle {

    @Autowired
    ServicoDeAuditoria auditoria;

    @Autowired
    TransactionTemplate transacao;

    UsuarioDeTeste ana;
    UsuarioDeTeste bia;
    final UUID produto = UUID.randomUUID();
    final UUID usuarioCriado = UUID.randomUUID();
    final UUID devolucao = UUID.randomUUID();
    final UUID autorizacao = UUID.randomUUID();

    @BeforeAll
    void massa() {
        ana = usuarios.criarDona();
        bia = usuarios.criarVendedor();
        Map<String, Object> segredos = new LinkedHashMap<>();
        segredos.put("login", "novo.login");
        segredos.put("senha_hash", "$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ012");
        segredos.put("senhaTemporaria", "segredo-123");
        segredos.put("token", "tok-secreto");
        segredos.put("ativo", true);
        transacao.executeWithoutResult(s -> {
            auditoria.registrar(Ator.usuario(ana.id()), "PRECO_VENDA_ALTERADO", "produtos", produto,
                    Map.of("preco_venda", "10.00"), Map.of("preco_venda", "12.50"));
            auditoria.registrar(Ator.usuario(ana.id()), "USUARIO_CRIADO", "usuarios", usuarioCriado, null, segredos);
            auditoria.registrar(Ator.usuario(bia.id()), "DEVOLUCAO_REGISTRADA", "devolucoes", devolucao, null,
                    Map.of("valor_reembolsado", "5.00", "dentro_garantia", true, "forma_reembolso", "DINHEIRO"));
            auditoria.registrar(Ator.PELO_SERVIDOR, "AUTORIZACAO_VENCIDA", "autorizacoes_excecao", autorizacao,
                    Map.of("situacao", "PEDIDA"), Map.of("situacao", "VENCIDA"));
        });
    }

    @Test
    void filtraPorPessoaEMostraAntesEDepoisEmTextoDaLoja() throws Exception {
        JsonNode r = ler("/auditoria?usuarioId=" + ana.id());
        assertThat(r.get("total").asInt()).isEqualTo(2);
        assertThat(r.get("itens")).hasSize(2);
        JsonNode preco = linhaDe(r, "PRECO_VENDA_ALTERADO");
        assertThat(preco.get("quem").asString()).isEqualTo(ana.nome());
        assertThat(preco.get("oQueFoi").asString()).isEqualTo("Preço de venda alterado");
        assertThat(preco.get("area").asString()).isEqualTo("Produto");
        assertThat(preco.get("antes").asString()).isEqualTo("Preço de venda: 10.00");
        assertThat(preco.get("depois").asString()).isEqualTo("Preço de venda: 12.50");
        assertThat(preco.get("registroId").asString()).isEqualTo(produto.toString());
        assertThat(preco.get("ocorridaEm").asString()).isNotBlank();
        assertThat(total(r, "todos").get("quantidade").asInt()).isEqualTo(2);
        assertThat(total(r, "por-pessoas").get("quantidade").asInt()).isEqualTo(2);
        assertThat(total(r, "pelo-servidor").get("quantidade").asInt()).isZero();
    }

    @Test
    void nuncaMostraSenhaHashNemToken() throws Exception {
        JsonNode r = ler("/auditoria?registroId=" + usuarioCriado);
        assertThat(r.get("total").asInt()).isEqualTo(1);
        JsonNode linha = r.get("itens").get(0);
        String depois = linha.get("depois").asString();
        assertThat(depois).contains("Login: novo.login").contains("Ativo: Sim");
        assertThat(depois.toLowerCase()).doesNotContain("senha").doesNotContain("hash").doesNotContain("$2a")
                .doesNotContain("segredo").doesNotContain("tok-secreto").doesNotContain("token");
        assertThat(linha.get("antes").isNull()).isTrue();
        assertThat(json.writeValueAsString(r)).doesNotContain("$2a$10").doesNotContain("segredo-123");
    }

    @Test
    void registroDoServidorApareceComoPeloServidor() throws Exception {
        JsonNode r = ler("/auditoria?registroId=" + autorizacao);
        assertThat(r.get("total").asInt()).isEqualTo(1);
        JsonNode linha = r.get("itens").get(0);
        assertThat(linha.get("quem").asString()).isEqualTo("Pelo servidor");
        assertThat(linha.get("usuarioId").isNull()).isTrue();
        assertThat(linha.get("antes").asString()).isEqualTo("Situação: PEDIDA");
        assertThat(total(r, "pelo-servidor").get("quantidade").asInt()).isEqualTo(1);
    }

    @Test
    void filtraPorAcaoETabelaEBuscaEPeriodo() throws Exception {
        assertThat(ler("/auditoria?usuarioId=" + bia.id() + "&acao=DEVOLUCAO_REGISTRADA").get("total").asInt()).isEqualTo(1);
        assertThat(ler("/auditoria?usuarioId=" + bia.id() + "&acao=PRECO_VENDA_ALTERADO").get("total").asInt()).isZero();
        assertThat(ler("/auditoria?usuarioId=" + ana.id() + "&tabela=usuarios").get("total").asInt()).isEqualTo(1);
        assertThat(ler("/auditoria?busca=" + bia.nome()).get("total").asInt()).isEqualTo(1);
        assertThat(ler("/auditoria?usuarioId=" + ana.id() + "&de=2000-01-01&ate=2000-01-31").get("total").asInt()).isZero();
        String hoje = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        assertThat(ler("/auditoria?usuarioId=" + ana.id() + "&de=" + hoje + "&ate=" + hoje).get("total").asInt()).isEqualTo(2);
        chamar("/auditoria?usuarioId=" + ana.id() + "&de=2026-02-01&ate=2026-01-01").andExpect(status().isBadRequest());
    }

    @Test
    void recorteMostraSoAsLinhasDoTotalEDevolucaoMostraReembolsoNaLoja() throws Exception {
        JsonNode r = ler("/auditoria?usuarioId=" + bia.id() + "&recorte=por-pessoas");
        assertThat(r.get("recorte").asString()).isEqualTo("por-pessoas");
        assertThat(r.get("itens")).hasSize(1);
        assertThat(r.get("itens").get(0).get("depois").asString())
                .contains("Valor reembolsado: 5.00").contains("Dentro da garantia: Sim").contains("Forma do reembolso: DINHEIRO");
        chamar("/auditoria?recorte=nao-existe").andExpect(status().isBadRequest());
        chamar("/auditoria?usuarioId=nao-e-uuid").andExpect(status().isBadRequest());
    }

    @Test
    void ordemDoMaisNovoParaOMaisAntigoEPaginacao() throws Exception {
        JsonNode r = ler("/auditoria?usuarioId=" + ana.id() + "&tamanho=1&pagina=0");
        assertThat(r.get("itens")).hasSize(1);
        assertThat(r.get("total").asInt()).isEqualTo(2);
        assertThat(r.get("itens").get(0).get("acao").asString()).isEqualTo("USUARIO_CRIADO");
        JsonNode segunda = ler("/auditoria?usuarioId=" + ana.id() + "&tamanho=1&pagina=1");
        assertThat(segunda.get("itens").get(0).get("acao").asString()).isEqualTo("PRECO_VENDA_ALTERADO");
    }

    private static JsonNode linhaDe(JsonNode relatorio, String acao) {
        for (JsonNode l : relatorio.get("itens")) {
            if (l.get("acao").asString().equals(acao)) {
                return l;
            }
        }
        throw new AssertionError("Sem a linha " + acao);
    }
}
