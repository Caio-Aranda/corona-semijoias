package br.com.coronasemijoias.api.vendedores;

import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * As abas do cadastro do vendedor, uma rota por aba (endereço, contato, trabalho, cônjuge, MEI e
 * observações) e as referências. Cada aba se grava sozinha, com a versão que a tela leu (0 se a aba
 * ainda não foi gravada).
 */
@RestController
@RequestMapping("/api/dona/vendedores/{id}")
@ExigePerfil("ADMIN")
public class SecoesDoVendedorController {

    private final ServicoDeSecoes secoes;
    private final ServicoDeReferencias referencias;
    private final RepositorioDeEnderecos enderecos;
    private final RepositorioDeContatos contatos;
    private final RepositorioDeTrabalhos trabalhos;
    private final RepositorioDeConjuges conjuges;
    private final RepositorioDeMeis meis;
    private final RepositorioDeObservacoes observacoes;

    public SecoesDoVendedorController(ServicoDeSecoes secoes, ServicoDeReferencias referencias,
            RepositorioDeEnderecos enderecos, RepositorioDeContatos contatos, RepositorioDeTrabalhos trabalhos,
            RepositorioDeConjuges conjuges, RepositorioDeMeis meis, RepositorioDeObservacoes observacoes) {
        this.secoes = secoes;
        this.referencias = referencias;
        this.enderecos = enderecos;
        this.contatos = contatos;
        this.trabalhos = trabalhos;
        this.conjuges = conjuges;
        this.meis = meis;
        this.observacoes = observacoes;
    }

    // --- Endereço ---

    @Operation(summary = "Ver a aba Endereço", description = "Aba nunca gravada: campos vazios e version 0.")
    @GetMapping("/endereco")
    public EnderecoDoVendedor.Dados verEndereco(@PathVariable UUID id) {
        return secoes.ler(id, enderecos, EnderecoDoVendedor::dados, EnderecoDoVendedor.vazia());
    }

    @Operation(summary = "Gravar a aba Endereço", description = "Mande a version que a tela leu (0 se a aba é nova).")
    @PutMapping("/endereco")
    public EnderecoDoVendedor.Dados gravarEndereco(@PathVariable UUID id,
            @Valid @RequestBody EnderecoDoVendedor.Dados dados) {
        return secoes.gravar(id, dados.version(), enderecos, EnderecoDoVendedor::new, s -> s.aplicar(dados),
                EnderecoDoVendedor::dados);
    }

    // --- Filiação / Contato ---

    @Operation(summary = "Ver a aba Filiação / Contato")
    @GetMapping("/contato")
    public ContatoDoVendedor.Dados verContato(@PathVariable UUID id) {
        return secoes.ler(id, contatos, ContatoDoVendedor::dados, ContatoDoVendedor.vazia());
    }

    @Operation(summary = "Gravar a aba Filiação / Contato")
    @PutMapping("/contato")
    public ContatoDoVendedor.Dados gravarContato(@PathVariable UUID id,
            @Valid @RequestBody ContatoDoVendedor.Dados dados) {
        return secoes.gravar(id, dados.version(), contatos, ContatoDoVendedor::new, s -> s.aplicar(dados),
                ContatoDoVendedor::dados);
    }

    // --- Trabalho ---

    @Operation(summary = "Ver a aba Trabalho")
    @GetMapping("/trabalho")
    public TrabalhoDoVendedor.Dados verTrabalho(@PathVariable UUID id) {
        return secoes.ler(id, trabalhos, TrabalhoDoVendedor::dados, TrabalhoDoVendedor.vazia());
    }

    @Operation(summary = "Gravar a aba Trabalho")
    @PutMapping("/trabalho")
    public TrabalhoDoVendedor.Dados gravarTrabalho(@PathVariable UUID id,
            @Valid @RequestBody TrabalhoDoVendedor.Dados dados) {
        return secoes.gravar(id, dados.version(), trabalhos, TrabalhoDoVendedor::new, s -> s.aplicar(dados),
                TrabalhoDoVendedor::dados);
    }

