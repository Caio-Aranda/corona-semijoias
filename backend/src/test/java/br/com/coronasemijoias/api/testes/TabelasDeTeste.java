package br.com.coronasemijoias.api.testes;

import br.com.coronasemijoias.api.sync.RegistroDeTabelas;
import br.com.coronasemijoias.api.sync.TabelaDoServidor;
import br.com.coronasemijoias.api.sync.TabelaSincronizavel;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Tabelas da migração só de teste (V9001), somadas ao registro da aplicação nos testes. O
 * serviço de pull e o teste de convenções enxergam o registro somado.
 */
@TestConfiguration
public class TabelasDeTeste {

    public static final TabelaSincronizavel SYNC_A =
            new TabelaSincronizavel("teste_sync_a", List.of("texto", "valor", "quantidade"), false);
    public static final TabelaSincronizavel SYNC_B =
            new TabelaSincronizavel("teste_sync_b", List.of("nome", "porcentagem"), false);
    public static final TabelaSincronizavel SYNC_IMUTAVEL =
            new TabelaSincronizavel("teste_sync_imutavel", List.of("valor"), true);

    public static final RegistroDeTabelas REGISTRO = new RegistroDeTabelas(
            List.of(SYNC_A, SYNC_B, SYNC_IMUTAVEL),
            List.of(new TabelaDoServidor("teste_servidor", false), new TabelaDoServidor("teste_servidor_historico", true)));

    /** Registro da aplicação mais as tabelas de teste. */
    public static RegistroDeTabelas registroDosTestes() {
        return RegistroDeTabelas.daAplicacao().com(REGISTRO);
    }

    @Bean
    @Primary
    RegistroDeTabelas registroDeTabelasDosTestes() {
        return registroDosTestes();
    }
}
