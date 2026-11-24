package br.com.coronasemijoias.api.relatorios;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.compras.ServicoDeCompras;
import br.com.coronasemijoias.api.compras.ServicoDeCompras.ItemPedido;
import br.com.coronasemijoias.api.compras.ServicoDeCompras.PedidoDeCompra;
import br.com.coronasemijoias.api.trocas.ApoioDasTrocas;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Apoio dos testes do custo da peça, do resultado do período e do lucro (tarefa 8.6): peças com o custo do cadastro dado,
 * compras com a data que o teste quer (o relógio fica parado no dia só durante a chamada ao serviço, que não passa pela rede,
 * então o token não vence) e a cotação dos dias da massa. Tudo de 2292 em diante, fora do que os outros testes usam.
 */
abstract class ApoioDoCusto extends ApoioDasTrocas {

    @Autowired
    protected ServicoDeCompras compras;

    protected UUID fornecedor() throws Exception {
        String nome = "Fornecedor " + UUID.randomUUID().toString().substring(0, 8);
        return UUID.fromString(corpoDe(postar("/api/dona/fornecedores", dona(), mapa("razaoSocial", nome))
                .andExpect(status().isCreated())).get("id").asString());
    }

    protected UUID categoria() throws Exception {
        return UUID.fromString(corpoDe(postar("/api/dona/categorias", dona(), mapa("nome", "Cat " + UUID.randomUUID()))
                .andExpect(status().isCreated())).get("id").asString());
    }

    /** Peça com o preço de venda e o custo do cadastro dados (o que vale quando não há compra até a data). */
    protected UUID pecaComCusto(UUID categoria, String preco, String custoDoCadastro) throws Exception {
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        return UUID.fromString(corpoDe(postar("/api/dona/produtos", dona(), mapa("nome", "Colar " + sufixo, "categoriaId", categoria,
                "precoVenda", preco, "precoCusto", custoDoCadastro, "estoqueMinimo", 1,
                "codigos", List.of(mapa("codigo", "RS-" + sufixo, "tipo", "LOJA", "principal", true))))
                .andExpect(status().isCreated())).get("id").asString());
    }

    /** O custo que o cadastro tem hoje (o que o resultado usa quando a peça não tem compra até a data da venda). */
    protected void cadastroPassaA(UUID produto, String custo) {
        jdbc.update("UPDATE corona.produtos_custos SET preco_custo = ?::numeric WHERE produto_id = ?", custo, produto);
    }

    /** Uma compra de uma peça só, de um dia, pelo serviço (gera entrada no estoque e o custo no histórico). */
    protected UUID comprarEm(UUID fornecedor, String dia, UUID produto, int quantidade, String custo) {
        LocalDate data = LocalDate.parse(dia);
        Instant meioDia = data.atTime(LocalTime.NOON).atZone(BRASILIA).toInstant();
        relogio.fixar(meioDia);
        try {
            UUID id = UUID.randomUUID();
            compras.registrar(new PedidoDeCompra(id, fornecedor, data, null, null,
                    List.of(new ItemPedido(produto, null, quantidade, new BigDecimal(custo)))), usuarios.dona().id());
            return id;
        } finally {
            relogio.reiniciar();
        }
    }

    protected void desfazerCompra(UUID compra) {
        relogio.fixar(brasilia("2292-12-01T12:00:00"));
        try {
            compras.desfazer(compra, UUID.randomUUID(), "Veio errada", usuarios.dona().id());
        } finally {
            relogio.reiniciar();
        }
    }

    /** Grava (ou acha) a cotação do dia, em Dólar e Guarani de venda (R$ por US$ e R$ por Gs.). */
    protected void cotacao(String dia, String usd, String pyg) {
        jdbc.update("""
                INSERT INTO corona.cotacoes (id, data_cotacao, usd_compra, usd_venda, usd_paridade_compra,
                    usd_paridade_venda, pyg_compra, pyg_venda, pyg_paridade_compra, pyg_paridade_venda, buscada_em, fonte, vigente_desde)
                VALUES (uuidv7(), ?::date, ?::numeric, ?::numeric, 1, 1, ?::numeric, ?::numeric, 1, 1, now(), 'teste', ?::date::timestamp AT TIME ZONE 'America/Sao_Paulo')
                ON CONFLICT (data_cotacao) WHERE origem = 'BANCO_CENTRAL' DO NOTHING""", dia, usd, usd, pyg, pyg, dia);
    }
}
