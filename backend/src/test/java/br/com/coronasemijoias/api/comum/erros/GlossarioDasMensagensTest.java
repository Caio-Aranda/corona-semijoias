package br.com.coronasemijoias.api.comum.erros;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.coronasemijoias.api.aplicativo.TipoDeApp;
import br.com.coronasemijoias.api.cadastros.Mensagens;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * As palavras proibidas na tela (docs/glossario-interface.md, parte 2) não podem aparecer em nenhuma
 * mensagem que a API manda para a tela: os textos de {@link CodigoErro}, as frases que o servidor
 * acrescenta, as mensagens de conferência dos campos e as recusas com o nome do campo.
 *
 * <p>Se falhar, a lista mostra a palavra, a mensagem e de onde ela vem. Corrija a MENSAGEM (e o
 * glossário primeiro, se o texto aprovado mudar); nunca a lista de palavras.
 */
class GlossarioDasMensagensTest {

    private static final Path GLOSSARIO = achar("docs/glossario-interface.md");
    private static final Path FONTES = achar("src/main/java").toAbsolutePath();

    /** Formas a mais, além do plural simples, que o glossário manda considerar (por exemplo "sincronizar"). */
    private static final Map<String, String> FORMAS_EXTRAS = Map.of(
            "sync", "sincroniz\\p{L}*",
            "sincronização", "sincroniz\\p{L}*",
            "fila de sincronização", "sincroniz\\p{L}*",
            "bipar", "bip\\p{L}*",
            "bipe", "bip\\p{L}*",
            "movimentação", "moviment\\p{L}*");

    /** Acha o caminho tanto rodando na pasta backend/ (Maven) quanto na raiz do repositório. */
    private static Path achar(String relativo) {
        for (String prefixo : List.of("", "../", "backend/")) {
            Path caminho = Path.of(prefixo + relativo);
            if (Files.exists(caminho)) {
                return caminho;
            }
        }
        throw new IllegalStateException("Não achei " + relativo + " a partir de " + Path.of("").toAbsolutePath());
    }

    // ---- as palavras do glossário ----------------------------------------------------------------------

    static List<String> bloco(String nome) throws IOException {
        String texto = Files.readString(GLOSSARIO, StandardCharsets.UTF_8);
        Matcher achou = Pattern.compile("<!-- " + nome + ":INICIO -->(.*?)<!-- " + nome + ":FIM -->", Pattern.DOTALL)
                .matcher(texto);
        assertThat(achou.find()).as("bloco %s do glossário", nome).isTrue();
        List<String> palavras = new ArrayList<>();
        for (String linha : achou.group(1).split("\\R")) {
            String limpa = linha.strip();
            if (!limpa.isEmpty() && !limpa.startsWith("```")) {
                palavras.add(limpa);
            }
        }
        assertThat(palavras).as("palavras do bloco %s", nome).isNotEmpty();
        return palavras;
    }

