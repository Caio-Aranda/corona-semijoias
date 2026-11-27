package br.com.coronasemijoias.api.consignado;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.coronasemijoias.api.conciliacoes.ConciliacoesDaFase7;
import br.com.coronasemijoias.api.consignado.ServicoDoFechamentoDaMaleta.DecisaoDaFalta;
import br.com.coronasemijoias.api.consignado.ServicoDoFechamentoDaMaleta.LeituraDoFechamento;
import br.com.coronasemijoias.api.consignado.ServicoDoFechamentoDaMaleta.LinhaDaPeca;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Falta na conclusão e peças que voltam depois do fechamento (revisão de 27/11/2026, C2 e C3; ESPECIFICACAO §23.10, itens 1
 * e 3). PostgreSQL de verdade. Escritos antes do código.
 */
class FaltaEVoltaDoFechamentoTest extends ApoioDoConsignado {

    @Autowired
    ServicoDoFechamentoDaMaleta fechamento;

    private UUID donaId() {
        return usuarios.dona().id();
    }

    private static ServicoDoFechamentoDaMaleta.Leitura bipe(UUID produto, int q) {
        return new ServicoDoFechamentoDaMaleta.Leitura(UUID.randomUUID(), produto, q);
    }

    private static DecisaoDaFalta cobrar(UUID produto) {
        return new DecisaoDaFalta(UUID.randomUUID(), produto, "COBRAR_DO_REPRESENTANTE", null);
    }

    private static DecisaoDaFalta perda(UUID produto, String motivo) {
        return new DecisaoDaFalta(UUID.randomUUID(), produto, "PERDA_DA_LOJA", motivo);
    }

    private LinhaDaPeca linha(LeituraDoFechamento l, UUID produto) {
        return l.pecas().stream().filter(p -> p.produtoId().equals(produto)).findFirst().orElseThrow();
    }

    private int estoque(UUID produto) {
        return contar("SELECT estoque_atual FROM corona.produtos WHERE id = ?", produto);
    }

    private String status(UUID maleta) {
        return jdbc.queryForObject("SELECT status FROM corona.remessas WHERE id = ?", String.class, maleta);
    }

    /** C8, C11 e C12 da Fase 7 sem nenhuma diferença desta maleta. */
    private void conferenciasLimpas(UUID maleta, UUID... produtos) {
        for (String chave : List.of("C8", "C11", "C12")) {
            var d = new ConciliacoesDaFase7(jdbc).conferir(chave).conferencias().get(0).divergencias();
            for (var x : d) {
                String id = String.valueOf(x.referencia());
                assertThat(id).as(chave + " " + d).isNotEqualTo(maleta.toString());
                for (UUID p : produtos) {
                    assertThat(id).as(chave + " " + d).isNotEqualTo(p.toString());
                }
            }
        }
    }

    @Test
    void comFaltaSemDecisaoNaoConcluiENadaEGravado() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("100.00", 10);
        UUID maleta = maletaEnviada(v, anel, 2);
        fechamento.iniciar(maleta, donaId());
        fechamento.registrarLeituras(maleta, List.of(bipe(anel, 1)), donaId());

