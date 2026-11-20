package br.com.coronasemijoias.api.consignado;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.consignado.ServicoDoFechamentoDaMaleta.LeituraDoFechamento;
import br.com.coronasemijoias.api.consignado.ServicoDoFechamentoDaMaleta.LinhaDaPeca;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * O Fechamento da maleta (ADR-0016, item 14): enviadas, devolvidas pelos clientes, vendidas, com clientes, esperado, bipadas e
 * diferença; rateio dos valores pelo VD de cada maleta; concluir devolve ao estoque e fecha a maleta. PostgreSQL de verdade.
 * Escritos antes do código.
 */
class FechamentoDaMaletaTest extends ApoioDoConsignado {

    @Autowired
    ServicoDoFechamentoDaMaleta fechamento;

    private UUID donaId() {
        return usuarios.dona().id();
    }

    private static ServicoDoFechamentoDaMaleta.Leitura bipe(UUID produto, int q) {
        return new ServicoDoFechamentoDaMaleta.Leitura(UUID.randomUUID(), produto, q);
    }

    private LinhaDaPeca linha(LeituraDoFechamento l, UUID produto) {
        return l.pecas().stream().filter(p -> p.produtoId().equals(produto)).findFirst().orElseThrow();
    }

    @Test
    void leituraComEnviadasDevolvidasVendidasComClientesBipadasEDiferenca() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cliente = cliente(v);
        UUID outro = cliente(v);
        UUID anel = peca("100.00", 20);
        UUID colar = peca("50.00", 20);
        UUID maleta = maletaEnviada(v, anel, 10, colar, 6);
        UUID semana = abrirSemana(v);
        // Nota 1 (acertada): 5 anéis, 2 voltam, 3 vendidos. Nota 2 (sem acerto): 2 anéis e 1 colar com outro cliente.
        UUID n1 = criarNota(v, semana, 1, cliente, item(anel, maleta, 5, "100.00"));
        acertar(v, n1, semana, "30", List.of(volta(anel, maleta, 2)), 3, paga("PIX", "100.00"));
        criarNota(v, semana, 2, outro, item(anel, maleta, 2, "100.00"), item(colar, maleta, 1, "50.00"));

        fechamento.iniciar(maleta, donaId());
        // Na volta, a dona bipa 3 anéis e 5 colares (esperava 5 anéis na maleta e 5 colares).
        fechamento.registrarLeituras(maleta, List.of(bipe(anel, 3), bipe(colar, 5)), donaId());
        LeituraDoFechamento l = fechamento.ler(maleta);

