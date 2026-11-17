package br.com.coronasemijoias.api.relatorios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;

/**
 * Relatório de Acessos (tarefa 8.9, §12 Controle). Massa feita à mão, de duas pessoas só desta classe, e de um login que não existe:
 *
 * <pre>
 * Pessoa C (dona, Corona Gestão): 1 entrada; 2 senhas erradas, a segunda completou 5 erros e bloqueou o login (15 min).
 * Pessoa D (vendedor, Corona Vendas): 1 entrada; 1 tentativa recusada por bloqueio; 1 usuário desativado.
 * Login que não existe: 1 senha errada, mostrado sem nome de pessoa.
 * Por pessoa C: 3 tentativas, 1 entrada, 2 erradas, 1 com bloqueio. Por pessoa D: 3 tentativas, 1 entrada, 0 erradas, 1 bloqueio.
 * </pre>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ControleDeAcessosTest extends ApoioDoControle {

    UsuarioDeTeste c;
    UsuarioDeTeste d;
    final String desconhecido = "ninguem." + UUID.randomUUID().toString().substring(0, 8);
    final Instant bloqueioAte = Instant.now().plus(15, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);

    @BeforeAll
    void massa() {
        c = usuarios.criarDona();
        d = usuarios.criarVendedor();
        tentativa(c.login(), "gestao", "SUCESSO", "203.0.113.5", null);
        tentativa(c.login(), "gestao", "SENHA_INCORRETA", "203.0.113.5", null);
        tentativa(c.login(), "gestao", "SENHA_INCORRETA", "203.0.113.5", bloqueioAte);
        tentativa(d.login(), "vendas", "SUCESSO", "203.0.113.6", null);
        tentativa(d.login(), "vendas", "BLOQUEADO", "203.0.113.6", null);
        tentativa(d.login(), "vendas", "USUARIO_DESATIVADO", "203.0.113.6", null);
        tentativa(desconhecido, "gestao", "SENHA_INCORRETA", "203.0.113.7", null);
    }

    private void tentativa(String login, String app, String resultado, String ip, Instant bloqueio) {
        jdbc.update("""
                INSERT INTO corona.tentativas_login (id, login, ip, tipo_app, resultado, bloqueio_ate, ocorrida_em)
                VALUES (?, ?, ?::inet, ?, ?, ?, now())""", UUID.randomUUID(), login, ip, app, resultado,
                bloqueio == null ? null : Timestamp.from(bloqueio));
    }

    @Test
    void totaisDaPessoaDona() throws Exception {
        JsonNode r = ler("/acessos?busca=" + c.login());
        assertThat(r.get("total").asInt()).isEqualTo(3);
        assertThat(total(r, "todos").get("quantidade").asInt()).isEqualTo(3);
        assertThat(total(r, "entradas").get("quantidade").asInt()).isEqualTo(1);
        assertThat(total(r, "erradas").get("quantidade").asInt()).isEqualTo(2);
        assertThat(total(r, "bloqueios").get("quantidade").asInt()).isEqualTo(1);
    }

    @Test
    void totaisDoVendedorContamBloqueioRecusado() throws Exception {
        JsonNode r = ler("/acessos?busca=" + d.login());
        assertThat(r.get("total").asInt()).isEqualTo(3);
        assertThat(total(r, "entradas").get("quantidade").asInt()).isEqualTo(1);
        assertThat(total(r, "erradas").get("quantidade").asInt()).isZero();
        assertThat(total(r, "bloqueios").get("quantidade").asInt()).isEqualTo(1);
    }

    @Test
    void linhaTemPessoaAplicativoResultadoEmPalavrasSimplesEBloqueio() throws Exception {
        JsonNode r = ler("/acessos?busca=" + c.login() + "&resultado=SENHA_INCORRETA");
        assertThat(r.get("itens")).hasSize(2);
        JsonNode bloqueada = r.get("itens").get(0); // a mais nova
        assertThat(bloqueada.get("pessoa").asString()).isEqualTo(c.nome());
        assertThat(bloqueada.get("login").asString()).isEqualTo(c.login());
        assertThat(bloqueada.get("aplicativo").asString()).isEqualTo("Corona Gestão");
        assertThat(bloqueada.get("resultado").asString()).isEqualTo("SENHA_INCORRETA");
        assertThat(bloqueada.get("resultadoNaTela").asString()).isEqualTo("Senha errada");
        assertThat(bloqueada.get("ip").asString()).isEqualTo("203.0.113.5");
        assertThat(Instant.parse(bloqueada.get("bloqueadoAte").asString())).isEqualTo(bloqueioAte);
        assertThat(r.get("itens").get(1).get("bloqueadoAte").isNull()).isTrue();
    }

    @Test
    void loginQueNaoExisteApareceSemNomeDePessoa() throws Exception {
        JsonNode r = ler("/acessos?busca=" + desconhecido);
        assertThat(r.get("total").asInt()).isEqualTo(1);
        JsonNode linha = r.get("itens").get(0);
        assertThat(linha.get("pessoa").isNull()).isTrue();
        assertThat(linha.get("login").asString()).isEqualTo(desconhecido);
    }

    @Test
    void filtraPorAplicativoEBuscaPeloNomeDaPessoa() throws Exception {
        assertThat(ler("/acessos?busca=" + d.login() + "&aplicativo=gestao").get("total").asInt()).isZero();
        assertThat(ler("/acessos?busca=" + d.login() + "&aplicativo=vendas").get("total").asInt()).isEqualTo(3);
        assertThat(ler("/acessos?busca=" + c.nome().replace(" ", "%20")).get("total").asInt()).isEqualTo(3);
    }

    @Test
    void recorteEPeriodoEErros() throws Exception {
        JsonNode r = ler("/acessos?busca=" + c.login() + "&recorte=bloqueios");
        assertThat(r.get("itens")).hasSize(1);
        assertThat(ler("/acessos?busca=" + c.login() + "&de=2000-01-01&ate=2000-01-02").get("total").asInt()).isZero();
        String hoje = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        assertThat(ler("/acessos?busca=" + c.login() + "&de=" + hoje + "&ate=" + hoje).get("total").asInt()).isEqualTo(3);
        chamar("/acessos?recorte=nada").andExpect(status().isBadRequest());
        chamar("/acessos?resultado=QUALQUER").andExpect(status().isBadRequest());
        chamar("/acessos?aplicativo=outro").andExpect(status().isBadRequest());
    }
}
