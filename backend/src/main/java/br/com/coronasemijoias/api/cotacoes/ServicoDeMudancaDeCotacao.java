package br.com.coronasemijoias.api.cotacoes;

import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.dinheiro.ConversaoDeMoeda;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A dona muda a cotação do Dólar e do Guarani durante o dia (ADR-0006, item 15). A cotação dela é uma linha nova de
 * {@code cotacoes}, imutável e com vigência a partir de agora: corrigir é lançar outra. Aqui moram a validação dos
 * valores, a comparação com o Banco Central (aviso a partir de 15%, sem travar) e a auditoria.
 */
@Service
public class ServicoDeMudancaDeCotacao {

    /** Diferença do Banco Central a partir da qual a tela avisa (sem travar). */
    static final BigDecimal LIMITE_DO_AVISO = new BigDecimal("15");

    private static final BigDecimal DOLAR_MINIMO = new BigDecimal("0.01");
    private static final BigDecimal DOLAR_MAXIMO = new BigDecimal("999.99");
    private static final BigDecimal GUARANI_MINIMO = BigDecimal.ONE;
    private static final BigDecimal GUARANI_MAXIMO = new BigDecimal("99999");
    private static final Pattern DECIMAL = Pattern.compile("^\\d{1,10}(\\.\\d+)?$");

    private final RepositorioDeCotacoes repositorio;
    private final CotacaoVigente vigente;
    private final ServicoDeAuditoria auditoria;
    private final JdbcTemplate jdbc;
    private final Clock relogio;

    public ServicoDeMudancaDeCotacao(
            RepositorioDeCotacoes repositorio, CotacaoVigente vigente, ServicoDeAuditoria auditoria, JdbcTemplate jdbc,
            Clock relogio) {
        this.repositorio = repositorio;
        this.vigente = vigente;
        this.auditoria = auditoria;
        this.jdbc = jdbc;
        this.relogio = relogio;
    }

    /** O Banco Central que a dona vê ao lado do que digitou. */
    public record DoBancoCentral(LocalDate dataCotacao, BigDecimal dolar, BigDecimal guaranisPorReal) {
    }

    /**
     * @param moedaDoAviso {@code DOLAR} ou {@code GUARANI}: qual moeda passou dos 15% (a tela escreve o aviso nos dois idiomas)
     * @param aviso        o texto pronto, ou {@code null} quando a diferença é menor que 15% (ou não há Banco Central)
     */
    public record Previa(
            BigDecimal dolar, BigDecimal guaranisPorReal, BigDecimal guarani, Optional<DoBancoCentral> bancoCentral,
            String diferencaDolarPorCento, String diferencaGuaraniPorCento, boolean avisoDeDiferenca, String moedaDoAviso,
            String aviso) {
    }

    /** O que foi gravado; {@code jaExistia} quando é o reenvio de um pedido já gravado. */
    public record Resultado(Cotacao cotacao, Previa previa, boolean jaExistia) {
    }

    /** Uma linha do histórico do dia. */
    public record LinhaDoDia(Cotacao cotacao, String lancadaPor, boolean valeAgora) {
    }

    /** O que vale na cotação nova: o digitado ou, na moeda que ficou vazia, exatamente o da cotação que valia. */
    public record Valores(BigDecimal usd, BigDecimal guaranisPorReal, BigDecimal pygVenda) {
    }

    /**
     * Confere o que a dona digitou (texto com ponto). Ela pode mudar só o Dólar ou só o Guarani (24/11/2026): a moeda vazia fica
     * exatamente como estava na cotação que vale {@code agora}, sem arredondar. As duas vazias, ou uma vazia sem cotação nenhuma
     * para copiar, é 400 com mensagem simples.
     */
    private Valores resolver(String usd, String guaranisPorReal, Instant agora) {
        boolean semDolar = usd == null || usd.isBlank();
        boolean semGuarani = guaranisPorReal == null || guaranisPorReal.isBlank();
        if (semDolar && semGuarani) {
            throw Validacoes.recusar("usd", "Mude o valor do dólar, do guarani ou dos dois.");
        }
        BigDecimal dolar = semDolar ? null : conferirDolar(usd);
        BigDecimal guaranis = semGuarani ? null : conferirGuarani(guaranisPorReal);
        if (!semDolar && !semGuarani) {
            return new Valores(dolar, guaranis, ConversaoDeMoeda.taxaDoGuarani(guaranis));
        }
        Optional<Cotacao> valia = vigente.vigenteEm(agora);
        if (valia.isEmpty()) {
            throw semDolar ? Validacoes.recusar("usd", "Informe quantos reais vale 1 dólar.")
                    : Validacoes.recusar("guaranisPorReal", "Informe quantos guaranis vale 1 real.");
        }
        Cotacao c = valia.get();
        if (semDolar) {
            return new Valores(c.taxas().usdVenda(), guaranis, ConversaoDeMoeda.taxaDoGuarani(guaranis));
        }
        BigDecimal pyg = c.taxas().pygVenda();
        BigDecimal guaranisQueValiam = c.guaranisPorReal() != null ? c.guaranisPorReal()
                : ConversaoDeMoeda.guaranisPorRealComOitoCasas(pyg);
        return new Valores(dolar, guaranisQueValiam, pyg);
    }

