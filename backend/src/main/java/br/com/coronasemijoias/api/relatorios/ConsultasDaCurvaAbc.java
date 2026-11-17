package br.com.coronasemijoias.api.relatorios;

import static br.com.coronasemijoias.api.relatorios.MotorDeRelatorio.DefinicaoDeTotal.emTresMoedas;
import static br.com.coronasemijoias.api.relatorios.VendidoLiquido.CONCLUIDA;
import static br.com.coronasemijoias.api.relatorios.VendidoLiquido.somaEmDolar;
import static br.com.coronasemijoias.api.relatorios.VendidoLiquido.somaEmGuarani;

import br.com.coronasemijoias.api.comum.dinheiro.Dinheiro;
import br.com.coronasemijoias.api.relatorios.MotorDeRelatorio.Consulta;
import br.com.coronasemijoias.api.relatorios.MotorDeRelatorio.DefinicaoDeTotal;
import br.com.coronasemijoias.api.relatorios.VendidoLiquido.Recorte;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Curva ABC da dona (tarefa 8.5; §12, "Estoque"; ADR-0015, item 4): por peça, pelo <b>vendido líquido</b> do período (a mesma
 * consulta dos relatórios de vendas, {@link VendidoLiquido}), da maior para a menor; o empate vai pelo código da peça. Classe A
 * até 80% do acumulado, B até 95%, C o resto; a peça que cruza a fronteira fica na classe de cima (a classe sai do acumulado
 * ANTES da peça, em números exatos). Peça sem venda no período, ou que voltou inteira, fica fora da curva. Os percentuais
 * saem de {@link Dinheiro} (HALF_UP, duas casas); a classe nunca usa o percentual arredondado. Sem custo.
 */
@Service
public class ConsultasDaCurvaAbc {

    /** Os filtros da curva: os do vendido líquido (peça, categoria, vendedor, praça, cliente, busca e período). */
    public record Filtros(String busca, String vendedorId, String pracaId, String clienteId, String produtoId,
            String categoriaId, LocalDate de, LocalDate ate, String recorte, Integer pagina, Integer tamanho) {
    }

    public record LinhaDaCurva(long posicao, UUID produtoId, String nome, String referencia, String codigo,
            String fotoPrincipalHash, String categoria, long pecas, BigDecimal liquido, BigDecimal liquidoEmDolar,
            BigDecimal liquidoEmGuarani, BigDecimal percentual, BigDecimal percentualAcumulado, String classe) {
    }

    private final MotorDeRelatorio motor;

    ConsultasDaCurvaAbc(MotorDeRelatorio motor) {
        this.motor = motor;
    }

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDaCurva> curva(Filtros f) {
        Consulta filtros = VendidoLiquido.filtrar(new Recorte(f.busca(), f.vendedorId(), f.pracaId(), f.clienteId(), f.produtoId(),
                f.categoriaId(), f.de(), f.ate()));
        String ordem = "ORDER BY b.liquido DESC, lower(coalesce(b.codigo, '')), b.produto_id";
        String porPeca = "SELECT l.produto_id, l.produto AS nome, l.referencia, max(l.codigo) AS codigo, "
                + "max(l.foto_principal_hash) AS foto, l.categoria, coalesce(sum(l.pecas), 0) AS pecas, sum(l.liquido) AS liquido, "
                + somaEmDolar("l.liquido") + " AS liquido_dolar, " + somaEmGuarani("l.liquido") + " AS liquido_guarani"
                + " FROM (" + VendidoLiquido.FONTE + ") l WHERE " + CONCLUIDA
                + (filtros.condicoes.isEmpty() ? "" : " AND " + String.join(" AND ", filtros.condicoes))
                + " GROUP BY l.produto_id, l.produto, l.referencia, l.categoria HAVING sum(l.liquido) > 0";
        String corrida = "SELECT b.*, sum(b.liquido) OVER () AS total_geral, row_number() OVER (" + ordem + ") AS posicao, "
                + "sum(b.liquido) OVER (" + ordem + " ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS acumulado "
                + "FROM (" + porPeca + ") b";
        String classificada = "SELECT r.*, CASE WHEN (r.acumulado - r.liquido) * 100 < 80 * r.total_geral THEN 'A' "
                + "WHEN (r.acumulado - r.liquido) * 100 < 95 * r.total_geral THEN 'B' ELSE 'C' END AS classe FROM (" + corrida + ") r";
        List<DefinicaoDeTotal> totais = List.of(
                emTresMoedas("liquido", "Vendido líquido", null, "x.pecas", "x.liquido", "x.liquido_dolar", "x.liquido_guarani"),
                faixa("A", "Classe A"), faixa("B", "Classe B"), faixa("C", "Classe C"));
        Consulta c = new Consulta("x.*", "(" + classificada + ") x", "x.posicao", totais);
        c.parametros.addValues(filtros.parametros.getValues());
        return motor.executar(c, f.recorte(), f.pagina(), f.tamanho(), (rs, n) -> {
            BigDecimal liquido = MotorDeRelatorio.reais(rs, "liquido");
            BigDecimal todo = MotorDeRelatorio.reais(rs, "total_geral");
            return new LinhaDaCurva(rs.getLong("posicao"), rs.getObject("produto_id", UUID.class), rs.getString("nome"),
                    rs.getString("referencia"), rs.getString("codigo"), rs.getString("foto"), rs.getString("categoria"),
                    rs.getLong("pecas"), liquido, rs.getBigDecimal("liquido_dolar"), rs.getBigDecimal("liquido_guarani"),
                    Dinheiro.percentualDe(liquido, todo), Dinheiro.percentualDe(MotorDeRelatorio.reais(rs, "acumulado"), todo),
                    rs.getString("classe"));
        });
    }

    private static DefinicaoDeTotal faixa(String classe, String rotulo) {
        return emTresMoedas("classe-" + classe.toLowerCase(), rotulo, "x.classe = '" + classe + "'", "x.pecas", "x.liquido", "x.liquido_dolar",
                "x.liquido_guarani");
    }
}
