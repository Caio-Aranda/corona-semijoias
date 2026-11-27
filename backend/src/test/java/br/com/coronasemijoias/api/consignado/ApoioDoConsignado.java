package br.com.coronasemijoias.api.consignado;

import br.com.coronasemijoias.api.configuracoes.ServicoDoConsignado;

import br.com.coronasemijoias.api.consignado.ServicoDeAcertosDaNota.Devolvido;
import br.com.coronasemijoias.api.consignado.ServicoDeAcertosDaNota.NovoAcerto;
import br.com.coronasemijoias.api.consignado.ServicoDeAcertosDaNota.ReciboDoAcerto;
import br.com.coronasemijoias.api.consignado.ServicoDeRecibos.FormaPaga;
import br.com.coronasemijoias.api.consignado.ServicoDeNotas.ItemDaNota;
import br.com.coronasemijoias.api.consignado.ServicoDeNotas.NovaNota;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import br.com.coronasemijoias.api.trocas.ApoioDasTrocas;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Apoio dos testes do consignado ao cliente (tarefa 9.1): vendedor, cliente, maleta, semana, nota e as contas de conferência
 * (saldo da maleta, DX por linha, pendência do cliente). Os serviços são chamados direto, como o envio do computador faz.
 */
public abstract class ApoioDoConsignado extends ApoioDasTrocas {

    private static final AtomicInteger NUMERO_DE_SEMANA = new AtomicInteger(1);

    @Autowired
    protected ServicoDeSemanasDoVendedor semanas;

    @Autowired
    protected ServicoDeNotas notas;

    @Autowired
    protected ServicoDeAcertosDaNota acertos;

    @Autowired
    protected ServicoDeRecibos recibos;

    @Autowired
    protected DebitosDoCliente debitos;

    @Autowired
    protected ServicoDoConsignado configuracaoDoConsignado;

    protected ContextoDoConsignado ctx(UsuarioDeTeste v) {
        return new ContextoDoConsignado(v.vendedorId(), v.id(), null, relogio.instant(), false);
    }

    protected ContextoDoConsignado ctxEm(UsuarioDeTeste v, Instant quando) {
        return new ContextoDoConsignado(v.vendedorId(), v.id(), null, quando, false);
    }

    /** Abre uma semana (a anterior, se houver, fecha). Devolve o id. */
    protected UUID abrirSemana(UsuarioDeTeste v, int numero, Instant quando) {
        UUID id = UUID.randomUUID();
        semanas.abrir(ctxEm(v, quando), new ServicoDeSemanasDoVendedor.SemanaNova(id, numero, quando));
        return id;
    }

    protected UUID abrirSemana(UsuarioDeTeste v) {
        return abrirSemana(v, NUMERO_DE_SEMANA.getAndIncrement(), relogio.instant());
    }

    protected static ItemDaNota item(UUID produto, UUID remessa, int quantidade, String preco) {
        return new ItemDaNota(UUID.randomUUID(), produto, remessa, quantidade, new BigDecimal(preco), UUID.randomUUID());
    }

    /** Uma nota com os itens, na semana, com o número semana-sequência dado e cobrança daqui a 7 dias. */
    protected NovaNota nota(UUID semana, int sequencia, UUID cliente, ItemDaNota... itens) {
        return new NovaNota(UUID.randomUUID(), semana, sequencia, cliente, null, LocalDate.now().plusDays(7), List.of(itens), null);
    }

    protected UUID criarNota(UsuarioDeTeste v, UUID semana, int sequencia, UUID cliente, ItemDaNota... itens) {
        NovaNota n = nota(semana, sequencia, cliente, itens);
        notas.criar(ctx(v), n);
        return n.id();
    }

    /** Peças ainda na maleta: enviada + movimentos (o saldo da §7.1). */
    protected int disponivel(UUID maleta, UUID produto) {
        return saldo(maleta, produto);
    }

