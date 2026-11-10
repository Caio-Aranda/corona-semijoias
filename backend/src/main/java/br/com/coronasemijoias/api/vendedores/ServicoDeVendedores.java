package br.com.coronasemijoias.api.vendedores;

import br.com.coronasemijoias.api.cadastros.Documentos;
import br.com.coronasemijoias.api.cadastros.Mensagens;
import br.com.coronasemijoias.api.cadastros.ServicoDeCadastro;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.vendedores.Vendedor.Dados;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cadastro do vendedor. Só a dona usa (as rotas exigem o perfil ADMIN). O servidor confere o limite de
 * consignação (zero ou mais), a comissão (de 0 a 100), o CPF ou CNPJ e se a praça e a restrição existem.
 */
@Service
public class ServicoDeVendedores extends ServicoDeCadastro<Vendedor> {

    private static final BigDecimal CEM = new BigDecimal("100");

    private final RepositorioDeVendedores vendedores;
    private final JdbcTemplate jdbc;

    public ServicoDeVendedores(RepositorioDeVendedores vendedores, Clock relogio, JdbcTemplate jdbc) {
        super(vendedores, relogio);
        this.vendedores = vendedores;
        this.jdbc = jdbc;
    }

    /** Quais vendedores a lista mostra. */
    public enum Situacao {
        ATIVOS(List.of(true)), INATIVOS(List.of(false)), TODOS(List.of(true, false));

        private final List<Boolean> valores;

        Situacao(List<Boolean> valores) {
            this.valores = valores;
        }
    }

    @Transactional(readOnly = true)
    public Page<Vendedor> listar(String busca, String situacao, Integer pagina, Integer tamanho) {
        Situacao filtro = situacao(situacao);
        String codigo = busca == null ? "" : busca.strip();
        // Só números e curto (até 6 dígitos) é busca pelo código do vendedor; telefones e CPF têm mais dígitos.
        boolean soCodigo = codigo.matches("[0-9]{1,6}");
        return vendedores.buscar(soCodigo ? "-" : padraoDeTexto(busca), soCodigo ? "-" : padraoDeDocumento(busca), codigo, filtro.valores,
                pagina(pagina, tamanho));
    }

    private static Situacao situacao(String texto) {
        if (texto == null || texto.isBlank()) {
            return Situacao.TODOS;
        }
        try {
            return Situacao.valueOf(texto.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw Validacoes.recusar("situacao", "Escolha ATIVOS, INATIVOS ou TODOS.");
        }
    }

    @Transactional
    public Vendedor criar(Dados d, UUID cadastradoPor) {
        return gravarNovo(new Vendedor(conferir(d), cadastradoPor));
    }

    @Transactional
    public Vendedor editar(UUID id, Integer versao, Dados d) {
        Dados conferidos = conferir(d);
        return editarComVersao(id, Validacoes.versaoDoPedido(versao), vendedor -> vendedor.alterar(conferidos));
    }

    @Transactional
    public Vendedor definirAtivo(UUID id, boolean ativo) {
        int versao = obter(id).getVersion();
        return editarComVersao(id, versao, vendedor -> vendedor.definirAtivo(ativo));
    }

    /** Confere e limpa os campos: o que o banco também recusa, a API recusa antes com o nome do campo. */
    private Dados conferir(Dados d) {
        BigDecimal limite = d.limiteConsignacao() == null ? BigDecimal.ZERO : d.limiteConsignacao();
        if (limite.signum() < 0) {
            throw Validacoes.recusar("limiteConsignacao", "O limite não pode ser menor que zero.");
        }
        BigDecimal comissao = d.comissaoPercentual() == null ? BigDecimal.ZERO : d.comissaoPercentual();
        if (comissao.signum() < 0 || comissao.compareTo(CEM) > 0) {
            throw Validacoes.recusar("comissaoPercentual", "A comissão deve ficar entre 0 e 100.");
        }
        conferirExiste("pracas", d.pracaId(), "pracaId");
        conferirExiste("restricoes", d.restricaoId(), "restricaoId");
        return new Dados(d.nome().strip(), d.dataNascimento(), Validacoes.limpar(d.sexo()), d.estrangeiro(),
                Validacoes.limpar(d.rgIe()), Validacoes.limpar(d.rgUf()), cpfOuCnpj(d.cpfCnpj()),
                Validacoes.limpar(d.naturalidade()), Validacoes.limpar(d.escolaridade()),
                Validacoes.limpar(d.telefone1()), Validacoes.limpar(d.telefone2()), Validacoes.limpar(d.whatsapp()),
                Validacoes.limpar(d.email()), Validacoes.limpar(d.facebook()), Validacoes.limpar(d.outrasMidias()),
                limite.setScale(2), d.restricaoId(), comissao.setScale(2), d.pracaId());
    }

    /** CPF (11 dígitos) ou CNPJ (14 caracteres) com os dígitos verificadores certos; só o número é guardado. */
    private static String cpfOuCnpj(String digitado) {
        String numero = Documentos.normalizarCnpj(digitado);
        if (numero == null) {
            return null;
        }
        boolean certo = numero.matches("[0-9]{11}") ? Documentos.cpfValido(numero)
                : numero.length() == 14 && Documentos.cnpjValido(numero);
        if (!certo) {
            throw Validacoes.recusar("cpfCnpj", "Confira o CPF ou o CNPJ: o número não parece estar certo.");
        }
        return numero;
    }

    private void conferirExiste(String tabela, UUID id, String campo) {
        if (id == null) {
            return;
        }
        Boolean existe = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM corona." + tabela + " WHERE id = ? AND deleted_at IS NULL)", Boolean.class, id);
        if (!Boolean.TRUE.equals(existe)) {
            throw Validacoes.recusar(campo, Mensagens.OPCAO_INEXISTENTE);
        }
    }
}
