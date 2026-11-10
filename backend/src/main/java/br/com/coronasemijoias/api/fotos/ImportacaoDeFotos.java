package br.com.coronasemijoias.api.fotos;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Importação de várias fotos de uma vez (§20). A foto é achada pelo <strong>nome do arquivo sem a
 * extensão</strong> = código da peça (ADR-0007: sem espaços nas pontas, sem diferenciar maiúsculas).
 *
 * <p>Duas fases. A PRÉVIA recebe só os nomes (nada é enviado nem gravado) e diz o que vai acontecer.
 * O CONFIRMAR recebe as fotos, um lote por vez, e grava cada uma sozinha: uma foto com problema não
 * derruba as outras, e o relatório diz qual foi e por quê. Repetir o mesmo lote não duplica nada
 * (a foto é identificada pelo hash do conteúdo).
 */
@Service
public class ImportacaoDeFotos {

    private static final Logger LOG = LoggerFactory.getLogger(ImportacaoDeFotos.class);

    static final int NOMES_NA_PREVIA = 5000;
    static final int FOTOS_POR_LOTE = 10;
    private static final int LIMITE_DA_LISTA_DE_SEM_FOTO = 200;
    private static final Set<String> EXTENSOES = Set.of(".jpg", ".jpeg", ".png", ".webp");

    // --- respostas ---

    public record Associada(String nome, String codigo, UUID produtoId, String produtoNome, int fotosAtuais) {
    }

    public record SemProduto(String nome, String codigo, String mensagem) {
    }

    public record Ambigua(String codigo, List<String> nomes, String mensagem) {
    }

    public record ProdutoSemFoto(UUID id, String nome, String codigoPrincipal) {
    }

    public record ProdutosSemFoto(long total, List<ProdutoSemFoto> itens) {
    }

    public record Previa(List<Associada> associadas, List<SemProduto> semProduto, List<Ambigua> ambiguas,
            ProdutosSemFoto produtosSemFoto) {
    }

    public enum Situacao { GRAVADA, JA_EXISTIA, SEM_PRODUTO, AMBIGUA, RECUSADA }

    public record ResultadoDaFoto(String nome, Situacao situacao, UUID produtoId, String produtoNome, String mensagem) {
    }

    public record Relatorio(List<ResultadoDaFoto> resultados, int gravadas, int jaExistiam, int naoGravadas) {
    }

    private record Produto(UUID id, String nome) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final ServicoDeFotos fotos;

    public ImportacaoDeFotos(NamedParameterJdbcTemplate jdbc, ServicoDeFotos fotos) {
        this.jdbc = jdbc;
        this.fotos = fotos;
    }

    // --- nome do arquivo ---

    /** O código que o nome do arquivo diz: sem pasta, sem a extensão de imagem, sem espaços nas pontas. */
    static String codigoDoArquivo(String nome) {
        String texto = Normalizer.normalize(nome == null ? "" : nome, Normalizer.Form.NFC).replace('\\', '/');
        texto = texto.substring(texto.lastIndexOf('/') + 1).strip();
        String minusculo = texto.toLowerCase(Locale.ROOT);
        for (String extensao : EXTENSOES) {
            if (minusculo.endsWith(extensao)) {
                texto = texto.substring(0, texto.length() - extensao.length()).strip();
                break;
            }
        }
        return texto;
    }

    private static String chave(String codigo) {
        return codigo.toLowerCase(Locale.ROOT);
    }

    // --- prévia ---

