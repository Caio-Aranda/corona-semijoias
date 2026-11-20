package br.com.coronasemijoias.api.relatorios;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.caixa.ServicoDeCategoriasDoCaixa;
import br.com.coronasemijoias.api.caixa.ServicoDeCategoriasDoCaixa.PedidoDeCategoria;
import br.com.coronasemijoias.api.caixa.ServicoDeLancamentosManuais;
import br.com.coronasemijoias.api.caixa.ServicoDeLancamentosManuais.PedidoDeEstorno;
import br.com.coronasemijoias.api.caixa.ServicoDeLancamentosManuais.PedidoDeLancamento;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.ModoDaDevolucao;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.PecaDoPedido;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.PedidoDaDevolucaoDoCliente;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.OrigemDoMovimento;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.Pedido;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque.TipoDeMovimento;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.LinhaDoPedido;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.PagamentoDoPedido;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.PedidoDeVenda;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Os blocos para montar a massa do resultado do período (tarefa 8.6): vendas e devoluções pelos serviços (como na 8.5), compras,
 * avaria, inventário e caixa manual também pelos serviços (com o relógio parado só durante a chamada), e o fechamento do acerto
 * por SQL, como os testes do acerto fazem (as travas do banco valem: comissão, evento, pendência e caixa são conferidos no
 * COMMIT). Tudo de 2292, fora do que os outros testes usam. A massa de abril está calculada à mão em {@link ResultadoDoPeriodoTest}.
 */
abstract class MassaDoResultado extends ApoioDoCusto {

    @Autowired
    protected TransactionTemplate transacao;

    @Autowired
    protected ServicoDeLancamentosManuais lancamentos;

    @Autowired
    protected ServicoDeCategoriasDoCaixa categoriasDoCaixa;

    /** Os ids dos lançamentos manuais e das categorias que a massa criou (saem no fim). */
    protected final List<UUID> lancamentosDaMassa = new ArrayList<>();
    protected final List<UUID> categoriasDaMassa = new ArrayList<>();

    /** O dia do mês, ao meio-dia de Brasília. */
    protected static Instant dia(String data) {
        return brasilia(data + "T12:00:00");
    }

    /** Cotações dos dias de uma lista: até o dia 12, R$ 5,00 por US$ e R$ 0,001 por Gs.; do 15 em diante, R$ 4,00 e R$ 0,002. */
    protected void cotacoesDe(String anoMes, int... dias) {
        for (int d : dias) {
            String data = anoMes + "-" + (d < 10 ? "0" + d : String.valueOf(d));
            if (d <= 12) {
                cotacao(data, "5.00000000", "0.00100000");
            } else {
                cotacao(data, "4.00000000", "0.00200000");
            }
        }
    }

    protected UUID praca() throws Exception {
        return UUID.fromString(corpoDe(postar("/api/dona/pracas", dona(), mapa("nome", "Praça " + UUID.randomUUID()))
                .andExpect(status().isCreated())).get("id").asString());
    }

    protected UsuarioDeTeste vendedorNaPraca(UUID praca) {
        UsuarioDeTeste v = usuarios.criarVendedor();
        jdbc.update("UPDATE corona.vendedores SET praca_id = ? WHERE id = ?", praca, v.vendedorId());
        return v;
    }

    protected UUID vender(UsuarioDeTeste v, UUID maleta, UUID cliente, String dia, String total, LinhaDoPedido... linhas) {
        UUID id = UUID.randomUUID();
        vendas.vender(v.vendedorId(), v.id(), usuarios.aparelhoDe(v), new PedidoDeVenda(id, maleta, cliente, List.of(linhas),
                List.of(new PagamentoDoPedido(UUID.randomUUID(), new BigDecimal(total), "DINHEIRO")), null), dia(dia), false);
        return id;
    }

    protected UUID devolverPeca(UsuarioDeTeste v, UUID venda, UUID produto, int quantidade, String dia) {
        UUID id = UUID.randomUUID();
        devolucoes.devolver(v.vendedorId(), v.id(), usuarios.aparelhoDe(v), new PedidoDaDevolucaoDoCliente(id, venda,
                "ARREPENDIMENTO", null, "DINHEIRO", List.of(new PecaDoPedido(produto, quantidade, "BOA")), null), dia(dia), false,
                ModoDaDevolucao.ONLINE);
        return id;
    }

    protected void estornarVenda(UsuarioDeTeste v, UUID venda, String dia) {
        vendas.estornar(venda, v.vendedorId(), v.id(), usuarios.aparelhoDe(v), UUID.randomUUID(), "O cliente desistiu na hora",
                dia(dia));
    }

    /** Avaria do estoque central, no dia dado (o relógio fica parado só durante a chamada). */
    protected void avariar(UUID produto, int quantidade, String dia) {
        parado(dia, () -> estoque.registrar(new Pedido(null, produto, TipoDeMovimento.AVARIA, quantidade, "Quebrou",
                OrigemDoMovimento.MANUAL, null), usuarios.dona().id()));
    }

