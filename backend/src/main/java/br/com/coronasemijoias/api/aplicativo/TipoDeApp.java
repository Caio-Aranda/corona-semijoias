package br.com.coronasemijoias.api.aplicativo;

import java.util.Optional;

/**
 * Qual aplicativo está chamando (cabeçalho {@code X-App-Tipo}; ADR-0004, item 10). É só conveniência:
 * escolhe a mensagem e a versão mínima certas. NUNCA autoriza nada; quem autoriza é o perfil do token.
 */
public enum TipoDeApp {

    /** Corona Gestão, o app da dona. */
    GESTAO("gestao", "Corona Gestão"),

    /** Corona Vendas, o app do vendedor. */
    VENDAS("vendas", "Corona Vendas");

    private final String codigo;
    private final String nome;

    TipoDeApp(String codigo, String nome) {
        this.codigo = codigo;
        this.nome = nome;
    }

    /** O valor do cabeçalho e da coluna {@code tentativas_login.tipo_app}. */
    public String codigo() {
        return codigo;
    }

    /** O nome que a pessoa vê na tela. */
    public String nome() {
        return nome;
    }

    /** Só os valores exatos {@code gestao} e {@code vendas}. */
    public static Optional<TipoDeApp> de(String valor) {
        for (TipoDeApp tipo : values()) {
            if (tipo.codigo.equals(valor)) {
                return Optional.of(tipo);
            }
        }
        return Optional.empty();
    }
}
