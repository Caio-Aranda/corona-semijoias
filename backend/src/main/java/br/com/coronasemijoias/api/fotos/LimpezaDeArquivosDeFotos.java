package br.com.coronasemijoias.api.fotos;

import br.com.coronasemijoias.api.arquivos.ArmazenamentoDeArquivos;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Tira do disco os arquivos das fotos que foram trocadas ou excluídas há mais de {@link #CARENCIA} (uma foto só por
 * peça, ESPECIFICACAO §20). O arquivo de uma foto que outra peça ou uma foto ainda recente usa (mesmo hash, mesmos
 * arquivos) fica. Roda todo dia pela {@link AgendaDaLimpezaDeFotos}; é seguro rodar de novo a qualquer hora.
 */
@Service
public class LimpezaDeArquivosDeFotos {

    /** Quanto tempo o arquivo de uma foto excluída fica no disco (dá tempo de desfazer um engano pelo backup). */
    public static final Duration CARENCIA = Duration.ofDays(7);

    private static final Logger LOG = LoggerFactory.getLogger(LimpezaDeArquivosDeFotos.class);

    private final JdbcTemplate jdbc;
    private final ArmazenamentoDeArquivos armazenamento;
    private final Clock relogio;

    public LimpezaDeArquivosDeFotos(JdbcTemplate jdbc, ArmazenamentoDeArquivos armazenamento, Clock relogio) {
        this.jdbc = jdbc;
        this.armazenamento = armazenamento;
        this.relogio = relogio;
    }

    /** Devolve quantos arquivos foram apagados do disco. */
    public int limpar() {
        Timestamp corte = Timestamp.from(relogio.instant().minus(CARENCIA));
        List<Object[]> fotos = jdbc.query("""
                SELECT f.id, f.chave_miniatura, f.chave_media, f.chave_grande FROM corona.produtos_fotos f
                 WHERE f.deleted_at IS NOT NULL AND f.deleted_at < ? AND f.arquivos_removidos_em IS NULL
                 ORDER BY f.deleted_at LIMIT 500""",
                (rs, n) -> new Object[] {rs.getObject(1), rs.getString(2), rs.getString(3), rs.getString(4)}, corte);
        int apagados = 0;
        for (Object[] f : fotos) {
            for (int i = 1; i <= 3; i++) {
                String chave = (String) f[i];
                if (emUso(chave, f[0], corte)) {
                    continue;
                }
                try {
                    armazenamento.apagar(chave);
                    apagados++;
                } catch (RuntimeException e) {
                    LOG.warn("Fotos: não consegui apagar o arquivo {}; tento de novo amanhã.", chave, e);
                }
            }
            jdbc.update("UPDATE corona.produtos_fotos SET arquivos_removidos_em = ? WHERE id = ?",
                    Timestamp.from(relogio.instant()), f[0]);
        }
        if (apagados > 0) {
            LOG.info("Fotos: {} arquivo(s) de fotos trocadas ou excluídas saíram do disco.", apagados);
        }
        return apagados;
    }

    /** Outra foto, ativa ou excluída há pouco, usa o mesmo arquivo (a mesma imagem em outra peça). */
    private boolean emUso(String chave, Object fotoId, Timestamp corte) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM corona.produtos_fotos o
                                WHERE o.id <> ? AND o.arquivos_removidos_em IS NULL
                                  AND (o.deleted_at IS NULL OR o.deleted_at >= ?)
                                  AND ? IN (o.chave_miniatura, o.chave_media, o.chave_grande))""",
                Boolean.class, fotoId, corte, chave));
    }
}
