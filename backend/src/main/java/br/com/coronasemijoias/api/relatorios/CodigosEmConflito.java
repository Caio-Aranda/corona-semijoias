package br.com.coronasemijoias.api.relatorios;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.produtos.Ean13;
import br.com.coronasemijoias.api.relatorios.RelatorioDeCadastro.Total;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Relatório "códigos duplicados ou em conflito" (§12 e ADR-0007). O servidor já recusa o mesmo código
 * (sem diferenciar maiúsculas e sem espaços nas pontas); o que sobra para avisar é o que passa por essa
 * conferência e continua sendo risco na leitura da etiqueta:
 *
 * <ul>
 *   <li><b>QUASE_IGUAL</b>: códigos que só diferem por maiúsculas, espaços, traços e pontuação ("AB-12" e "ab 12");
 *   <li><b>FORNECEDORES_DIFERENTES</b>: o mesmo caso, mas entre códigos de fornecedores diferentes;
 *   <li><b>EAN13_INVALIDO</b>: código do tipo código de barras sem 13 números ou com o dígito verificador errado.
 * </ul>
 *
 * Calculado em memória (uma loja tem milhares de códigos, não milhões) para reaproveitar a regra do
 * {@link Ean13} do servidor, sem duplicar a conta no SQL.
 */
@Service
public class CodigosEmConflito {

    public static final String QUASE_IGUAL = "QUASE_IGUAL";
    public static final String FORNECEDORES_DIFERENTES = "FORNECEDORES_DIFERENTES";
    public static final String EAN13_INVALIDO = "EAN13_INVALIDO";

    public record Linha(UUID codigoId, String codigo, String tipo, String motivo, String descricao, UUID produtoId,
            String produto, String fornecedor, boolean ativo) {
    }