        LinhaDaPeca a = linha(l, anel);
        assertThat(a.enviadas()).isEqualTo(10);
        assertThat(a.devolvidasPelosClientes()).isEqualTo(2);
        assertThat(a.vendidas()).isEqualTo(3);
        assertThat(a.comClientes()).isEqualTo(2);
        assertThat(a.esperadoNaMaleta()).isEqualTo(5); // 10 - 5 - 2 + 2
        assertThat(a.bipadas()).isEqualTo(3);
        assertThat(a.diferenca()).isEqualTo(-2);
        assertThat(a.aindaComClientes()).hasSize(1);
        assertThat(a.aindaComClientes().get(0).quantidade()).isEqualTo(2);
        LinhaDaPeca c = linha(l, colar);
        assertThat(c.esperadoNaMaleta()).isEqualTo(5);
        assertThat(c.diferenca()).isZero();
        // VD 3 x 100 = 300; comissão 30% = 90; recebido 100; pendência = 210 - 100.
        assertThat(l.valores().vd()).isEqualByComparingTo("300.00");
        assertThat(l.valores().comissaoDosClientes()).isEqualByComparingTo("90.00");
        assertThat(l.valores().recebido()).isEqualByComparingTo("100.00");
        assertThat(l.valores().pendenciasAReceber()).isEqualByComparingTo("110.00");
    }

    @Test
    void valoresDaNotaComDuasMaletasSaoDivididosPeloVDEFechamContaExata() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cliente = cliente(v);
        UUID p1 = peca("10.00", 9);
        UUID p2 = peca("20.00", 9);
        UUID m1 = maletaEnviada(v, p1, 9);
        UUID m2 = maletaEnviada(v, p2, 9);
        UUID semana = abrirSemana(v);
        UUID nota = criarNota(v, semana, 1, cliente, item(p1, m1, 1, "10.00"), item(p2, m2, 1, "20.00"));
        // VD 30; comissão 33,33% -> 10,00 (arredondado); bruto 20; pago 10,01 para o rateio não fechar redondo.
        UUID acerto = acertar(v, nota, semana, "33.33", List.of(), 2, paga("PIX", "10.01"));
        assertThat(acerto).isNotNull();
        fechamento.iniciar(m1, donaId());
        fechamento.iniciar(m2, donaId());
        var a = fechamento.ler(m1).valores();
        var b = fechamento.ler(m2).valores();
        assertThat(a.vd().add(b.vd())).isEqualByComparingTo("30.00");
        assertThat(a.comissaoDosClientes().add(b.comissaoDosClientes())).isEqualByComparingTo(valor("SELECT comissao FROM corona.acertos_nota WHERE id = ?", acerto));
        assertThat(a.recebido().add(b.recebido())).isEqualByComparingTo("10.01");
        assertThat(a.pendenciasAReceber().add(b.pendenciasAReceber()))
                .isEqualByComparingTo(valor("SELECT bruto FROM corona.acertos_nota WHERE id = ?", acerto).subtract(new java.math.BigDecimal("10.01")));
    }

    @Test
    void concluirDevolveAoEstoqueOQueFoiBipadoEFechaAMaleta() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("100.00", 10);
        UUID maleta = maletaEnviada(v, anel, 10);
        UUID semana = abrirSemana(v);
        criarNota(v, semana, 1, cliente(v), item(anel, maleta, 4, "100.00")); // 6 ficam na maleta
        int estoqueAntes = contar("SELECT estoque_atual FROM corona.produtos WHERE id = ?", anel);
        fechamento.iniciar(maleta, donaId());
        fechamento.registrarLeituras(maleta, List.of(bipe(anel, 5), bipe(anel, 3)), donaId()); // 8 bipadas, só 6 esperadas
        fechamento.concluir(maleta, donaId());

        assertThat(jdbc.queryForObject("SELECT status FROM corona.remessas WHERE id = ?", String.class, maleta)).isEqualTo("FECHADA");
        assertThat(contar("SELECT estoque_atual FROM corona.produtos WHERE id = ?", anel)).isEqualTo(estoqueAntes + 6);
        assertThat(contar("SELECT -sum(quantidade) FROM corona.movimentos_remessa WHERE remessa_id = ? AND tipo = 'DEVOLUCAO_DONA'", maleta)).isEqualTo(6);
        LinhaDaPeca l = linha(fechamento.ler(maleta), anel);
        assertThat(l.diferenca()).isEqualTo(2); // bipou 8, esperava 6: sobra para a dona decidir
        assertThat(l.devolvidasALoja()).isEqualTo(6);
        assertThat(l.comClientes()).isEqualTo(4);
        assertThat(codigoDoErro(catchThrowable(() -> fechamento.iniciar(maleta, donaId())))).isEqualTo("MALETA_JA_FECHADA");
    }

    @Test
    void retornoDepoisDoFechamentoEAceitoESaidaDepoisERecusada() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("100.00", 10);
        UUID maleta = maletaEnviada(v, anel, 10);
        UUID semana = abrirSemana(v);
        UUID cliente = cliente(v);
        UUID nota = criarNota(v, semana, 1, cliente, item(anel, maleta, 4, "100.00"));
        fechamento.iniciar(maleta, donaId());
        fechamento.registrarLeituras(maleta, List.of(bipe(anel, 6)), donaId());
        fechamento.concluir(maleta, donaId());

        // O acerto da nota acontece depois do fechamento: a devolução volta à maleta fechada.
        UUID acerto = acertar(v, nota, semana, "30", List.of(volta(anel, maleta, 1)), null);
        assertThat(acerto).isNotNull();
        assertThat(linha(fechamento.ler(maleta), anel).devolvidasPelosClientes()).isEqualTo(1);
        // Mas a maleta fechada não recebe saída de nota.
        Throwable erro = catchThrowable(() -> notas.criar(ctx(v), nota(semana, 2, cliente, item(anel, maleta, 1, "100.00"))));
        assertThat(codigoDoErro(erro)).isEqualTo("MALETA_NAO_ESTA_COM_VOCE");
    }

    @Test
    void fechamentoSoDeMaletaComORepresentanteUmPorVezEBipeForaDaMaletaRecusado() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("100.00", 10);
        UUID estranha = peca("1.00", 5);
        UUID maleta = maletaEnviada(v, anel, 10);
        fechamento.iniciar(maleta, donaId());
        assertThat(codigoDoErro(catchThrowable(() -> fechamento.iniciar(maleta, donaId())))).isEqualTo("FECHAMENTO_JA_EM_ANDAMENTO");
        assertThat(codigoDoErro(catchThrowable(() -> fechamento.registrarLeituras(maleta, List.of(bipe(estranha, 1)), donaId()))))
                .isEqualTo("PECA_NAO_E_DESTA_MALETA");
        // O mesmo bipe reenviado não duplica; com outra quantidade é conflito.
        var b = bipe(anel, 2);
        assertThat(fechamento.registrarLeituras(maleta, List.of(b), donaId())).isEqualTo(1);
        assertThat(fechamento.registrarLeituras(maleta, List.of(b), donaId())).isZero();
        var outro = new ServicoDoFechamentoDaMaleta.Leitura(b.id(), anel, 3);
        assertThat(codigoDoErro(catchThrowable(() -> fechamento.registrarLeituras(maleta, List.of(outro), donaId()))))
                .isEqualTo("LANCAMENTO_JA_REGISTRADO_DIFERENTE");
    }

    @Test
    void pecaQueEDeOutraMaletaDizDeQualMaletaEDeSemMexerNaLeitura() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("100.00", 10);
        UUID brinco = peca("50.00", 10);
        UUID maleta = maletaEnviada(v, anel, 5);
        UUID outra = maletaEnviada(usuarios.criarVendedor(), brinco, 5);
        String codigoDaOutra = jdbc.queryForObject("SELECT codigo FROM corona.remessas WHERE id = ?", String.class, outra);
        fechamento.iniciar(maleta, donaId());

        Throwable erro = catchThrowable(() -> fechamento.registrarLeituras(maleta, List.of(bipe(brinco, 1)), donaId()));

        assertThat(codigoDoErro(erro)).isEqualTo("PECA_NAO_E_DESTA_MALETA");
        assertThat(((ErroDaApi) erro).getBody().getDetail()).contains("não é desta maleta").contains("maleta " + codigoDaOutra);
        assertThat(contar("SELECT count(*) FROM corona.leituras_fechamento_maleta WHERE produto_id = ?", brinco)).isZero();
    }
}