    /** DX da linha (produto + maleta) de uma nota: a soma das quantidades dos itens. */
    protected int dx(UUID nota, UUID maleta, UUID produto) {
        return contar("SELECT coalesce(sum(quantidade), 0) FROM corona.itens_nota_consignado WHERE nota_id = ? AND remessa_id = ? AND produto_id = ?",
                nota, maleta, produto);
    }

    /** Os alertas do vendedor (o banco é dividido entre os testes: cada um conta só os seus). */
    protected int alertas(UsuarioDeTeste v, String tipo) {
        return contar("SELECT count(*) FROM corona.alertas_consignado WHERE tipo = ? AND vendedor_id = ?", tipo, v.vendedorId());
    }

    protected String situacaoDaNota(UUID nota) {
        return jdbc.queryForObject("SELECT situacao FROM corona.notas_consignado WHERE id = ?", String.class, nota);
    }

    protected BigDecimal pendencia(UUID cliente) {
        return debitos.pendenciaDoCliente(cliente);
    }

    protected List<UUID> ids(UUID... ids) {
        return new ArrayList<>(List.of(ids));
    }

    protected static FormaPaga paga(String forma, String valor) {
        return new FormaPaga(forma, new BigDecimal(valor));
    }

    protected static Devolvido volta(UUID produto, UUID maleta, int quantidade) {
        return new Devolvido(UUID.randomUUID(), produto, maleta, quantidade, UUID.randomUUID());
    }

    /** Acerta a nota: comissão, o que voltou e, se houver formas, o recibo do acerto com o número dado. Devolve o id do acerto. */
    /**
     * Um acerto da nota com a comissão {@code comissao}. Desde 27/11/2026 (C5) o servidor sempre decide pela meta: para o
     * teste valer com a porcentagem pedida, a configuração do consignado fica com essa porcentagem dos dois lados da meta só
     * durante o acerto, e volta à de antes (os testes da meta chamam o serviço direto).
     */
    protected UUID acertar(UsuarioDeTeste v, UUID nota, UUID semana, String comissao, List<Devolvido> devolvidos, Integer sequenciaDoRecibo,
            FormaPaga... formas) {
        UUID id = UUID.randomUUID();
        ReciboDoAcerto recibo = formas.length == 0 ? null : new ReciboDoAcerto(UUID.randomUUID(), sequenciaDoRecibo, List.of(formas));
        comComissao(new BigDecimal(comissao),
                () -> acertos.acertar(ctx(v), new NovoAcerto(id, nota, semana, new BigDecimal(comissao), devolvidos, recibo, null)));
        return id;
    }

    /**
     * Roda {@code acao} com a configuração da meta valendo {@code comissao} dos dois lados (só porcentagem inteira de 0 a 40, a que
     * a configuração aceita; outra vai direto, para o servidor recusar ou arredondar como faria) e volta à de antes.
     */
    protected <T> T comComissao(BigDecimal comissao, java.util.function.Supplier<T> acao) {
        boolean inteira = comissao.stripTrailingZeros().scale() <= 0 && comissao.signum() >= 0 && comissao.compareTo(BigDecimal.valueOf(40)) <= 0;
        if (!inteira) {
            return acao.get();
        }
        var antes = configuracaoDoConsignado.ler();
        int p = comissao.intValueExact();
        configuracaoDoConsignado.alterar(new ServicoDoConsignado.Consignado(antes.metaGuarani(), p, p, antes.diasDoRetorno()),
                usuarios.dona().id());
        try {
            return acao.get();
        } finally {
            configuracaoDoConsignado.alterar(antes, usuarios.dona().id());
        }
    }

    protected BigDecimal saldoDoDebito(UUID acerto) {
        return debitos.saldoDoDebito(acerto);
    }

    protected BigDecimal aAplicar(UUID recibo) {
        return debitos.aAplicar(recibo);
    }

    protected UUID reciboDoAcerto(UUID acerto) {
        return jdbc.queryForObject("SELECT recibo_id FROM corona.acertos_nota WHERE id = ?", UUID.class, acerto);
    }
}