    public Previa previa(List<String> nomes) {
        if (nomes == null || nomes.isEmpty()) {
            throw Validacoes.recusar("nomes", "Escolha pelo menos uma foto para conferir.");
        }
        if (nomes.size() > NOMES_NA_PREVIA) {
            throw Validacoes.recusar("nomes", "Confira no máximo " + NOMES_NA_PREVIA + " fotos por vez.");
        }
        List<Arquivo> arquivos = nomes.stream().map(n -> new Arquivo(n == null ? "" : n)).toList();
        Map<String, Produto> produtos = produtosPorCodigo(arquivos);

        List<SemProduto> semProduto = new ArrayList<>();
        Map<UUID, List<Arquivo>> porProduto = new LinkedHashMap<>();
        for (Arquivo a : arquivos) {
            Produto p = produtos.get(chave(a.codigo));
            if (p == null) {
                semProduto.add(new SemProduto(a.nome, a.codigo, semProdutoMensagem(a.codigo)));
            } else {
                porProduto.computeIfAbsent(p.id(), k -> new ArrayList<>()).add(a);
            }
        }
        List<Associada> associadas = new ArrayList<>();
        List<Ambigua> ambiguas = new ArrayList<>();
        Map<UUID, Integer> fotosAtuais = fotosAtuais(porProduto.keySet());
        porProduto.forEach((produtoId, lista) -> {
            if (lista.size() > 1) {
                ambiguas.add(new Ambigua(codigosDe(lista), lista.stream().map(a -> a.nome).toList(),
                        ambiguaMensagem(codigosDe(lista))));
            } else {
                Arquivo a = lista.get(0);
                associadas.add(new Associada(a.nome, a.codigo, produtoId, produtos.get(chave(a.codigo)).nome(),
                        fotosAtuais.getOrDefault(produtoId, 0)));
            }
        });
        Set<UUID> comFotoNova = new java.util.HashSet<>();
        associadas.forEach(a -> comFotoNova.add(a.produtoId()));
        return new Previa(associadas, semProduto, ambiguas, produtosQueFicamSemFoto(comFotoNova));
    }

    private ProdutosSemFoto produtosQueFicamSemFoto(Set<UUID> comFotoNova) {
        MapSqlParameterSource p = new MapSqlParameterSource("associados",
                comFotoNova.isEmpty() ? List.of(new UUID(0, 0)) : List.copyOf(comFotoNova));
        String filtro = """
                FROM corona.produtos p
                WHERE p.deleted_at IS NULL AND p.ativo
                  AND NOT EXISTS (SELECT 1 FROM corona.produtos_fotos f WHERE f.produto_id = p.id AND f.deleted_at IS NULL)
                  AND p.id NOT IN (:associados)""";
        Long total = jdbc.queryForObject("SELECT count(*) " + filtro, p, Long.class);
        List<ProdutoSemFoto> itens = jdbc.query("""
                SELECT p.id, p.nome,
                       (SELECT c.codigo FROM corona.produto_codigos c
                        WHERE c.produto_id = p.id AND c.deleted_at IS NULL ORDER BY c.principal DESC, c.created_at LIMIT 1) AS codigo
                """ + filtro + " ORDER BY lower(p.nome), p.id LIMIT " + LIMITE_DA_LISTA_DE_SEM_FOTO, p,
                (rs, n) -> new ProdutoSemFoto(rs.getObject("id", UUID.class), rs.getString("nome"), rs.getString("codigo")));
        return new ProdutosSemFoto(total == null ? 0 : total, itens);
    }

    // --- confirmar ---

    public Relatorio confirmar(List<MultipartFile> enviados) {
        if (enviados == null || enviados.isEmpty()) {
            throw Validacoes.recusar("arquivos", "Escolha pelo menos uma foto para enviar.");
        }
        if (enviados.size() > FOTOS_POR_LOTE) {
            throw Validacoes.recusar("arquivos", "Envie no máximo " + FOTOS_POR_LOTE + " fotos por vez.");
        }
        List<Arquivo> arquivos = enviados.stream().map(m -> new Arquivo(m.getOriginalFilename())).toList();
        Map<String, Produto> produtos = produtosPorCodigo(arquivos);
        Map<UUID, List<Arquivo>> porProduto = new LinkedHashMap<>();
        for (Arquivo a : arquivos) {
            Produto p = produtos.get(chave(a.codigo));
            if (p != null) {
                porProduto.computeIfAbsent(p.id(), k -> new ArrayList<>()).add(a);
            }
        }

        List<ResultadoDaFoto> resultados = new ArrayList<>();
        int gravadas = 0;
        int jaExistiam = 0;
        for (int i = 0; i < arquivos.size(); i++) {
            Arquivo a = arquivos.get(i);
            Produto p = produtos.get(chave(a.codigo));
            ResultadoDaFoto resultado;
            if (p == null) {
                resultado = new ResultadoDaFoto(a.nome, Situacao.SEM_PRODUTO, null, null, semProdutoMensagem(a.codigo));
            } else if (porProduto.get(p.id()).size() > 1) {
                resultado = new ResultadoDaFoto(a.nome, Situacao.AMBIGUA, p.id(), p.nome(),
                        ambiguaMensagem(codigosDe(porProduto.get(p.id()))));
            } else {
                resultado = gravar(a.nome, p, enviados.get(i));
            }
            if (resultado.situacao() == Situacao.GRAVADA) {
                gravadas++;
            } else if (resultado.situacao() == Situacao.JA_EXISTIA) {
                jaExistiam++;
            }
            resultados.add(resultado);
        }
        return new Relatorio(resultados, gravadas, jaExistiam, resultados.size() - gravadas - jaExistiam);
    }

