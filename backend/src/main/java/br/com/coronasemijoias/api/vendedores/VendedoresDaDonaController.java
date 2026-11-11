package br.com.coronasemijoias.api.vendedores;

import br.com.coronasemijoias.api.cadastros.Mensagens;
import br.com.coronasemijoias.api.cadastros.Pagina;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cadastro do vendedor, só da dona (o Corona Vendas nunca recebe dados de vendedores; ESPECIFICACAO §4).
 * O limite de consignação, a comissão e a restrição só mudam por aqui. As abas, as referências e os
 * documentos têm as rotas abaixo de {@code /{id}}.
 */
@RestController
@RequestMapping("/api/dona/vendedores")
@ExigePerfil("ADMIN")
public class VendedoresDaDonaController {

    private static final String MENSAGEM_COMISSAO = "A comissão deve ficar entre 0 e 100.";

    /**
     * {@code version} (a que a tela leu) só vale ao editar. Dinheiro e comissão vão como texto com ponto
     * ("1500.00", "12.50"). CPF ou CNPJ pode vir com ou sem pontos, traço e barra.
     */
    public record Pedido(
            @NotBlank(message = Mensagens.OBRIGATORIO) @Size(max = 200, message = Mensagens.LIMITE_LONGO) String nome,
            LocalDate dataNascimento,
            @Pattern(regexp = "FEMININO|MASCULINO|OUTRO", message = "Escolha uma das opções da lista.") String sexo,
            Boolean estrangeiro,
            @Size(max = 30, message = Mensagens.LIMITE_CURTO) String rgIe,
            @Size(max = 20, message = Mensagens.LIMITE_CURTO) String rgUf,
            @Size(max = 30, message = Mensagens.LIMITE_CURTO) String cpfCnpj,
            @Size(max = 100, message = Mensagens.LIMITE_LONGO) String naturalidade,
            @Size(max = 100, message = Mensagens.LIMITE_LONGO) String escolaridade,
            @Size(max = 30, message = Mensagens.LIMITE_CURTO) String telefone1,
            @Size(max = 30, message = Mensagens.LIMITE_CURTO) String telefone2,
            @Size(max = 30, message = Mensagens.LIMITE_CURTO) String whatsapp,
            @Size(max = 200, message = Mensagens.LIMITE_LONGO) String email,
            @Size(max = 200, message = Mensagens.LIMITE_LONGO) String facebook,
            @Size(max = 300, message = Mensagens.LIMITE_LONGO) String outrasMidias,
            @DecimalMin(value = "0", message = "O limite não pode ser menor que zero.") BigDecimal limiteConsignacao,
            Boolean semLimite,
            UUID restricaoId,
            @DecimalMin(value = "0", message = MENSAGEM_COMISSAO) @DecimalMax(value = "100", message = MENSAGEM_COMISSAO)
            BigDecimal comissaoPercentual,
            UUID pracaId,
            Integer version) {

        Vendedor.Dados dados() {
            return new Vendedor.Dados(nome, dataNascimento, sexo, Boolean.TRUE.equals(estrangeiro), rgIe, rgUf,
                    cpfCnpj, naturalidade, escolaridade, telefone1, telefone2, whatsapp, email, facebook, outrasMidias,
                    limiteConsignacao, Boolean.TRUE.equals(semLimite), restricaoId, comissaoPercentual, pracaId);
        }
    }

    public record Resposta(UUID id, int version, int codigo, String nome, LocalDate dataCadastro,
            LocalDate dataNascimento, String sexo, boolean estrangeiro, String rgIe, String rgUf, String cpfCnpj,
            String naturalidade, String escolaridade, String telefone1, String telefone2, String whatsapp,
            String email, String facebook, String outrasMidias, BigDecimal limiteConsignacao, boolean semLimite,
            UUID restricaoId, BigDecimal comissaoPercentual, UUID pracaId, boolean ativo, UUID cadastradoPor) {

        static Resposta de(Vendedor v) {
            return new Resposta(v.getId(), v.getVersion(), v.getCodigo(), v.getNome(), v.getDataCadastro(),
                    v.getDataNascimento(), v.getSexo(), v.isEstrangeiro(), v.getRgIe(), v.getRgUf(), v.getCpfCnpj(),
                    v.getNaturalidade(), v.getEscolaridade(), v.getTelefone1(), v.getTelefone2(), v.getWhatsapp(),
                    v.getEmail(), v.getFacebook(), v.getOutrasMidias(), v.getLimiteConsignacao(), v.isLimiteConsignacaoIlimitado(), v.getRestricaoId(),
                    v.getComissaoPercentual(), v.getPracaId(), v.isAtivo(), v.getCadastradoPor());
        }
    }

    private final ServicoDeVendedores servico;

    public VendedoresDaDonaController(ServicoDeVendedores servico) {
        this.servico = servico;
    }

    @Operation(summary = "Listar vendedores",
            description = "Busca pelo nome, CPF ou CNPJ e telefones; só números com até 6 dígitos buscam pelo código. situacao: ATIVOS, INATIVOS ou TODOS (padrão). "
                    + "A página começa em 0 (até 100 por página).")
    @GetMapping
    public Pagina<Resposta> listar(@RequestParam(required = false) String busca,
            @RequestParam(required = false) String situacao, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return Pagina.de(servico.listar(busca, situacao, pagina, tamanho), Resposta::de);
    }

    @GetMapping("/{id}")
    public Resposta ver(@PathVariable UUID id) {
        return Resposta.de(servico.obter(id));
    }

    @Operation(summary = "Cadastrar vendedor",
            description = "O código do vendedor é dado pelo servidor, em sequência. Limite e comissão começam em zero.")
    @PostMapping
    public ResponseEntity<Resposta> criar(@Valid @RequestBody Pedido pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Resposta.de(servico.criar(pedido.dados(), quem.id())));
    }

    @Operation(summary = "Editar vendedor", description = "Mande a version que a tela leu; se outra pessoa alterou antes, volta 409.")
    @PutMapping("/{id}")
    public Resposta editar(@PathVariable UUID id, @Valid @RequestBody Pedido pedido) {
        return Resposta.de(servico.editar(id, pedido.version(), pedido.dados()));
    }

    @Operation(summary = "Inativar vendedor", description = "O vendedor sai das listas de ativos, mas o histórico fica.")
    @PostMapping("/{id}/inativar")
    public Resposta inativar(@PathVariable UUID id) {
        return Resposta.de(servico.definirAtivo(id, false));
    }

    @Operation(summary = "Reativar vendedor")
    @PostMapping("/{id}/reativar")
    public Resposta reativar(@PathVariable UUID id) {
        return Resposta.de(servico.definirAtivo(id, true));
    }

    @Operation(summary = "Excluir vendedor", description = "Exclusão lógica: some das listas, mas o histórico fica.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> excluir(@PathVariable UUID id) {
        servico.excluir(id);
        return ResponseEntity.noContent().build();
    }
}
