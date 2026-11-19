package br.com.coronasemijoias.api.consignado;

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

    protected int alertas(String tipo) {
        return contar("SELECT count(*) FROM corona.alertas_consignado WHERE tipo = ?", tipo);
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
}
