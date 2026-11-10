package br.com.coronasemijoias.api.cadastros;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.Size;

/** Endereço de fornecedor e de cliente. Tudo é opcional; o campo {@code uf} é livre (a loja atende na fronteira). */
@Embeddable
public record Endereco(
        @Column(name = "endereco_logradouro") @Size(max = 200, message = Mensagens.LIMITE_LONGO) String logradouro,
        @Column(name = "endereco_numero") @Size(max = 20, message = Mensagens.LIMITE_CURTO) String numero,
        @Column(name = "endereco_complemento") @Size(max = 100, message = Mensagens.LIMITE_LONGO) String complemento,
        @Column(name = "endereco_bairro") @Size(max = 100, message = Mensagens.LIMITE_LONGO) String bairro,
        @Column(name = "endereco_cidade") @Size(max = 100, message = Mensagens.LIMITE_LONGO) String cidade,
        @Column(name = "endereco_uf") @Size(max = 20, message = Mensagens.LIMITE_CURTO) String uf,
        @Column(name = "endereco_cep") @Size(max = 20, message = Mensagens.LIMITE_CURTO) String cep) {

    /** Os textos sem espaços nas pontas; {@code null} se nada foi preenchido. */
    public static Endereco limpo(Endereco e) {
        if (e == null) {
            return null;
        }
        Endereco limpo = new Endereco(Validacoes.limpar(e.logradouro), Validacoes.limpar(e.numero),
                Validacoes.limpar(e.complemento), Validacoes.limpar(e.bairro), Validacoes.limpar(e.cidade),
                Validacoes.limpar(e.uf), Validacoes.limpar(e.cep));
        boolean vazio = limpo.logradouro == null && limpo.numero == null && limpo.complemento == null
                && limpo.bairro == null && limpo.cidade == null && limpo.uf == null && limpo.cep == null;
        return vazio ? null : limpo;
    }
}
