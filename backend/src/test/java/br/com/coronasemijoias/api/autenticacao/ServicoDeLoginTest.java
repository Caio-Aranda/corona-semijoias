package br.com.coronasemijoias.api.autenticacao;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.coronasemijoias.api.acessos.ControleDeTentativas;
import br.com.coronasemijoias.api.aplicativo.PropriedadesDoApp;
import br.com.coronasemijoias.api.aplicativo.TipoDeApp;
import br.com.coronasemijoias.api.acessos.PropriedadesDasTentativas;
import br.com.coronasemijoias.api.acessos.TravaPorIp;
import br.com.coronasemijoias.api.autenticacao.ServicoDeLogin.PedidoDeEntrada;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.seguranca.EmissorDeToken;
import br.com.coronasemijoias.api.sessoes.ServicoDeSessoes;
import br.com.coronasemijoias.api.usuarios.ConferenciaDeSenha;
import br.com.coronasemijoias.api.usuarios.UsuarioRepositorio;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Todo login passa por uma conferência BCrypt, para o tempo de resposta não revelar quais logins
 * existem (ADR-0004, item 6): também o login inexistente e o que nem pode existir.
 */
class ServicoDeLoginTest {

    private final UsuarioRepositorio usuarios = mock(UsuarioRepositorio.class);
    private final ConferenciaDeSenha conferencia = mock(ConferenciaDeSenha.class);
    private final ServicoDeLogin servico = new ServicoDeLogin(
            usuarios, conferencia, mock(EmissorDeToken.class), mock(ServicoDeSessoes.class),
            mock(ControleDeTentativas.class), new TravaPorIp(),
            new PropriedadesDasTentativas(0, null, null, 0, null, null, null),
            new PropriedadesDoApp(java.util.Map.of("gestao", "1.0.0", "vendas", "1.0.0"),
                    java.util.Map.of("gestao", java.util.List.of("ADMIN"), "vendas", java.util.List.of("VENDEDOR"))),
            TransactionOperations.withoutTransaction(), Clock.systemUTC(),
            mock(br.com.coronasemijoias.api.aparelhos.ServicoDeAparelhos.class));

    private void recusadoComoLoginInvalido(String login, String senha) {
        assertThatThrownBy(() -> servico.entrar(new PedidoDeEntrada(login, senha, null, "10.0.0.1", TipoDeApp.GESTAO)))
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