    // --- Cônjuge ---

    @Operation(summary = "Ver a aba Cônjuge")
    @GetMapping("/conjuge")
    public ConjugeDoVendedor.Dados verConjuge(@PathVariable UUID id) {
        return secoes.ler(id, conjuges, ConjugeDoVendedor::dados, ConjugeDoVendedor.vazia());
    }

    @Operation(summary = "Gravar a aba Cônjuge", description = "O CPF é conferido e guardado só com os números.")
    @PutMapping("/conjuge")
    public ConjugeDoVendedor.Dados gravarConjuge(@PathVariable UUID id,
            @Valid @RequestBody ConjugeDoVendedor.Dados dados) {
        return secoes.gravar(id, dados.version(), conjuges, ConjugeDoVendedor::new, s -> s.aplicar(dados),
                ConjugeDoVendedor::dados);
    }

    // --- MEI ---

    @Operation(summary = "Ver a aba MEI")
    @GetMapping("/mei")
    public MeiDoVendedor.Dados verMei(@PathVariable UUID id) {
        return secoes.ler(id, meis, MeiDoVendedor::dados, MeiDoVendedor.vazia());
    }

    @Operation(summary = "Gravar a aba MEI", description = "O CNPJ é conferido e guardado só com os caracteres do número.")
    @PutMapping("/mei")
    public MeiDoVendedor.Dados gravarMei(@PathVariable UUID id, @Valid @RequestBody MeiDoVendedor.Dados dados) {
        return secoes.gravar(id, dados.version(), meis, MeiDoVendedor::new, s -> s.aplicar(dados),
                MeiDoVendedor::dados);
    }

    // --- Observações ---

    @Operation(summary = "Ver a aba Observação")
    @GetMapping("/observacoes")
    public ObservacaoDoVendedor.Dados verObservacoes(@PathVariable UUID id) {
        return secoes.ler(id, observacoes, ObservacaoDoVendedor::dados, ObservacaoDoVendedor.vazia());
    }

    @Operation(summary = "Gravar a aba Observação")
    @PutMapping("/observacoes")
    public ObservacaoDoVendedor.Dados gravarObservacoes(@PathVariable UUID id,
            @Valid @RequestBody ObservacaoDoVendedor.Dados dados) {
        return secoes.gravar(id, dados.version(), observacoes, ObservacaoDoVendedor::new, s -> s.aplicar(dados),
                ObservacaoDoVendedor::dados);
    }

    // --- Referências ---

    @Operation(summary = "Listar as referências do vendedor")
    @GetMapping("/referencias")
    public List<ReferenciaDoVendedor.Resposta> listarReferencias(@PathVariable UUID id) {
        return referencias.listar(id);
    }

    @Operation(summary = "Acrescentar uma referência")
    @PostMapping("/referencias")
    public ResponseEntity<ReferenciaDoVendedor.Resposta> criarReferencia(@PathVariable UUID id,
            @Valid @RequestBody ReferenciaDoVendedor.Pedido pedido) {
        return ResponseEntity.status(HttpStatus.CREATED).body(referencias.criar(id, pedido));
    }

    @Operation(summary = "Editar uma referência", description = "Mande a version que a tela leu.")
    @PutMapping("/referencias/{referenciaId}")
    public ReferenciaDoVendedor.Resposta editarReferencia(@PathVariable UUID id, @PathVariable UUID referenciaId,
            @Valid @RequestBody ReferenciaDoVendedor.Pedido pedido) {
        return referencias.editar(id, referenciaId, pedido);
    }

    @Operation(summary = "Excluir uma referência", description = "Exclusão lógica.")
    @DeleteMapping("/referencias/{referenciaId}")
    public ResponseEntity<Void> excluirReferencia(@PathVariable UUID id, @PathVariable UUID referenciaId) {
        referencias.excluir(id, referenciaId);
        return ResponseEntity.noContent().build();
    }
}
