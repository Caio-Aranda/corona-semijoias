package br.com.coronasemijoias.api.vendedor;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import br.com.coronasemijoias.api.vendedor.ClientesDoVendedor.Cliente;
import br.com.coronasemijoias.api.vendedor.ClientesDoVendedor.Dados;
import br.com.coronasemijoias.api.vendedor.ClientesDoVendedor.Resultado;
import io.swagger.v3.oas.annotations.Operation;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Clientes da loja vistos pelo vendedor (tarefa 4.3; ADR-0011, item 13). Só VENDEDOR; o vendedor vem do token. */
@RestController
@RequestMapping("/api/vendedor/clientes")
@ExigePerfil("VENDEDOR")
public class ClientesDoVendedorController {

    public record PedidoDeCliente(UUID id, String nome, String cpf, LocalDate dataNascimento, String telefone, String whatsapp,
            String observacoes) {
    }

    private final ClientesDoVendedor clientes;

    ClientesDoVendedorController(ClientesDoVendedor clientes) {
        this.clientes = clientes;
    }

    @Operation(operationId = "listarMeusClientes", summary = "Meus clientes",
            description = "Só os clientes ativos do representante logado (até 100, por nome): cada representante vê a sua lista e nunca "
                    + "a de outro (ADR-0016, item 4). A busca procura no nome, telefone, CPF e WhatsApp.")
    @GetMapping
    public List<Cliente> listar(@RequestParam(required = false) String busca, @AuthenticationPrincipal UsuarioAutenticado quem) {
        return clientes.listar(quem.vendedorId(), busca);
    }

    @Operation(operationId = "verMeuCliente", summary = "Ver um cliente meu", description = "Cliente de outro representante é 404.")
    @GetMapping("/{id}")
    public Cliente ver(@PathVariable UUID id, @AuthenticationPrincipal UsuarioAutenticado quem) {
        return clientes.ver(id, quem.vendedorId());
    }

    @Operation(operationId = "cadastrarMeuCliente", summary = "Cadastrar um cliente",
            description = "O cliente novo entra com o vendedor logado como responsável. O CPF é opcional, conferido pelo servidor e único: "
                    + "CPF já cadastrado devolve 409 CLIENTE_CPF_JA_CADASTRADO com o cliente existente em clienteExistente. "
                    + "Mesmo id e mesmos dados devolve 200; mesmo id com dados diferentes, 409.")
    @PostMapping
    public ResponseEntity<Cliente> cadastrar(@RequestBody PedidoDeCliente pedido, @AuthenticationPrincipal UsuarioAutenticado quem) {
        if (pedido == null) {
            throw Validacoes.recusar("cliente", "Informe os dados do cliente.");
        }
        Resultado r = clientes.criar(quem.vendedorId(), quem.id(), quem.dispositivoId(), new Dados(pedido.id(), pedido.nome(),
                pedido.cpf(), pedido.dataNascimento(), pedido.telefone(), pedido.whatsapp(), pedido.observacoes()));
        return ResponseEntity.status(r.jaExistia() ? HttpStatus.OK : HttpStatus.CREATED).body(r.cliente());
    }
}