    /** Palavra inteira, sem diferenciar maiúsculas; aceita plural e as formas extras. */
    static Pattern padraoDoBlocoA(String palavra) {
        String base = Pattern.quote(palavra.toLowerCase());
        String plural = "(?:s|es|" + (palavra.endsWith("ção") ? "ções" : "s") + ")?";
        String extra = FORMAS_EXTRAS.containsKey(palavra.toLowerCase()) ? "|" + FORMAS_EXTRAS.get(palavra.toLowerCase()) : "";
        return Pattern.compile("(?<![\\p{L}\\p{N}_])(?:" + base + plural + extra + ")(?![\\p{L}\\p{N}_])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /** Só a forma exata, em maiúsculas ("ABERTA" é proibida; "Aberta" não). */
    static Pattern padraoDoBlocoB(String palavra) {
        return Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(palavra) + "(?![A-Za-z0-9_])");
    }

    /** Cada problema: "palavra → mensagem (origem)". */
    static List<String> proibidasEm(String mensagem, String origem, List<String> blocoA, List<String> blocoB) {
        List<String> problemas = new ArrayList<>();
        for (String palavra : blocoA) {
            if (padraoDoBlocoA(palavra).matcher(mensagem).find()) {
                problemas.add("\"" + palavra + "\" → \"" + mensagem + "\" (" + origem + ")");
            }
        }
        for (String palavra : blocoB) {
            if (padraoDoBlocoB(palavra).matcher(mensagem).find()) {
                problemas.add("\"" + palavra + "\" → \"" + mensagem + "\" (" + origem + ")");
            }
        }
        return problemas;
    }

    // ---- as mensagens da API ---------------------------------------------------------------------------

    /** Mensagem → de onde vem. */
    private static Map<String, String> mensagensDaApi() throws IOException {
        Map<String, String> todas = new LinkedHashMap<>();
        for (CodigoErro codigo : CodigoErro.values()) {
            todas.put(codigo.titulo(), "CodigoErro." + codigo + " (título)");
            todas.put(codigo.mensagem(), "CodigoErro." + codigo + " (mensagem)");
        }
        // Frases que o servidor acrescenta ou monta.
        for (TipoDeApp app : TipoDeApp.values()) {
            todas.put(ErroDaApi.loginInvalido(app).getBody().getDetail(), "ErroDaApi.loginInvalido(" + app + ")");
            todas.put(ErroDaApi.senhaTemporariaVencida(app).getBody().getDetail(), "ErroDaApi.senhaTemporariaVencida(" + app + ")");
        }
        todas.put(ErroDaApi.acessoBloqueado(Duration.ofSeconds(30)).getBody().getDetail(), "ErroDaApi.acessoBloqueado(30 s)");
        todas.put(ErroDaApi.acessoBloqueado(Duration.ofMinutes(7)).getBody().getDetail(), "ErroDaApi.acessoBloqueado(7 min)");
        // Constantes de conferência dos campos.
        for (Field campo : Mensagens.class.getDeclaredFields()) {
            if (Modifier.isStatic(campo.getModifiers()) && campo.getType() == String.class) {
                try {
                    todas.put((String) campo.get(null), "Mensagens." + campo.getName());
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        // Textos escritos direto no código: message = "...", recusar("campo", "..."), campoInvalido(...).
        Pattern literal = Pattern.compile(
                "(?:message\\s*=\\s*|(?:recusar|campoInvalido|CampoInvalido)\\(\\s*\"[^\"]*\"\\s*,\\s*)\"((?:[^\"\\\\]|\\\\.)*)\"");
        try (Stream<Path> arquivos = Files.walk(FONTES)) {
            for (Path arquivo : arquivos.filter(a -> a.toString().endsWith(".java")).toList()) {
                String fonte = Files.readString(arquivo, StandardCharsets.UTF_8);
                Matcher achou = literal.matcher(fonte);
                while (achou.find()) {
                    todas.putIfAbsent(achou.group(1), FONTES.relativize(arquivo) + " (texto no código)");
                }
            }
        }
        todas.keySet().removeIf(String::isBlank);
        return todas;
    }

    @Test
    void nenhumaMensagemDaApiTemPalavraProibidaDoGlossario() throws IOException {
        List<String> blocoA = bloco("PROIBIDAS-A");
        List<String> blocoB = bloco("PROIBIDAS-B");
        List<String> problemas = new ArrayList<>();
        mensagensDaApi().forEach((mensagem, origem) -> problemas.addAll(proibidasEm(mensagem, origem, blocoA, blocoB)));
        assertThat(problemas)
                .as("Palavras proibidas do glossário (parte 2) em mensagens da API. Corrija a mensagem:")
                .isEmpty();
    }

    @Test
    void todaConferenciaDeCampoTemMensagemEmPortugues() throws IOException {
        // Sem "message", o validador devolve o texto padrão dele (pode sair em inglês): proibido.
        List<String> semMensagem = new ArrayList<>();
        Pattern anotacao = Pattern.compile(
                "@(NotNull|NotBlank|NotEmpty|Size|Pattern|Min|Max|DecimalMin|DecimalMax|Email|Positive|PositiveOrZero|Negative|"
                        + "NegativeOrZero|Past|PastOrPresent|Future|FutureOrPresent|AssertTrue|AssertFalse|Digits)\\b");
        try (Stream<Path> arquivos = Files.walk(FONTES)) {
            for (Path arquivo : arquivos.filter(a -> a.toString().endsWith(".java")).toList()) {
                String fonte = Files.readString(arquivo, StandardCharsets.UTF_8);
                Matcher achou = anotacao.matcher(fonte);
                while (achou.find()) {
                    String argumentos = argumentosDe(fonte, achou.end());
                    if (!Pattern.compile("\\bmessage\\s*=").matcher(argumentos).find()) {
                        int linha = (int) fonte.substring(0, achou.start()).chars().filter(c -> c == '\n').count() + 1;
                        semMensagem.add(FONTES.relativize(arquivo) + ":" + linha + " " + achou.group());
                    }
                }
            }
        }
        assertThat(semMensagem)
                .as("Conferências de campo sem message = ... (acrescente uma constante de Mensagens):")
                .isEmpty();
    }

    /** O que está entre os parênteses logo depois da anotação (vazio se não há), respeitando textos entre aspas. */
    static String argumentosDe(String fonte, int depoisDoNome) {
        int i = depoisDoNome;
        while (i < fonte.length() && Character.isWhitespace(fonte.charAt(i))) {
            i++;
        }
        if (i >= fonte.length() || fonte.charAt(i) != '(') {
            return "";
        }
        int nivel = 0;
        boolean emTexto = false;
        int inicio = i;
        for (; i < fonte.length(); i++) {
            char c = fonte.charAt(i);
            if (emTexto) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    emTexto = false;
                }
            } else if (c == '"') {
                emTexto = true;
            } else if (c == '(') {
                nivel++;
            } else if (c == ')' && --nivel == 0) {
                return fonte.substring(inicio, i + 1);
            }
        }
        return fonte.substring(inicio);
    }

    // ---- o próprio teste funciona ----------------------------------------------------------------------

    @Test
    void oConferidorPegaPalavraProibidaEIgnoraTextoBom() throws IOException {
        List<String> a = bloco("PROIBIDAS-A");
        List<String> b = bloco("PROIBIDAS-B");
        assertThat(proibidasEm("Falha no token da sessão.", "x", a, b)).isNotEmpty();
        assertThat(proibidasEm("Os Tokens venceram.", "x", a, b)).isNotEmpty();
        assertThat(proibidasEm("Não foi possível sincronizar.", "x", a, b)).isNotEmpty();
        assertThat(proibidasEm("Veja as movimentações.", "x", a, b)).isNotEmpty();
        assertThat(proibidasEm("A remessa saiu.", "x", a, b)).isNotEmpty();
        assertThat(proibidasEm("Situação ABERTA.", "x", a, b)).isNotEmpty();
        assertThat(proibidasEm("Situação Aberta.", "x", a, b)).isEmpty();
        assertThat(proibidasEm("Sua sessão terminou. Entre de novo.", "x", a, b)).isEmpty();
        assertThat(proibidasEm("Esta pessoa não tem acesso (nulo não conta).", "x", a, b)).isEmpty();
    }

    @Test
    void oLeitorDasAnotacoesNaoSeConfundeComParentesesDentroDoTexto() {
        String fonte = "@Pattern(regexp = \"A(B|C)\", message = \"Escolha.\") String x";
        assertThat(argumentosDe(fonte, "@Pattern".length())).contains("message");
        assertThat(argumentosDe("@NotNull String x", "@NotNull".length())).isEmpty();
    }
}