    private static BigDecimal conferirDolar(String usd) {
        BigDecimal dolar = decimal("usd", usd, "Informe quantos reais vale 1 dólar.");
        if (dolar.stripTrailingZeros().scale() > 4) {
            throw Validacoes.recusar("usd", "Use no máximo 4 casas depois da vírgula no valor do dólar.");
        }
        if (dolar.compareTo(DOLAR_MINIMO) < 0 || dolar.compareTo(DOLAR_MAXIMO) > 0) {
            throw Validacoes.recusar("usd", "O valor do dólar precisa estar entre R$ 0,01 e R$ 999,99.");
        }
        return dolar;
    }

    private static BigDecimal conferirGuarani(String guaranisPorReal) {
        BigDecimal guaranis = decimal("guaranisPorReal", guaranisPorReal, "Informe quantos guaranis vale 1 real.");
        if (guaranis.stripTrailingZeros().scale() > 2) {
            throw Validacoes.recusar("guaranisPorReal", "Use no máximo 2 casas depois da vírgula no valor do guarani.");
        }
        if (guaranis.compareTo(GUARANI_MINIMO) < 0 || guaranis.compareTo(GUARANI_MAXIMO) > 0) {
            throw Validacoes.recusar("guaranisPorReal", "O valor do guarani precisa estar entre Gs. 1 e Gs. 99.999 por real.");
        }
        return guaranis;
    }

    private static BigDecimal decimal(String campo, String texto, String semValor) {
        if (texto == null || texto.isBlank()) {
            throw Validacoes.recusar(campo, semValor);
        }
        String limpo = texto.trim();
        if (!DECIMAL.matcher(limpo).matches()) {
            throw Validacoes.recusar(campo, "Confira o valor: use só números, com ponto nos centavos.");
        }
        return new BigDecimal(limpo);
    }

    /** O que a tela mostra antes de gravar: a conversão e a comparação com o Banco Central. Nada é gravado. */
    @Transactional(readOnly = true)
    public Previa previa(String usd, String guaranisPorReal) {
        Instant agora = relogio.instant();
        return calcular(resolver(usd, guaranisPorReal, agora), agora);
    }

    private Previa calcular(Valores v, Instant agora) {
        TaxasDoDia taxas = TaxasDoDia.daDona(v.usd(), v.guaranisPorReal(), v.pygVenda());
        Optional<DoBancoCentral> bcb = repositorio.bancoCentralVigenteEm(agora).map(c -> new DoBancoCentral(
                c.dataCotacao(), c.taxas().usdVenda(),
                ConversaoDeMoeda.guaranisPorReal(c.taxas().pygVenda())));
        if (bcb.isEmpty()) {
            return new Previa(v.usd(), v.guaranisPorReal(), taxas.pygVenda(), bcb, null, null, false, null, null);
        }
        BigDecimal difDolar = diferenca(v.usd(), bcb.get().dolar());
        BigDecimal difGuarani = diferenca(v.guaranisPorReal(), bcb.get().guaranisPorReal());
        String aviso = null;
        String moedaDoAviso = null;
        if (difDolar.compareTo(LIMITE_DO_AVISO) >= 0) {
            moedaDoAviso = "DOLAR";
            aviso = "Este valor está bem diferente do Banco Central (R$ " + virgula(bcb.get().dolar(), 2)
                    + "). Confira antes de gravar.";
        } else if (difGuarani.compareTo(LIMITE_DO_AVISO) >= 0) {
            moedaDoAviso = "GUARANI";
            aviso = "Este valor está bem diferente do Banco Central (Gs. " + milhares(bcb.get().guaranisPorReal())
                    + "). Confira antes de gravar.";
        }
        return new Previa(v.usd(), v.guaranisPorReal(), taxas.pygVenda(), bcb, difDolar.toPlainString(),
                difGuarani.toPlainString(), aviso != null, moedaDoAviso, aviso);
    }