    /** Inventário que achou menos peças (diferença negativa), no dia dado. */
    protected void inventarioParaMenos(UUID produto, int diferenca, String dia) {
        parado(dia, () -> estoque.registrar(new Pedido(null, produto, TipoDeMovimento.AJUSTE, diferenca, "Contagem do inventário",
                OrigemDoMovimento.INVENTARIO, UUID.randomUUID()), usuarios.dona().id()));
    }

    protected UUID categoriaDoCaixa(String nome, String tipo) {
        UUID id = UUID.randomUUID();
        categoriasDoCaixa.criar(usuarios.dona().id(), new PedidoDeCategoria(id, nome, tipo));
        categoriasDaMassa.add(id);
        return id;
    }

    protected UUID lancarNoCaixa(UUID categoria, String tipo, String valor, String dia, String descricao) {
        UUID id = UUID.randomUUID();
        parado(dia, () -> lancamentos.lancar(usuarios.dona().id(),
                new PedidoDeLancamento(id, tipo, new BigDecimal(valor), "DINHEIRO", categoria, LocalDate.parse(dia), descricao)));
        lancamentosDaMassa.add(id);
        return id;
    }

    protected void estornarLancamento(UUID lancamento, String dia) {
        UUID estorno = UUID.randomUUID();
        parado(dia, () -> lancamentos.estornar(usuarios.dona().id(), lancamento, new PedidoDeEstorno(estorno, "Lançado por engano")));
        lancamentosDaMassa.add(estorno);
    }

    private void parado(String dia, Runnable acao) {
        relogio.fixar(dia(dia));
        try {
            acao.run();
        } finally {
            relogio.reiniciar();
        }
    }

    // --- o acerto fechado, por SQL, com as travas do banco valendo ---

    /** Uma linha de comissão: a venda e o percentual; ou o estorno de uma devolução (valor negativo) da venda. */
    protected record Comissao(UUID venda, String base, String percentual, String valor, UUID devolucao) {
    }

