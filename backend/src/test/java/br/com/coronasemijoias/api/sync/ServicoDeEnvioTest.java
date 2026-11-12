package br.com.coronasemijoias.api.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.coronasemijoias.api.aparelhos.ServicoDeAparelhos;
import br.com.coronasemijoias.api.aparelhos.SituacaoDoAparelho;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import br.com.coronasemijoias.api.sync.OperacoesDoVendedor.Aplicada;
import br.com.coronasemijoias.api.sync.OperacoesDoVendedor.Lida;
import br.com.coronasemijoias.api.sync.ServicoDeEnvio.OperacaoEnviada;
import br.com.coronasemijoias.api.sync.ServicoDeEnvio.PedidoDeEnvio;
import br.com.coronasemijoias.api.sync.ServicoDeEnvio.Resultado;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;

/**
 * Erro passageiro numa operação (tempo esgotado, trava, conexão): ela fica {@code NAO_PROCESSADO} e o lote PARA ali,
 * para não quebrar a ordem (ADR-0013, item 2). As anteriores ficam aceitas; o envio não conta como completo.
 */
class ServicoDeEnvioTest {

    @Test
    void erroPassageiroParaOLoteNaOperacaoEMantemAOrdem() {
        OperacoesDoVendedor operacoes = mock(OperacoesDoVendedor.class);
        ServicoDeAparelhos aparelhos = mock(ServicoDeAparelhos.class);
        PlatformTransactionManager transacoes = mock(PlatformTransactionManager.class);
        when(transacoes.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
        UUID usuario = UUID.randomUUID();
        UUID aparelho = UUID.randomUUID();
        when(aparelhos.situacao(aparelho, usuario)).thenReturn(Optional.of(SituacaoDoAparelho.ATIVO));
        UUID primeira = UUID.randomUUID();
        UUID segunda = UUID.randomUUID();
        UUID terceira = UUID.randomUUID();
        Lida lida1 = new Lida("CLIENTE_NOVO", 1, new Object(), "a".repeat(64), null);
        Lida lida2 = new Lida("CLIENTE_NOVO", 1, new Object(), "b".repeat(64), null);
        when(operacoes.ler(eq(primeira), any(), anyInt(), any())).thenReturn(lida1);
        when(operacoes.ler(eq(segunda), any(), anyInt(), any())).thenReturn(lida2);
        when(operacoes.ler(eq(terceira), any(), anyInt(), any())).thenReturn(lida1);
        when(operacoes.aplicar(eq(lida1), any())).thenReturn(new Aplicada(false, null));
        when(operacoes.aplicar(eq(lida2), any())).thenThrow(new QueryTimeoutException("tempo esgotado"));

        ServicoDeEnvio servico = new ServicoDeEnvio(mock(JdbcTemplate.class), JsonMapper.builder().build(), operacoes, aparelhos,
                mock(ServicoDeAuditoria.class), transacoes, Clock.systemUTC());
        UsuarioAutenticado quem = new UsuarioAutenticado(usuario, "VENDEDOR", UUID.randomUUID(), "jti", UUID.randomUUID(),
                aparelho, null);
        ServicoDeEnvio.Envio envio = servico.enviar(quem, new PedidoDeEnvio(Instant.now(), 0, List.of(
                new OperacaoEnviada(primeira, "CLIENTE_NOVO", 1, null, null, null),
                new OperacaoEnviada(segunda, "CLIENTE_NOVO", 1, null, null, null),
                new OperacaoEnviada(terceira, "CLIENTE_NOVO", 1, null, null, null))));

        assertThat(envio.resultados()).extracting(ServicoDeEnvio.ResultadoDaOperacao::resultado)
                .containsExactly(Resultado.ACEITO, Resultado.NAO_PROCESSADO, Resultado.NAO_PROCESSADO);
        assertThat(envio.apagarDados()).isFalse();
        // A terceira nem foi tentada; o envio não é "completo".
        verify(operacoes, org.mockito.Mockito.times(1)).aplicar(eq(lida1), any());
        verify(aparelhos).registrarPush(aparelho, 0, false);
    }
}
