package br.com.coronasemijoias.api.seguranca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.coronasemijoias.api.comum.configuracao.AnalisadorConfiguracaoInvalida;
import br.com.coronasemijoias.api.comum.configuracao.ConfiguracaoInvalidaException;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** A API não sobe sem a chave atual válida, e explica em português (ADR-0004, item 1). */
class ChavesDoTokenTest {

    private static final String VALIDA = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String OUTRA_VALIDA = Base64.getEncoder().encodeToString(new byte[48]);

    private static PropriedadesDeSeguranca.Jwt jwt(String idAtual, String atual, String idAnterior, String anterior) {
        return new PropriedadesDeSeguranca.Jwt("corona-api", Duration.ofMinutes(15),
                new PropriedadesDeSeguranca.Chave(idAtual, atual),
                new PropriedadesDeSeguranca.Chave(idAnterior, anterior));
    }

    private static ConfiguracaoInvalidaException erro(PropriedadesDeSeguranca.Jwt jwt) {
        try {
            ChavesDoToken.de(jwt);
        } catch (ConfiguracaoInvalidaException e) {
            return e;
        }
        throw new AssertionError("devia recusar a configuração");
    }

    @Test
    void chaveAtualAusente() {
        assertThat(erro(jwt("chave-1", "", "", "")).descricao()).contains("JWT_CHAVE_ATUAL está vazia");
        assertThat(erro(jwt("chave-1", null, null, null)).descricao()).contains("JWT_CHAVE_ATUAL está vazia");
    }

    @Test
    void chaveAtualComMenosDe256Bits() {
        String curta = Base64.getEncoder().encodeToString(new byte[31]);
        ConfiguracaoInvalidaException erro = erro(jwt("chave-1", curta, "", ""));
        assertThat(erro.descricao()).contains("JWT_CHAVE_ATUAL").contains("pelo menos 32 bytes (256 bits)");
        assertThat(erro.descricao() + erro.acao()).doesNotContain(curta);
    }

    @Test
    void chaveAtualForaDoBase64() {
        assertThat(erro(jwt("chave-1", "troque-isto", "", "")).descricao()).contains("Base64");
    }

    @Test
    void nomeDaChaveInvalido() {
        assertThat(erro(jwt("", VALIDA, "", "")).descricao()).contains("JWT_CHAVE_ATUAL_ID");
        assertThat(erro(jwt("chave com espaço", VALIDA, "", "")).descricao()).contains("JWT_CHAVE_ATUAL_ID");
    }

    @Test
    void chaveAnteriorIncompletaOuComOMesmoNome() {
        assertThat(erro(jwt("chave-2", VALIDA, "chave-1", "")).descricao()).contains("JWT_CHAVE_ANTERIOR está vazia");
        assertThat(erro(jwt("chave-2", VALIDA, "chave-2", OUTRA_VALIDA)).descricao()).contains("mesmo nome");
        assertThat(erro(jwt("chave-2", VALIDA, "chave-1", "curta")).descricao()).contains("JWT_CHAVE_ANTERIOR");
    }

    @Test
    void duasChavesValidas() {
        ChavesDoToken chaves = ChavesDoToken.de(jwt("chave-2", VALIDA, "chave-1", OUTRA_VALIDA));
        assertThat(chaves.idAtual()).isEqualTo("chave-2");
        assertThat(chaves.porId("chave-2")).isSameAs(chaves.atual());
        assertThat(chaves.porId("chave-1").getEncoded()).hasSize(48);
        assertThat(chaves.porId("chave-3")).isNull();
        assertThat(chaves.porId(null)).isNull();
    }

    @Test
    void semChaveAnteriorSoAAtualVale() {
        ChavesDoToken chaves = ChavesDoToken.de(jwt("chave-1", VALIDA, "", ""));
        assertThat(chaves.porId("chave-1")).isNotNull();
    }

    @Test
    void segredoNaoApareceNoTextoDaConfiguracao() {
        assertThat(new PropriedadesDeSeguranca.Chave("chave-1", VALIDA).toString()).doesNotContain(VALIDA);
    }

    @Test
    void analisadorMostraADescricaoEAAcao() {
        ConfiguracaoInvalidaException erro = erro(jwt("chave-1", "", "", ""));
        FailureAnalysis analise = new AnalisadorConfiguracaoInvalida().analyze(new RuntimeException(erro));
        assertThat(analise.getDescription()).isEqualTo(erro.descricao());
        assertThat(analise.getAction()).contains("openssl rand -base64 32").contains(".env");
        assertThatThrownBy(() -> ChavesDoToken.de(null)).isInstanceOf(ConfiguracaoInvalidaException.class);
    }
}
