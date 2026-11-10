package br.com.coronasemijoias.api.cadastros;

import br.com.coronasemijoias.api.cadastros.ServicoDeFornecedores.Dados;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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

/** Fornecedores: cadastro da dona (tarefa 1.2). Só a dona vê; o vendedor nunca recebe fornecedor. */
@RestController
@RequestMapping("/api/dona/fornecedores")
@ExigePerfil("ADMIN")
public class FornecedoresDaDonaController {

    /** {@code version} (a que a tela leu) só vale ao editar. O CNPJ pode vir com ou sem pontos, barra e traço. */
    public record Pedido(
            @NotBlank(message = Mensagens.OBRIGATORIO) @Size(max = 200, message = Mensagens.LIMITE_LONGO) String razaoSocial,
            @Size(max = 30, message = Mensagens.LIMITE_CURTO) String cnpj,
            @Size(max = 100, message = Mensagens.LIMITE_LONGO) String contatoNome,
            @Size(max = 30, message = Mensagens.LIMITE_CURTO) String telefone,
            @Size(max = 200, message = Mensagens.LIMITE_LONGO) String email,
            @Valid Endereco endereco,
            Integer version) {

        Dados dados() {
            return new Dados(razaoSocial, cnpj, contatoNome, telefone, email, endereco);
        }
    }

    public record Resposta(UUID id, int version, String razaoSocial, String cnpj, String contatoNome, String telefone,
            String email, Endereco endereco) {

        static Resposta de(Fornecedor f) {
            return new Resposta(f.getId(), f.getVersion(), f.getRazaoSocial(), f.getCnpj(), f.getContatoNome(),
                    f.getTelefone(), f.getEmail(), f.getEndereco());
        }
    }

    private final ServicoDeFornecedores servico;

    public FornecedoresDaDonaController(ServicoDeFornecedores servico) {
        this.servico = servico;
    }

    @Operation(summary = "Listar fornecedores",
            description = "Busca pela razão social, pelo contato ou pelo CNPJ. A página começa em 0 (até 100 por página).")
    @GetMapping
    public Pagina<Resposta> listar(@RequestParam(required = false) String busca,
            @RequestParam(required = false) Integer pagina, @RequestParam(required = false) Integer tamanho) {
        return Pagina.de(servico.listar(busca, pagina, tamanho), Resposta::de);
    }

    @GetMapping("/{id}")
    public Resposta ver(@PathVariable UUID id) {
        return Resposta.de(servico.obter(id));
    }

    @Operation(summary = "Criar fornecedor",
            description = "O CNPJ é opcional; se vier, o servidor confere os dígitos e guarda só os caracteres do número. Não pode repetir.")
    @PostMapping
    public ResponseEntity<Resposta> criar(@Valid @RequestBody Pedido pedido) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Resposta.de(servico.criar(pedido.dados())));
    }

    @Operation(summary = "Editar fornecedor", description = "Mande a version que a tela leu; se outra pessoa alterou antes, volta 409.")
    @PutMapping("/{id}")
    public Resposta editar(@PathVariable UUID id, @Valid @RequestBody Pedido pedido) {
        return Resposta.de(servico.editar(id, pedido.version(), pedido.dados()));
    }

    @Operation(summary = "Excluir fornecedor", description = "Exclusão lógica: o registro some das listas e o CNPJ fica livre.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> excluir(@PathVariable UUID id) {
        servico.excluir(id);
        return ResponseEntity.noContent().build();
    }
}
