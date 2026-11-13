package br.com.coronasemijoias.api.devolucoes;

import br.com.coronasemijoias.api.avisos.AvisoDaDona;
import br.com.coronasemijoias.api.avisos.FonteDeAvisos;
import br.com.coronasemijoias.api.devolucoes.TaxaDeDevolucao.VendedorAcima;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * "O que precisa da sua atenção": pedidos de autorização esperando a dona, devoluções para revisar e vendedores com
 * muitas devoluções (tarefa 6.4; ESPECIFICACAO §19.3). Calculado na hora, nunca gravado: o aviso some sozinho quando a
 * dona decide. Os textos são os do glossário (parte 6). Só a porcentagem, sem valor: nenhum Real sem Dólar e Guarani.
 */
@Component
public class FonteDeAvisosDasDevolucoes implements FonteDeAvisos {

    public static final String TIPO_PARA_AUTORIZAR = "DEVOLUCOES_PARA_AUTORIZAR";
    public static final String TIPO_PARA_REVISAR = "DEVOLUCOES_PARA_REVISAR";
    public static final String TIPO_MUITAS_DEVOLUCOES = "MUITAS_DEVOLUCOES";

    private final JdbcTemplate jdbc;
    private final TaxaDeDevolucao taxa;

    FonteDeAvisosDasDevolucoes(JdbcTemplate jdbc, TaxaDeDevolucao taxa) {
        this.jdbc = jdbc;
        this.taxa = taxa;
    }

    @Override
    public List<AvisoDaDona> avisos() {
        List<AvisoDaDona> avisos = new ArrayList<>();
        long pedidas = jdbc.queryForObject(
                "SELECT count(*) FROM corona.autorizacoes_excecao WHERE situacao = 'PEDIDA' AND deleted_at IS NULL", Long.class);
        avisoDosPedidos(pedidas).ifPresent(avisos::add);
        long emRevisao = jdbc.queryForObject(
                "SELECT count(*) FROM corona.devolucoes WHERE situacao = 'EM_REVISAO' AND deleted_at IS NULL", Long.class);
        avisoDasRevisoes(emRevisao).ifPresent(avisos::add);
        for (VendedorAcima v : taxa.acimaDoPercentual()) {
            avisos.add(avisoDaTaxa(v));
        }
        return avisos;
    }

    static Optional<AvisoDaDona> avisoDosPedidos(long quantos) {
        if (quantos <= 0) {
            return Optional.empty();
        }
        return Optional.of(new AvisoDaDona(TIPO_PARA_AUTORIZAR,
                quantos == 1 ? "Há 1 pedido de autorização de devolução esperando você."
                        : "Há " + quantos + " pedidos de autorização de devolução esperando você.",
                "Abra Devoluções para autorizar e escolha Aprovar ou Negar.", List.of()));
    }

    static Optional<AvisoDaDona> avisoDasRevisoes(long quantas) {
        if (quantas <= 0) {
            return Optional.empty();
        }
        return Optional.of(new AvisoDaDona(TIPO_PARA_REVISAR,
                quantas == 1 ? "Há 1 devolução para revisar." : "Há " + quantas + " devoluções para revisar.",
                "Abra Devoluções para revisar e escolha Manter ou Não aceitar.", List.of()));
    }

    static AvisoDaDona avisoDaTaxa(VendedorAcima v) {
        String texto = v.percentual() == null
                ? "Nas últimas 4 semanas, " + v.nome() + " teve devoluções e nenhuma venda. Confira as devoluções dele."
                : "Nas últimas 4 semanas, " + v.nome() + " devolveu " + v.percentual() + "% do que vendeu. "
                        + "Confira as devoluções dele.";
        return new AvisoDaDona(TIPO_MUITAS_DEVOLUCOES, "Muitas devoluções de " + v.nome() + ".", texto, List.of());
    }
}
