package br.com.coronasemijoias.api.testes;

import br.com.coronasemijoias.api.sync.FiltroDoVendedor;
import br.com.coronasemijoias.api.sync.LinhaDoPull;
import br.com.coronasemijoias.api.sync.LoteDoPull;
import br.com.coronasemijoias.api.sync.ServicoDePull;
import java.util.ArrayList;
import java.util.List;

/** Tudo que o pull entrega a partir de um cursor, lote a lote, até não haver mais nada. */
public record LeituraDoPull(List<LinhaDoPull> linhas, String cursorFinal) {

    public static LeituraDoPull esvaziar(ServicoDePull pull, String cursor) {
        List<LinhaDoPull> linhas = new ArrayList<>();
        LoteDoPull lote;
        do {
            lote = pull.puxar(cursor, ServicoDePull.LOTE_MAXIMO, FiltroDoVendedor.SEM_FILTRO);
            linhas.addAll(lote.linhas());
            cursor = lote.proximoCursor();
        } while (lote.haMais());
        return new LeituraDoPull(List.copyOf(linhas), cursor);
    }
}
