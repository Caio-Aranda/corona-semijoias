package br.com.coronasemijoias.api.testes;

import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rotas que existem só nos testes (a API ainda não tem rotas de negócio). Entram no contexto
 * apenas por {@code @Import}, nunca na aplicação.
 *
 * <p>A anotação da classe aceita os dois perfis; cada método restringe ao seu grupo. A rota
 * {@code /api/auth/teste} usa só a da classe.
 */
@TestComponent
@RestController
@ExigePerfil({"ADMIN", "VENDEDOR"})
public class RotasDeTeste {

    public record Tempo(Instant instante) {
    }

    public record ValorDeTeste(BigDecimal valor) {
    }

    public record CadastroDeTeste(@NotBlank String nome) {
    }

    @ExigePerfil("ADMIN")
    @GetMapping("/api/dona/teste/tempo")
    public Tempo tempo() {
        return new Tempo(Instant.parse("2026-11-09T12:00:00Z"));
    }

    @ExigePerfil("ADMIN")
    @GetMapping("/api/dona/teste/dinheiro")
    public ValorDeTeste valorDaDona() {
        return new ValorDeTeste(new BigDecimal("123.45"));
    }

    @ExigePerfil("ADMIN")
    @PostMapping("/api/dona/teste/dinheiro")
    public ValorDeTeste eco(@RequestBody ValorDeTeste entrada) {
        return entrada;
    }

    @ExigePerfil("VENDEDOR")
    @GetMapping("/api/vendedor/teste/dinheiro")
    public ValorDeTeste valorDoVendedor() {
        return new ValorDeTeste(new BigDecimal("7.5"));
    }

    @ExigePerfil("VENDEDOR")
    @PostMapping("/api/vendedor/teste/validar")
    public CadastroDeTeste validarDoVendedor(@Valid @RequestBody CadastroDeTeste cadastro) {
        return cadastro;
    }

    /** Sem anotação no método: vale a da classe (os dois perfis). */
    @GetMapping("/api/auth/teste")
    public Tempo rotaDeLogin() {
        return tempo();
    }

    /** O método restringe mais que a classe: só o vendedor. */
    @ExigePerfil("VENDEDOR")
    @GetMapping("/api/auth/teste/so-vendedor")
    public Tempo soVendedor() {
        return tempo();
    }

    @ExigePerfil("ADMIN")
    @PostMapping("/api/dona/teste/validar")
    public CadastroDeTeste validar(@Valid @RequestBody CadastroDeTeste cadastro) {
        return cadastro;
    }

    @ExigePerfil("ADMIN")
    @GetMapping("/api/dona/teste/falha")
    public void falha() {
        throw new IllegalStateException("segredo-interno-que-nao-pode-vazar");
    }
}
