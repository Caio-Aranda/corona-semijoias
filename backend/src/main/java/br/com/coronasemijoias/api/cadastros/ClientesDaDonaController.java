package br.com.coronasemijoias.api.cadastros;

import br.com.coronasemijoias.api.cadastros.ServicoDeClientes.Dados;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Clientes: cadastro da dona (tarefa 1.2). O vendedor cadastra os dele pela sincronização (Fase 5). */
@RestController
@RequestMapping("/api/dona/clientes")
@ExigePerfil("ADMIN")
public class ClientesDaDonaController {

    /**
     * {@code id} (opcional, UUID do aplicativo) só vale ao criar; {@code version} (a que a tela leu) só ao
     * editar. O CPF pode vir com ou sem pontos e traço. {@code vendedorResponsavelId} precisa ser de um vendedor
     * que existe.
     */
    public record Pedido(
            UUID id,
            @NotBlank(message = Mensagens.OBRIGATORIO) @Size(max = 200, message = Mensagens.LIMITE_LONGO) String nome,
            @Size(max = 20, message = Mensagens.LIMITE_CURTO) String cpf,
            LocalDate dataNascimento,
            @Size(max = 30, message = Mensagens.LIMITE_CURTO) String telefone,
            @Size(max = 30, message = Mensagens.LIMITE_CURTO) String whatsapp,
            @Valid Endereco endereco,
            @Size(max = 2000, message = "Use no máximo {max} caracteres.") String observacoes,
            UUID vendedorResponsavelId,
            Integer version,
            @Size(max = 40, message = Mensagens.LIMITE_CURTO) String documento,
            @Size(max = 10, message = Mensagens.LIMITE_CURTO) String documentoTipo,
            UUID indicadoPorClienteId,
            @Size(max = 200, message = Mensagens.LIMITE_LONGO) String indicadoPorNome,
            @Size(max = 500, message = "Use no máximo {max} caracteres.") String comoConheceu,
            BigDecimal comissaoPercentual) {

        Dados dados() {
            return new Dados(nome, cpf, dataNascimento, telefone, whatsapp, endereco, observacoes, vendedorResponsavelId, documento,
                    documentoTipo, indicadoPorClienteId, indicadoPorNome, comoConheceu, comissaoPercentual);
        }
    }

    public record Resposta(UUID id, int version, String nome, String cpf, LocalDate dataNascimento, String telefone, String whatsapp,
            Endereco endereco, String observacoes, UUID vendedorResponsavelId, boolean ativo, String documento, String documentoTipo,
            UUID indicadoPorClienteId, String indicadoPorNome, String comoConheceu, BigDecimal comissaoPercentual) {

        static Resposta de(Cliente c) {
            return new Resposta(c.getId(), c.getVersion(), c.getNome(), c.getCpf(), c.getDataNascimento(), c.getTelefone(), c.getWhatsapp(),
                    c.getEndereco(), c.getObservacoes(), c.getVendedorResponsavelId(), c.isAtivo(),
                    c.getDocumento(), c.getDocumentoTipo(), c.getIndicadoPorClienteId(), c.getIndicadoPorNome(), c.getComoConheceu(),
                    c.getComissaoPercentual());
        }
    }

    private final ServicoDeClientes servico;

    public ClientesDaDonaController(ServicoDeClientes servico) {
        this.servico = servico;
    }

    @Operation(summary = "Listar clientes",
            description = "Busca pelo nome, telefone, WhatsApp ou CPF. A página começa em 0 (até 100 por página).")
    @GetMapping
    public Pagina<Resposta> listar(@RequestParam(required = false) String busca,
            @RequestParam(required = false) String situacao,
            @RequestParam(required = false) Integer pagina, @RequestParam(required = false) Integer tamanho) {
        return Pagina.de(servico.listar(busca, situacao, pagina, tamanho), Resposta::de);
    }

    @GetMapping("/{id}")
    public Resposta ver(@PathVariable UUID id) {
        return Resposta.de(servico.obter(id));
    }

    @Operation(summary = "Criar cliente",
            description = "O documento é opcional. Do tipo CPF (ou sem tipo, ou pelo campo cpf), o servidor confere os dígitos e guarda só os números; CPF repetido é barrado. RG, CI ou outro é texto livre e não exige CPF válido. A comissão vai de 0 a 40 (padrão 30). O representante, depois de definido, não muda.")
    @PostMapping
    public ResponseEntity<Resposta> criar(@Valid @RequestBody Pedido pedido) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Resposta.de(servico.criar(pedido.id(), pedido.dados())));
    }

    @Operation(summary = "Editar cliente", description = "Mande a version que a tela leu; se outra pessoa alterou antes, volta 409.")
    @PutMapping("/{id}")
    public Resposta editar(@PathVariable UUID id, @Valid @RequestBody Pedido pedido) {
        return Resposta.de(servico.editar(id, pedido.version(), pedido.dados()));
    }

    @Operation(summary = "Inativar cliente",
            description = "Inativar não é excluir: o cliente some das escolhas e das buscas (a lista de ativos), mas continua nos relatórios "
                    + "e nos históricos, e pode ser reativado a qualquer hora. Mande a version que a tela leu (opcional).")
    @PostMapping("/{id}/inativar")
    public Resposta inativar(@PathVariable UUID id, @RequestBody(required = false) PedidoDeSituacao pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        return Resposta.de(servico.definirAtivo(id, pedido == null ? null : pedido.version(), false, quem.id()));
    }

    @Operation(summary = "Reativar cliente", description = "Desfaz a inativação: volta para as escolhas e para a lista de ativos.")
    @PostMapping("/{id}/reativar")
    public Resposta reativar(@PathVariable UUID id, @RequestBody(required = false) PedidoDeSituacao pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        return Resposta.de(servico.definirAtivo(id, pedido == null ? null : pedido.version(), true, quem.id()));
    }

    @Operation(summary = "Excluir cliente", description = "Exclusão lógica: o cliente some das listas, mas o histórico fica.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> excluir(@PathVariable UUID id) {
        servico.excluir(id);
        return ResponseEntity.noContent().build();
    }
}