    /** |digitado − Banco Central| ÷ Banco Central, em %, 2 casas. */
    private static BigDecimal diferenca(BigDecimal digitado, BigDecimal bcb) {
        // Sem referência que sirva (uma taxa do Banco Central que dá 0 guaranis por real), não há o que comparar: sem aviso.
        if (bcb.signum() <= 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return ConversaoDeMoeda.diferencaPorCento(digitado, bcb);
    }

    /** "5,50": o valor já arredondado pela regra única de conversão, só com a vírgula do português. */
    private static String virgula(BigDecimal valor, int casas) {
        return new DecimalFormat("0." + "0".repeat(casas), DecimalFormatSymbols.getInstance(Locale.forLanguageTag("pt-BR")))
                .format(ConversaoDeMoeda.paraCasas(valor, casas));
    }

    /** "1.117": o valor já arredondado pela regra única de conversão, com o ponto de milhar do português. */
    private static String milhares(BigDecimal valor) {
        return new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.forLanguageTag("pt-BR")))
                .format(ConversaoDeMoeda.paraCasas(valor, 0));
    }

    /**
     * Grava a cotação da dona, que vale a partir de agora. Com {@code id} (gerado pela tela), o reenvio é idempotente: mesmo
     * id e mesmas taxas devolve a mesma linha; mesmo id e outras taxas é conflito (409).
     */
    @Transactional
    public Resultado gravar(UUID id, String usd, String guaranisPorReal, UUID usuarioId) {
        Instant agora = relogio.instant();
        Valores v = resolver(usd, guaranisPorReal, agora);
        TaxasDoDia taxas = TaxasDoDia.daDona(v.usd(), v.guaranisPorReal(), v.pygVenda());
        if (id != null) {
            Optional<Cotacao> existente = repositorio.porId(id);
            if (existente.isPresent()) {
                Cotacao c = existente.get();
                boolean igual = c.daDona() && c.taxas().usdVenda().compareTo(taxas.usdVenda()) == 0
                        && c.guaranisPorReal().compareTo(v.guaranisPorReal()) == 0;
                if (!igual) {
                    throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.COTACAO_JA_GRAVADA_DIFERENTE);
                }
                return new Resultado(c, calcular(v, c.vigenteDesde()), true);
            }
        }
        Previa previa = calcular(v, agora);
        Cotacao gravada = id == null
                ? repositorio.gravarDaDona(taxas, v.guaranisPorReal(), usuarioId, agora)
                : repositorio.gravarDaDona(id, taxas, v.guaranisPorReal(), usuarioId, agora);

        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("dolar", v.usd().toPlainString());
        depois.put("guaranis_por_real", v.guaranisPorReal().toPlainString());
        previa.bancoCentral().ifPresent(b -> {
            depois.put("banco_central_dolar", b.dolar().toPlainString());
            depois.put("banco_central_guaranis_por_real", b.guaranisPorReal().toPlainString());
        });
        depois.put("aviso_de_diferenca", previa.avisoDeDiferenca());
        auditoria.registrar(Ator.usuario(usuarioId), "COTACAO_LANCADA_PELA_DONA", "cotacoes", gravada.id(), null, depois);
        return new Resultado(gravada, previa, false);
    }

    /** As cotações do dia (Banco Central e da dona), na ordem em que passaram a valer, com a que vale agora marcada. */
    @Transactional(readOnly = true)
    public List<LinhaDoDia> doDia(LocalDate dia) {
        UUID valeAgora = vigente.vigenteEm(relogio.instant()).map(Cotacao::id).orElse(null);
        return repositorio.doDia(dia).stream()
                .map(c -> new LinhaDoDia(c, nomeDe(c.lancadaPor()), c.id().equals(valeAgora)))
                .toList();
    }

    private String nomeDe(UUID usuarioId) {
        if (usuarioId == null) {
            return null;
        }
        return jdbc.query("SELECT nome FROM corona.usuarios WHERE id = ?", (rs, n) -> rs.getString(1), usuarioId)
                .stream().findFirst().orElse(null);
    }
}