    /**
     * Abre e fecha um acerto de uma vez, na data de fechamento dada. {@code vendasDoAcerto} entram como eventos; as devoluções
     * e o estorno de comissão entram pelas linhas {@code estornos}. As contas do item 8 são feitas pelos números dados.
     */
    protected UUID acertoFechado(UsuarioDeTeste v, UUID semana, String diaDoFechamento, String percentual,
            List<UUID> vendasDoAcerto, List<UUID> devolucoesDoAcerto, String comissao, String faltasCobradas,
            String pagoAoVendedor, ComPendencias pendencias) {
        UUID acerto = UUID.randomUUID();
        UUID cotacao = jdbc.queryForObject("SELECT id FROM corona.cotacoes WHERE data_cotacao = ?::date", UUID.class,
                diaDoFechamento);
        transacao.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO corona.acertos (id, vendedor_id, semana_id, status, pedido_pelo_vendedor, iniciado_em, iniciado_por)
                    VALUES (?, ?, ?, 'EM_ACERTO', false, now(), ?)""", acerto, v.vendedorId(), semana, usuarios.dona().id());
            BigDecimal devolvido = BigDecimal.ZERO;
            BigDecimal soma = BigDecimal.ZERO;
            BigDecimal bruto = BigDecimal.ZERO;
            BigDecimal descontos = BigDecimal.ZERO;
            for (UUID venda : vendasDoAcerto) {
                var linha = jdbc.queryForMap("SELECT total, subtotal, desconto FROM corona.vendas WHERE id = ?", venda);
                BigDecimal total = (BigDecimal) linha.get("total");
                bruto = bruto.add((BigDecimal) linha.get("subtotal"));
                descontos = descontos.add((BigDecimal) linha.get("desconto"));
                UUID evento = UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO corona.acerto_eventos (id, acerto_id, vendedor_id, origem_tipo, origem_id, venda_id, valor)
                        VALUES (?, ?, ?, 'VENDA', ?, ?, ?)""", evento, acerto, v.vendedorId(), venda, venda, total);
                soma = soma.add(total);
                BigDecimal pct = new BigDecimal(percentual);
                jdbc.update("""
                        INSERT INTO corona.comissoes (id, vendedor_id, acerto_id, venda_id, evento_id, tipo, base, percentual, valor)
                        VALUES (uuidv7(), ?, ?, ?, ?, 'COMISSAO', ?, ?, ?)""", v.vendedorId(), acerto, venda, evento, total, pct,
                        total.multiply(pct).divide(new BigDecimal("100")).setScale(2, java.math.RoundingMode.HALF_UP));
            }
            for (UUID devolucao : devolucoesDoAcerto) {
                var d = jdbc.queryForMap("SELECT venda_id, valor_devolvido FROM corona.devolucoes WHERE id = ?", devolucao);
                UUID venda = (UUID) d.get("venda_id");
                BigDecimal valor = (BigDecimal) d.get("valor_devolvido");
                UUID evento = UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO corona.acerto_eventos (id, acerto_id, vendedor_id, origem_tipo, origem_id, venda_id, valor)
                        VALUES (?, ?, ?, 'DEVOLUCAO', ?, ?, ?)""", evento, acerto, v.vendedorId(), devolucao, venda, valor.negate());
                var original = jdbc.queryForMap("SELECT id, percentual FROM corona.comissoes WHERE venda_id = ? AND tipo = 'COMISSAO'",
                        venda);
                BigDecimal pct = (BigDecimal) original.get("percentual");
                jdbc.update("""
                        INSERT INTO corona.comissoes (id, vendedor_id, acerto_id, venda_id, evento_id, tipo, base, percentual, valor,
                            estorna_id) VALUES (uuidv7(), ?, ?, ?, ?, 'ESTORNO', ?, ?, ?, ?)""", v.vendedorId(), acerto, venda, evento,
                        valor, pct, valor.multiply(pct).divide(new BigDecimal("100")).setScale(2, java.math.RoundingMode.HALF_UP)
                                .negate(), original.get("id"));
                devolvido = devolvido.add(valor);
                soma = soma.subtract(valor);
            }
            if (pendencias != null) {
                pendencias.criar(acerto);
            }
            BigDecimal comissaoDoAcerto = new BigDecimal(comissao);
            BigDecimal faltas = new BigDecimal(faltasCobradas);
            BigDecimal pago = new BigDecimal(pagoAoVendedor);
            BigDecimal liquido = soma.subtract(comissaoDoAcerto).add(faltas);
            BigDecimal ficouDevendo = liquido.add(pago);
            if (ficouDevendo.signum() > 0) {
                jdbc.update("""
                        INSERT INTO corona.contas_receber (id, devedor_tipo, vendedor_id, origem_tipo, origem_id, parcela,
                            total_parcelas, vencimento, valor) VALUES (?, 'VENDEDOR', ?, 'ACERTO', ?, 1, 1, ?, ?)""", UUID.randomUUID(),
                        v.vendedorId(), acerto, LocalDate.parse(diaDoFechamento).plusDays(30), ficouDevendo);
            }
            jdbc.update("""
                    UPDATE corona.acertos SET status = 'ACERTADO', fechamento_id = ?, conteudo_do_fechamento = 'teste', fechado_em = ?,
                           fechado_por = ?, cotacao_id = ?, meta_guarani = 700000, percentual_comissao = ?::numeric,
                           vendido_para_meta_guarani = 0, vendido_bruto = ?, descontos = ?, devolvido = ?, vendido_liquido = ?,
                           comissao = ?, faltas_cobradas = ?, liquido = ?, recebido_no_acerto = 0, pago_ao_vendedor = ?,
                           ficou_devendo = ? WHERE id = ?""", UUID.randomUUID(), Timestamp.from(dia(diaDoFechamento)),
                    usuarios.dona().id(), cotacao, percentual, bruto, descontos, devolvido, soma, comissaoDoAcerto, faltas, liquido,
                    pago, ficouDevendo, acerto);
            if (pago.signum() > 0) {
                jdbc.update("""
                        INSERT INTO corona.lancamentos_caixa (id, tipo, valor, forma, categoria_id, origem_tipo, origem_id,
                            data_lancamento, descricao) VALUES (?, 'SAIDA', ?, 'DINHEIRO', (SELECT id FROM corona.categorias_caixa
                             WHERE nome = 'Pagamento ao representante'), 'ACERTO_A_FAVOR_DO_VENDEDOR', ?, ?::date, 'Acerto a favor do vendedor')""",
                        UUID.randomUUID(), pago.negate(), acerto, diaDoFechamento);
            }
        });
        return acerto;
    }

    /** O que nasce junto com o acerto, antes de ele fechar. */
    @FunctionalInterface
    protected interface ComPendencias {
        void criar(UUID acerto);
    }

    protected UUID pendenciaDeFalta(UUID acerto, UsuarioDeTeste v, UUID produto, int quantidade, String valor, String status,
            String diaDaResolucao) {
        UUID id = UUID.randomUUID();
        boolean cobrada = "COBRADA".equals(status);
        boolean assumida = "ASSUMIDA_PELA_LOJA".equals(status);
        jdbc.update("""
                INSERT INTO corona.pendencias_acerto (id, acerto_id, vendedor_id, tipo, produto_id, quantidade, valor, status,
                    cobrada_no_acerto_id, motivo, resolvida_em, resolvida_por)
                VALUES (?, ?, ?, 'FALTA', ?, ?, ?::numeric, ?, ?, ?, ?, ?)""", id, acerto, v.vendedorId(), produto, quantidade, valor,
                status, cobrada ? acerto : null, assumida ? "A loja assumiu" : null,
                assumida ? Timestamp.from(dia(diaDaResolucao)) : null, assumida ? usuarios.dona().id() : null);
        return id;
    }

    /** A falta que ficou em aberto no fechamento e foi encontrada depois. */
    protected void faltaEncontrada(UUID pendencia, String dia) {
        jdbc.update("""
                UPDATE corona.pendencias_acerto SET status = 'ENCONTRADA', resolvida_em = ?, resolvida_por = ?,
                       motivo = 'A peça apareceu', resolucao_id = ? WHERE id = ?""", Timestamp.from(dia(dia)),
                usuarios.dona().id(), UUID.randomUUID(), pendencia);
    }
}