    private ResultadoDaFoto gravar(String nome, Produto produto, MultipartFile arquivo) {
        try {
            ServicoDeFotos.Enviada enviada = fotos.adicionar(produto.id(), arquivo.getBytes());
            return enviada.nova()
                    ? new ResultadoDaFoto(nome, Situacao.GRAVADA, produto.id(), produto.nome(),
                            "Foto gravada na peça " + produto.nome() + ".")
                    : new ResultadoDaFoto(nome, Situacao.JA_EXISTIA, produto.id(), produto.nome(),
                            "Esta foto já estava na peça " + produto.nome() + ". Nada mudou.");
        } catch (ErroDaApi e) {
            return new ResultadoDaFoto(nome, Situacao.RECUSADA, produto.id(), produto.nome(), e.getBody().getDetail());
        } catch (IOException | RuntimeException e) {
            LOG.error("Falha ao gravar a foto {} do produto {}", nome, produto.id(), e);
            return new ResultadoDaFoto(nome, Situacao.RECUSADA, produto.id(), produto.nome(),
                    "Não foi possível gravar esta foto. Tente enviar de novo.");
        }
    }

    // --- apoio ---

    private static final class Arquivo {
        final String nome;
        final String codigo;

        Arquivo(String nome) {
            this.nome = nome == null ? "" : nome;
            this.codigo = codigoDoArquivo(this.nome);
        }
    }

    private static String codigosDe(List<Arquivo> lista) {
        return String.join(" e ", lista.stream().map(a -> a.codigo).distinct().toList());
    }

    private static String semProdutoMensagem(String codigo) {
        return "Nenhuma peça tem o código " + codigo + ". Confira o nome do arquivo ou cadastre a peça.";
    }

    private static String ambiguaMensagem(String codigos) {
        return "Há mais de um arquivo para o código " + codigos + ". Deixe só um e envie de novo.";
    }

    /** O produto de cada código (chave minúscula), entre os produtos e códigos que não foram excluídos. */
    private Map<String, Produto> produtosPorCodigo(List<Arquivo> arquivos) {
        List<String> chaves = arquivos.stream().map(a -> chave(a.codigo)).filter(c -> !c.isEmpty()).distinct().toList();
        Map<String, Produto> produtos = new LinkedHashMap<>();
        if (chaves.isEmpty()) {
            return produtos;
        }
        jdbc.query("""
                SELECT lower(c.codigo) AS chave, p.id, p.nome
                FROM corona.produto_codigos c JOIN corona.produtos p ON p.id = c.produto_id
                WHERE c.deleted_at IS NULL AND p.deleted_at IS NULL AND lower(c.codigo) IN (:chaves)""",
                new MapSqlParameterSource("chaves", chaves),
                rs -> {
                    produtos.put(rs.getString("chave"), new Produto(rs.getObject("id", UUID.class), rs.getString("nome")));
                });
        return produtos;
    }

    private Map<UUID, Integer> fotosAtuais(Set<UUID> produtoIds) {
        Map<UUID, Integer> contagem = new LinkedHashMap<>();
        if (produtoIds.isEmpty()) {
            return contagem;
        }
        jdbc.query("""
                SELECT produto_id, count(*) AS n FROM corona.produtos_fotos
                WHERE deleted_at IS NULL AND produto_id IN (:ids) GROUP BY produto_id""",
                new MapSqlParameterSource("ids", List.copyOf(produtoIds)),
                rs -> {
                    contagem.put(rs.getObject("produto_id", UUID.class), rs.getInt("n"));
                });
        return contagem;
    }
}