    private record Codigo(UUID id, String codigo, String tipo, UUID fornecedorId, String fornecedor, UUID produtoId,
            String produto, boolean produtoAtivo, String texto) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public CodigosEmConflito(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** O código sem maiúsculas, acentos, espaços e pontuação: só letras e números. */
    static String chaveParecida(String codigo) {
        String semAcento = Normalizer.normalize(codigo, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return semAcento.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<Linha> relatorio(String busca, String fornecedorId, String situacao, String recorte,
            Integer pagina, Integer tamanho) {
        int p = MotorDeRelatorio.pagina(pagina);
        int t = MotorDeRelatorio.tamanho(tamanho);
        String sit = situacao == null || situacao.isBlank() ? "ATIVOS" : situacao.strip().toUpperCase();
        if (!List.of("ATIVOS", "INATIVOS", "TODOS").contains(sit)) {
            throw Validacoes.recusar("situacao", "Escolha ativos, inativos ou todos.");
        }
        UUID fornecedor = MotorDeRelatorio.idOpcional(fornecedorId, "fornecedor");
        String limpo = MotorDeRelatorio.limparBusca(busca);

        List<Codigo> codigos = jdbc.query("""
                SELECT c.id, c.codigo, c.tipo, c.fornecedor_id, f.razao_social, c.produto_id, p.nome, p.ativo
                FROM corona.produto_codigos c
                JOIN corona.produtos p ON p.id = c.produto_id AND p.deleted_at IS NULL
                LEFT JOIN corona.fornecedores f ON f.id = c.fornecedor_id
                WHERE c.deleted_at IS NULL""", new MapSqlParameterSource(),
                (rs, n) -> new Codigo(rs.getObject("id", UUID.class), rs.getString("codigo"), rs.getString("tipo"),
                        rs.getObject("fornecedor_id", UUID.class), rs.getString("razao_social"),
                        rs.getObject("produto_id", UUID.class), rs.getString("nome"), rs.getBoolean("ativo"),
                        chaveParecida(rs.getString("codigo"))));

        Map<String, List<Codigo>> grupos = codigos.stream().filter(c -> !c.texto().isEmpty())
                .collect(Collectors.groupingBy(Codigo::texto, LinkedHashMap::new, Collectors.toList()));
        List<Linha> todas = new ArrayList<>();
        for (List<Codigo> grupo : grupos.values()) {
            if (grupo.size() < 2) {
                continue;
            }
            long fornecedoresDistintos = grupo.stream().filter(c -> "FORNECEDOR".equals(c.tipo()))
                    .map(Codigo::fornecedorId).filter(Objects::nonNull).distinct().count();
            String motivo = fornecedoresDistintos >= 2 ? FORNECEDORES_DIFERENTES : QUASE_IGUAL;
            for (Codigo c : grupo) {
                todas.add(linha(c, motivo));
            }
        }
        for (Codigo c : codigos) {
            if ("EAN13".equals(c.tipo()) && !(Ean13.temTrezeDigitos(c.codigo()) && Ean13.digitoConfere(c.codigo()))) {
                todas.add(linha(c, EAN13_INVALIDO));
            }
        }

        Map<UUID, Codigo> porId = codigos.stream().collect(Collectors.toMap(Codigo::id, c -> c));
        List<Linha> filtradas = todas.stream()
                .filter(l -> filtra(l, porId, limpo, fornecedor, sit))
                .sorted(Comparator.comparing((Linha l) -> chaveParecida(l.codigo())).thenComparing(Linha::motivo)
                        .thenComparing(l -> l.codigo().toLowerCase(Locale.ROOT)).thenComparing(Linha::codigoId))
                .toList();
        List<Total> totais = List.of(
                total(MotorDeRelatorio.TODOS, "Códigos em conflito", filtradas.size()),
                total("fornecedores-diferentes", "Mesmo código em fornecedores diferentes", contar(filtradas, FORNECEDORES_DIFERENTES)),
                total("quase-iguais", "Códigos quase iguais", contar(filtradas, QUASE_IGUAL)),
                total("ean13-invalido", "Código de barras inválido", contar(filtradas, EAN13_INVALIDO)));

        String pedido = recorte == null || recorte.isBlank() ? null : recorte.strip();
        List<Linha> detalhe = filtradas;
        if (pedido != null) {
            String motivoPedido = switch (pedido) {
                case MotorDeRelatorio.TODOS -> null;
                case "fornecedores-diferentes" -> FORNECEDORES_DIFERENTES;
                case "quase-iguais" -> QUASE_IGUAL;
                case "ean13-invalido" -> EAN13_INVALIDO;
                default -> throw Validacoes.recusar("recorte", "Escolha um dos totais do relatório.");
            };
            if (motivoPedido != null) {
                detalhe = filtradas.stream().filter(l -> l.motivo().equals(motivoPedido)).toList();
            }
        }
        int de = (int) Math.min((long) p * t, detalhe.size());
        int ate = Math.min(de + t, detalhe.size());
        return new RelatorioDeCadastro<>(detalhe.subList(de, ate), detalhe.size(), p, t, totais, pedido);
    }

    private static boolean filtra(Linha l, Map<UUID, Codigo> porId, String busca, UUID fornecedor, String situacao) {
        Codigo c = porId.get(l.codigoId());
        if (fornecedor != null && !fornecedor.equals(c.fornecedorId())) {
            return false;
        }
        if (("ATIVOS".equals(situacao) && !c.produtoAtivo()) || ("INATIVOS".equals(situacao) && c.produtoAtivo())) {
            return false;
        }
        return busca.isEmpty() || c.codigo().toLowerCase(Locale.ROOT).contains(busca)
                || c.produto().toLowerCase(Locale.ROOT).contains(busca);
    }

    private static long contar(List<Linha> linhas, String motivo) {
        return linhas.stream().filter(l -> l.motivo().equals(motivo)).count();
    }

    private static Total total(String chave, String rotulo, long quantidade) {
        return new Total(chave, rotulo, quantidade, null);
    }

    private static Linha linha(Codigo c, String motivo) {
        String descricao = switch (motivo) {
            case FORNECEDORES_DIFERENTES -> "Parecido com o código de outro fornecedor";
            case QUASE_IGUAL -> "Parecido com outro código (muda só espaço, traço ou letra maiúscula)";
            default -> "Código de barras com número errado ou incompleto";
        };
        return new Linha(c.id(), c.codigo(), c.tipo(), motivo, descricao, c.produtoId(), c.produto(), c.fornecedor(),
                c.produtoAtivo());
    }
}
