package br.com.coronasemijoias.api.vendedores;

import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.cadastros.Documentos;
import br.com.coronasemijoias.api.cadastros.Mensagens;
import br.com.coronasemijoias.api.cadastros.ServicoDeCadastro;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.usuarios.GestaoDeUsuarios;
import br.com.coronasemijoias.api.vendedores.Vendedor.Dados;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
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

    private final GestaoDeUsuarios usuarios;
    private final ServicoDeAuditoria auditoria;

    public ServicoDeVendedores(RepositorioDeVendedores vendedores, Clock relogio, JdbcTemplate jdbc,
            GestaoDeUsuarios usuarios, ServicoDeAuditoria auditoria) {
        super(vendedores, relogio);
        this.auditoria = auditoria;
        this.vendedores = vendedores;
        this.jdbc = jdbc;
        this.usuarios = usuarios;
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

    /**
     * Edita o cadastro. Trocar ou tirar a restrição do vendedor (a "liberação de restrição" da §11) fica na auditoria, na mesma
     * transação, com a restrição de antes e a de depois; a regra não muda.
     */
    @Transactional
    public Vendedor editar(UUID id, Integer versao, Dados d, UUID quem) {
        Dados conferidos = conferir(d);
        UUID restricaoAntes = obter(id).getRestricaoId();
        Vendedor editado = editarComVersao(id, Validacoes.versaoDoPedido(versao), vendedor -> vendedor.alterar(conferidos));
        if (!java.util.Objects.equals(restricaoAntes, conferidos.restricaoId())) {
            auditoria.registrar(Ator.usuario(quem), "VENDEDOR_RESTRICAO_ALTERADA", "vendedores", id,
                    Map.of("restricao", nomeDaRestricao(restricaoAntes)), Map.of("restricao", nomeDaRestricao(conferidos.restricaoId())));
        }
        return editado;
    }

    private String nomeDaRestricao(UUID restricaoId) {
        if (restricaoId == null) {
            return "Nenhuma";
        }
        return jdbc.query("SELECT codigo || ' - ' || descricao FROM corona.restricoes WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : restricaoId.toString(), restricaoId);
    }

    /**
     * Inativa ou reativa o cadastro. Ao inativar, os usuários ligados ao vendedor também são desativados e as sessões
     * deles encerradas, com auditoria (ADR-0011, linha de 11/11/2026). Reativar o cadastro não reativa o usuário: a
     * dona faz isso nos usuários.
     */
    @Transactional
    public Vendedor definirAtivo(UUID id, boolean ativo, UUID quem) {
        int versao = obter(id).getVersion();
        Vendedor vendedor = editarComVersao(id, versao, v -> v.definirAtivo(ativo));
        if (!ativo) {
            usuarios.desativarDoVendedor(id, quem);
        }
        return vendedor;
    }

    /** Confere e limpa os campos: o que o banco também recusa, a API recusa antes com o nome do campo. */
    private Dados conferir(Dados d) {
        // O limite é obrigatório: um valor em reais (zero vale zero) OU "Sem limite" marcado de propósito, nunca os dois.
        BigDecimal limite = d.limiteConsignacao();
        if (d.semLimite()) {
            if (limite != null) {
                throw Validacoes.recusar("limiteConsignacao", "Escolha um valor para o limite ou marque \"Sem limite\", não os dois.");
            }
        } else if (limite == null) {
            throw Validacoes.recusar("limiteConsignacao",
                    "Informe o limite de consignação em reais ou marque \"Sem limite\".");
        } else if (limite.signum() < 0) {
            throw Validacoes.recusar("limiteConsignacao", "O limite não pode ser menor que zero.");
        } else if (limite.scale() > 2 && limite.stripTrailingZeros().scale() > 2) {
            throw Validacoes.recusar("limiteConsignacao", "Use no máximo 2 casas depois da vírgula.");
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
                limite == null ? null : limite.setScale(2), d.semLimite(), d.restricaoId(), comissao.setScale(2), d.pracaId());
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
