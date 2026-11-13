package br.com.coronasemijoias.api.configuracoes;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Migração V040 (tarefa 6.13): os prazos de garantia passam a 45 dias só quando ainda estão no valor padrão antigo
 * (7 e 30); o que a dona já mudou pela tela fica como está. A migração roda de novo sobre o banco já migrado, com os
 * valores antigos postos à mão.
 */
class MigracaoDosPrazosDe45DiasTest extends TesteDeIntegracao {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Configuracoes configuracoes;

    @AfterEach
    void voltarAosPadroes() {
        definir(45, 45);
    }

    private void definir(int arrependimento, int defeito) {
        jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = ? WHERE chave = 'prazo_arrependimento_dias'",
                arrependimento);
        jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = ? WHERE chave = 'prazo_defeito_dias'", defeito);
    }

    private void rodarMigracao() throws Exception {
        String sql = new ClassPathResource("db/migration/V040__prazos_de_45_dias.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        for (String comando : sql.split(";")) {
            String limpo = comando.lines().filter(l -> !l.trim().startsWith("--")).reduce("", (a, b) -> a + b + "\n")
                    .trim();
            if (!limpo.isEmpty()) {
                jdbc.execute(limpo);
            }
        }
    }

    private int arrependimento() {
        return configuracoes.inteiro(ChaveDeConfiguracao.PRAZO_ARREPENDIMENTO_DIAS);
    }

    private int defeito() {
        return configuracoes.inteiro(ChaveDeConfiguracao.PRAZO_DEFEITO_DIAS);
    }

    @Test
    void osPadroesAntigosDe7E30ViramDe45() throws Exception {
        definir(7, 30);
        rodarMigracao();
        assertThat(arrependimento()).isEqualTo(45);
        assertThat(defeito()).isEqualTo(45);
    }

    @Test
    void oQueADonaJaMudouNaoMuda() throws Exception {
        definir(10, 60);
        rodarMigracao();
        assertThat(arrependimento()).isEqualTo(10);
        assertThat(defeito()).isEqualTo(60);
    }

    @Test
    void cadaPrazoEDecididoSozinho() throws Exception {
        definir(7, 60);
        rodarMigracao();
        assertThat(arrependimento()).isEqualTo(45);
        assertThat(defeito()).isEqualTo(60);
    }

    @Test
    void umBancoNovoJaNasceCom45() {
        assertThat(arrependimento()).isEqualTo(45);
        assertThat(defeito()).isEqualTo(45);
    }
}
