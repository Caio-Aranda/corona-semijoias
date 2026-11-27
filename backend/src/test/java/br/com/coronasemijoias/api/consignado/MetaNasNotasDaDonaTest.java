package br.com.coronasemijoias.api.consignado;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.coronasemijoias.api.configuracoes.ServicoDoConsignado;
import br.com.coronasemijoias.api.configuracoes.ServicoDoConsignado.Consignado;
import br.com.coronasemijoias.api.consignado.ConsultasDoConsignado.NotaResumo;
import br.com.coronasemijoias.api.consignado.ServicoDeAcertosDaNota.NovoAcerto;
import br.com.coronasemijoias.api.testes.CotacoesDaMassa;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A dona vê, na lista e na ficha da nota, se a nota bateu a meta (plano acerto-45-dias, seção 4, item 4; ESPECIFICACAO §23.13).
 * Regra INFORMADA fica sem meta. Escritos antes do código. PostgreSQL de verdade.
 */
class MetaNasNotasDaDonaTest extends ApoioDoConsignado {

    private static final Consignado PADRAO = new Consignado(700_000, 30, 40, 45);

    @Autowired
    ServicoDoConsignado configuracaoDoConsignado;

    @Autowired
    ConsultasDoConsignado consultas;

    @BeforeEach
    @AfterEach
    void padraoESemCotacao() throws SQLException {
        configuracaoDoConsignado.alterar(PADRAO, usuarios.dona().id());
        try (var con = conectarComoMigracao()) {
            CotacoesDaMassa.tirar(con);
        }
    }

    private UUID notaAcertada(String preco, boolean formatoNovo) throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cliente = cliente(v);
        UUID peca = peca(preco, 5);
        UUID maleta = maletaEnviada(v, peca, 3);
        UUID semana = abrirSemana(v);
        UUID nota = criarNota(v, semana, 1, cliente, item(peca, maleta, 1, preco));
        acertos.acertar(ctx(v), new NovoAcerto(UUID.randomUUID(), nota, semana,
                formatoNovo ? null : new java.math.BigDecimal("30"), List.of(), null, null));
        return nota;
    }

    private NotaResumo naLista(UUID nota) {
        return consultas.notas(null, null, null, null, null).stream().filter(n -> n.id().equals(nota)).findFirst().orElseThrow();
    }

    @Test
    void naMetaExataABateuEMostraOVdEmGuarani() throws Exception {
        CotacoesDaMassa.doExemplo(jdbc);
        UUID nota = notaAcertada("630.00", true);
        NotaResumo n = naLista(nota);
        assertThat(n.regraDaComissao()).isEqualTo("META");
        assertThat(n.bateuMeta()).isTrue();
        assertThat(n.vdGuarani()).isEqualTo(700_000L);
        assertThat(n.metaGuarani()).isEqualTo(700_000L);
        var a = consultas.detalhe(nota).acertos().get(0);
        assertThat(a.bateuMeta()).isTrue();
        assertThat(a.vdGuarani()).isEqualTo(700_000L);
        assertThat(a.regraDaComissao()).isEqualTo("META");
    }

    @Test
    void umGuaraniAbaixoNaoBateu() throws Exception {
        CotacoesDaMassa.doExemplo(jdbc);
        NotaResumo n = naLista(notaAcertada("629.99", true));
        assertThat(n.bateuMeta()).isFalse();
        assertThat(n.vdGuarani()).isEqualTo(699_989L);
    }

    @Test
    void semCotacaoNaoBateuEFicaSemGuarani() throws Exception {
        NotaResumo n = naLista(notaAcertada("630.00", true));
        assertThat(n.regraDaComissao()).isEqualTo("META");
        assertThat(n.bateuMeta()).isFalse();
        assertThat(n.vdGuarani()).isNull();
    }

    @Test
    void acertoDoFormatoAntigoFicaSemMetaNemGuarani() throws Exception {
        NotaResumo n = naLista(notaAcertada("630.00", false));
        assertThat(n.regraDaComissao()).isEqualTo("INFORMADA");
        assertThat(n.bateuMeta()).isNull();
        assertThat(n.metaGuarani()).isNull();
        assertThat(n.vdGuarani()).isNull();
    }

    @Test
    void notaSemAcertoNaoTemMeta() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID peca = peca("10.00", 5);
        UUID maleta = maletaEnviada(v, peca, 3);
        UUID nota = criarNota(v, abrirSemana(v), 1, cliente(v), item(peca, maleta, 1, "10.00"));
        NotaResumo n = naLista(nota);
        assertThat(n.regraDaComissao()).isNull();
        assertThat(n.bateuMeta()).isNull();
    }
}
