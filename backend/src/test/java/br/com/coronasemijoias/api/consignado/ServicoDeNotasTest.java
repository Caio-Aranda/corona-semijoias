package br.com.coronasemijoias.api.consignado;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.coronasemijoias.api.consignado.ServicoDeNotas.ItemDaNota;
import br.com.coronasemijoias.api.consignado.ServicoDeNotas.NovaNota;
import br.com.coronasemijoias.api.consignado.ServicoDeNotas.PecaQueEntra;
import br.com.coronasemijoias.api.consignado.ServicoDeNotas.PecaQueVolta;
import br.com.coronasemijoias.api.consignado.ServicoDeNotas.Reposicao;
import br.com.coronasemijoias.api.consignado.ServicoDeNotas.Troca;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Semana do representante e nota do consignado (ADR-0016, itens 2, 3, 5, 6, 7 e 11), com PostgreSQL de verdade. Escritos antes
 * do código.
 */
class ServicoDeNotasTest extends ApoioDoConsignado {

    // --- semana ---

    @Test
    void novaSemanaFechaAAnteriorEFicaUmaSoAberta() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        Instant t = relogio.instant();
        UUID s1 = abrirSemana(v, 1, t);
        UUID s2 = abrirSemana(v, 2, t.plusSeconds(3600));
        assertThat(jdbc.queryForObject("SELECT situacao FROM corona.semanas_vendedor WHERE id = ?", String.class, s1)).isEqualTo("FECHADA");
        assertThat(jdbc.queryForObject("SELECT situacao FROM corona.semanas_vendedor WHERE id = ?", String.class, s2)).isEqualTo("ABERTA");
        assertThat(contar("SELECT count(*) FROM corona.semanas_vendedor WHERE vendedor_id = ? AND situacao = 'ABERTA'", v.vendedorId()))
                .isEqualTo(1);
    }

    @Test
    void semanaRepetidaComOMesmoIdEInofensivaEComNumeroRepetidoERecusada() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID id = UUID.randomUUID();
        Instant t = relogio.instant();
        assertThat(semanas.abrir(ctx(v), new ServicoDeSemanasDoVendedor.SemanaNova(id, 5, t))).isFalse();
        assertThat(semanas.abrir(ctx(v), new ServicoDeSemanasDoVendedor.SemanaNova(id, 5, t))).isTrue();
        Throwable erro = catchThrowable(() -> semanas.abrir(ctx(v), new ServicoDeSemanasDoVendedor.SemanaNova(UUID.randomUUID(), 5, t)));
        assertThat(codigoDoErro(erro)).isEqualTo("NUMERO_REPETIDO");
        Throwable outro = catchThrowable(() -> semanas.abrir(ctx(v), new ServicoDeSemanasDoVendedor.SemanaNova(id, 6, t)));
        assertThat(codigoDoErro(outro)).isEqualTo("CONSIGNADO_JA_REGISTRADO_DIFERENTE");
    }

    // --- nota ---

    @Test
    void notaComDuasMaletasGravaItensEMovimentosESaldoCerto() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cliente = cliente(v);
        UUID anel = peca("100.00", 20);
        UUID colar = peca("50.00", 20);
        UUID m1 = maletaEnviada(v, anel, 10, colar, 5);
        UUID m2 = maletaEnviada(v, anel, 6);
        UUID semana = abrirSemana(v);

        UUID nota = criarNota(v, semana, 1, cliente, item(anel, m1, 3, "100.00"), item(colar, m1, 2, "50.00"),
                item(anel, m2, 4, "100.00"));

        assertThat(dx(nota, m1, anel)).isEqualTo(3);
        assertThat(dx(nota, m2, anel)).isEqualTo(4);
        assertThat(disponivel(m1, anel)).isEqualTo(7);
        assertThat(disponivel(m1, colar)).isEqualTo(3);
        assertThat(disponivel(m2, anel)).isEqualTo(2);
        assertThat(situacaoDaNota(nota)).isEqualTo("EM_ABERTO");
        // "Com clientes" = −Σ movimentos do consignado (sem acerto ainda).
        assertThat(contar("SELECT -coalesce(sum(quantidade), 0) FROM corona.movimentos_remessa WHERE nota_id = ?", nota)).isEqualTo(9);
        // O número semana-sequência fica reservado.
        assertThat(contar("SELECT count(*) FROM corona.numeros_da_semana WHERE registro_id = ? AND tipo = 'NOTA'", nota)).isEqualTo(1);
    }

    @Test
    void notaSaiDeMaletaEnviadaSemConferencia() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("10.00", 5);
        UUID maleta = maletaEnviada(v, anel, 5);
        assertThat(jdbc.queryForObject("SELECT status FROM corona.remessas WHERE id = ?", String.class, maleta)).isEqualTo("ENVIADA");
        criarNota(v, abrirSemana(v), 1, cliente(v), item(anel, maleta, 1, "10.00"));
        assertThat(disponivel(maleta, anel)).isEqualTo(4);
    }

    @Test
    void pecaForaDaMaletaERecusada() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("10.00", 5);
        UUID outra = peca("20.00", 5);
        UUID maleta = maletaEnviada(v, anel, 5);
        UUID semana = abrirSemana(v);
        UUID cliente = cliente(v);
        Throwable erro = catchThrowable(() -> notas.criar(ctx(v), nota(semana, 1, cliente, item(outra, maleta, 1, "20.00"))));
        assertThat(codigoDoErro(erro)).isEqualTo("PECA_FORA_DA_MALETA");
        assertThat(contar("SELECT count(*) FROM corona.notas_consignado WHERE vendedor_id = ?", v.vendedorId())).isZero();
    }

    @Test
    void maletaDeOutroVendedorEmRecusada() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UsuarioDeTeste outro = usuarios.criarVendedor();
        UUID anel = peca("10.00", 5);
        UUID maletaDoOutro = maletaEnviada(outro, anel, 5);
        UUID semana = abrirSemana(v);
        UUID cliente = cliente(v);
        Throwable erro = catchThrowable(() -> notas.criar(ctx(v), nota(semana, 1, cliente, item(anel, maletaDoOutro, 1, "10.00"))));
        assertThat(codigoDoErro(erro)).isEqualTo("MALETA_NAO_ESTA_COM_VOCE");
    }

    @Test
    void clienteDeOutroVendedorERecusado() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UsuarioDeTeste outro = usuarios.criarVendedor();
        UUID anel = peca("10.00", 5);
        UUID maleta = maletaEnviada(v, anel, 5);
        UUID semana = abrirSemana(v);
        UUID clienteDoOutro = cliente(outro);
        Throwable erro = catchThrowable(() -> notas.criar(ctx(v), nota(semana, 1, clienteDoOutro, item(anel, maleta, 1, "10.00"))));
        assertThat(codigoDoErro(erro)).isEqualTo("CLIENTE_DE_OUTRO_VENDEDOR");
    }

    @Test
    void notaSemPecasERecusada() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID semana = abrirSemana(v);
        UUID cliente = cliente(v);
        Throwable erro = catchThrowable(() -> notas.criar(ctx(v), nota(semana, 1, cliente)));
        assertThat(codigoDoErro(erro)).isEqualTo("PEDIDO_SEM_PECAS");
    }

    @Test
    void semSaldoNaMaletaEAceitoComAlerta() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("10.00", 5);
        UUID maleta = maletaEnviada(v, anel, 2);
        UUID nota = criarNota(v, abrirSemana(v), 1, cliente(v), item(anel, maleta, 5, "10.00"));
        assertThat(dx(nota, maleta, anel)).isEqualTo(5);
        assertThat(alertas(v, "PECA_SEM_SALDO_NA_MALETA")).isEqualTo(1);
        // Aceita: a peça está com o cliente; o saldo da maleta fica negativo, e o alerta avisa a dona.
        assertThat(disponivel(maleta, anel)).isEqualTo(-3);
    }

    @Test
    void precoDiferenteDaMaletaVaiComOPrecoDaMaletaEAlerta() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("100.00", 5);
        UUID maleta = maletaEnviada(v, anel, 5);
        UUID nota = criarNota(v, abrirSemana(v), 1, cliente(v), item(anel, maleta, 1, "90.00"));
        assertThat(valor("SELECT preco_unitario FROM corona.itens_nota_consignado WHERE nota_id = ?", nota)).isEqualByComparingTo("100.00");
        assertThat(alertas(v, "PRECO_DIFERENTE_DA_MALETA")).isEqualTo(1);
    }

    @Test
    void notaEmSemanaFechadaERecusadaMasAnteriorAoFechamentoPassa() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("10.00", 9);
        UUID maleta = maletaEnviada(v, anel, 9);
        UUID cliente = cliente(v);
        Instant t = relogio.instant();
        UUID s1 = abrirSemana(v, 1, t.minusSeconds(7200));
        abrirSemana(v, 2, t.minusSeconds(3600)); // fecha a 1 uma hora atrás

        // Feita antes do fechamento (sem internet): passa.
        NovaNota antes = new NovaNota(UUID.randomUUID(), s1, 1, cliente, null, LocalDate.now().plusDays(7),
                List.of(item(anel, maleta, 1, "10.00")), null);
        notas.criar(ctxEm(v, t.minusSeconds(5400)), antes);
        // Feita depois do fechamento: recusada.
        NovaNota depois = new NovaNota(UUID.randomUUID(), s1, 2, cliente, null, LocalDate.now().plusDays(7),
                List.of(item(anel, maleta, 1, "10.00")), null);
        Throwable erro = catchThrowable(() -> notas.criar(ctxEm(v, t), depois));
        assertThat(codigoDoErro(erro)).isEqualTo("SEMANA_DA_NOTA_FECHADA");
    }

    @Test
    void numeroRepetidoComOutroIdERecusadoEMesmoIdEInofensivo() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("10.00", 9);
        UUID maleta = maletaEnviada(v, anel, 9);
        UUID cliente = cliente(v);
        UUID semana = abrirSemana(v);
        NovaNota n = nota(semana, 7, cliente, item(anel, maleta, 1, "10.00"));
        assertThat(notas.criar(ctx(v), n)).isFalse();
        assertThat(notas.criar(ctx(v), n)).isTrue();
        assertThat(disponivel(maleta, anel)).isEqualTo(8); // não duplicou a saída

        Throwable repetido = catchThrowable(() -> notas.criar(ctx(v), nota(semana, 7, cliente, item(anel, maleta, 1, "10.00"))));
        assertThat(codigoDoErro(repetido)).isEqualTo("NUMERO_REPETIDO");
        NovaNota diferente = new NovaNota(n.id(), semana, 7, cliente, null, n.dataCobranca(), List.of(item(anel, maleta, 2, "10.00")), null);
        Throwable conflito = catchThrowable(() -> notas.criar(ctx(v), diferente));
        assertThat(codigoDoErro(conflito)).isEqualTo("CONSIGNADO_JA_REGISTRADO_DIFERENTE");
        assertThat(disponivel(maleta, anel)).isEqualTo(8);
    }

    // --- reposição, troca, remarcação ---

    @Test
    void reposicaoNaSemanaAcrescentaPecasNaMesmaNota() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("10.00", 9);
        UUID colar = peca("30.00", 9);
        UUID maleta = maletaEnviada(v, anel, 5, colar, 4);
        UUID nota = criarNota(v, abrirSemana(v), 1, cliente(v), item(anel, maleta, 2, "10.00"));

        Reposicao r = new Reposicao(UUID.randomUUID(), nota, List.of(item(anel, maleta, 1, "10.00"), item(colar, maleta, 2, "30.00")));
        assertThat(notas.reporNaNota(ctx(v), r)).isFalse();
        assertThat(notas.reporNaNota(ctx(v), r)).isTrue();

        assertThat(dx(nota, maleta, anel)).isEqualTo(3);
        assertThat(dx(nota, maleta, colar)).isEqualTo(2);
        assertThat(disponivel(maleta, anel)).isEqualTo(2);
        assertThat(disponivel(maleta, colar)).isEqualTo(2);
        assertThat(contar("SELECT count(*) FROM corona.reposicoes_nota WHERE nota_id = ?", nota)).isEqualTo(1);
    }

    @Test
    void reposicaoDeSemanaAnteriorEUmaNotaNovaComAMesmaDataDeCobranca() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("10.00", 9);
        UUID maleta = maletaEnviada(v, anel, 9);
        UUID cliente = cliente(v);
        Instant t = relogio.instant();
        UUID s1 = abrirSemana(v, 1, t.minusSeconds(7200));
        UUID antiga = criarNota(v, s1, 1, cliente, item(anel, maleta, 2, "10.00"));
        UUID s2 = abrirSemana(v, 2, t.minusSeconds(3600));

        NovaNota nova = new NovaNota(UUID.randomUUID(), s2, 1, cliente, antiga, LocalDate.now().plusDays(99),
                List.of(item(anel, maleta, 1, "10.00")), null);
        notas.criar(ctx(v), nova);

        // A data vem da nota de origem (o app não decide).
        assertThat(jdbc.queryForObject("SELECT data_cobranca FROM corona.notas_consignado WHERE id = ?", LocalDate.class, nova.id()))
                .isEqualTo(jdbc.queryForObject("SELECT data_cobranca FROM corona.notas_consignado WHERE id = ?", LocalDate.class, antiga));
        assertThat(jdbc.queryForObject("SELECT nota_origem_id FROM corona.notas_consignado WHERE id = ?", UUID.class, nova.id()))
                .isEqualTo(antiga);
    }

    @Test
    void trocaDevolvePecaEPoeOutraPeloPrecoDaMaleta() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("100.00", 9);
        UUID brinco = peca("60.00", 9);
        UUID maleta = maletaEnviada(v, anel, 5, brinco, 5);
        UUID nota = criarNota(v, abrirSemana(v), 1, cliente(v), item(anel, maleta, 3, "100.00"));

        Troca troca = new Troca(UUID.randomUUID(), nota, new PecaQueVolta(UUID.randomUUID(), anel, maleta, UUID.randomUUID()),
                new PecaQueEntra(UUID.randomUUID(), brinco, maleta, new java.math.BigDecimal("60.00"), UUID.randomUUID()));
        assertThat(notas.trocar(ctx(v), troca)).isFalse();
        assertThat(notas.trocar(ctx(v), troca)).isTrue();

        assertThat(dx(nota, maleta, anel)).isEqualTo(2);
        assertThat(dx(nota, maleta, brinco)).isEqualTo(1);
        assertThat(disponivel(maleta, anel)).isEqualTo(3); // 5 - 3 + 1
        assertThat(disponivel(maleta, brinco)).isEqualTo(4);
        // Preço diferente muda o DX: 2 x 100 + 1 x 60.
        assertThat(valor("SELECT sum(quantidade * preco_unitario) FROM corona.itens_nota_consignado WHERE nota_id = ?", nota))
                .isEqualByComparingTo("260.00");
    }

    @Test
    void trocaDePecaQueNaoEstaNaNotaERecusada() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("100.00", 9);
        UUID brinco = peca("60.00", 9);
        UUID maleta = maletaEnviada(v, anel, 5, brinco, 5);
        UUID nota = criarNota(v, abrirSemana(v), 1, cliente(v), item(anel, maleta, 1, "100.00"));
        Troca troca = new Troca(UUID.randomUUID(), nota, new PecaQueVolta(UUID.randomUUID(), brinco, maleta, UUID.randomUUID()),
                new PecaQueEntra(UUID.randomUUID(), anel, maleta, new java.math.BigDecimal("100.00"), UUID.randomUUID()));
        assertThat(codigoDoErro(catchThrowable(() -> notas.trocar(ctx(v), troca)))).isEqualTo("DEVOLUCAO_MAIOR_QUE_O_LEVADO");
    }

    @Test
    void remarcarMudaADataDeCobrancaEMarcaRemarcado() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("10.00", 9);
        UUID maleta = maletaEnviada(v, anel, 9);
        UUID nota = criarNota(v, abrirSemana(v), 1, cliente(v), item(anel, maleta, 1, "10.00"));
        LocalDate nova = LocalDate.now().plusDays(20);
        ServicoDeNotas.Remarcacao r = new ServicoDeNotas.Remarcacao(UUID.randomUUID(), nota, nova);
        assertThat(notas.remarcar(ctx(v), r)).isFalse();
        assertThat(notas.remarcar(ctx(v), r)).isTrue();
        assertThat(jdbc.queryForObject("SELECT data_cobranca FROM corona.notas_consignado WHERE id = ?", LocalDate.class, nota)).isEqualTo(nova);
        assertThat(situacaoDaNota(nota)).isEqualTo("REMARCADO");
        assertThat(contar("SELECT count(*) FROM corona.remarcacoes_nota WHERE nota_id = ?", nota)).isEqualTo(1);
    }

    @Test
    void notaDeOutroVendedorNaoSeMexe() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UsuarioDeTeste outro = usuarios.criarVendedor();
        UUID anel = peca("10.00", 9);
        UUID maleta = maletaEnviada(v, anel, 9);
        UUID nota = criarNota(v, abrirSemana(v), 1, cliente(v), item(anel, maleta, 1, "10.00"));
        ServicoDeNotas.Remarcacao r = new ServicoDeNotas.Remarcacao(UUID.randomUUID(), nota, LocalDate.now().plusDays(20));
        assertThat(codigoDoErro(catchThrowable(() -> notas.remarcar(ctx(outro), r)))).isEqualTo("NOTA_NAO_ENCONTRADA");
    }

    @Test
    void ItemDaNotaGuardaSoOQueOAppViu() {
        ItemDaNota i = item(UUID.randomUUID(), UUID.randomUUID(), 2, "10.00");
        assertThat(i.quantidade()).isEqualTo(2);
    }
}
