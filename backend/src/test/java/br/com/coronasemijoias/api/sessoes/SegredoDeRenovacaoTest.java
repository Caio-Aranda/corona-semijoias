package br.com.coronasemijoias.api.sessoes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import br.com.coronasemijoias.api.comum.configuracao.ConfiguracaoInvalidaException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** O segredo que deriva os tokens de renovação (ADR-0004, item 2) e a conferência dele ao subir. */
class SegredoDeRenovacaoTest {

    private static String aleatorio(int bytes) {
        byte[] valor = new byte[bytes];
        new SecureRandom().nextBytes(valor);
        return Base64.getEncoder().encodeToString(valor);
    }

    private static SegredoDeRenovacao segredo(String valor, String chaveDoToken) {
        return new SegredoDeRenovacao(new PropriedadesDaSessao(null, null, valor), chaveDoToken, "");
    }

    private static String erro(String valor, String chaveDoToken) {
        return catchThrowableOfType(ConfiguracaoInvalidaException.class, () -> segredo(valor, chaveDoToken))
                .descricao();
    }

    @Test
    void ausenteCurtoOuIgualAChaveDoTokenNaoSobe() {
        String chave = aleatorio(32);
        assertThat(erro("", chave)).contains("SESSAO_SEGREDO_RENOVACAO está vazia");
        assertThat(erro(null, chave)).contains("SESSAO_SEGREDO_RENOVACAO está vazia");
        assertThat(erro("troque-isto", chave)).contains("pelo menos 32 bytes");
        assertThat(erro(aleatorio(16), chave)).contains("pelo menos 32 bytes");
        assertThat(erro(chave, chave)).contains("igual a uma das chaves do token de acesso");
    }

    @Test
    void oSucessorDerivadoEhSempreOMesmoParaOMesmoRegistro() {
        SegredoDeRenovacao segredo = segredo(aleatorio(32), aleatorio(32));
        UUID id = UUID.randomUUID();
        assertThat(segredo.derivado(id)).isEqualTo(segredo.derivado(id)).hasSize(43)
                .isNotEqualTo(segredo.derivado(UUID.randomUUID()));
        // Com outro segredo, o mesmo registro dá outro valor.
        assertThat(segredo(aleatorio(32), aleatorio(32)).derivado(id)).isNotEqualTo(segredo.derivado(id));
        assertThat(segredo.aleatorio()).hasSize(43).isNotEqualTo(segredo.aleatorio());
        assertThat(SegredoDeRenovacao.hash("abc")).hasSize(32);
        assertThat(segredo.toString()).doesNotContain("=");
    }
}
