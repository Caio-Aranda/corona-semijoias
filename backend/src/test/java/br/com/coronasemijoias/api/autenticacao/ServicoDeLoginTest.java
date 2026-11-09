package br.com.coronasemijoias.api.autenticacao;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.seguranca.EmissorDeToken;
import br.com.coronasemijoias.api.usuarios.ConferenciaDeSenha;
import br.com.coronasemijoias.api.usuarios.UsuarioRepositorio;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Todo login passa por uma conferência BCrypt, para o tempo de resposta não revelar quais logins
 * existem (ADR-0004, item 6): também o login inexistente e o que nem pode existir.
 */
class ServicoDeLoginTest {

    private final UsuarioRepositorio usuarios = mock(UsuarioRepositorio.class);
    private final ConferenciaDeSenha conferencia = mock(ConferenciaDeSenha.class);
    private final ServicoDeLogin servico = new ServicoDeLogin(
            usuarios, conferencia, mock(EmissorDeToken.class), Clock.systemUTC());

    private void recusadoComoLoginInvalido(String login, String senha) {
        assertThatThrownBy(() -> servico.entrar(login, senha))
                .isInstanceOfSatisfying(ErroDaApi.class, e -> org.assertj.core.api.Assertions
                        .assertThat(e.codigo()).isEqualTo(CodigoErro.LOGIN_INVALIDO));
    }

    @Test
    void loginInexistenteConfereContraOHashFixo() {
        when(usuarios.findByLogin("ninguem")).thenReturn(Optional.empty());
        recusadoComoLoginInvalido("  NINGUEM ", "senha-1");
        verify(usuarios).findByLogin("ninguem");
        verify(conferencia, times(1)).confere(anyString(), isNull());
    }

    @Test
    void loginQueNaoPodeExistirNemEhProcuradoMasPassaPeloBcrypt() {
        for (String login : new String[] {"", "   ", "com espaço", "com\ttab", "com\u0000nulo"}) {
            recusadoComoLoginInvalido(login, "senha-1");
        }
        verify(usuarios, never()).findByLogin(anyString());
        verify(conferencia, times(5)).confere(anyString(), isNull());
    }
}