        Throwable erro = catchThrowable(() -> fechamento.concluir(maleta, List.of(), donaId()));
        assertThat(codigoDoErro(erro)).isEqualTo("FALTA_SEM_DECISAO");
        assertThat(status(maleta)).isEqualTo("ENVIADA");
        assertThat(contar("SELECT count(*) FROM corona.movimentos_remessa WHERE remessa_id = ? AND tipo IN ('PERDA', 'DEVOLUCAO_DONA')", maleta))
                .isZero();
        assertThat(contar("SELECT count(*) FROM corona.pendencias_da_maleta WHERE remessa_id = ?", maleta)).isZero();
    }

    @Test
    void cobrarDoRepresentanteGravaAPerdaEAPendenciaAPrecoDaMaleta() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("100.00", 10);
        UUID colar = peca("50.00", 10);
        UUID maleta = maletaEnviada(v, anel, 3, colar, 2);
        fechamento.iniciar(maleta, donaId());
        fechamento.registrarLeituras(maleta, List.of(bipe(anel, 1), bipe(colar, 2)), donaId());

        fechamento.concluir(maleta, List.of(cobrar(anel)), donaId());

        assertThat(status(maleta)).isEqualTo("FECHADA");
        assertThat(estoque(anel)).isEqualTo(10 - 3 + 1);
        assertThat(contar("SELECT -sum(quantidade) FROM corona.movimentos_remessa WHERE remessa_id = ? AND produto_id = ? AND tipo = 'PERDA'",
                maleta, anel)).isEqualTo(2);
        var p = jdbc.queryForMap("SELECT quantidade, preco_unitario, valor, decisao, vendedor_id FROM corona.pendencias_da_maleta WHERE remessa_id = ?",
                maleta);
        assertThat(p.get("quantidade")).isEqualTo(2);
        assertThat((BigDecimal) p.get("preco_unitario")).isEqualByComparingTo("100.00");
        assertThat((BigDecimal) p.get("valor")).isEqualByComparingTo("200.00");
        assertThat(p.get("decisao")).isEqualTo("COBRAR_DO_REPRESENTANTE");
        assertThat(p.get("vendedor_id")).isEqualTo(v.vendedorId());

        LeituraDoFechamento l = fechamento.ler(maleta);
        assertThat(l.pendencias()).singleElement().satisfies(x -> {
            assertThat(x.quantidade()).isEqualTo(2);
            assertThat(x.decisao()).isEqualTo("COBRAR_DO_REPRESENTANTE");
            assertThat(x.valor()).isEqualByComparingTo("200.00");
        });
        assertThat(linha(l, anel).diferenca()).isZero();
        conferenciasLimpas(maleta, anel, colar);
    }

    @Test
    void perdaDaLojaPedeMotivoEDecisaoDePecaSemFaltaERecusada() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("100.00", 10);
        UUID colar = peca("50.00", 10);
        UUID maleta = maletaEnviada(v, anel, 2, colar, 1);
        fechamento.iniciar(maleta, donaId());
        fechamento.registrarLeituras(maleta, List.of(bipe(colar, 1)), donaId());

        assertThat(codigoDoErro(catchThrowable(() -> fechamento.concluir(maleta, List.of(perda(anel, "  ")), donaId()))))
                .isEqualTo("DADOS_INVALIDOS");
        assertThat(codigoDoErro(catchThrowable(() -> fechamento.concluir(maleta, List.of(perda(anel, "quebrou"), perda(colar, "x")),
                donaId())))).isEqualTo("DADOS_INVALIDOS");
        assertThat(status(maleta)).isEqualTo("ENVIADA");

        fechamento.concluir(maleta, List.of(perda(anel, "Quebrou na viagem")), donaId());
        assertThat(jdbc.queryForObject("SELECT decisao FROM corona.pendencias_da_maleta WHERE remessa_id = ?", String.class, maleta))
                .isEqualTo("PERDA_DA_LOJA");
        assertThat(jdbc.queryForObject("SELECT motivo FROM corona.pendencias_da_maleta WHERE remessa_id = ?", String.class, maleta))
                .isEqualTo("Quebrou na viagem");
        conferenciasLimpas(maleta, anel, colar);
    }

    @Test
    void pecaQueVoltaDepoisDoFechamentoELidaEVoltaAoEstoqueSoAteOQueAClienteDevolveu() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("100.00", 10);
        UUID maleta = maletaEnviada(v, anel, 3);
        UUID semana = abrirSemana(v);
        UUID nota = criarNota(v, semana, 1, cliente(v), item(anel, maleta, 2, "100.00"));
        fechamento.iniciar(maleta, donaId());
        fechamento.registrarLeituras(maleta, List.of(bipe(anel, 1)), donaId());
        fechamento.concluir(maleta, List.of(), donaId());
        assertThat(estoque(anel)).isEqualTo(10 - 3 + 1);

        // Antes do acerto as 2 estão com a cliente: ler agora é recusado e nada muda.
        assertThat(linha(fechamento.ler(maleta), anel).paraLerDepois()).isZero();
        assertThat(codigoDoErro(catchThrowable(() -> fechamento.registrarVoltas(maleta, List.of(bipe(anel, 1)), donaId()))))
                .isEqualTo("PECA_AINDA_COM_A_CLIENTE");

        // A cliente devolve 1 no acerto (e fica com 1, vendida): há 1 para ler depois.
        acertar(v, nota, semana, "30", List.of(volta(anel, maleta, 1)), null);
        LinhaDaPeca antes = linha(fechamento.ler(maleta), anel);
        assertThat(antes.paraLerDepois()).isEqualTo(1);
        assertThat(antes.comClientes()).isZero();

        var b = bipe(anel, 1);
        assertThat(fechamento.registrarVoltas(maleta, List.of(b), donaId())).isEqualTo(1);
        assertThat(fechamento.registrarVoltas(maleta, List.of(b), donaId())).isZero(); // reenviar não duplica
        assertThat(estoque(anel)).isEqualTo(10 - 3 + 1 + 1);
        assertThat(status(maleta)).isEqualTo("FECHADA");
        LinhaDaPeca depois = linha(fechamento.ler(maleta), anel);
        assertThat(depois.paraLerDepois()).isZero();
        assertThat(depois.diferenca()).isZero();
        // Mais uma: não há mais nada que a cliente devolveu.
        assertThat(codigoDoErro(catchThrowable(() -> fechamento.registrarVoltas(maleta, List.of(bipe(anel, 1)), donaId()))))
                .isEqualTo("PECA_AINDA_COM_A_CLIENTE");
        conferenciasLimpas(maleta, anel);
    }

    @Test
    void lerDepoisSoNaMaletaFechada() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("100.00", 10);
        UUID maleta = maletaEnviada(v, anel, 1);
        assertThat(codigoDoErro(catchThrowable(() -> fechamento.registrarVoltas(maleta, List.of(bipe(anel, 1)), donaId()))))
                .isEqualTo("FECHAMENTO_NAO_ENCONTRADO");
    }
}
